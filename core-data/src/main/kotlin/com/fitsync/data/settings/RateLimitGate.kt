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
 * Garmin 的限流分两级，社区（python-garminconnect / garmin-grafana 的排障文档）
 * 反复记录过同一个规律：
 *
 * - **单请求限流**：一次 429 只影响当前这一点，`Retry-After` 通常 30–120 秒；
 * - **账号级锁定**：**10 分钟内第二次 429** 就会把整个账号锁 48–72 小时，
 *   期间**所有**接口都返回 429，`Retry-After` 也不再可信。
 *
 * 所以第一档给 30 分钟（远大于 10 分钟，保证不会连撞两次），
 * 后面按账号锁定的窗口逐级抬到 48 小时。这个表的目的是「宁可停久一点，
 * 也不要把账号推进 48 小时的黑屋」。
 */
object RateLimitBackoff {

    /** 连击 1、2、3、4 次以上对应的冷却时长 */
    private val LADDER_MS = longArrayOf(
        30 * 60_000L,           // 第 1 次：30 分钟
        6 * 60 * 60_000L,       // 第 2 次：6 小时
        24 * 60 * 60_000L,      // 第 3 次：24 小时
        48 * 60 * 60_000L,      // 第 4 次起：48 小时（账号锁定窗口）
    )

    /**
     * 超过这个时间没再撞限流，就当作账号已恢复，连击计数从 1 算起。
     *
     * 必须大于最长一档冷却（48 小时）：否则 48 小时冷却刚到点又撞一次，
     * 计数会被判成「已恢复」退回到 30 分钟，反而比实际需要的等待更短，
     * 用户又会连续试探，把刚解开的账号重新锁回去。
     */
    const val RESET_AFTER_MS = 7 * 24 * 60 * 60_000L

    /** 最长冷却：服务端给出更长的 `Retry-After` 时也不超过 72 小时 */
    const val MAX_MS = 72 * 60 * 60_000L

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
     *
     * 超过一天时必须带上日期：账号锁定是 48 小时级别，只写 `23:58` 会让人
     * 以为今晚就能恢复，于是到点继续点，又把锁定续上。
     */
    fun waitLabel(now: Long = System.currentTimeMillis()): String {
        val left = remainingMs(now)
        if (left <= 0L) return "已恢复"
        val until = now + left
        val clock = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(until))
        val day = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(until))
        val human = when {
            left < 60_000L -> "${(left / 1000).coerceAtLeast(1)} 秒"
            left < 60 * 60_000L -> "${(left / 60_000L).coerceAtLeast(1)} 分钟"
            left < 24 * 60 * 60_000L -> {
                val hours = left / (60 * 60_000L)
                val minutes = (left % (60 * 60_000L)) / 60_000L
                if (minutes > 0) "$hours 小时 $minutes 分" else "$hours 小时"
            }
            else -> {
                val days = left / (24 * 60 * 60_000L)
                val hours = (left % (24 * 60 * 60_000L)) / (60 * 60_000L)
                if (hours > 0) "$days 天 $hours 小时" else "$days 天"
            }
        }
        return if (left < 24 * 60 * 60_000L) "$human（$clock 后自动恢复）"
        else "$human（$day 后自动恢复）"
    }

    private companion object {
        const val PREFS = "fitsync_rate_limit"
        const val KEY_UNTIL = "until_ms"
        const val KEY_STRIKES = "strikes"
        const val KEY_LAST_HIT = "last_hit_ms"
    }
}
