package com.fitsync.data.network

/**
 * access_token 是否还值得直接拿去用。
 *
 * 这里踩过一个坑：只判断 `accessToken.isNotBlank()` 的话，本地那张一小时后必过期的
 * 令牌会被一直复用，同步直接吃 401。现在改为按 `tokenExpiresAtMs` 判断：
 *
 * - `tokenExpiresAtMs <= 0`：老版本凭据没记过期时间，先信任，交给 401 重试兜底；
 * - 剩余时间不足 [EXPIRY_SKEW_MS]：提前换牌，避免请求发到一半就过期。
 */
internal fun StorableCredential.isTokenFresh(now: Long = System.currentTimeMillis()): Boolean =
    accessToken.isNotBlank() &&
        (tokenExpiresAtMs <= 0L || tokenExpiresAtMs - now > EXPIRY_SKEW_MS)

/** 提前 5 分钟换牌 */
internal const val EXPIRY_SKEW_MS = 5 * 60 * 1000L
