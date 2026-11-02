package com.fitsync.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 趋势页的时间窗口查询。
 *
 * 趋势只画 30 天，以前三个 `observe*` 都把整张表读出来再在内存里筛——
 * 攒到一两年数据后每次进首页都要搬几千行。这里确认带 `since` 的查询
 * **真的只返回窗口内的行**，且仍然按时间倒序（趋势是按日期索引取值的）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrendWindowTest {

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

    private suspend fun account(): Long = repository.upsertAccount(
        AccountEntity(
            sourceType = SourceType.GARMIN.code,
            region = "cn",
            externalUserId = "u1",
            displayName = "u1",
            enabled = true,
            addedAtMs = 1L,
        )
    )

    private fun midnight(date: String): Long =
        java.time.LocalDate.parse(date)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `活动只返回窗口内的行`() = runBlocking {
        val id = account()
        val since = midnight("2026-10-01")
        repository.upsertActivity(
            ActivityEntity(
                accountId = id, externalId = "old", sport = "running",
                activityType = "running", startTimeMs = since - 86_400_000L,
            )
        )
        repository.upsertActivity(
            ActivityEntity(
                accountId = id, externalId = "in", sport = "running",
                activityType = "running", startTimeMs = since + 3_600_000L,
            )
        )

        val rows = repository.observeActivitiesSince(id, since).first()
        assertEquals(listOf("in"), rows.map { it.externalId })
    }

    @Test
    fun `日汇总按日期字符串比较`() = runBlocking {
        val id = account()
        repository.upsertDailySummaries(
            listOf(
                DailySummaryEntity(accountId = id, date = "2026-09-30", steps = 100),
                DailySummaryEntity(accountId = id, date = "2026-10-01", steps = 200),
                DailySummaryEntity(accountId = id, date = "2026-10-02", steps = 300),
            )
        )

        val rows = repository.observeDailySummariesSince(id, "2026-10-01").first()
        // 倒序：与老查询一致，趋势按 date 建 map，顺序不影响，但保持行为不变
        assertEquals(listOf("2026-10-02", "2026-10-01"), rows.map { it.date })
    }

    @Test
    fun `睡眠只返回窗口内开始的记录`() = runBlocking {
        val id = account()
        val since = midnight("2026-10-01")
        repository.upsertSleepSession(
            SleepSessionEntity(
                accountId = id, externalId = "old",
                startTimeMs = since - 3_600_000L, endTimeMs = since - 1_800_000L,
                totalSleepMin = 400,
            )
        )
        repository.upsertSleepSession(
            SleepSessionEntity(
                accountId = id, externalId = "in",
                startTimeMs = since + 1_000L, endTimeMs = since + 25_000_000L,
                totalSleepMin = 430,
            )
        )

        val rows = repository.observeSleepSessionsSince(id, since).first()
        assertEquals(listOf("in"), rows.map { it.externalId })
    }
}
