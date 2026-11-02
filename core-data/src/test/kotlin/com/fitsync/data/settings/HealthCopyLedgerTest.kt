package com.fitsync.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 搬运记账本。
 *
 * 两个曾经踩过的坑都固化成断言：
 * 1. 每次记账都「读全量 → 拼字符串 → 落盘」，365 天就是 365 次 O(n²)；
 * 2. 进程被杀之前没落盘，重试时把搬过的天又搬一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthCopyLedgerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 每个用例都从干净状态开始：prefs 是全局共享的
        context.getSharedPreferences("fitsync_health_copy_ledger", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `记账后能读回同一天`() {
        val ledger = HealthCopyLedger(context)
        ledger.markCopied(1L, 2L, "2026-10-01")
        assertTrue("2026-10-01" in ledger.copiedDates(1L, 2L))
    }

    @Test
    fun `方向不同互不影响`() {
        val ledger = HealthCopyLedger(context)
        ledger.markCopied(1L, 2L, "2026-10-01")
        assertFalse("2026-10-01" in ledger.copiedDates(2L, 1L))
    }

    @Test
    fun `攒够一批才落盘，flush 之后新实例也能读到`() {
        val ledger = HealthCopyLedger(context)
        repeat(HealthCopyLedger.FLUSH_EVERY - 1) { i ->
            ledger.markCopied(1L, 2L, "2026-09-%02d".format(i + 1))
        }
        // 还没到批量阈值，磁盘上应该还是空的（这正是不再逐条写盘的意义）
        val fresh = HealthCopyLedger(context)
        assertEquals(0, fresh.copiedDates(1L, 2L).size)

        ledger.flush()
        val afterFlush = HealthCopyLedger(context)
        assertEquals(HealthCopyLedger.FLUSH_EVERY - 1, afterFlush.copiedDates(1L, 2L).size)
    }

    @Test
    fun `攒满阈值会自动落盘`() {
        val ledger = HealthCopyLedger(context)
        repeat(HealthCopyLedger.FLUSH_EVERY) { i ->
            ledger.markCopied(1L, 2L, "2026-08-%02d".format(i + 1))
        }
        val fresh = HealthCopyLedger(context)
        assertEquals(HealthCopyLedger.FLUSH_EVERY, fresh.copiedDates(1L, 2L).size)
    }

    @Test
    fun `断点是最老的那一天`() {
        val ledger = HealthCopyLedger(context)
        ledger.markCopied(1L, 2L, "2026-10-01")
        ledger.markCopied(1L, 2L, "2025-08-12")
        ledger.markCopied(1L, 2L, "2026-03-04")

        val progress = ledger.progress(1L, 2L)
        assertEquals("2025-08-12", progress?.oldestDate)
        assertEquals(3, progress?.copiedDays)
    }

    @Test
    fun `从没搬过时没有断点`() {
        assertNull(HealthCopyLedger(context).progress(1L, 2L))
    }

    @Test
    fun `清空之后断点消失`() {
        val ledger = HealthCopyLedger(context)
        ledger.markCopied(1L, 2L, "2026-10-01")
        ledger.clear(1L, 2L)
        assertNull(ledger.progress(1L, 2L))
    }

    @Test
    fun `剩余天数是窗口起点到断点的距离`() {
        val today = LocalDate.of(2026, 11, 2)
        // 断点比窗口起点更老 → 窗口内都搬过了
        assertEquals(0, HealthCopyLedger.remainingDays("2025-01-01", 30, today))
        // 只搬了今天：365 天里还剩 364 天
        assertEquals(364, HealthCopyLedger.remainingDays("2026-11-02", 365, today))
        // 窗口起点 2025-11-03，断点 2025-08-12 比它老 → 全搬过
        assertEquals(0, HealthCopyLedger.remainingDays("2025-08-12", 365, today))
        // 窗口起点 2026-10-27，断点 2026-10-20 → 一个都不在窗口内？断点更老，剩余 0
        assertEquals(0, HealthCopyLedger.remainingDays("2026-10-20", 7, today))
        // 断点 2026-10-30 在窗口内：窗口 10-27..11-02，比 10-30 更老的只有 10-27..10-29
        assertEquals(3, HealthCopyLedger.remainingDays("2026-10-30", 7, today))
    }

    @Test
    fun `剩余天数对坏日期不崩`() {
        val today = LocalDate.of(2026, 11, 2)
        assertEquals(30, HealthCopyLedger.remainingDays("not-a-date", 30, today))
        assertEquals(0, HealthCopyLedger.remainingDays("2026-11-02", 0, today))
    }

    @Test
    fun `两年跨度下断点只减掉已搬的那一段`() {
        val today = LocalDate.of(2026, 11, 2)
        // 窗口起点 2024-11-03。只搬了今天 → 730 天里还剩 729 天
        assertEquals(729, HealthCopyLedger.remainingDays("2026-11-02", 730, today))
        // 已经搬满一年（断点 2025-11-02）→ 2 年跨度里只剩更早的那 364 天，
        // 也就是「先搬 1 年、再选 2 年」实际只跑一半，不会从头再来
        assertEquals(364, HealthCopyLedger.remainingDays("2025-11-02", 730, today))
        // 断点正好落在窗口起点 → 整段都搬过了
        assertEquals(0, HealthCopyLedger.remainingDays("2024-11-03", 730, today))
    }

    @Test
    fun `记账窗口要覆盖一次两年搬运的整个周期`() {
        // 90 天时，选 2 年、隔一个多月再点第二轮，第一年会被当成过期重跑
        assertEquals(
            800L * 24 * 60 * 60 * 1000,
            HealthCopyLedger.SKIP_WINDOW_MS,
        )
        // 必须严格长于最大跨度：正好 730 天时，最老那条记录会在第 730 天过期，
        // 而它对应的正是两年期最后一个待搬日
        assertTrue(
            "窗口必须长于 730 天，否则两年搬运分两轮会重跑",
            HealthCopyLedger.SKIP_WINDOW_MS > 730L * 24 * 60 * 60 * 1000,
        )
    }

    // ==================== 中断标记 ====================

    @Test
    fun `开始时记下范围，重启后能读回同一个断点`() {
        val ledger = HealthCopyLedger(context)
        ledger.markRunning(3L, 4L, days = 365)

        // 新建一个实例模拟「进程被杀后重新启动」：只能靠盘上的痕迹
        val restarted = HealthCopyLedger(context)
        val mark = restarted.interruptedRun()
        assertEquals(3L, mark?.fromAccountId)
        assertEquals(4L, mark?.toAccountId)
        assertEquals(365, mark?.days)
    }

    @Test
    fun `正常搬完清掉标记后不再提示`() {
        val ledger = HealthCopyLedger(context)
        ledger.markRunning(3L, 4L, days = 7)
        ledger.clearRunning(3L, 4L)
        assertNull(HealthCopyLedger(context).interruptedRun())
    }

    @Test
    fun `换一对账号搬运会覆盖旧标记，只留一条`() {
        val ledger = HealthCopyLedger(context)
        ledger.markRunning(3L, 4L, days = 7)
        ledger.markRunning(5L, 6L, days = 30)

        val mark = ledger.interruptedRun()
        assertEquals(5L, mark?.fromAccountId)
        assertEquals(6L, mark?.toAccountId)
        assertEquals(30, mark?.days)
    }

    @Test
    fun `用户选择不再提示时所有标记都清掉`() {
        val ledger = HealthCopyLedger(context)
        ledger.markRunning(3L, 4L, days = 7)
        ledger.markCopied(3L, 4L, "2026-10-01")
        ledger.dismissAllRunMarks()

        assertNull(ledger.interruptedRun())
        // 只清「在跑」标记，搬运记账本身不能丢，否则会重搬
        assertEquals(setOf("2026-10-01"), ledger.copiedDates(3L, 4L))
    }
}
