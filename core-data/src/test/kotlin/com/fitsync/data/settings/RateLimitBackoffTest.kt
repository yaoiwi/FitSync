package com.fitsync.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 限流退避表。
 *
 * 依据的是社区反复记录的 Garmin 两级限流：单请求 429 可恢复，
 * 但 **10 分钟内第二次 429 会把账号锁 48–72 小时**。所以第一档必须
 * 远大于 10 分钟，后面按账号锁定窗口逐级抬升。
 */
class RateLimitBackoffTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `第一次限流冷却三十分钟`() {
        assertEquals(30 * minute, RateLimitBackoff.cooldownMs(1))
    }

    @Test
    fun `第一档远大于十分钟的二次触发窗口`() {
        // 只要冷却短于 10 分钟，用户/自动同步就会在窗口内再撞一次，
        // 从而触发 48 小时账号锁定——这条断言就是防止有人把第一档改小
        assert(RateLimitBackoff.cooldownMs(1) > 10 * minute)
    }

    @Test
    fun `连续触发逐级抬升到账号锁定窗口`() {
        assertEquals(30 * minute, RateLimitBackoff.cooldownMs(1))
        assertEquals(6 * hour, RateLimitBackoff.cooldownMs(2))
        assertEquals(24 * hour, RateLimitBackoff.cooldownMs(3))
        assertEquals(48 * hour, RateLimitBackoff.cooldownMs(4))
    }

    @Test
    fun `超过倍率表长度仍是最后一档`() {
        assertEquals(48 * hour, RateLimitBackoff.cooldownMs(5))
        assertEquals(48 * hour, RateLimitBackoff.cooldownMs(99))
    }

    @Test
    fun `服务端 Retry-After 更长时以它为准`() {
        assertEquals(45 * minute, RateLimitBackoff.cooldownMs(1, retryAfterMs = 45 * minute))
    }

    @Test
    fun `Retry-After 比默认短时仍按默认来`() {
        assertEquals(30 * minute, RateLimitBackoff.cooldownMs(1, retryAfterMs = 2 * minute))
    }

    @Test
    fun `冷却不超过七十二小时`() {
        assertEquals(72 * hour, RateLimitBackoff.cooldownMs(4, retryAfterMs = 200 * hour))
    }

    @Test
    fun `连击窗口内再次触发算连击加一`() {
        val now = 1_000_000L
        assertEquals(2, RateLimitBackoff.nextStrikes(previousStrikes = 1, lastHitMs = now - 5 * minute, nowMs = now))
        assertEquals(4, RateLimitBackoff.nextStrikes(previousStrikes = 3, lastHitMs = now - 5 * minute, nowMs = now))
    }

    @Test
    fun `四十八小时冷却到点后再次触发不会退回第一档`() {
        // 关键回归：RESET 窗口必须大于最长冷却，否则 48 小时刚到点又撞一次会被
        // 判成「已恢复」，冷却退回 30 分钟，用户接着连点，账号重新被锁
        val now = 1_000_000_000L
        val lastHit = now - 48 * hour - 60_000L
        assertEquals(
            4,
            RateLimitBackoff.nextStrikes(previousStrikes = 4, lastHitMs = lastHit, nowMs = now),
        )
    }

    @Test
    fun `超过重置窗口视为账号已恢复重新从一档起步`() {
        val now = 1_000_000L
        val lastHit = now - RateLimitBackoff.RESET_AFTER_MS - 1
        assertEquals(1, RateLimitBackoff.nextStrikes(previousStrikes = 4, lastHitMs = lastHit, nowMs = now))
    }

    @Test
    fun `从没被限流过时首次就是一次`() {
        assertEquals(1, RateLimitBackoff.nextStrikes(previousStrikes = 0, lastHitMs = 0L, nowMs = 1_000_000L))
    }
}
