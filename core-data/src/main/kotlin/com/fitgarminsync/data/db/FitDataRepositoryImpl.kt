package com.fitgarminsync.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [FitDataRepository] 的 Room 实现。
 * 通过 `@Inject` 构造注入 [FitGarminDatabase]，由 Hilt/Dagger 管理生命周期。
 */
class FitDataRepositoryImpl @Inject constructor(
    private val db: FitGarminDatabase,
) : FitDataRepository {

    private val accountDao get() = db.accountDao()
    private val capDao get() = db.accountCapabilityDao()
    private val cursorDao get() = db.syncCursorDao()
    private val taskDao get() = db.syncTaskDao()
    private val logDao get() = db.syncLogDao()
    private val lockDao get() = db.syncLockDao()
    private val backfillDao get() = db.backfillShardDao()
    private val activityDao get() = db.activityDao()
    private val sampleDao get() = db.activitySampleDao()
    private val routeDao get() = db.activityRouteDao()
    private val lapDao get() = db.activityLapDao()
    private val dailyDao get() = db.dailySummaryDao()
    private val sleepDao get() = db.sleepSessionDao()
    private val sleepStageDao get() = db.sleepStageDao()
    private val hrDao get() = db.heartRateSampleDao()
    private val dailyHrDao get() = db.dailyHeartRateDao()
    private val wellnessDao get() = db.wellnessSampleDao()
    private val bodyDao get() = db.bodyMetricDao()
    private val deviceDao get() = db.deviceDao()

    // ==================== 账号域 ====================
    override fun observeEnabledAccounts(): Flow<List<AccountEntity>> =
        accountDao.observeEnabledAll()

    override suspend fun getAccount(accountId: Long) =
        accountDao.getById(accountId)

    override suspend fun getAccountByUniqueKey(
        sourceType: String,
        region: String,
        externalUserId: String,
    ) =
        accountDao.byUniqueKey(sourceType, region, externalUserId)

    override suspend fun upsertAccount(account: AccountEntity): Long =
        accountDao.upsert(account)

    override suspend fun updateAccountStatus(accountId: Long, status: String, lastSyncAtMs: Long) =
        accountDao.updateStatus(accountId, status, lastSyncAtMs)

    override suspend fun upsertCapability(cap: AccountCapabilityEntity) =
        capDao.upsert(cap)

    override suspend fun getCapability(accountId: Long) =
        capDao.byAccount(accountId)

    override suspend fun deleteAccount(accountId: Long) =
        accountDao.deleteById(accountId)

    // ==================== 同步游标 / 任务 / 日志 / 锁 ====================
    override suspend fun getSyncCursor(accountId: Long, domain: String) =
        cursorDao.get(accountId, domain)

    override suspend fun upsertSyncCursor(cursor: SyncCursorEntity) {
        cursorDao.upsert(cursor)
    }

    override suspend fun advanceSyncCursor(
        accountId: Long,
        domain: String,
        date: String,
        ts: Long,
        cursor: String,
    ) {
        cursorDao.advance(accountId, domain, date, ts, cursor)
    }

    override fun observeSyncTasks(accountId: Long): Flow<List<SyncTaskEntity>> =
        taskDao.observeByAccount(accountId)

    override fun observeEnabledSyncTasks(): Flow<List<SyncTaskEntity>> =
        taskDao.observeEnabled()

    override suspend fun upsertSyncTask(task: SyncTaskEntity): Long =
        taskDao.upsert(task)

    override suspend fun insertSyncLog(log: SyncLogEntity): Long =
        logDao.insert(log)

    override fun observeRecentSyncLogs(accountId: Long): Flow<List<SyncLogEntity>> =
        logDao.observeRecent(accountId)

    override suspend fun acquireSyncLock(lock: SyncLockEntity) =
        lockDao.acquire(lock)

    override suspend fun releaseSyncLock(accountId: Long) =
        lockDao.release(accountId)

    override suspend fun upsertBackfillShards(shards: List<BackfillShardEntity>) =
        backfillDao.upsertAll(shards)

    override suspend fun pendingBackfillShards(accountId: Long) =
        backfillDao.pending(accountId)

    // ==================== 运动域 ====================
    override fun observeActivities(accountId: Long): Flow<List<ActivityEntity>> =
        activityDao.observeByAccount(accountId)

    override fun observeRecentActivities(limit: Int): Flow<List<ActivityEntity>> =
        activityDao.observeRecent(limit)

    override fun observeActivityCount(): Flow<Int> = activityDao.observeTotalCount()

    override suspend fun getActivity(activityId: Long) =
        activityDao.getById(activityId)

    override suspend fun upsertActivity(activity: ActivityEntity): Long =
        activityDao.upsert(activity)

    override suspend fun upsertActivitySamples(samples: List<ActivitySampleEntity>) =
        sampleDao.upsertAll(samples)

    override suspend fun upsertActivityRoutes(routes: List<ActivityRouteEntity>) =
        routeDao.upsertAll(routes)

    override suspend fun upsertActivityLaps(laps: List<ActivityLapEntity>) =
        lapDao.upsertAll(laps)

    override suspend fun deleteActivitiesByAccount(accountId: Long) {
        activityDao.deleteByAccount(accountId)
    }

    // ==================== 健康域 ====================
    override fun observeDailySummaries(accountId: Long): Flow<List<DailySummaryEntity>> =
        dailyDao.observeByAccount(accountId)

    override fun observeLatestDailySummary(): Flow<DailySummaryEntity?> =
        dailyDao.observeLatest()

    override fun observeDailyCount(): Flow<Int> = dailyDao.observeCount()

    // ==================== 跨账号合并（中国区 ↔ 国际区） ====================
    //
    // Garmin 不提供写入他人账号健康数据的接口，所以"互相同步"只能在本地做：
    // 两个账号各拉各的，按日期/夜晚对齐后做**字段级合并**——主账号有值用主账号，
    // 主账号缺失（为 0）的字段用另一区补齐。这样首页看到的是一份完整档案。

    override fun observeMergedLatestDaily(primaryAccountId: Long?): Flow<DailySummaryEntity?> =
        dailyDao.observeRecent(MERGE_WINDOW).map { rows ->
            if (rows.isEmpty()) return@map null
            // 已按 date DESC，取最新有数据的那一天
            val latestDate = rows.firstOrNull { it.hasAnyData() }?.date ?: return@map null
            val sameDay = rows.filter { it.date == latestDate && it.hasAnyData() }
            if (sameDay.isEmpty()) return@map null

            val primary = sameDay.firstOrNull { it.accountId == primaryAccountId } ?: sameDay.first()
            val other = sameDay.firstOrNull { it.accountId != primary.accountId } ?: return@map primary

            primary.copy(
                steps = primary.steps.takeIf { it > 0 } ?: other.steps,
                distanceM = primary.distanceM.takeIf { it > 0 } ?: other.distanceM,
                floors = primary.floors.takeIf { it > 0 } ?: other.floors,
                caloriesActive = primary.caloriesActive.takeIf { it > 0 } ?: other.caloriesActive,
                caloriesResting = primary.caloriesResting.takeIf { it > 0 } ?: other.caloriesResting,
                standMin = primary.standMin.takeIf { it > 0 } ?: other.standMin,
                restingHr = primary.restingHr.takeIf { it > 0 } ?: other.restingHr,
                hrvMs = primary.hrvMs.takeIf { it > 0 } ?: other.hrvMs,
                respiratoryRate = primary.respiratoryRate.takeIf { it > 0 } ?: other.respiratoryRate,
                stressAvg = primary.stressAvg.takeIf { it > 0 } ?: other.stressAvg,
                stressMax = primary.stressMax.takeIf { it > 0 } ?: other.stressMax,
                bodyBatteryMin = primary.bodyBatteryMin.takeIf { it > 0 } ?: other.bodyBatteryMin,
                bodyBatteryMax = primary.bodyBatteryMax.takeIf { it > 0 } ?: other.bodyBatteryMax,
                spo2Avg = primary.spo2Avg.takeIf { it > 0 } ?: other.spo2Avg,
            )
        }

    override fun observeMergedLatestSleep(primaryAccountId: Long?): Flow<SleepSessionEntity?> =
        sleepDao.observeRecent(MERGE_WINDOW).map { rows ->
            if (rows.isEmpty()) return@map null
            val latestEnd = rows.maxOf { it.endTimeMs }
            // 结束时间相差 12 小时内算"同一晚"
            val sameNight = rows.filter {
                kotlin.math.abs(it.endTimeMs - latestEnd) < 12 * 3_600_000L
            }
            val primary = sameNight.firstOrNull { it.accountId == primaryAccountId } ?: sameNight.first()
            val other = sameNight.firstOrNull { it.accountId != primary.accountId } ?: return@map primary

            primary.copy(
                totalSleepMin = primary.totalSleepMin.takeIf { it > 0 } ?: other.totalSleepMin,
                inBedMin = primary.inBedMin.takeIf { it > 0 } ?: other.inBedMin,
                sleepScore = primary.sleepScore.takeIf { it > 0 } ?: other.sleepScore,
                sleepDebtMin = primary.sleepDebtMin.takeIf { it > 0 } ?: other.sleepDebtMin,
                avgHr = primary.avgHr.takeIf { it > 0 } ?: other.avgHr,
                avgSpo2 = primary.avgSpo2.takeIf { it > 0 } ?: other.avgSpo2,
                avgBreathing = primary.avgBreathing.takeIf { it > 0 } ?: other.avgBreathing,
                startTimeMs = listOf(primary.startTimeMs, other.startTimeMs)
                    .filter { it > 0 }.minOrNull() ?: primary.startTimeMs,
                endTimeMs = maxOf(primary.endTimeMs, other.endTimeMs),
            )
        }

    override fun observeMergedRecentActivities(
        primaryAccountId: Long?,
        limit: Int,
    ): Flow<List<ActivityEntity>> =
        activityDao.observeRecent(limit * 3).map { rows ->
            val merged = LinkedHashMap<String, ActivityEntity>()
            rows.filter { it.accountId == primaryAccountId }
                .forEach { merged[it.externalId] = it }
            rows.filter { it.accountId != primaryAccountId }
                .forEach { merged.putIfAbsent(it.externalId, it) }
            merged.values.sortedByDescending { it.startTimeMs }.take(limit)
        }

    override suspend fun upsertDailySummaries(list: List<DailySummaryEntity>) =
        dailyDao.upsertAll(list)

    override fun observeSleepSessions(accountId: Long): Flow<List<SleepSessionEntity>> =
        sleepDao.observeByAccount(accountId)

    override fun observeLatestSleep(): Flow<SleepSessionEntity?> =
        sleepDao.observeLatest()

    override suspend fun upsertSleepSession(session: SleepSessionEntity): Long =
        sleepDao.upsert(session)

    override suspend fun upsertSleepStages(stages: List<SleepStageEntity>) =
        sleepStageDao.upsertAll(stages)

    override suspend fun upsertHeartRateSamples(list: List<HeartRateSampleEntity>) =
        hrDao.upsertAll(list)

    override suspend fun upsertDailyHeartRate(list: List<DailyHeartRateEntity>) =
        dailyHrDao.upsertAll(list)

    override fun observeDailyHeartRate(accountId: Long): Flow<List<DailyHeartRateEntity>> =
        dailyHrDao.observeByAccount(accountId)

    override suspend fun upsertWellnessSamples(list: List<WellnessSampleEntity>) =
        wellnessDao.upsertAll(list)

    override suspend fun upsertBodyMetrics(list: List<BodyMetricEntity>) =
        bodyDao.upsertAll(list)

    override fun observeDevices(accountId: Long): Flow<List<DeviceEntity>> =
        deviceDao.observeByAccount(accountId)

    override suspend fun upsertDevices(list: List<DeviceEntity>) =
        deviceDao.upsertAll(list)

    // ==================== 回收站 / 账号级联物理清理 ====================
    override suspend fun purgeAccount(accountId: Long) {
        // 1) 健康域物理清理
        dailyDao.deleteByAccount(accountId)
        sleepStageDao.deleteByAccount(accountId)
        sleepDao.deleteByAccount(accountId)
        hrDao.deleteByAccount(accountId)
        dailyHrDao.deleteByAccount(accountId)
        wellnessDao.deleteByAccount(accountId)
        bodyDao.deleteByAccount(accountId)
        deviceDao.deleteByAccount(accountId)

        // 2) 运动域物理清理（子表先于父表）
        sampleDao.deleteByAccount(accountId)
        routeDao.deleteByAccount(accountId)
        lapDao.deleteByAccount(accountId)
        activityDao.deleteByAccount(accountId)

        // 3) 同步引擎元数据清理
        backfillDao.clear(accountId)
        cursorDao.clear(accountId)
        lockDao.release(accountId)

        // 4) 账号本体
        capDao.deleteByAccount(accountId)
        accountDao.deleteById(accountId)
    }
}

/** 合并窗口：最多回看多少天的行（个人数据量很小，一次取回在内存里合并即可） */
private const val MERGE_WINDOW = 120

private fun DailySummaryEntity.hasAnyData(): Boolean =
    steps > 0 || distanceM > 0.0 || caloriesActive > 0.0 || restingHr > 0 ||
        stressAvg > 0 || spo2Avg > 0
