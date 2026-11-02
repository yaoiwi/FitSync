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

    // ==================== 文件级记账 ====================
    //
    // 以前只记「这天搬过了」。睡眠在 Garmin 云端是事后生成的：今天搬的时候手表还没
    // 把今早的睡眠传上去，包里只有 monitoring，照样被记成搬完，第二天再搬直接跳过，
    // 那天的睡眠就永远补不上。改成记「这天哪些文件已经确认在目标账号上」。

    @Test
    fun `同一天的几个文件都能读回`() {
        val ledger = HealthCopyLedger(context)
        ledger.markFiles(1L, 2L, "2026-11-02", setOf("monitor:400", "sleep:41794"))

        val record = ledger.dayRecord(1L, 2L, "2026-11-02")
        assertEquals(setOf("monitor:400", "sleep:41794"), record?.files)
        // 日期级的判断仍然成立，搬运页的「已搬天数」不受影响
        assertTrue("2026-11-02" in ledger.copiedDates(1L, 2L))
    }

    @Test
    fun `同一天再记新文件是合并而不是替换`() {
        // 「第二天补睡眠」能成立的前提：昨天记的 monitor 不能被今天的 sleep 挤掉，
        // 否则明天又得把 monitor 重传一遍
        val ledger = HealthCopyLedger(context)
        ledger.markFiles(1L, 2L, "2026-11-02", setOf("monitor:400"))
        ledger.markFiles(1L, 2L, "2026-11-02", setOf("sleep:41794"))

        assertEquals(
            setOf("monitor:400", "sleep:41794"),
            ledger.dayRecord(1L, 2L, "2026-11-02")?.files,
        )
    }

    @Test
    fun `文件集合能跨进程读回`() {
        val ledger = HealthCopyLedger(context)
        ledger.markFiles(1L, 2L, "2026-11-01", setOf("monitor:400#1", "sleep:173#1"))
        ledger.flush()

        val restarted = HealthCopyLedger(context)
        assertEquals(
            setOf("monitor:400#1", "sleep:173#1"),
            restarted.dayRecord(1L, 2L, "2026-11-01")?.files,
        )
    }

    @Test
    fun `老版本只记了日期的记录读回来文件集合为空`() {
        // 1.5.8 之前写的是 `日期=时间戳`，没有文件明细。
        // 读的时候不能当成损坏，否则升级后会把最近几百天全部重搬一遍
        val now = System.currentTimeMillis()
        context.getSharedPreferences("fitsync_health_copy_ledger", Context.MODE_PRIVATE)
            .edit().putString("copy_1_2", "2026-10-01=$now").commit()

        val record = HealthCopyLedger(context).dayRecord(1L, 2L, "2026-10-01")
        assertEquals(emptySet<String>(), record?.files)
        assertEquals(now, record!!.atMs)
    }

    @Test
    fun `指纹里的分隔符不会把一条记录拆成两半`() {
        val ledger = HealthCopyLedger(context)
        ledger.markFiles(1L, 2L, "2026-11-02", setOf("weird,name=1;x:400"))
        ledger.flush()

        val restarted = HealthCopyLedger(context)
        assertEquals(1, restarted.dayRecord(1L, 2L, "2026-11-02")?.files?.size)
    }

    @Test
    fun `1_5_9 写下的无序号指纹读回来自动补序号`() {
        // 1.5.9 的格式是 `sleep:173`，语义上就是「第一个同类型同大小的文件」。
        // 补成 `sleep:173#1` 之后，1.6.0 算出来的指纹能直接命中，升级不用把最近几天再传一遍
        val now = System.currentTimeMillis()
        context.getSharedPreferences("fitsync_health_copy_ledger", Context.MODE_PRIVATE)
            .edit().putString("copy_1_2", "2026-10-01=$now|sleep:173;monitor:418").commit()

        val record = HealthCopyLedger(context).dayRecord(1L, 2L, "2026-10-01")
        assertEquals(setOf("sleep:173#1", "monitor:418#1"), record?.files)
    }

    @Test
    fun `有睡眠的日期复查三天就够`() {
        val now = 1_800_000_000_000L
        val day = 24L * 60 * 60 * 1000

        // 没记录过 → 当然要搬
        assertTrue(HealthCopyLedger.needsCopy(null, now))
        // 刚搬过 → 要复查：那天后来可能多了压力/身体电量
        assertTrue(
            HealthCopyLedger.needsCopy(
                HealthCopyLedger.DayRecord(now - 60_000, setOf("monitor:400#1", "sleep:41794#1")),
                now,
            ),
        )
        // 边界前两天 → 还在窗口内
        assertTrue(
            HealthCopyLedger.needsCopy(
                HealthCopyLedger.DayRecord(now - 2 * day, setOf("sleep:41794#1")),
                now,
            ),
        )
        // 有睡眠、超过 3 天 → 跳过，不再下载
        assertFalse(
            HealthCopyLedger.needsCopy(
                HealthCopyLedger.DayRecord(now - 4 * day, setOf("monitor:400#1", "sleep:41794#1")),
                now,
            ),
        )
    }

    @Test
    fun `没有睡眠的日期要盯到十四天`() {
        val now = 1_800_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val onlyMonitor = setOf("monitor:312#1")

        // 第 4 天：有睡眠的早就跳过了，没有睡眠的还得继续看——
        // 手表放抽屉里几天再连手机，云端那时才补齐睡眠
        assertTrue(
            "没有睡眠不能 3 天就放弃",
            HealthCopyLedger.needsCopy(HealthCopyLedger.DayRecord(now - 4 * day, onlyMonitor), now),
        )
        assertTrue(
            HealthCopyLedger.needsCopy(HealthCopyLedger.DayRecord(now - 10 * day, onlyMonitor), now),
        )
        assertTrue(
            HealthCopyLedger.needsCopy(HealthCopyLedger.DayRecord(now - 13 * day, onlyMonitor), now),
        )
        // 14 天是上限：再往后基本可以认定「那天真没戴表睡」
        assertFalse(
            HealthCopyLedger.needsCopy(HealthCopyLedger.DayRecord(now - 15 * day, onlyMonitor), now),
        )
    }

    @Test
    fun `老格式没有文件明细时按没睡眠处理`() {
        // 1.5.8 之前只有时间戳，不知道那天有没有睡眠。按「没有」处理，
        // 14 天内复查一次，顺带把指纹补全；不能当成「齐了」直接丢下
        val now = 1_800_000_000_000L
        val day = 24L * 60 * 60 * 1000
        assertTrue(
            HealthCopyLedger.needsCopy(HealthCopyLedger.DayRecord(now - 5 * day, emptySet()), now),
        )
    }

    @Test
    fun `睡眠指纹能认出来，其它类型不算`() {
        val withSleep = HealthCopyLedger.DayRecord(0, setOf("monitor:312#1", "sleep:173#1"))
        val withoutSleep = HealthCopyLedger.DayRecord(0, setOf("monitor:312#1", "stress:60#1"))
        assertTrue(withSleep.hasSleep())
        assertFalse(withoutSleep.hasSleep())
    }

    @Test
    fun `复查窗口覆盖手表延迟同步的那几天`() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(
            "至少要覆盖今天和昨天，否则隔夜补传的睡眠会漏掉",
            HealthCopyLedger.RECHECK_WINDOW_MS >= 2 * day,
        )
        assertTrue(
            "再长就是白下载：数据早就在云端了",
            HealthCopyLedger.RECHECK_WINDOW_MS <= 7 * day,
        )
        assertTrue(
            "没睡眠的日期必须盯得比有睡眠的久",
            HealthCopyLedger.SLEEP_WAIT_WINDOW_MS > HealthCopyLedger.RECHECK_WINDOW_MS,
        )
        assertTrue(
            "一周才同步一次手表的情况也要接住",
            HealthCopyLedger.SLEEP_WAIT_WINDOW_MS >= 7 * day,
        )
    }

    // ==================== 手动补齐 ====================

    @Test
    fun `作废最近三天只删这三天，更早的断点不动`() {
        val ledger = HealthCopyLedger(context)
        val today = LocalDate.of(2026, 11, 2)
        listOf("2026-10-25", "2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02")
            .forEach { ledger.markFiles(1L, 2L, it, setOf("monitor:400#1")) }

        ledger.forgetRecent(1L, 2L, days = 3, today = today)

        // 最近三天 = 10-31、11-01、11-02
        assertEquals(
            setOf("2026-10-25", "2026-10-30"),
            ledger.copiedDates(1L, 2L),
        )
    }

    @Test
    fun `作废之后新实例也读不到那几天`() {
        val ledger = HealthCopyLedger(context)
        val today = LocalDate.of(2026, 11, 2)
        ledger.markFiles(1L, 2L, "2026-11-02", setOf("monitor:400#1"))
        ledger.forgetRecent(1L, 2L, days = 3, today = today)

        // 不落盘的话，进程一重启这几天又「已经搬过」了
        val restarted = HealthCopyLedger(context)
        assertTrue(restarted.copiedDates(1L, 2L).isEmpty())
        assertNull(restarted.dayRecord(1L, 2L, "2026-11-02"))
    }

    @Test
    fun `没有记录时作废是空操作`() {
        val ledger = HealthCopyLedger(context)
        ledger.forgetRecent(1L, 2L, days = 3, today = LocalDate.of(2026, 11, 2))
        assertTrue(ledger.copiedDates(1L, 2L).isEmpty())
    }
}
