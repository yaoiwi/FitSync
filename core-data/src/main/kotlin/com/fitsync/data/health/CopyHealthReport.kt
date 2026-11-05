package com.fitsync.data.health

import com.fitsync.data.settings.HealthCopyLedger
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 「搬运体检」：把台账和本地库拼成一张按天展开的清单。
 *
 * **为什么需要它**：搬运结果原来只有一行汇总（N 天成功、N 天 409、N 天失败），
 * 用户真正想知道的是「具体哪一天还缺睡眠、为什么缺、还会不会再补」。
 * 这些信息分散在台账、本地日汇总、本地睡眠、复查窗口四处，只能靠人拼。
 * 这里用一个纯函数把它们合成一行一天的结论，界面照着画即可。
 *
 * **判断顺序**（先命中先返回）：
 * 1. 台账里有 sleep 文件 → [Kind.COMPLETE]，这天齐了；
 * 2. 台账里有记录但没有任何文件明细（1.5.9 之前的老记录）→ 按窗口判「等复查」或「已定稿」；
 * 3. 包里所有 FIT 加起来不到 [EMPTY_PACKAGE_BYTES] → [Kind.EMPTY_PACKAGE]，
 *    这是没戴表、只连了一下手机的那天，本来就没有健康数据；
 * 4. 没有 sleep、但还在等待窗口内 → [Kind.WAITING_SLEEP]，下次搬运会再核一遍；
 * 5. 没有 sleep、窗口已过 → [Kind.STOPPED]，不再复查；
 * 6. 台账里没有这天，但目标账号本地已有完整数据 → [Kind.LOCAL_ONLY]；
 * 7. 其余 → [Kind.NOT_COPIED]。
 *
 * 纯函数、不碰 IO，方便直接单测各种边界。
 */
object CopyHealthReport {

    enum class Kind {
        /** 有睡眠，数据齐了 */
        COMPLETE,

        /** 只有步数/压力，睡眠还没出现，仍在等待窗口内 */
        WAITING_SLEEP,

        /** 等待窗口已过，不会再复查 */
        STOPPED,

        /** 当天是空包（没戴表 / 只连了一下手机） */
        EMPTY_PACKAGE,

        /** 目标账号本地本来就有这天的数据，不需要搬 */
        LOCAL_ONLY,

        /** 没搬过，本地也没有 */
        NOT_COPIED,
    }

    data class Day(
        val date: String,
        val kind: Kind,
        /** 台账里记着的文件数，0 表示只有日期没有明细 */
        val fileCount: Int,
        /** 这些 FIT 的字节数之和，用来识别空包 */
        val totalBytes: Long,
        /** 还在等睡眠时，窗口还剩几天；0 表示已经不再复查 */
        val waitingDaysLeft: Int,
    )

    data class Summary(
        val days: List<Day> = emptyList(),
        /** 参与统计的天数 */
        val span: Int = 0,
    ) {
        fun count(kind: Kind): Int = days.count { it.kind == kind }

        val complete: Int get() = count(Kind.COMPLETE)
        val waitingSleep: Int get() = count(Kind.WAITING_SLEEP)
        val stopped: Int get() = count(Kind.STOPPED)
        val emptyPackage: Int get() = count(Kind.EMPTY_PACKAGE)
        val localOnly: Int get() = count(Kind.LOCAL_ONLY)
        val notCopied: Int get() = count(Kind.NOT_COPIED)

        /** 台账里确认有睡眠的天数占比，用于一句话结论 */
        val completeRatio: Float
            get() = if (days.isEmpty()) 0f else complete.toFloat() / days.size

        /**
         * 一句话结论。
         *
         * 先给「有多少天真的齐了」，再给最需要处理的那一类；两类都为 0 时
         * 说明这段范围还没搬过，直接说清楚，避免用户以为页面坏了。
         */
        val headline: String
            get() = when {
                days.isEmpty() -> "没有可核对的天数"
                complete == days.size -> "最近 ${days.size} 天都有睡眠，数据是齐的"
                complete == 0 && waitingSleep == 0 && stopped == 0 &&
                    emptyPackage == 0 && localOnly == 0 -> "最近 ${days.size} 天还没搬过"
                else -> buildString {
                    append("最近 ").append(days.size).append(" 天里 ")
                    append(complete).append(" 天有睡眠")
                    if (waitingSleep > 0) append("，").append(waitingSleep).append(" 天等手表补传")
                    if (emptyPackage > 0) append("，").append(emptyPackage).append(" 天是空包")
                    if (stopped > 0) append("，").append(stopped).append(" 天已停止复查")
                    if (notCopied > 0) append("，").append(notCopied).append(" 天没搬过")
                }
            }
    }

    /**
     * @param today 以哪天为「第 0 天」往前数，测试里可以固定
     * @param span 往前看多少天
     * @param records 台账：日期 → 记录（来自 [HealthCopyLedger.records]）
     * @param localDates 目标账号本地有日汇总的日期
     * @param localSleepDates 目标账号本地有睡眠的日期
     * @param nowMs 当前时间，用来算等待窗口还剩多久
     */
    fun build(
        today: LocalDate,
        span: Int,
        records: Map<String, HealthCopyLedger.DayRecord>,
        localDates: Set<String> = emptySet(),
        localSleepDates: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
    ): Summary {
        if (span <= 0) return Summary()
        // 与 HealthSyncService 的 fullyPresent 保持一致：本地一条睡眠都没有时，
        // 不能拿「本地有日汇总」当成「本地有睡眠」，否则会把缺睡眠的日期误判成「本来就有」
        val trustLocalSleep = localSleepDates.isNotEmpty()
        val days = (0 until span).map { offset ->
            val date = today.minusDays(offset.toLong()).toString()
            classify(
                date = date,
                record = records[date],
                localHasDaily = date in localDates,
                localHasSleep = date in localSleepDates,
                trustLocalSleep = trustLocalSleep,
                nowMs = nowMs,
            )
        }
        return Summary(days = days, span = span)
    }

    private fun classify(
        date: String,
        record: HealthCopyLedger.DayRecord?,
        localHasDaily: Boolean,
        localHasSleep: Boolean,
        trustLocalSleep: Boolean,
        nowMs: Long,
    ): Day {
        if (record == null) {
            val locallyComplete = localHasDaily && (!trustLocalSleep || localHasSleep)
            return Day(
                date = date,
                kind = if (locallyComplete) Kind.LOCAL_ONLY else Kind.NOT_COPIED,
                fileCount = 0,
                totalBytes = 0L,
                waitingDaysLeft = 0,
            )
        }

        val sizes = record.files.mapNotNull { bytesOf(it) }
        val totalBytes = sizes.sum()
        // 文件明细为空说明是 1.5.9 之前的老记录：不知道有没有睡眠，只能按窗口判断
        val hasSleep = record.hasSleep()

        if (hasSleep) {
            return Day(date, Kind.COMPLETE, record.files.size, totalBytes, 0)
        }

        val waitingDaysLeft = waitingDaysLeft(record.atMs, nowMs)
        // 老记录没有文件明细，不能当成空包（totalBytes 会是 0）
        val emptyPackage = record.files.isNotEmpty() &&
            totalBytes in 1 until EMPTY_PACKAGE_BYTES

        return when {
            emptyPackage -> Day(date, Kind.EMPTY_PACKAGE, record.files.size, totalBytes, waitingDaysLeft)
            waitingDaysLeft > 0 -> Day(date, Kind.WAITING_SLEEP, record.files.size, totalBytes, waitingDaysLeft)
            else -> Day(date, Kind.STOPPED, record.files.size, totalBytes, 0)
        }
    }

    /**
     * 从指纹里取字节数：`sleep:17300#1` → 17300，`sleep:173`（1.5.9 老格式）→ 173。
     * 解析不出来返回 null，不参与求和（宁可少算，也不要把空包判成有数据）。
     */
    fun bytesOf(fingerprint: String): Long? {
        val colon = fingerprint.indexOf(':')
        if (colon < 0) return null
        val rest = fingerprint.substring(colon + 1)
        val digits = rest.takeWhile { it.isDigit() }
        return digits.toLongOrNull()
    }

    /** 还剩几天复查窗口；已过窗口返回 0 */
    private fun waitingDaysLeft(atMs: Long, nowMs: Long): Int {
        val remainingMs = HealthCopyLedger.SLEEP_WAIT_WINDOW_MS - (nowMs - atMs)
        if (remainingMs <= 0) return 0
        // 向上取整：还剩几小时也算「还有 1 天」，避免显示「还剩 0 天」却还在复查
        val dayMs = 24L * 60 * 60 * 1000
        return ((remainingMs + dayMs - 1) / dayMs).toInt()
    }

    /** 低于这个字节数的一整天，基本只有 file_id / device_info，是空包 */
    const val EMPTY_PACKAGE_BYTES = 1024L

    /** 两个日期之间差几天（`yyyy-MM-dd`），供界面显示「已过 N 天」 */
    fun daysBetween(from: String, to: LocalDate): Int? =
        runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(from), to).toInt()
        }.getOrNull()
}
