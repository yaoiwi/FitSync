package com.fitsync.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 限流退避表：429 是「越点越久」，所以冷却时长必须逐级抬升。
 */
class RateLimitBackoffTest {

    private val minute = 60_000L

    @Test
    fun `第一次限流冷却十分钟`() {
        assertEquals(10 * minute, RateLimitBackoff.cooldownMs(1))
    }

    @Test
    fun `连续触发逐级抬升`() {
        assertEquals(10 * minute, RateLimitBackoff.cooldownMs(1))
        assertEquals(30 * minute, RateLimitBackoff.cooldownMs(2))
        assertEquals(60 * minute, RateLimitBackoff.cooldownMs(3))
        assertEquals(180 * minute, RateLimitBackoff.cooldownMs(4))
    }

    @Test
    fun `超过倍率表长度仍是最后一档`() {
        assertEquals(180 * minute, RateLimitBackoff.cooldownMs(5))
        assertEquals(180 * minute, RateLimitBackoff.cooldownMs(99))
    }

    @Test
    fun `服务端 Retry-After 更长时以它为准`() {
        assertEquals(45 * minute, RateLimitBackoff.cooldownMs(1, retryAfterMs = 45 * minute))
    }

    @Test
    fun `Retry-After 比默认短时仍按默认来`() {
        assertEquals(10 * minute, RateLimitBackoff.cooldownMs(1, retryAfterMs = 2 * minute))
    }

    @Test
    fun `冷却不超过六小时`() {
        assertEquals(6 * 60 * minute, RateLimitBackoff.cooldownMs(4, retryAfterMs = 12 * 60 * minute))
    }

    @Test
    fun `六小时内再次触发算连击加一`() {
        val now = 1_000_000L
        assertEquals(2, RateLimitBackoff.nextStrikes(previousStrikes = 1, lastHitMs = now - 5 * minute, nowMs = now))
        assertEquals(4, RateLimitBackoff.nextStrikes(previousStrikes = 3, lastHitMs = now - 5 * minute, nowMs = now))
    }

    @Test
    fun `超过六小时视为账号已恢复重新从一档起步`() {
        val now = 1_000_000L
        val lastHit = now - RateLimitBackoff.RESET_AFTER_MS - 1
        assertEquals(1, RateLimitBackoff.nextStrikes(previousStrikes = 4, lastHitMs = lastHit, nowMs = now))
    }

    @Test
    fun `从没被限流过时首次就是一次`() {
        assertEquals(1, RateLimitBackoff.nextStrikes(previousStrikes = 0, lastHitMs = 0L, nowMs = 1_000_000L))
    }
}
