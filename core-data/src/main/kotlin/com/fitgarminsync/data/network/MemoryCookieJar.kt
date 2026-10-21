package com.fitgarminsync.data.network

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 内存 CookieJar。
 *
 * Garmin SSO 是标准 Cookie 会话：bootstrap 拿到的 `JSESSIONID` / `XSRF-TOKEN`
 * 必须原样带回登录 POST、MFA POST 以及后续 profile 请求，否则服务端每次都是新会话，
 * 登录永远拿不到 ticket。
 *
 * 只放在内存里：凭据本身由 [CredentialVault] 加密落盘，这里存的是会话态，
 * 进程重启后重新登录即可，不落盘反而更安全。
 */
@Singleton
class MemoryCookieJar @Inject constructor() : CookieJar {

    private val store = ConcurrentHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            val key = keyOf(cookie)
            if (cookie.expiresAt < now) store.remove(key) else store[key] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.filter { it.expiresAt >= now && it.matches(url) }
    }

    fun clear() = store.clear()

    /**
     * 导入 WebView 的 Cookie 头（形如 `SESSION=xxx; GARMIN-SSO=1`）。
     *
     * 为什么需要：CAS ticket 与签发它的 SSO 会话绑定，
     * 换 token 时如果换成另一个"干净"的会话，服务端会认为
     * "ticket 不存在"，返回 `401 Expected parameters oauth token or SSO ticket`。
     * WebView 登录成功后，把它的 Cookie 灌进来，等于沿用同一个会话。
     */
    fun importFromHeader(host: String, cookieHeader: String?) {
        if (cookieHeader.isNullOrBlank()) return
        cookieHeader.split(';').forEach { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@forEach
            val name = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (name.isEmpty()) return@forEach
            val cookie = runCatching {
                Cookie.Builder()
                    .domain(host.removePrefix("."))
                    .path("/")
                    .name(name)
                    .value(value)
                    .expiresAt(Long.MAX_VALUE)
                    .build()
            }.getOrNull() ?: return@forEach
            store["${cookie.domain}|/|${cookie.name}"] = cookie
        }
    }

    fun snapshot(): Map<String, String> =
        store.values.associate { "${it.domain}${it.path}:${it.name}" to it.value }

    private fun keyOf(cookie: Cookie) = "${cookie.domain}|${cookie.path}|${cookie.name}"
}
