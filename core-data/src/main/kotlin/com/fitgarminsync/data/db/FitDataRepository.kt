package com.fitgarminsync.data.db

import kotlinx.coroutines.flow.Flow

/**
 * 数据访问统一门面（第 4 章收尾）。
 *
 * 使用原则：
 * - UI / ViewModel 不直接触达 DAO，统一走本接口。
 * - 同步引擎、回收站清理器在 core-data 内部通过 [FitGarminDatabase] 的抽象 DAO 聚合；
 *   本接口暴露给上层功能模块（feature-account / feature-activity / feature-health 等）。
 * - 所有返回 `Flow` 的查询都是可观察数据源；一次性数据使用 `suspend` 方法。
 */
interface FitDataRepository {

    // ==================== 账号域 ====================
    fun observeEnabledAccounts(): Flow<List<AccountEntity>>
    suspend fun getAccount(accountId: Long): AccountEntity?
    suspend fun getAccountByUniqueKey(
        sourceType: String,
        region: String,
        externalUserId: String,
    ): AccountEntity?
    suspend fun upsertAccount(account: AccountEntity): Long
    suspend fun updateAccountStatus(accountId: Long, status: String, lastSyncAtMs: Long)
    suspend fun upsertCapability(cap: AccountCapabilityEntity)
    suspend fun getCapability(accountId: Long): AccountCapabilityEntity?
    suspend fun deleteAccount(accountId: Long)

    // ==================== 同步游标 / 任务 / 日志 / 锁 ====================
    suspend fun getSyncCursor(accountId: Long, domain: String): SyncCursorEntity?
    suspend fun upsertSyncCursor(cursor: SyncCursorEntity)
    suspend fun advanceSyncCursor(accountId: Long, domain: String, date: String, ts: Long, cursor: String)
    fun observeSyncTasks(accountId: Long): Flow<List<SyncTaskEntity>>
    fun observeEnabledSyncTasks(): Flow<List<SyncTaskEntity>>
    suspend fun upsertSyncTask(task: SyncTaskEntity): Long
    suspend fun insertSyncLog(log: SyncLogEntity): Long
    fun observeRecentSyncLogs(accountId: Long): Flow<List<SyncLogEntity>>
    suspend fun acquireSyncLock(lock: SyncLockEntity)
    suspend fun releaseSyncLock(accountId: Long)
    suspend fun upsertBackfillShards(shards: List<BackfillShardEntity>)
    suspend fun pendingBackfillShards(accountId: Long): List<BackfillShardEntity>

    // ==================== 运动域 ====================
    fun observeActivities(accountId: Long): Flow<List<ActivityEntity>>
    fun observeRecentActivities(limit: Int): Flow<List<ActivityEntity>>
    fun observeActivityCount(): Flow<Int>
    suspend fun getActivity(activityId: Long): ActivityEntity?
    suspend fun upsertActivity(activity: ActivityEntity): Long
    suspend fun upsertActivitySamples(samples: List<ActivitySampleEntity>)
    suspend fun upsertActivityRoutes(routes: List<ActivityRouteEntity>)
    suspend fun upsertActivityLaps(laps: List<ActivityLapEntity>)
    suspend fun deleteActivitiesByAccount(accountId: Long)

    // ==================== 健康域 ====================
    fun observeDailySummaries(accountId: Long): Flow<List<DailySummaryEntity>>
    /** 跨账号的最新一天汇总（首页概览用） */
    fun observeLatestDailySummary(): Flow<DailySummaryEntity?>

    /** 库里已有多少天汇总（用于确认同步是否真的落了数据） */
    fun observeDailyCount(): Flow<Int>

    /**
     * 跨账号合并后的最新一天（主账号优先，缺失字段用另一区补齐）。
     * 主账号为 null 时取最新日期任意一条作为基准。
     */
    fun observeMergedLatestDaily(primaryAccountId: Long?): Flow<DailySummaryEntity?>

    /** 跨账号合并后的最新一次睡眠（主账号优先，缺失字段用另一区补齐） */
    fun observeMergedLatestSleep(primaryAccountId: Long?): Flow<SleepSessionEntity?>

    /** 两个区的活动合并去重（按 externalId），主账号优先 */
    fun observeMergedRecentActivities(primaryAccountId: Long?, limit: Int): Flow<List<ActivityEntity>>
    suspend fun upsertDailySummaries(list: List<DailySummaryEntity>)
    fun observeSleepSessions(accountId: Long): Flow<List<SleepSessionEntity>>
    fun observeLatestSleep(): Flow<SleepSessionEntity?>
    suspend fun upsertSleepSession(session: SleepSessionEntity): Long
    suspend fun upsertSleepStages(stages: List<SleepStageEntity>)
    suspend fun upsertHeartRateSamples(list: List<HeartRateSampleEntity>)
    suspend fun upsertDailyHeartRate(list: List<DailyHeartRateEntity>)
    fun observeDailyHeartRate(accountId: Long): Flow<List<DailyHeartRateEntity>>
    suspend fun upsertWellnessSamples(list: List<WellnessSampleEntity>)
    suspend fun upsertBodyMetrics(list: List<BodyMetricEntity>)
    fun observeDevices(accountId: Long): Flow<List<DeviceEntity>>
    suspend fun upsertDevices(list: List<DeviceEntity>)

    // ==================== 回收站 / 账号级联物理清理 ====================
    /**
     * 回收站到期（30 天）后调用。物理删除该账号及其所有运动/健康数据。
     * 与 §12.3 “软删 30 天 → 物理清理” 对齐。
     */
    suspend fun purgeAccount(accountId: Long)
}