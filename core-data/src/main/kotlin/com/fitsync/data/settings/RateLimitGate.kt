package com.fitsync.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 429 冷却倍率表。
 *
 * Garmin 的限流不是一个固定窗口：连续触发会让窗口变长。实测的规律是
 * 「10 分钟 → 半小时 → 1 小时」逐级抬升，所以这里做指数式退避，
 * 而不是每次都让用户等 10 分钟再撞一次。
 */
object RateLimitBackoff {

    /** 连击 1、2、3、4 次以上对应的冷却时长 */
    private val LADDER_MS = longArrayOf(
        10 * 60_000L,
        30 * 60_000L,
        60 * 60_000L,
        3 * 60 * 60_000L,
    )

    /** 超过这个时间没再撞限流，就当作账号已恢复，连击计数清零 */
    const val RESET_AFTER_MS = 6 * 60 * 60_000L

    /** 最长冷却：再久就不该让用户干等了，届时应改为提示检查账号状态 */
    const val MAX_MS = 6 * 60 * 60_000L

    /**
     * 本次应冷却多久。
     *
     * @param strikes 这是连续第几次触发（从 1 开始）
     * @param retryAfterMs 服务端 `Retry-After` 给的秒数，有就以它为准（取两者较大值）
     */
    fun cooldownMs(strikes: Int, retryAfterMs: Long? = null): Long {
        val base = LADDER_MS[(strikes - 1).coerceIn(0, LADDER_MS.lastIndex)]
        return maxOf(base, retryAfterMs ?: 0L).coerceAtMost(MAX_MS)
    }

    /**
     * 计算这次算第几次连击。
     *
     * 距上次触发超过 [RESET_AFTER_MS] 说明已经恢复正常，重新从 1 开始，
     * 否则在原有基础上 +1，最多停在倍率表长度。
     */
    fun nextStrikes(previousStrikes: Int, lastHitMs: Long, nowMs: Long): Int =
        if (nowMs - lastHitMs > RESET_AFTER_MS) 1
        else (previousStrikes + 1).coerceIn(1, LADDER_MS.size)
}

/**
 * 全局的「Garmin 限流冷却闸门」。
 *
 * **为什么需要它**：以前 429 只影响当次操作，界面上写一句「等 5–10 分钟」就完了。
 * 但用户看到失败会立刻再点一次，自动同步也会在每次打开 App 时重试——
 * 每一次重试都会把 Garmin 的限流窗口往后推，结果就是「一直显示账号限制」，
 * 明明什么都没做却永远恢复不了。
 *
 * 这里把「下次可以发请求的时间」持久化下来（进程重启、换页面都有效），
 * 冷却期内 **任何** Garmin 请求入口（手动同步 / 自动同步 / 后台 Worker / 搬运 / 复制活动）
 * 都直接跳过，一个请求也不发，等窗口自然过去。
 */
@Singleton
class RateLimitGate @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 距离解禁还剩多少毫秒，0 表示现在可以请求 */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        (prefs.getLong(KEY_UNTIL, 0L) - now).coerceAtLeast(0L)

    fun isBlocked(now: Long = System.currentTimeMillis()): Boolean = remainingMs(now) > 0L

    /** 最近一次触发的连击次数，用于展示「这是第几次」 */
    fun strikes(): Int = prefs.getInt(KEY_STRIKES, 0)

    /**
     * 记一次限流，返回解禁时间点（毫秒时间戳）。
     *
     * 每撞一次 429 就调用一次：冷却时长按连击次数递增，避免用户反复点击
     * 把账号越锁越久。
     */
    fun noteRateLimited(
        retryAfterMs: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val strikes = RateLimitBackoff.nextStrikes(
            previousStrikes = prefs.getInt(KEY_STRIKES, 0),
            lastHitMs = prefs.getLong(KEY_LAST_HIT, 0L),
            nowMs = now,
        )
        val until = now + RateLimitBackoff.cooldownMs(strikes, retryAfterMs)
        prefs.edit()
            .putLong(KEY_UNTIL, until)
            .putInt(KEY_STRIKES, strikes)
            .putLong(KEY_LAST_HIT, now)
            .apply()
        return until
    }

    /** 一次完整成功的同步：账户已恢复，清掉连击计数（冷却已过的情况下） */
    fun noteSuccess(now: Long = System.currentTimeMillis()) {
        if (remainingMs(now) > 0L) return
        prefs.edit().putInt(KEY_STRIKES, 0).putLong(KEY_LAST_HIT, 0L).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * 给界面用的剩余时间文案，例如 `12 分钟（21:58 后自动恢复）`。
     * 不足 1 分钟时显示秒，避免「还有 0 分钟」这种看不懂的提示。
     */
    fun waitLabel(now: Long = System.currentTimeMillis()): String {
        val left = remainingMs(now)
        if (left <= 0L) return "已恢复"
        val until = now + left
        val clock = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(until))
        val human = when {
            left < 60_000L -> "${(left / 1000).coerceAtLeast(1)} 秒"
            left < 60 * 60_000L -> "${(left / 60_000L).coerceAtLeast(1)} 分钟"
            else -> {
                val hours = left / (60 * 60_000L)
                val minutes = (left % (60 * 60_000L)) / 60_000L
                if (minutes > 0) "$hours 小时 $minutes 分" else "$hours 小时"
            }
        }
        return "$human（$clock 后自动恢复）"
    }

    private companion object {
        const val PREFS = "fitsync_rate_limit"
        const val KEY_UNTIL = "until_ms"
        const val KEY_STRIKES = "strikes"
        const val KEY_LAST_HIT = "last_hit_ms"
    }
}
