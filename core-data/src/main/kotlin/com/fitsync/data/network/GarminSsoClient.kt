package com.fitsync.data.network

import com.fitsync.data.db.AccountRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Garmin 登录收尾：**CAS ticket → OAuth1 token → OAuth2 access token → 用户资料**。
 *
 * 这里已去掉账号密码登录：凭据只在 Garmin 官方页面（WebView）里输入，
 * 本应用只接一张一次性 ticket，因此不需要、也不应该保存密码。
 *
 * 协议（以 garth 的实现为准，且已在生产端点验证过参数形状）：
 *
 * ```
 * GET  {api}/oauth-service/oauth/preauthorized
 *        ?ticket=ST-...&service={mobileService}&accepts-mfa-tokens=true
 *      ← 需要 OAuth1 签名（consumer key/secret 为 Garmin 移动端公开凭据）
 *      ← 响应是 form-urlencoded：oauth_token=...&oauth_token_secret=...
 *
 * POST {api}/oauth-service/oauth/exchange/user/2.0
 *      ← 用上一步的 oauth_token/secret 再做一次 OAuth1 签名
 *      body: audience=GARMIN_CONNECT_MOBILE_ANDROID_DI
 *      → JSON: access_token / refresh_token / expires_in / jti
 *
 * GET  {api}/userprofile-service/socialProfile   Authorization: Bearer <access_token>
 * ```
 *
 * 刷新：OAuth1 token 长期有效，重新走 `exchange/user/2.0` 即可，
 * 所以本地必须保存 `oauth_token` / `oauth_token_secret`（同样加密存储）。
 */
@Singleton
// open：单测需要覆盖 refreshSession 验证 401 重试链路
open class GarminSsoClient @Inject constructor(
    private val credentialStore: CredentialStore,
    private val httpClient: OkHttpClient,
    private val cookieJar: MemoryCookieJar,
) {

    /** 测试可替换；生产使用 [GarminEndpoints.forRegion] */
    internal var endpoints: (AccountRegion) -> GarminEndpoints = { GarminEndpoints.forRegion(it) }

    /** 用一次性 ticket 完成登录（网页登录 / WebView 截获的 ticket） */
    suspend fun completeWithTicket(
        request: GarminSsoLoginRequest,
        ticket: String,
    ): GarminSsoResult = withContext(Dispatchers.IO) {
        require(ticket.isNotBlank()) { "ticket 为空" }
        val ep = endpoints(request.region)

        val oauth1 = exchangeTicket(ep, ticket)
        val tokens = exchangeOAuth2(ep, oauth1)
        if (tokens.accessToken.isBlank()) {
            throw GarminNetworkException.Unknown("Garmin 未下发 access_token")
        }

        val profile = fetchProfile(ep, tokens.accessToken)

        GarminSsoResult(
            completed = true,
            session = SessionInfo(
                ssoToken = ticket,
                csrfToken = "",
                sessionKey = tokens.sessionKey,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtMs = tokens.expiresAtMs,
                oauth1Token = oauth1.token,
                oauth1Secret = oauth1.secret,
            ),
            profile = profile,
        )
    }

    /**
     * 刷新 access token：用保存的 OAuth1 token 重新 exchange。
     * 没有 OAuth1 token 说明是旧版本凭据，要求用户重新走网页登录。
     */
    // open：单测里要替换成"返回一张新令牌"的假实现，验证 401 重试链路
    open suspend fun refreshSession(credential: StorableCredential): StorableCredential =
        withContext(Dispatchers.IO) {
            if (credential.oauth1Token.isBlank() || credential.oauth1Secret.isBlank()) {
                throw GarminNetworkException.Unknown(
                    "本地没有可用的登录凭据（旧版本账号），请在登录页重新登录一次"
                )
            }
            val ep = endpoints(AccountRegion.from(credential.region))
            val tokens = exchangeOAuth2(
                ep,
                OAuth1Token(credential.oauth1Token, credential.oauth1Secret),
            )
            credential.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.ifBlank { credential.refreshToken },
                tokenExpiresAtMs = tokens.expiresAtMs,
            )
        }

    // ============================ 协议实现 ============================

    private data class OAuth1Token(val token: String, val secret: String)

    private data class TokenBundle(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
        val sessionKey: String,
    )

    /**
     * 第一步：ticket → oauth_token / oauth_token_secret。
     *
     * Garmin 这个端点对参数名很敏感，而且不同来源的 ticket 需要不同组合：
     * - 网页登录（/sso/signin）签发的 ticket 要带 `service`（与签发时一致）；
     * - 移动端登录的 ticket 走 `login-url`；
     * - 部分版本接受 `clientId`。
     * 因此这里按可能性依次尝试，第一组成功即返回；全部失败时把每组的
     * 状态码与 Garmin 原话一起抛出，避免"只知道失败、不知道为什么"。
     */
    private fun exchangeTicket(ep: GarminEndpoints, ticket: String): OAuth1Token {
        val base = ep.apiBase + "/oauth-service/oauth/preauthorized"
        val variants = listOf(
            // 网页登录（/sso/signin?service=sso/embed）签发的 ticket 走这一组
            "login-url" to linkedMapOf(
                "ticket" to ticket,
                "login-url" to "${ep.ssoOrigin}/sso/embed",
                "accepts-mfa-tokens" to "true",
            ),
            "service" to linkedMapOf(
                "ticket" to ticket,
                "service" to ep.mobileService,
                "accepts-mfa-tokens" to "true",
            ),
            "clientId" to linkedMapOf(
                "ticket" to ticket,
                "clientId" to "GCM_ANDROID_DARK",
                "accepts-mfa-tokens" to "true",
            ),
        )

        val failures = mutableListOf<String>()
        variants.forEach { (label, params) ->
            val url = base.toHttpUrl().newBuilder().apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()

            val request = OAuth1Signer.sign(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .header("Accept", "*/*")
                    .get()
                    .build()
            )

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val parsed = body.split("&").mapNotNull { pair ->
                        val idx = pair.indexOf('=')
                        if (idx <= 0) null
                        else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                            URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    }.toMap()
                    val token = parsed["oauth_token"].orEmpty()
                    val secret = parsed["oauth_token_secret"].orEmpty()
                    if (token.isNotBlank() && secret.isNotBlank()) {
                        return OAuth1Token(token, secret)
                    }
                    failures += "$label: 200 但缺 oauth_token（${body.take(80)}）"
                } else {
                    failures += "$label: HTTP ${response.code} ${body.take(90).trim()}"
                }
            }
        }
        throw GarminNetworkException.Unknown(
            "ticket 换 token 失败，已依次尝试 login-url / service / clientId：\n" +
                failures.joinToString("\n")
        )
    }

    /** 第二步：oauth_token → OAuth2 access_token */
    private fun exchangeOAuth2(ep: GarminEndpoints, oauth1: OAuth1Token): TokenBundle {
        val url = (ep.apiBase + "/oauth-service/oauth/exchange/user/2.0").toHttpUrl()
        val form = FormBody.Builder()
            .add("audience", "GARMIN_CONNECT_MOBILE_ANDROID_DI")
            .build()

        val request = OAuth1Signer.sign(
            Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(form)
                .build(),
            token = oauth1.token,
            tokenSecret = oauth1.secret,
        )

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GarminNetworkException.Unknown(
                    "换取 access_token 失败（HTTP ${response.code}）：${body.take(160).ifBlank { "无响应体" }}"
                )
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw GarminNetworkException.Unknown("token 响应不是 JSON（${body.take(120)}）")
            }
            val access = json.optString("access_token")
            if (access.isBlank()) {
                throw GarminNetworkException.Unknown("token 响应缺少 access_token（${body.take(120)}）")
            }
            val expiresIn = json.optLong("expires_in", 0L)
            return TokenBundle(
                accessToken = access,
                refreshToken = json.optString("refresh_token"),
                expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                sessionKey = json.optString("jti"),
            )
        }
    }

    private fun fetchProfile(ep: GarminEndpoints, accessToken: String): GarminProfile? =
        runCatching {
            val request = Request.Builder()
                .url((ep.apiBase + "/userprofile-service/socialProfile").toHttpUrl())
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                GarminProfile(
                    displayName = json.optString("displayName"),
                    fullName = json.optString("fullName"),
                    avatarUrl = json.optString("profileImageUrlLarge")
                        .ifBlank { json.optString("profileImageUrlMedium") },
                    email = json.optString("emailAddress"),
                    countryCode = json.optString("countryCode"),
                    units = json.optString("unitOfMeasure"),
                )
            }
        }.getOrNull()

    private companion object {
        /** 与 OAuth1 consumer 匹配的移动端 UA（garth 同样使用这个值） */
        const val MOBILE_USER_AGENT = "com.garmin.android.apps.connectmobile"
    }
}
