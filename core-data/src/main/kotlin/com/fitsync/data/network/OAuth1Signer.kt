package com.fitsync.data.network

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OAuth 1.0a（HMAC-SHA1）请求签名。
 *
 * 为什么必须有：Garmin 的 `preauthorized`（ticket → oauth_token）与
 * `exchange/user/2.0`（oauth_token → access_token）都要求 OAuth1 签名。
 * 缺签名时服务端返回 `400 Bad Request`（不解释原因），
 * 加上签名后才会进入参数校验（401 + 具体原因）。
 *
 * consumer key/secret 是 Garmin 官方移动端 App 的公开凭据
 * （与 garth / garmin-connect 等项目使用的一致），不是应用私有密钥。
 */
internal object OAuth1Signer {

    const val CONSUMER_KEY = "fc3e99d2-118c-44b8-8ae3-03370dde24c0"
    const val CONSUMER_SECRET = "E08WAR897WEy2knn7aFBrvegVAf0AFdWBBF"

    private val random = SecureRandom()

    /**
     * 给请求加上 `Authorization: OAuth ...` 头。
     *
     * @param token OAuth1 token（第一步为空，第二步用 preauthorized 拿到的 oauth_token）
     * @param tokenSecret 对应的 secret（第一步为空）
     */
    fun sign(request: Request, token: String = "", tokenSecret: String = ""): Request {
        val url = request.url
        val oauthParams = linkedMapOf(
            "oauth_consumer_key" to CONSUMER_KEY,
            "oauth_nonce" to nonce(),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_version" to "1.0",
        )
        if (token.isNotEmpty()) oauthParams["oauth_token"] = token

        // 签名参数 = query + oauth_* + form body（当 Content-Type 为 form 时）
        val params = mutableListOf<Pair<String, String>>()
        url.queryParameterNames.forEach { name ->
            url.queryParameterValues(name).forEach { value -> params += name to (value ?: "") }
        }
        oauthParams.forEach { (k, v) -> params += k to v }
        (request.body as? FormBody)?.let { form ->
            for (i in 0 until form.size) params += form.name(i) to form.value(i)
        }

        val baseString = listOf(
            request.method.uppercase(),
            encode(baseUrl(url)),
            encode(
                params.sortedWith(compareBy({ it.first }, { it.second }))
                    .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
            ),
        ).joinToString("&")

        val key = "${encode(CONSUMER_SECRET)}&${encode(tokenSecret)}"
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        }
        val signature = Base64.getEncoder()
            .encodeToString(mac.doFinal(baseString.toByteArray(Charsets.UTF_8)))

        val header = (oauthParams + ("oauth_signature" to signature))
            .toSortedMap()
            .entries
            .joinToString(", ") { "${encode(it.key)}=\"${encode(it.value)}\"" }

        return request.newBuilder()
            .header("Authorization", "OAuth $header")
            .build()
    }

    private fun baseUrl(url: HttpUrl): String = buildString {
        append(url.scheme).append("://").append(url.host)
        if (url.port != HttpUrl.defaultPort(url.scheme)) append(":").append(url.port)
        append(url.encodedPath)
    }

    /** RFC 3986：除 A-Z a-z 0-9 - . _ ~ 之外全部百分号编码 */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")

    private fun nonce(): String = ByteArray(16)
        .also { random.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
}
