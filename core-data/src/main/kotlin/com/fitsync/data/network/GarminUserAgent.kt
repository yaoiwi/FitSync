package com.fitsync.data.network

/**
 * 发给 `connectapi` / `diauth` / SSO 的身份头。
 *
 * **只发一个 `User-Agent`，且是 iOS 的指纹。**
 *
 * 这一版把 1.14.6～1.14.9 的猜测全部推翻了。事实依据来自 garth 0.8.0
 * （Python 社区维护的 Garmin 客户端，随包发布的 cassettes 里记录了真实请求）：
 * 它对 `connectapi` 发的头只有 `User-Agent: GCM-iOS-5.22.1.4`，
 * **没有任何 `X-Garmin-*`**，登录用的却是 Android 的 `GCM_ANDROID_DARK`。
 *
 * 反过来看我们自己的诊断：1.14.5～1.14.9 期间，无论发 `GCM-Android-5.23` 还是
 * 浏览器 UA、无论带不带 `X-Garmin-*`，业务接口一律回
 * `{"message":null,"error":"ForbiddenException"}`——说明被挡的**不是**「缺设备头」，
 * 而是「这套指纹不在放行名单里」。Android 指纹在这轮收紧里被整段拉黑了。
 *
 * 因此这里刻意反向操作：**去掉全部 `X-Garmin-*`，UA 换成 iOS**。
 * 多带一个服务端不认识的设备头只会增加被判定为伪造的风险。
 */
object GarminUserAgent {

    /**
     * 官方 iOS App 的 User-Agent。
     *
     * 与 garth 0.8.0 的 `http.USER_AGENT` 保持一致。改动它之前先确认 garth 是否也改了，
     * 否则会再次出现「自己觉得更像官方、实际被网关拒绝」的情况。
     */
    const val MOBILE = "GCM-iOS-5.22.1.4"

    fun current(): String = MOBILE

    /**
     * 完整请求头。
     *
     * 只有 UA。不要加回 `X-Garmin-*`：1.14.8 加过一整套，结果 403 一条没少。
     */
    fun deviceHeaders(): Map<String, String> = linkedMapOf(
        "User-Agent" to MOBILE,
    )
}
