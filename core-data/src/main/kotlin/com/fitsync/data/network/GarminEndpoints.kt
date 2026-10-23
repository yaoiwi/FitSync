package com.fitsync.data.network

import com.fitsync.data.db.AccountRegion
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Garmin 三个域的地址：SSO、Connect API、Web。
 *
 * 中国区与国际区是两套完全独立的域名与账号体系，必须按 region 分别拼接。
 * 单独抽出来是为了让网络层可在 JVM 单测里指向 MockWebServer。
 */
data class GarminEndpoints(
    val ssoBase: String,
    val apiBase: String,
    val webBase: String,
    /** 移动端 SSO 的 service 参数（官方 App 用它换 ticket） */
    val mobileService: String = "https://mobile.integration.garmin.com/gcm/android",
) {
    /**
     * 站点根（scheme://host[:port]）。
     *
     * 注意：`ssoBase` 是 `https://sso.garmin.cn/sso`，而移动端接口在根路径下
     * （`https://sso.garmin.cn/mobile/api/login`）。**不能**用字符串截断求 origin——
     * `substringBefore("/sso")` 会把 `https://` 里的 `//sso` 匹配掉，得到 `https:/`。
     */
    val ssoOrigin: String
        get() {
            val url = ssoBase.toHttpUrl()
            val port = if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
            return "${url.scheme}://${url.host}$port"
        }

    companion object {
        fun forRegion(region: AccountRegion): GarminEndpoints = when (region) {
            AccountRegion.CN -> GarminEndpoints(
                ssoBase = "https://sso.garmin.cn/sso",
                apiBase = "https://connectapi.garmin.cn",
                webBase = "https://connect.garmin.cn",
                mobileService = "https://mobile.integration.garmin.cn/gcm/android",
            )
            AccountRegion.INTERNATIONAL -> GarminEndpoints(
                ssoBase = "https://sso.garmin.com/sso",
                apiBase = "https://connectapi.garmin.com",
                webBase = "https://connect.garmin.com",
            )
            AccountRegion.UNKNOWN -> throw GarminNetworkException.Unknown("未知区域，无法确定 Garmin 服务地址")
        }
    }
}
