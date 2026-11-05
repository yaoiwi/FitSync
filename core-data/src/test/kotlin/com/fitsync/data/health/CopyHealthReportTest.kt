package com.fitsync.data.health

import com.fitsync.data.settings.HealthCopyLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 搬运体检的分类规则。
 *
 * 这些断言对应几个真实场景：手表隔夜才补传睡眠、出差一周后才连手机、
 * 没戴表那天只有 312 字节的空包、以及升级前没有文件明细的老记录。
 */
class CopyHealthReportTest {

    private val today = LocalDate.of(2026, 11, 4)
    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun record(
        hoursAgo: Long = 1,
        vararg files: String,
    ) = HealthCopyLedger.DayRecord(
        atMs = now - hoursAgo * 60 * 60 * 1000,
        files = files.toSet(),
    )

    private fun build(
        span: Int = 3,
        records: Map<String, HealthCopyLedger.DayRecord> = emptyMap(),
        localDates: Set<String> = emptySet(),
        localSleepDates: Set<String> = emptySet(),
    ) = CopyHealthReport.build(
        today = today,
        span = span,
        records = records,
        localDates = localDates,
        localSleepDates = localSleepDates,
        nowMs = now,
    )

    @Test
    fun `有睡眠文件的日期记为完整`() {
        val report = build(
            span = 1,
            records = mapOf("2026-11-04" to record(1, "monitor:418#1", "sleep:17300#1")),
        )
        assertEquals(CopyHealthReport.Kind.COMPLETE, report.days.single().kind)
        assertEquals(1, report.complete)
        assertEquals(17718L, report.days.single().totalBytes)
    }

    @Test
    fun `只有步数且还在窗口内时记为等待补传`() {
        val report = build(
            span = 1,
            records = mapOf("2026-11-04" to record(1, "monitor:12000#1")),
        )
        val day = report.days.single()
        assertEquals(CopyHealthReport.Kind.WAITING_SLEEP, day.kind)
        // 14 天窗口，刚处理 1 小时，应该还剩 14 天
        assertEquals(14, day.waitingDaysLeft)
        assertEquals(1, report.waitingSleep)
        assertTrue(report.headline, report.headline.contains("等手表补传"))
    }

    @Test
    fun `超过十四天还没有睡眠就停止复查`() {
        val report = build(
            span = 1,
            records = mapOf("2026-11-04" to record(hoursAgo = 15L * 24, "monitor:12000#1")),
        )
        val day = report.days.single()
        assertEquals(CopyHealthReport.Kind.STOPPED, day.kind)
        assertEquals(0, day.waitingDaysLeft)
        assertEquals(1, report.stopped)
    }

    @Test
    fun `几百字节的包识别为空包而不是缺睡眠`() {
        val report = build(
            span = 1,
            records = mapOf("2026-11-04" to record(1, "fit:312#1")),
        )
        val day = report.days.single()
        assertEquals(CopyHealthReport.Kind.EMPTY_PACKAGE, day.kind)
        assertEquals(312L, day.totalBytes)
        assertEquals(1, report.emptyPackage)
    }

    @Test
    fun `空包也会显示还剩几天窗口`() {
        val report = build(
            span = 1,
            records = mapOf("2026-11-04" to record(2, "fit:312#1")),
        )
        // 空包不是定稿：手表后来补传了睡眠，这天还会被复查
        assertEquals(14, report.days.single().waitingDaysLeft)
    }

    @Test
    fun `没有台账但本地已有睡眠时记为本地已有`() {
        val report = build(
            span = 1,
            localDates = setOf("2026-11-04"),
            localSleepDates = setOf("2026-11-04"),
        )
        assertEquals(CopyHealthReport.Kind.LOCAL_ONLY, report.days.single().kind)
        assertEquals(1, report.localOnly)
    }

    @Test
    fun `本地只有日汇总没有睡眠时不能算本地已有`() {
        val report = build(
            span = 1,
            localDates = setOf("2026-11-04"),
            // 另一个日期有睡眠，说明这个账号确实会上报睡眠，只是这天没有
            localSleepDates = setOf("2026-11-03"),
        )
        assertEquals(CopyHealthReport.Kind.NOT_COPIED, report.days.single().kind)
    }

    @Test
    fun `完全没有睡眠数据的账号不启用本地睡眠判断`() {
        val report = build(
            span = 1,
            localDates = setOf("2026-11-04"),
            localSleepDates = emptySet(),
        )
        // 从不戴表睡 / 设备不支持睡眠：本地有汇总就算有，不该每天重搬
        assertEquals(CopyHealthReport.Kind.LOCAL_ONLY, report.days.single().kind)
    }

    @Test
    fun `没有记录也没有本地数据时记为没搬过`() {
        val report = build(span = 2)
        assertEquals(2, report.notCopied)
        assertTrue(report.headline, report.headline.contains("还没搬过"))
    }

    @Test
    fun `结果按日期从新到旧排列`() {
        val report = build(span = 3)
        assertEquals(listOf("2026-11-04", "2026-11-03", "2026-11-02"), report.days.map { it.date })
    }

    @Test
    fun `老记录没有文件明细时按窗口判断而不是当成空包`() {
        val fresh = build(
            span = 1,
            records = mapOf("2026-11-04" to HealthCopyLedger.DayRecord(now - 60_000, emptySet())),
        )
        assertEquals(CopyHealthReport.Kind.WAITING_SLEEP, fresh.days.single().kind)
        assertFalse(fresh.days.single().kind == CopyHealthReport.Kind.EMPTY_PACKAGE)

        val old = build(
            span = 1,
            records = mapOf("2026-11-04" to HealthCopyLedger.DayRecord(now - 20 * dayMs, emptySet())),
        )
        assertEquals(CopyHealthReport.Kind.STOPPED, old.days.single().kind)
    }

    @Test
    fun `指纹字节数兼容带序号和不带序号两种格式`() {
        assertEquals(17300L, CopyHealthReport.bytesOf("sleep:17300#1"))
        assertEquals(173L, CopyHealthReport.bytesOf("sleep:173"))
        assertEquals(418L, CopyHealthReport.bytesOf("monitor:418#2"))
        assertEquals(null, CopyHealthReport.bytesOf("fit"))
    }

    @Test
    fun `全部有睡眠时的结论直接说数据齐了`() {
        val report = build(
            span = 2,
            records = mapOf(
                "2026-11-04" to record(1, "sleep:17300#1"),
                "2026-11-03" to record(2, "sleep:16200#1"),
            ),
        )
        assertEquals(2, report.complete)
        assertEquals("最近 2 天都有睡眠，数据是齐的", report.headline)
    }

    @Test
    fun `天数为零时不崩`() {
        val report = build(span = 0)
        assertEquals(0, report.days.size)
        assertEquals("没有可核对的天数", report.headline)
    }
}
