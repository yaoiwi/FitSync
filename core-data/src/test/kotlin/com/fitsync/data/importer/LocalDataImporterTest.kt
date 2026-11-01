package com.fitsync.data.importer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.FitDataRepositoryImpl
import com.fitsync.data.db.FitSyncDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 本地导入测试：真实 Room（内存库）+ 真实 zip/csv/json 解析。
 *
 * 覆盖「Garmin 导出包」「单个 JSON」「CSV」三条路径，
 * 以及"认不出就跳过、不中断整次导入"的行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalDataImporterTest {

    private lateinit var db: FitSyncDatabase
    private lateinit var repository: FitDataRepository
    private lateinit var importer: LocalDataImporter

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FitSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = FitDataRepositoryImpl(db)
        importer = LocalDataImporter(repository)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `Garmin导出zip三类数据都能入库`() = runBlocking {
        val uds = """
            [{"calendarDate":"2026-10-17","totalSteps":8123,"totalDistanceMeters":6234.5,
              "floorsClimbed":7,"activeKilocalories":420.0,"restingKilocalories":1600.0,
              "restingHeartRate":52,"averageStressLevel":31,"maxStressLevel":77,
              "bodyBatteryLowestValue":18,"bodyBatteryHighestValue":72,"averageSpo2":96}]
        """.trimIndent()
        val sleep = """
            {"dailySleepDTO":{"calendarDate":"2026-10-17","sleepTimeSeconds":27000,
              "awakeSleepSeconds":1800,"sleepStartTimestampGMT":1760640000000,
              "sleepEndTimestampGMT":1760670000000,"sleepScores":{"overall":{"value":81}}}}
        """.trimIndent()
        val activities = """
            [{"activityId":987654,"activityName":"Morning Run","activityType":{"typeKey":"running"},
              "startTimeGMT":"2026-10-17 00:30:00","duration":1830.5,"distance":5021.0,
              "averageHR":148,"maxHR":171,"calories":310.0,"elevationGain":42.0}]
        """.trimIndent()

        val zip = buildZip(
            mapOf(
                "DI_CONNECT/DI-Connect-Aggregator/UDSFile_20261017_20261017.json" to uds,
                "DI_CONNECT/DI-Connect-Wellness/20261017_sleepData.json" to sleep,
                "DI_CONNECT/DI-Connect-Fitness/user_summarizedActivities.json" to activities,
            )
        )

        val result = importer.import(zip, "export.zip")

        assertEquals(1, result.daily)
        assertEquals(1, result.sleep)
        assertEquals(1, result.activities)
        assertTrue("不应有解析错误：${result.errors}", result.errors.isEmpty())

        val daily = repository.observeLatestDailySummary().first()!!
        assertEquals("2026-10-17", daily.date)
        assertEquals(8123, daily.steps)
        assertEquals(52, daily.restingHr)

        val sleepRow = repository.observeLatestSleep().first()!!
        assertEquals(450, sleepRow.totalSleepMin)
        assertEquals(81, sleepRow.sleepScore)

        val activity = repository.observeRecentActivities(5).first().first()
        assertEquals(1, repository.observeActivityCount(activity.accountId).first())
        assertEquals("running", activity.sport)
        assertEquals(5021.0, activity.distanceM, 0.01)
    }

    @Test
    fun `CSV按表头识别日汇总`() = runBlocking {
        val csv = """
            date,steps,distance_m,calories,resting_hr,sleep_min,sleep_score
            2026-10-16,10000,8000,600,49,420,88
            2026-10-17,9000,7200,550,50,450,85
        """.trimIndent()

        val result = importer.import(csv.toByteArray(), "health.csv")

        assertEquals(2, result.daily)
        assertEquals(2, result.sleep)

        val latest = repository.observeLatestDailySummary().first()!!
        assertEquals("2026-10-17", latest.date)
        assertEquals(9000, latest.steps)
        assertEquals(50, latest.restingHr)
    }

    @Test
    fun `无法识别的文件不会崩且给出空结果`() = runBlocking {
        val result = importer.import("hello world".toByteArray(), "notes.txt")
        assertTrue(result.isEmpty)
    }

    @Test
    fun `同一份文件重复导入不会产生重复行`() = runBlocking {
        val json = """
            [{"calendarDate":"2026-10-17","totalSteps":8000,"totalDistanceMeters":6000.0}]
        """.trimIndent()
        importer.import(json.toByteArray(), "a.json")
        importer.import(json.toByteArray(), "a.json")

        // 主键 (account_id, date) 上 upsert，按日期去重后仍是一条
        val all = db.dailySummaryDao().observeByAccount(
            repository.observeEnabledAccounts().first().first().id
        ).first()
        assertEquals(1, all.size)
    }

    @Test
    fun `导入会创建一个本地数据源账号`() = runBlocking {
        importer.import(
            """[{"calendarDate":"2026-10-17","totalSteps":1}]""".toByteArray(),
            "a.json",
        )
        val accounts = repository.observeEnabledAccounts().first()
        assertEquals(1, accounts.size)
        assertEquals("file", accounts.first().sourceType)
        assertEquals("本地导入", accounts.first().displayName)
    }

    @Test
    fun `嵌套zip里的json也能读出来`() = runBlocking {
        // Garmin 的真实导出包：外层 zip 里再套一个 UploadedFiles_*.zip
        val inner = buildZip(
            mapOf(
                "DI-Connect-Aggregator/UDSFile.json" to
                    """[{"calendarDate":"2026-09-01","totalSteps":4321}]""",
            )
        )
        val outer = buildZipBinary(
            mapOf("DI_CONNECT/DI-Connect-Uploaded-Files/UploadedFiles_0.zip" to inner)
        )

        val result = importer.import(outer, "export.zip")

        assertEquals(1, result.daily)
        assertTrue("应记下日期：${result.dates}", result.dates.contains("2026-09-01"))
        val latest = repository.observeLatestDailySummary().first()!!
        assertEquals(4321, latest.steps)
    }

    @Test
    fun `指定账号时数据写进该账号且不再新建本地导入账号`() = runBlocking {
        val accountId = repository.upsertAccount(
            com.fitsync.data.db.AccountEntity(
                sourceType = "garmin",
                region = "cn",
                externalUserId = "u1",
                displayName = "测试账号",
                status = "normal",
                enabled = true,
                addedAtMs = System.currentTimeMillis(),
                lastSyncAtMs = 0L,
            )
        )

        val result = importer.import(
            """[{"calendarDate":"2026-10-17","totalSteps":7777}]""".toByteArray(),
            "a.json",
            targetAccountId = accountId,
        )

        assertEquals(1, result.daily)
        assertEquals(accountId, result.accountId)
        val accounts = repository.observeEnabledAccounts().first()
        assertEquals("不应再创建「本地导入」账号：$accounts", 1, accounts.size)
        assertEquals("garmin", accounts.first().sourceType)
        assertEquals(7777, repository.observeLatestDailySummary().first()!!.steps)
    }

    @Test
    fun `导出包里的FIT会被识别为wellness而不是活动`() = runBlocking {
        // 用真实的 FIT 编码器造一个 monitoring 文件，验证探测能认出类型
        val tmp = java.io.File.createTempFile("monitor", ".fit")
        com.fitsync.data.health.MonitoringFitBuilder.build(
            target = tmp,
            serialNumber = 1234567890L,
            productId = 3992,
            dayStartMs = java.time.LocalDate.parse("2026-09-05")
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            steps = 8000,
            restingHr = 52,
            sleepMinutes = 420,
            stressAvg = 30,
        )
        val fitBytes = tmp.readBytes()
        tmp.delete()

        val zip = buildZipBinary(mapOf("DI_CONNECT/DI-Connect-Wellness/monitoring.fit" to fitBytes))
        val result = importer.import(zip, "export.zip")

        assertEquals(1, result.fitFiles)
        assertEquals("monitoring 文件不该被当成活动", 0, result.activityFits)
    }

    @Test
    fun `导入缺少字段的文件不会清掉已有指标`() = runBlocking {
        // 第一次：完整日汇总（同步或官方导出都长这样）
        importer.import(
            """
            [{"calendarDate":"2026-10-17","totalSteps":8123,"totalDistanceMeters":6234.5,
              "activeKilocalories":420.0,"restingHeartRate":52,"averageStressLevel":31,
              "maxStressLevel":77,"bodyBatteryLowestValue":18,"bodyBatteryHighestValue":72,
              "averageSpo2":96}]
            """.trimIndent().toByteArray(),
            "full.json",
        )

        // 第二次：同一天，但只有步数（典型的旧版导出 / 简化 CSV）
        val second = importer.import(
            """[{"calendarDate":"2026-10-17","totalSteps":9000}]""".toByteArray(),
            "partial.json",
        )

        val row = db.dailySummaryDao()
            .byDate(repository.observeEnabledAccounts().first().first().id, "2026-10-17")!!
        assertEquals("步数应当被更新", 9000, row.steps)
        // 关键：缺的字段必须原样保留，不能被 0 覆盖
        assertEquals("压力不该被清掉", 31, row.stressAvg)
        assertEquals("血氧不该被清掉", 96, row.spo2Avg)
        assertEquals("身体电量不该被清掉", 18, row.bodyBatteryMin)
        assertEquals("静息心率不该被清掉", 52, row.restingHr)

        assertEquals("这是补全不是新增", 0, second.inserted)
        assertEquals(1, second.updated)
    }

    @Test
    fun `显式的0会写进去而缺失的字段保留`() = runBlocking {
        importer.import(
            """[{"calendarDate":"2026-10-18","totalSteps":8000,"averageStressLevel":35}]"""
                .toByteArray(),
            "a.json",
        )
        // 明确写了 totalSteps: 0（卧床那天），这是真实值，不能被当成「没这个字段」
        importer.import(
            """[{"calendarDate":"2026-10-18","totalSteps":0}]""".toByteArray(),
            "b.json",
        )

        val row = db.dailySummaryDao()
            .byDate(repository.observeEnabledAccounts().first().first().id, "2026-10-18")!!
        assertEquals("显式 0 应当写入", 0, row.steps)
        assertEquals("未提及的压力要保留", 35, row.stressAvg)
    }

    @Test
    fun `同一份文件重复导入时统计为未改动`() = runBlocking {
        val json = """[{"calendarDate":"2026-10-19","totalSteps":6000,"averageStressLevel":20}]"""
        importer.import(json.toByteArray(), "a.json")
        val again = importer.import(json.toByteArray(), "a.json")

        assertEquals(0, again.inserted)
        assertEquals(0, again.updated)
        assertEquals(1, again.skipped)
        assertTrue("没有改动时不应报错：${again.errors}", again.errors.isEmpty())
    }

    @Test
    fun `睡眠缺评分时保留旧评分`() = runBlocking {
        importer.import(
            """
            {"dailySleepDTO":{"calendarDate":"2026-10-20","sleepTimeSeconds":27000,
              "sleepScores":{"overall":{"value":81}}}}
            """.trimIndent().toByteArray(),
            "sleep-full.json",
        )
        importer.import(
            """{"dailySleepDTO":{"calendarDate":"2026-10-20","sleepTimeSeconds":28800}}"""
                .toByteArray(),
            "sleep-partial.json",
        )

        val row = repository.observeLatestSleep().first()!!
        assertEquals("时长应当更新", 480, row.totalSleepMin)
        assertEquals("评分不该被清掉", 81, row.sleepScore)
    }

    private fun buildZip(entries: Map<String, String>): ByteArray =
        buildZipBinary(entries.mapValues { it.value.toByteArray() })

    private fun buildZipBinary(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
