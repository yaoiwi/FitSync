package com.fitsync.data.network

import com.fitsync.data.db.AccountRegion

/**
 * Garmin SSO 网络层模型（最小可联调版）。
 * 覆盖：账号密码登录 → 会话交换 → 用户资料 → MFA 分支。
 *
 * 说明：
 * - 这些对象用于 SSO 出站请求/入站响应的最小承载；
 * - 与本地 Room 实体解耦，后续真实响应体变化时优先改这里，不改 UI。
 */

// ======================= 登录请求 =======================

data class GarminSsoLoginRequest(
    val region: AccountRegion,
    /** 网页登录时邮箱只是给账号卡用的显示值，可能为空 */
    val email: String = "",
)

// ======================= 会话 / Profile =======================

data class SessionInfo(
    val ssoToken: String,
    val csrfToken: String,
    /** Connect 侧 sessionKey（旧 Web 接口用） */
    val sessionKey: String,
    /** OAuth access token：后续同步接口的鉴权凭据 */
    val accessToken: String = "",
    val refreshToken: String = "",
    /** access token 过期时间（epoch ms），0 表示未知 */
    val expiresAtMs: Long = 0L,
    /** OAuth1 token，用来刷新 access token（长期有效） */
    val oauth1Token: String = "",
    val oauth1Secret: String = "",
) {
    val hasOauthToken: Boolean get() = accessToken.isNotBlank()
}

data class GarminProfile(
    val displayName: String,
    val fullName: String,
    val avatarUrl: String,
    val email: String,
    val countryCode: String,
    val units: String,
)

// ======================= MFA 分支 =======================

data class MfaChallenge(
    /** Garmin 当前主要走 authenticator；保留扩展位 */
    val channel: String = "authenticator",
    val maskedContact: String? = null,
    /** 剩余有效期（秒），供 UI 倒计时 */
    val expiresInSeconds: Int = 120,
)

/**
 * 登录结果聚合。
 * [completed=true] 表示已拿到 [session] + [profile]，可落库；
 * [completed=false] 且 [challenge!=null] 表示需要 MFA 二次验证。
 */
data class GarminSsoResult(
    val completed: Boolean,
    val session: SessionInfo? = null,
    val profile: GarminProfile? = null,
    val challenge: MfaChallenge? = null,
    /** 登录成功但 ticket 换 token 失败时的原因（同步会不可用，必须让用户/日志看到） */
    val tokenError: String? = null,
) {
    /** 缓存命中（无 session/profile）专用构造器 */
    companion object {
        fun cachedHit() = GarminSsoResult(completed = true)
    }
}

// ======================= 网络异常 =======================

sealed class GarminNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 网络不可达 / 超时 */
    class NetworkUnavailable(cause: Throwable) :
        GarminNetworkException("无法访问 Garmin SSO：${cause.message}", cause)

    /** 登录凭据错误 */
    class BadCredentials(cause: Throwable? = null) :
        GarminNetworkException("账号或密码错误", cause)

    /** 账号被风控 / 锁定 */
    class RiskBlocked(cause: Throwable? = null) :
        GarminNetworkException("Garmin 正在限流（HTTP 429）：等 5–10 分钟再试，期间别连续点同步", cause)

    /** MFA 错误 */
    class MfaInvalid(message: String = "验证码错误或已过期") :
        GarminNetworkException(message)

    /**
     * 服务端拒绝当前 access_token（HTTP 401）。
     *
     * 单独成一类，是因为它可以被自动修复：调用方应拿 refresh_token / OAuth1
     * 换一张新令牌后重试，而不是直接判失败。
     */
    class Unauthorized(message: String = "access_token 已失效（401）") :
        GarminNetworkException(message)

    /** 凭据已无法自动续期，只能让用户重新登录 */
    class SessionExpired(message: String) :
        GarminNetworkException(message)

    /** 未知错误 */
    class Unknown(message: String, cause: Throwable? = null) :
        GarminNetworkException(message, cause)
}