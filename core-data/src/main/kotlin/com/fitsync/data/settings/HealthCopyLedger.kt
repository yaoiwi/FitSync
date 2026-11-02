package com.fitsync.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记录「哪一天的健康数据刚搬到哪个账号」。
 *
 * **为什么需要单独记**：搬运成功后，目标账号的本地日汇总要等一次同步才会出现。
 * 在这之前再点一次搬运，会重复下载 + 重复上传（服务端返回 409 判重）。
 * 记一下，[SKIP_WINDOW_MS] 内点第二次就不再重复打接口。
 *
 * **为什么带时间戳而不是只记日期**：只记日期的话，用户若怀疑没搬成功，
 * 就永远被跳过、也没法重试。超过窗口自动失效，重试是允许的。
 *
 * **为什么在内存里攒着、隔几天才落盘**：一年期搬运要记 365 次。原来每次都是
 * 「读全量字符串 → 逐项解析 → 拼回去 → 写 XML」，365 次就是 O(n²) 的字符串解析
 * 加 365 次磁盘写，在搬运这个本来就慢的流程里白占时间。现在只在进程内改 map，
 * 每 [FLUSH_EVERY] 条落一次盘；最坏情况丢这十几条记录，重试时服务端 409 直接跳过，
 * 代价只是多几个请求。
 */
@Singleton
class HealthCopyLedger @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** `from_to` → (日期 → 记账时间)。进程内唯一真相，[prefs] 只是它的持久化副本 */
    private val cache = mutableMapOf<String, MutableMap<String, Long>>()

    /** 哪些键有未落盘的改动 */
    private val dirty = mutableSetOf<String>()
    private var pendingWrites = 0

    /** 刚搬过的日期：在 [SKIP_WINDOW_MS] 内、且没被判定为「这天不用搬」的才在里面 */
    @Synchronized
    fun copiedDates(fromAccountId: Long, toAccountId: Long): Set<String> =
        alive(key(fromAccountId, toAccountId)).keys.toSet()

    // 搬运两天并行，读-改-写必须串行，否则两个工人的写入会互相覆盖，
    // 表现是「明明搬过，下一轮又重搬」——白白多打几百个请求。
    @Synchronized
    fun markCopied(fromAccountId: Long, toAccountId: Long, date: String) {
        val k = key(fromAccountId, toAccountId)
        val map = alive(k)
        map[date] = System.currentTimeMillis()
        dirty += k
        if (++pendingWrites >= FLUSH_EVERY) flushLocked()
    }

    /** 把攒下的记账写回磁盘。搬运结束时一定要调一次，否则最后十几条只在内存里 */
    @Synchronized
    fun flush() = flushLocked()

    @Synchronized
    fun clear(fromAccountId: Long, toAccountId: Long) {
        val k = key(fromAccountId, toAccountId)
        cache.remove(k)
        dirty.remove(k)
        prefs.edit().remove(k).apply()
    }

    /**
     * 上一次搬运留下的痕迹，用来在搬运页告诉用户「会从哪里接着往前搬」。
     *
     * 搬运按「今天 → 更早」的顺序推进，所以成功记录里**最老的那一天**就是断点。
     * 没有记录（从没搬过，或记录已过 [SKIP_WINDOW_MS]）时返回 null。
     */
    @Synchronized
    fun progress(fromAccountId: Long, toAccountId: Long): Progress? {
        val map = alive(key(fromAccountId, toAccountId))
        if (map.isEmpty()) return null
        return Progress(
            // 日期是 yyyy-MM-dd，字典序即时间序
            oldestDate = map.keys.min(),
            copiedDays = map.size,
            lastAtMs = map.values.max(),
        )
    }

    data class Progress(
        /** 记录里最老的一天：下次搬运会从它继续往前 */
        val oldestDate: String,
        /** 记录里共有多少天（都在 [SKIP_WINDOW_MS] 窗口内） */
        val copiedDays: Int,
        /** 最近一次记账时间 */
        val lastAtMs: Long,
    )

    // ==================== 中断标记 ====================

    /**
     * 一次没跑完的搬运。
     *
     * 搬运跑在协程里，进程被国产 ROM 清掉时 `finally` 不会执行，
     * 所以「标记还在」本身就等于「上次没搬完」——不需要再存一个状态字段。
     * 正常结束、被用户主动停止、以及把剩余天数搬完，都会调 [clearRunning]。
     */
    data class RunMark(
        val fromAccountId: Long,
        val toAccountId: Long,
        val days: Int,
        val startedAtMs: Long,
    )

    /** 开始搬运时登记。同一对账号只留一条，旧的先清掉 */
    @Synchronized
    fun markRunning(fromAccountId: Long, toAccountId: Long, days: Int) {
        dismissAllRunMarks()
        prefs.edit()
            .putString(runningKey(fromAccountId, toAccountId), "$days|${System.currentTimeMillis()}")
            .apply()
    }

    @Synchronized
    fun clearRunning(fromAccountId: Long, toAccountId: Long) {
        prefs.edit().remove(runningKey(fromAccountId, toAccountId)).apply()
    }

    /** 启动 / 进搬运页时问一次：上次是不是没跑完 */
    @Synchronized
    fun interruptedRun(): RunMark? {
        val entry = prefs.all.entries.firstOrNull { it.key.startsWith(RUNNING_PREFIX) } ?: return null
        val value = entry.value as? String ?: return null
        val ids = entry.key.removePrefix(RUNNING_PREFIX).split('_')
        if (ids.size != 2) return null
        val parts = value.split('|')
        return RunMark(
            fromAccountId = ids[0].toLongOrNull() ?: return null,
            toAccountId = ids[1].toLongOrNull() ?: return null,
            days = parts.getOrNull(0)?.toIntOrNull() ?: 7,
            startedAtMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
        )
    }

    @Synchronized
    fun dismissAllRunMarks() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(RUNNING_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** 读某个键的活记录，顺手丢掉过期项 */
    private fun alive(k: String): MutableMap<String, Long> {
        val map = cache.getOrPut(k) { load(k) }
        val now = System.currentTimeMillis()
        map.entries.removeAll { now - it.value >= SKIP_WINDOW_MS }
        return map
    }

    @Synchronized
    private fun flushLocked() {
        if (dirty.isEmpty()) {
            pendingWrites = 0
            return
        }
        val editor = prefs.edit()
        for (k in dirty) {
            val map = cache[k] ?: continue
            val text = map.entries.joinToString(",") { "${it.key}=${it.value}" }
            editor.putString(k, text)
        }
        editor.apply()
        dirty.clear()
        pendingWrites = 0
    }

    /** 读 `日期=时间戳` 形式的记录，自动丢掉过期项 */
    private fun load(k: String): MutableMap<String, Long> {
        val now = System.currentTimeMillis()
        return prefs.getString(k, null)
            ?.split(',')
            ?.mapNotNull { item ->
                val parts = item.split('=')
                if (parts.size != 2) return@mapNotNull null
                val date = parts[0].takeIf { it.length == 10 } ?: return@mapNotNull null
                val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
                if (now - ts >= SKIP_WINDOW_MS) null else date to ts
            }
            ?.toMap()
            ?.toMutableMap()
            ?: mutableMapOf()
    }

    private fun key(from: Long, to: Long) = "copy_${from}_$to"

    private fun runningKey(from: Long, to: Long) = "$RUNNING_PREFIX${from}_$to"

    companion object {
        private const val PREFS = "fitsync_health_copy_ledger"

        private const val RUNNING_PREFIX = "running_"

        /**
         * 搬完的日期在这段时间内不再重复搬。
         *
         * 原来只记 24 小时，是为了「用户怀疑没搬成功时能立刻重试」。但一次 365 天的
         * 搬运要分好几轮点（中途撞限流、切后台被杀都会中断），24 小时的窗口会让
         * 第二天的下一轮把前一天搬过的几百天全部重来一遍——白花几百个请求，
         * 正是最容易被限流的动作。改成 90 天：只记成功的日期，失败的日期不记，
         * 所以「重试」仍然有效，重试的只是没成功的那部分。
         *
         * 支持 730 天跨度后又发现 90 天不够：选 2 年、分三次点（每次隔一个多月），
         * 第二轮就会把第一轮的一整年重跑一遍。窗口改成 800 天 —— 必须比最大跨度
         * （730 天）还长一点，否则「最老那条记录」会在第 730 天刚好过期，
         * 而它对应的正是两年期最后一个待搬日。代价只是 SharedPreferences 里多存
         * 几百条日期（约 15 KB），而重跑几百天要花的是一小时的请求。
         */
        const val SKIP_WINDOW_MS = 800L * 24 * 60 * 60 * 1000

        /** 攒够这么多条落一次盘 */
        const val FLUSH_EVERY = 20

        /**
         * 按「记录里最老的一天」估算选定的跨度里还有多少天没搬。
         *
         * 搬运从今天往回推进，已经搬到 [oldestDate] 就说明它后面的都处理过了；
         * 跨度 [span] 天覆盖 `今天 ~ (今天 - span + 1)`，比 [oldestDate] 更老的才算剩余。
         * 纯函数，方便单测。
         */
        fun remainingDays(oldestDate: String, span: Int, today: LocalDate): Int {
            if (span <= 0) return 0
            val oldest = runCatching { LocalDate.parse(oldestDate) }.getOrNull() ?: return span
            val windowStart = today.minusDays((span - 1).toLong())
            // oldest 比窗口起点更老 → 窗口内都在记录覆盖范围内，剩余 0
            if (!oldest.isAfter(windowStart)) return 0
            return ChronoUnit.DAYS.between(windowStart, oldest).toInt().coerceAtLeast(0)
        }
    }
}
