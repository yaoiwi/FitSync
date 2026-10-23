package com.fitsync.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

/**
 * 中国区 ↔ 国际区合并测试。
 *
 * Garmin 不允许把健康数据写入另一个账号，所以"互相同步"只能在本地做：
 * 两个账号各拉各的，按日期对齐后做字段级合并，主账号优先。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergedDataTest {

    private lateinit var db: FitSyncDatabase
    private lateinit var repository: FitDataRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FitSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = FitDataRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun createAccount(region: String, name: String): Long =
        repository.upsertAccount(
            AccountEntity(
                sourceType = SourceType.GARMIN.code,
                region = region,
                externalUserId = name,
                displayName = name,
                enabled = true,
                addedAtMs = System.currentTimeMillis(),
            )
        )

    @Test
    fun `同一天主账号优先_缺失字段由另一区补齐`() = runBlocking {
        val cn = createAccount("cn", "cn_user")
        val intl = createAccount("com", "intl_user")

        repository.upsertDailySummaries(
            listOf(
                // 主账号：只有步数和距离，没有静息心率 / 压力
                DailySummaryEntity(accountId = cn, date = "2026-10-17", steps = 8000, distanceM = 6000.0),
                // 另一区：有静息心率、压力、血氧
                DailySummaryEntity(
                    accountId = intl, date = "2026-10-17",
                    steps = 3000, distanceM = 2000.0,
                    restingHr = 52, stressAvg = 30, spo2Avg = 96,
                ),
            )
        )

        val merged = repository.observeMergedLatestDaily(primaryAccountId = cn).first()!!

        assertEquals("主账号的步数优先", 8000, merged.steps)
        assertEquals("主账号的距离优先", 6000.0, merged.distanceM, 0.01)
        assertEquals("静息心率由另一区补齐", 52, merged.restingHr)
        assertEquals("压力由另一区补齐", 30, merged.stressAvg)
        assertEquals("血氧由另一区补齐", 96, merged.spo2Avg)
    }

    @Test
    fun `换主账号后同一份数据以新区为准`() = runBlocking {
        val cn = createAccount("cn", "cn_user")
        val intl = createAccount("com", "intl_user")

        repository.upsertDailySummaries(
            listOf(
                DailySummaryEntity(accountId = cn, date = "2026-10-17", steps = 8000, distanceM = 6000.0),
                DailySummaryEntity(accountId = intl, date = "2026-10-17", steps = 3000, distanceM = 2000.0),
            )
        )

        assertEquals(8000, repository.observeMergedLatestDaily(cn).first()!!.steps)
        assertEquals(3000, repository.observeMergedLatestDaily(intl).first()!!.steps)
    }

    @Test
    fun `主账号没有当天数据时用另一区`() = runBlocking {
        val cn = createAccount("cn", "cn_user")
        val intl = createAccount("com", "intl_user")

        repository.upsertDailySummaries(
            listOf(
                DailySummaryEntity(accountId = cn, date = "2026-10-16", steps = 5000, distanceM = 4000.0),
                DailySummaryEntity(accountId = intl, date = "2026-10-17", steps = 9000, distanceM = 7000.0),
            )
        )

        val merged = repository.observeMergedLatestDaily(primaryAccountId = cn).first()!!
        assertEquals("2026-10-17", merged.date)
        assertEquals(9000, merged.steps)
    }

    @Test
    fun `活动只返回主账号的数据`() = runBlocking {
        val cn = createAccount("cn", "cn_user")
        val intl = createAccount("com", "intl_user")

        repository.upsertActivity(
            ActivityEntity(
                accountId = cn, externalId = "A1", sport = "running",
                activityType = "running", startTimeMs = 1_000, distanceM = 5000.0,
            )
        )
        repository.upsertActivity(
            ActivityEntity(
                accountId = intl, externalId = "A1", sport = "running",
                activityType = "running", startTimeMs = 1_000, distanceM = 9999.0,
            )
        )
        repository.upsertActivity(
            ActivityEntity(
                accountId = intl, externalId = "A2", sport = "cycling",
                activityType = "cycling", startTimeMs = 2_000, distanceM = 20000.0,
            )
        )

        val merged = repository.observeMergedRecentActivities(cn, limit = 5).first()

        // 活动是"账号独有"的数据：CN 区和国际区各自一套活动 ID，
        // 即使内容是同一场运动也无法可靠去重，所以只返回主账号的。
        assertEquals("只应有主账号的一条活动", 1, merged.size)
        assertEquals(cn, merged.first().accountId)
        assertEquals("A1", merged.first().externalId)
        assertEquals(5000.0, merged.first().distanceM, 0.01)

        // 没有主账号时不应把别人的活动当成自己的返回
        assertTrue(repository.observeMergedRecentActivities(null, limit = 5).first().isEmpty())
    }
}
