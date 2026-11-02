package com.fitsync.feature.home

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 界面上的时间显示，统一从这里出。
 *
 * 以前每个用到时间的地方都写 `SimpleDateFormat(pattern, Locale.CHINA)`——那是个有状态的类：
 * 每 new 一个都要重新解析一遍模式串、重算时区规则。放在 Composable 里就意味着
 * 每帧、每行都要新建；活动列表 500 行、滚动时每秒几十帧，纯属白烧。
 *
 * `DateTimeFormatter` 是不可变且线程安全的，可以直接做成常量复用。
 * 时区仍取 `systemDefault()`，与原来的 SimpleDateFormat 行为一致。
 */
object TimeFmt {

    private fun fmt(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.CHINA)

    private val dayIso = fmt("yyyy-MM-dd")
    private val dayTitle = fmt("M月d日 EEEE")
    private val monthIso = fmt("yyyy-MM")
    private val clockShort = fmt("MM-dd HH:mm")
    private val clockFull = fmt("yyyy-MM-dd HH:mm")

    /** 2026-11-02 */
    fun day(ms: Long): String = local(ms).format(dayIso)

    /** 11月2日 星期日 */
    fun dayTitle(ms: Long): String = local(ms).format(dayTitle)

    /** 已经是 `yyyy-MM-dd` 的字符串再转标题，省掉一次时间戳往返 */
    fun dayTitleOf(isoDay: String): String =
        runCatching { LocalDate.parse(isoDay).format(dayTitle) }.getOrDefault(isoDay)

    /** 2026-11 */
    fun month(ms: Long): String = local(ms).format(monthIso)

    /** 11-02 14:30 */
    fun clockShort(ms: Long): String = local(ms).format(clockShort)

    /** 2026-11-02 14:30 */
    fun clockFull(ms: Long): String = local(ms).format(clockFull)

    private fun local(ms: Long): LocalDateTime =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()
}
