package com.fitsync.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room 数据库：19 张表统一纳入 `fitsync.db`（第 4 章）。
 *
 * - schema JSON 导出：模块 build 时在 `core-data/schemas/1/fitsync.db.json` 生成
 * - v1 采用单库；未来若按 accountId 分库（DailySync 多库隔离思路），再引入 `multiIfAbsent` 迁移
 * - 所有删除为软删（回收站 30 天），本文件只做 schema，不做数据迁移
 */
@Database(
    entities = [
        AccountEntity::class,
        AccountCapabilityEntity::class,
        SyncCursorEntity::class,
        SyncTaskEntity::class,
        SyncLogEntity::class,
        SyncLockEntity::class,
        BackfillShardEntity::class,
        ActivityEntity::class,
        ActivitySampleEntity::class,
        ActivityRouteEntity::class,
        ActivityLapEntity::class,
        DailySummaryEntity::class,
        SleepSessionEntity::class,
        SleepStageEntity::class,
        HeartRateSampleEntity::class,
        DailyHeartRateEntity::class,
        WellnessSampleEntity::class,
        BodyMetricEntity::class,
        DeviceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters.StringTypeConverter::class, Converters.LongStringConverter::class)
abstract class FitSyncDatabase : RoomDatabase() {
    companion object {
        const val DB_NAME = "fitsync.db"
    }

    abstract fun accountDao(): AccountDao
    abstract fun accountCapabilityDao(): AccountCapabilityDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun syncTaskDao(): SyncTaskDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun syncLockDao(): SyncLockDao
    abstract fun backfillShardDao(): BackfillShardDao
    abstract fun activityDao(): ActivityDao
    abstract fun activitySampleDao(): ActivitySampleDao
    abstract fun activityRouteDao(): ActivityRouteDao
    abstract fun activityLapDao(): ActivityLapDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun sleepStageDao(): SleepStageDao
    abstract fun heartRateSampleDao(): HeartRateSampleDao
    abstract fun dailyHeartRateDao(): DailyHeartRateDao
    abstract fun wellnessSampleDao(): WellnessSampleDao
    abstract fun bodyMetricDao(): BodyMetricDao
    abstract fun deviceDao(): DeviceDao
}