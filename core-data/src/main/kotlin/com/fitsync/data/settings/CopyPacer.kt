package com.fitsync.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 搬运时的并发档位（同时处理几天）。
 *
 * **为什么不是写死 2**：真实的限流阈值既看不到也测不准——同一账号不同时段都不同。
 * 写死 2 对「一年期搬运」是保守的（约 25 分钟），但对「一次搬 30 天」又完全够用；
 * 写死 3 则可能在某些账号上直接把自己送进 48 小时锁定。
 *
 * 这里的策略是**只朝好的一边走**：
 * - 起步 [DEFAULT_PARALLEL] 天（与原来一致，不改变已知安全的默认手感）；
 * - 连续 [CLEAN_DAYS_TO_RAISE] 天干净通过（没有 429），升一档，最多到 [MAX_PARALLEL]；
 * - 撞到一次 429 立刻退回 1 档，并把干净计数清零——恢复需要重新证明自己。
 *
 * 档位持久化在 prefs 里：429 的锁定是 48 小时级别的，进程重启后如果又回到 2 档，
 * 用户一开 App 就再撞一次。记住「上次是被限流打下来的」，下次从 1 档慢慢爬。
 */
@Singleton
class CopyPacer @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前档位，1..[MAX_PARALLEL] */
    val parallel: Int
        get() = prefs.getInt(KEY_PARALLEL, DEFAULT_PARALLEL).coerceIn(1, MAX_PARALLEL)

    /** 距离升档还差几天干净通过 */
    fun cleanStreak(): Int = prefs.getInt(KEY_CLEAN, 0)

    /**
     * 一天顺利处理完（传上去了、或者服务端说已有），记一次干净通过。
     * 攒够 [CLEAN_DAYS_TO_RAISE] 天就升一档。
     */
    @Synchronized
    fun noteCleanDay() {
        val current = parallel
        val streak = cleanStreak() + 1
        if (current < MAX_PARALLEL && streak >= CLEAN_DAYS_TO_RAISE) {
            prefs.edit()
                .putInt(KEY_PARALLEL, current + 1)
                .putInt(KEY_CLEAN, 0)
                .apply()
        } else {
            prefs.edit().putInt(KEY_CLEAN, streak).apply()
        }
    }

    /** 撞到 429：直接退回 1 档，干净计数清零 */
    @Synchronized
    fun noteRateLimited() {
        prefs.edit()
            .putInt(KEY_PARALLEL, 1)
            .putInt(KEY_CLEAN, 0)
            .apply()
    }

    /** 给界面用的一句话，说明现在几档、为什么 */
    fun label(): String = when {
        parallel >= MAX_PARALLEL -> "$parallel 天并行（连续 $CLEAN_DAYS_TO_RAISE 天没被限流后自动提速）"
        cleanStreak() > 0 -> "$parallel 天并行，再有 ${CLEAN_DAYS_TO_RAISE - cleanStreak()} 天没被限流会提到 ${parallel + 1} 天"
        else -> "$parallel 天并行"
    }

    private companion object {
        const val PREFS = "fitsync_copy_pacer"
        const val KEY_PARALLEL = "parallel"
        const val KEY_CLEAN = "clean_streak"

        /** 与 1.5.x 的手感一致：两天是已经验证过不会触发 429 的值 */
        const val DEFAULT_PARALLEL = 2

        /** 上限 3：再往上就是明显的突发流量，风险收益比不划算 */
        const val MAX_PARALLEL = 3

        /** 连续这么多天没 429 才敢升一档 */
        const val CLEAN_DAYS_TO_RAISE = 20
    }
}
