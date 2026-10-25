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

    private fun buildZip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
