package com.fitsync.data.network

import com.fitsync.data.db.AccountRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Garmin 登录收尾：**CAS ticket → DI access token → 用户资料**。
 *
 * 凭据只在 Garmin 官方页面（WebView）里输入，本应用只接一张一次性 ticket，
 * 因此不需要、也不应该保存密码。
 *
 * ## 换令牌有两条路，新的在前
 *
 * ```
 * [新] POST https://diauth.garmin.com/di-oauth2-service/oauth/token
 *      Authorization: Basic base64("{clientId}:")
 *      form: client_id / service_ticket / grant_type / service_url
 *      ← grant_type = https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket
 *      → JSON: access_token / refresh_token / expires_in
 *
 * [旧] GET  {api}/oauth-service/oauth/preauthorized?ticket=...
 *      ← OAuth1 签名
 *      → form-urlencoded: oauth_token / oauth_token_secret
 *      POST {api}/oauth-service/oauth/exchange/user/2.0
 *      → access_token
 * ```
 *
 * **为什么两条路都要留**：新路（`diauth`）是官方新一代换令牌接口，旧路
 * （`connectapi/oauth-service/oauth/exchange`）是 garth 0.8.0 至今仍在用的接口。
 * 两者签出来的令牌**可能只差一个 scope**：
 * - 带正确 `audience` 的令牌 scope 里有 `CONNECT_READ`，能读数据；
 * - 换错代次/换错 client_id 时 Garmin 照样返回 200，只是 scope 里没有这条权限，
 *   请求业务接口就得到 `{"message":null,"error":"ForbiddenException"}`。
 *
 * 所以换取阶段一律用 [TokenBundle.canReadConnectData] 校验，缺权限当失败处理，
 * 继续换下一个 audience / client_id，绝不把坏令牌落库。
 *
 * 旧路保留为兜底：万一 `diauth` 对某些区域账号不可用，至少还能登录上。
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

    /**
     * DI 授权服务地址。
     *
     * 全球统一（不分 `garmin.cn` / `garmin.com`），所以不走 [GarminEndpoints]。
     * 抽成字段是为了让单测能把它指到 MockWebServer。
     */
    internal var diTokenUrl: String = DI_TOKEN_URL

    /** 本次运行里已经换成功过的 client_id / audience，续期时优先用，省掉试探 */
    @Volatile
    private var preferredClientId: String? = null

    @Volatile
    private var preferredAudience: String? = null

    /** 用一次性 ticket 完成登录（网页登录 / WebView 截获的 ticket） */
    suspend fun completeWithTicket(
        request: GarminSsoLoginRequest,
        ticket: String,
    ): GarminSsoResult = withContext(Dispatchers.IO) {
        require(ticket.isNotBlank()) { "ticket 为空" }
        val ep = endpoints(request.region)
        // CAS 要求 service 与签发 ticket 时一致；WebView 登录用的是 {ssoOrigin}/sso/embed
        val serviceUrl = "${ep.ssoOrigin}/sso/embed"

        var oauth1: OAuth1Token? = null
        val tokens = try {
            exchangeServiceTicket(ep, ticket, serviceUrl)
        } catch (e: GarminNetworkException.Unknown) {
            // 新路整体不被接受（所有 client_id 都 400/404）才退回旧路。
            // 429 / 403 直接冒泡——那是要用户等或换网络，不是「换条路试试」。
            oauth1 = exchangeTicket(ep, ticket)
            exchangeOAuth2(ep, oauth1)
        }

        if (tokens.accessToken.isBlank()) {
            throw GarminNetworkException.Unknown("Garmin 未下发 access_token")
        }
        GarminDiagnostics.recordToken(tokens.scope, "登录")

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
                oauth1Token = oauth1?.token.orEmpty(),
                oauth1Secret = oauth1?.secret.orEmpty(),
                diClientId = tokens.clientId,
            ),
            profile = profile,
        )
    }

    /**
     * 刷新 access token。
     *
     * 优先用 DI 的 `refresh_token`（新路账号一定有）；没有才退回旧的 OAuth1 重新
     * exchange（1.14.8 之前登录的账号）。
     */
    // open：单测里要替换成"返回一张新令牌"的假实现，验证 401 重试链路
    open suspend fun refreshSession(credential: StorableCredential): StorableCredential =
        withContext(Dispatchers.IO) {
            val clientId = credential.diClientId
                .ifBlank { extractClientId(credential.accessToken).orEmpty() }

            if (credential.refreshToken.isNotBlank() && clientId.isNotBlank()) {
                // 旧路换出的 refresh_token 在 diauth 上可能不被接受（Unknown / 400），
                // 这种情况回落到下面的 OAuth1 老路，而不是直接让用户重登。
                // 429 / 403 依旧冒泡：那是要等或换网络，重试没有意义。
                val tokens = try {
                    refreshDiToken(clientId, credential.refreshToken)
                } catch (e: GarminNetworkException.Unknown) {
                    null
                }
                if (tokens != null) {
                    GarminDiagnostics.recordToken(tokens.scope, "续期")
                    return@withContext credential.copy(
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken.ifBlank { credential.refreshToken },
                        tokenExpiresAtMs = tokens.expiresAtMs,
                        diClientId = tokens.clientId.ifBlank { clientId },
                    )
                }
            }

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
            GarminDiagnostics.recordToken(tokens.scope, "续期")
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
        /** 签发这张令牌的 client_id，刷新时要原样带回去 */
        val clientId: String = "",
        /**
         * 令牌自带的权限列表。
         *
         * 这是判断「403 是能力问题还是端点问题」的唯一依据：没有 `CONNECT_READ`
         * 的令牌能通过签名校验，但访问任何业务接口都会被判 `ForbiddenException`。
         */
        val scope: String = "",
    )

    /**
     * 新路：CAS ticket → DI access token（`diauth.garmin.com`）。
     *
     * 与旧路的三点不同，缺一不可：
     * 1. 鉴权是 **Basic base64("{client_id}:")**，不是 OAuth1 签名；
     * 2. 参数是 `service_ticket` + `service_url`，不是 `ticket` + `login-url`；
     * 3. `grant_type` 是一个 URL，指向 `di-oauth2-service` 的 service_ticket 授权。
     *
     * `client_id` 有代次，新的在前。某个代次被拒（400/404）就换下一个；
     * 429 / 403 直接抛，不再继续撞。
     */
    private fun exchangeServiceTicket(
        ep: GarminEndpoints,
        ticket: String,
        serviceUrl: String,
    ): TokenBundle {
        val failures = mutableListOf<String>()
        val candidates = buildList {
            preferredClientId?.let { add(it) }
            addAll(DI_CLIENT_IDS.filter { it != preferredClientId })
        }

        for (clientId in candidates) {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("service_ticket", ticket)
                .add("grant_type", DI_GRANT_TYPE)
                .add("service_url", serviceUrl)
                .build()

            val builder = Request.Builder()
                .url(diTokenUrl.toHttpUrl())
                .header("Authorization", basicAuth(clientId))
                .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cache-Control", "no-cache")
            GarminUserAgent.deviceHeaders().forEach { (name, value) -> builder.header(name, value) }

            httpClient.newCall(builder.post(form).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val tokens = parseToken(body).copy(clientId = clientId)
                        if (tokens.canReadConnectData()) {
                            preferredClientId = clientId
                            return tokens
                        }
                        // 换到令牌但 scope 缺 CONNECT_READ：这个 client_id 只能签到
                        // 「能验签、不能读数据」的令牌，拿去同步照样 403
                        failures += "$clientId: scope 缺 CONNECT_READ"
                    }
                    response.code == 429 -> throw GarminNetworkException.RiskBlocked(
                        IOException("diauth 换令牌被限流（429）"),
                        retryAfterMs = response.header("Retry-After")?.toLongOrNull()
                            ?.takeIf { it > 0 }?.times(1000L),
                    )
                    response.code == 403 -> throw GarminNetworkException.Forbidden(
                        endpoint = "diauth/token",
                        cause = IOException("diauth 换令牌被拒（403）"),
                    )
                    // 401 说明 ticket 本身不被接受，换 client_id 也是同样的结果，不必再试
                    response.code == 401 -> {
                        failures += "$clientId: 401 ${body.take(120).trim()}"
                        break
                    }
                    else -> failures += "$clientId: HTTP ${response.code} ${body.take(120).trim()}"
                }
            }
        }
        throw GarminNetworkException.Unknown(
            "diauth 换令牌失败，已依次尝试 ${candidates.size} 个 client_id：\n" +
                failures.joinToString("\n")
        )
    }

    /** 用 refresh_token 续 DI 令牌 */
    private fun refreshDiToken(clientId: String, refreshToken: String): TokenBundle {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .build()

        val builder = Request.Builder()
            .url(diTokenUrl.toHttpUrl())
            .header("Authorization", basicAuth(clientId))
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cache-Control", "no-cache")
        GarminUserAgent.deviceHeaders().forEach { (name, value) -> builder.header(name, value) }

        httpClient.newCall(builder.post(form).build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> return parseToken(body).copy(clientId = clientId)
                response.code == 429 -> throw GarminNetworkException.RiskBlocked(
                    IOException("diauth 续期被限流（429）"),
                    retryAfterMs = response.header("Retry-After")?.toLongOrNull()
                        ?.takeIf { it > 0 }?.times(1000L),
                )
                response.code == 403 -> throw GarminNetworkException.Forbidden(
                    endpoint = "diauth/refresh",
                    cause = IOException("diauth 续期被拒（403）"),
                )
            }
            throw GarminNetworkException.Unknown(
                "DI 令牌续期失败（HTTP ${response.code}）：${body.take(160).ifBlank { "无响应体" }}"
            )
        }
    }

    /**
     * 旧路第一步：ticket → oauth_token / oauth_token_secret。
     *
     * Garmin 这个端点对参数名很敏感，不同来源的 ticket 需要不同组合：
     * 网页登录（`/sso/signin`）签发的 ticket 要带 `service`，移动端登录走 `login-url`。
     * 因此按可能性依次尝试，第一组成功即返回；全部失败时把每组的
     * 状态码与 Garmin 原话一起抛出。
     */
    private fun exchangeTicket(ep: GarminEndpoints, ticket: String): OAuth1Token {
        val base = ep.apiBase + "/oauth-service/oauth/preauthorized"
        val variants = listOf(
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
                    .header("Accept", "*/*")
                    .header("User-Agent", GarminUserAgent.current())
                    .get()
                    .build()
            )

            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
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
                    failures += "$label: HTTP ${resp.code} ${body.take(90).trim()}"
                }
            }
        }
        throw GarminNetworkException.Unknown(
            "ticket 换 token 失败，已依次尝试 login-url / service / clientId：\n" +
                failures.joinToString("\n")
        )
    }

    /**
     * 旧路第二步：oauth_token → OAuth2 access token。
     *
     * `audience` 决定签出来的是哪一代 DI 令牌。新网关只认新代次，旧名字虽然还能
     * 换出 token，但拿去请求业务接口会被判 `{"message":null,"error":"ForbiddenException"}`
     * （不像「没带令牌」那样带着 `HTTP 403 Forbidden` 的 message）。
     */
    private fun exchangeOAuth2(ep: GarminEndpoints, oauth1: OAuth1Token): TokenBundle {
        val url = (ep.apiBase + "/oauth-service/oauth/exchange/user/2.0").toHttpUrl()

        fun build(audience: String): Request {
            val form = FormBody.Builder().add("audience", audience).build()
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
            GarminUserAgent.deviceHeaders().forEach { (name, value) -> builder.header(name, value) }
            return OAuth1Signer.sign(builder.post(form).build(), oauth1.token, oauth1.secret)
        }

        val candidates = buildList {
            preferredAudience?.let { add(it) }
            addAll(AUDIENCES.filter { it != preferredAudience })
        }

        val failures = mutableListOf<String>()

        var lastCode = 0
        var lastBody = ""
        for (audience in candidates) {
            var tryNext = false
            httpClient.newCall(build(audience)).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val tokens = parseToken(body)
                    // 换到令牌不等于能用：audience 不对时 Garmin 照样签发，
                    // 只是 scope 里没有 CONNECT_READ，拿去请求业务接口就是 403。
                    if (tokens.canReadConnectData()) {
                        preferredAudience = audience
                        return tokens
                    }
                    failures += "$audience: scope 缺 CONNECT_READ"
                    tryNext = true
                } else {
                    when (resp.code) {
                        429 -> throw GarminNetworkException.RiskBlocked(
                            IOException("换取 access_token 被限流（429）"),
                            retryAfterMs = resp.header("Retry-After")?.toLongOrNull()
                                ?.takeIf { it > 0 }?.times(1000L),
                        )
                        403 -> throw GarminNetworkException.Forbidden(
                            endpoint = "oauth/exchange",
                            cause = IOException("换取 access_token 被拒（403）"),
                        )
                    }
                    lastCode = resp.code
                    lastBody = body
                    // 400 / 404 才是「这个 audience 不被接受」，换下一个；其它错误立刻停，
                    // 不要用一串注定失败的请求去撞限流
                    if (resp.code == 400 || resp.code == 404) {
                        failures += "$audience: HTTP ${resp.code} ${body.take(90).trim()}"
                        tryNext = true
                    } else {
                        throw GarminNetworkException.Unknown(
                            "换取 access_token 失败（HTTP ${resp.code}）：" +
                                body.take(160).ifBlank { "无响应体" }
                        )
                    }
                }
            }
            if (!tryNext) break
        }
        if (failures.isNotEmpty()) {
            throw GarminNetworkException.Unknown(
                "换取 access_token 失败，已依次尝试 ${candidates.size} 个 audience：\n" +
                    failures.joinToString("\n")
            )
        }
        throw GarminNetworkException.Unknown(
            "换取 access_token 失败（HTTP $lastCode）：${lastBody.take(160).ifBlank { "无响应体" }}"
        )
    }

    private fun parseToken(body: String): TokenBundle {
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
            clientId = extractClientId(access).orEmpty(),
            scope = json.optString("scope"),
        )
    }

    /**
     * 这张令牌能不能读业务数据。
     *
     * Garmin 的 OAuth2 响应里 `scope` 是一串空格分隔的权限名，真正决定
     * `connectapi` 能不能用的是 `CONNECT_READ`。缺了它，请求不会 401，
     * 而是回 `{"message":null,"error":"ForbiddenException"}` —— 也就是用户
     * 反复看到的那种 403。所以在换取阶段就要判掉，别让坏令牌进库。
     */
    private fun TokenBundle.canReadConnectData(): Boolean =
        scope.isBlank() || scope.split(' ').any { it == "CONNECT_READ" }

    /**
     * 从 JWT 的 payload 里取 `client_id`。
     *
     * 只是兜底：DI 令牌是 JWT，`client_id` 就写在里面，这样即使本地没存过
     * （旧版本账号）也能知道该用哪个 client_id 去续期。
     */
    private fun extractClientId(token: String): String? = runCatching {
        val parts = token.split(".")
        if (parts.size < 2) return@runCatching null
        val padded = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
        val json = JSONObject(String(java.util.Base64.getUrlDecoder().decode(padded)))
        json.optString("client_id").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun basicAuth(clientId: String): String =
        "Basic " + "$clientId:".encodeUtf8().base64()

    private fun fetchProfile(ep: GarminEndpoints, accessToken: String): GarminProfile? =
        runCatching {
            val builder = Request.Builder()
                .url((ep.apiBase + "/userprofile-service/socialProfile").toHttpUrl())
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $accessToken")
            GarminUserAgent.deviceHeaders().forEach { (name, value) -> builder.header(name, value) }

            httpClient.newCall(builder.get().build()).execute().use { response ->
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
        /** DI 授权服务：全球统一，不随区域变化 */
        const val DI_TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"

        /** `grant_type` 是一个 URL，指向 service_ticket 授权 */
        const val DI_GRANT_TYPE =
            "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"

        /**
         * DI 的 client_id，新的在前。
         *
         * 第一位是 garth 0.8.0 登录时用的 `GCM_ANDROID_DARK` 对应的 DI 名字；
         * 季度后缀（`_2025Q2` / `_2024Q4`）是社区里流传的猜测，实测换出来的令牌
         * 常常缺 `CONNECT_READ`，所以只作兜底。
         */
        val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_IOS_DI",
        )

        /**
         * 旧路第二步的 audience，按「实测可用」排序。
         *
         * 第一位来自 garth 0.8.0 的 `sso.exchange()`：`GARMIN_CONNECT_MOBILE_ANDROID_DI`，
         * **不带季度后缀**。换出来的令牌带完整 scope（含 `CONNECT_READ`）。
         * 带后缀的那两个换出来的令牌看着正常，实际读不了数据，只能排在后面。
         */
        val AUDIENCES = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
        )

    }
}
