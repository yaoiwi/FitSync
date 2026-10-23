package com.fitsync.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 19 张表的 DAO 聚合。
 * 统一采用「UPSERT 幂等」写法：外部数据每次同步可能重复回传，
 * 依赖唯一键 `UNIQUE(account_id, external_id)` 做去重。
 * 时间/日期字段统一用 `String`(yyyy-MM-dd) 或 `Long`(epoch ms) 落库，避免时区歧义。
 */
object Daos {
    // 说明：为保持文件紧凑，这里把 19 个 DAO 用接口逐个声明；
    // 实际工程可拆成 19 个文件。AppDatabase 统一引用。
}

/* ---------------- 账号域 ---------------- */
@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: AccountEntity): Long

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observe(id: Long): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE enabled = 1 ORDER BY added_at_ms DESC")
    fun observeEnabledAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE source_type = :src AND region = :region AND external_user_id = :uid")
    suspend fun byUniqueKey(src: String, region: String, uid: String): AccountEntity?

    @Query("UPDATE accounts SET status = :status, last_sync_at_ms = :ts WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, ts: Long)

    @Query("UPDATE accounts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Delete
    suspend fun delete(a: AccountEntity)

    /** 删除账号：级联清数据（保留回收站 30 天，见 12.3） */
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AccountCapabilityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: AccountCapabilityEntity)

    @Query("SELECT * FROM account_capabilities WHERE account_id = :accountId")
    suspend fun byAccount(accountId: Long): AccountCapabilityEntity?

    @Query("DELETE FROM account_capabilities WHERE account_id = :accountId")
    suspend fun deleteByAccount(accountId: Long)
}

@Dao
interface SyncCursorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: SyncCursorEntity)

    @Query("SELECT * FROM sync_cursors WHERE account_id = :a AND data_domain = :d")
    suspend fun get(a: Long, d: String): SyncCursorEntity?

    @Query("UPDATE sync_cursors SET last_sync_date = :date, last_sync_ts_ms = :ts, next_cursor = :cursor WHERE account_id = :a AND data_domain = :d")
    suspend fun advance(a: Long, d: String, date: String, ts: Long, cursor: String)

    @Query("DELETE FROM sync_cursors WHERE account_id = :a")
    suspend fun clear(a: Long)
}

/* ---------------- 任务域 ---------------- */
@Dao
interface SyncTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: SyncTaskEntity): Long

    @Query("SELECT * FROM sync_tasks WHERE account_id = :a")
    fun observeByAccount(a: Long): Flow<List<SyncTaskEntity>>

    @Query("SELECT * FROM sync_tasks WHERE enabled = 1")
    fun observeEnabled(): Flow<List<SyncTaskEntity>>

    @Query("UPDATE sync_tasks SET status = :status, last_run_at_ms = :ts, last_result = :result WHERE id = :id")
    suspend fun updateRun(id: Long, status: String, ts: Long, result: String)

    @Query("UPDATE sync_tasks SET next_run_at_ms = :ts WHERE id = :id")
    suspend fun setNextRun(id: Long, ts: Long)

    @Delete
    suspend fun delete(t: SyncTaskEntity)
}

@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(log: SyncLogEntity): Long

    @Query("SELECT * FROM sync_logs WHERE account_id = :a ORDER BY ts_ms DESC LIMIT :limit")
    fun observeRecent(a: Long, limit: Int = 20): Flow<List<SyncLogEntity>>

    @Query("DELETE FROM sync_logs WHERE ts_ms < :beforeMs")
    suspend fun cleanBefore(beforeMs: Long)
}

@Dao
interface SyncLockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun acquire(l: SyncLockEntity)

    @Query("SELECT * FROM sync_locks WHERE account_id = :a")
    suspend fun get(a: Long): SyncLockEntity?

    @Query("DELETE FROM sync_locks WHERE account_id = :a")
    suspend fun release(a: Long)
}

@Dao
interface BackfillShardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(shards: List<BackfillShardEntity>)

    @Query("SELECT * FROM backfill_shards WHERE account_id = :a AND status = 'pending' ORDER BY month")
    suspend fun pending(a: Long): List<BackfillShardEntity>

    @Query("UPDATE backfill_shards SET status = :status, updated_at_ms = :ts WHERE id = :id")
    suspend fun mark(id: Long, status: String, ts: Long)

    @Query("DELETE FROM backfill_shards WHERE account_id = :a AND status = 'done' AND updated_at_ms < :beforeMs")
    suspend fun cleanupDone(a: Long, beforeMs: Long)

    @Query("DELETE FROM backfill_shards WHERE account_id = :a AND status = 'done'")
    suspend fun cleanupAllDone(a: Long)

    @Query("DELETE FROM backfill_shards WHERE account_id = :a")
    suspend fun clear(a: Long)
}

/* ---------------- 运动域 ---------------- */
@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: ActivityEntity): Long

    @Query("SELECT * FROM activities WHERE account_id = :a ORDER BY start_time_ms DESC")
    fun observeByAccount(a: Long): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getById(id: Long): ActivityEntity?

    @Query("SELECT COUNT(*) FROM activities WHERE account_id = :a")
    suspend fun countByAccount(a: Long): Int

    @Query("SELECT * FROM activities ORDER BY start_time_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityEntity>>

    @Query("SELECT COUNT(*) FROM activities")
    fun observeTotalCount(): Flow<Int>

    @Query("DELETE FROM activities WHERE account_id = :a")
    suspend fun deleteByAccount(a: Long)
}

@Dao
interface ActivitySampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<ActivitySampleEntity>)

    @Query("SELECT * FROM activity_samples WHERE activity_id = :id ORDER BY ts_ms")
    suspend fun byActivity(id: Long): List<ActivitySampleEntity>

    @Query("SELECT * FROM activity_samples WHERE account_id = :a ORDER BY ts_ms")
    suspend fun byAccount(a: Long): List<ActivitySampleEntity>

    @Query("DELETE FROM activity_samples WHERE account_id = :a")
    suspend fun deleteByAccount(a: Long)
}

@Dao
interface ActivityRouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(points: List<ActivityRouteEntity>)

    @Query("SELECT * FROM activity_routes WHERE activity_id = :id ORDER BY seq")
    suspend fun byActivity(id: Long): List<ActivityRouteEntity>

    @Query("DELETE FROM activity_routes WHERE activity_id = :id")
    suspend fun deleteByActivity(id: Long)

    @Query("DELETE FROM activity_routes WHERE activity_id IN (SELECT id FROM activities WHERE account_id = :a)")
    suspend fun deleteByAccount(a: Long)
}

@Dao
interface ActivityLapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(laps: List<ActivityLapEntity>)

    @Query("SELECT * FROM activity_laps WHERE activity_id = :id ORDER BY lap")
    suspend fun byActivity(id: Long): List<ActivityLapEntity>

    @Query("DELETE FROM activity_laps WHERE activity_id = :id")
    suspend fun deleteByActivity(id: Long)

    @Query("DELETE FROM activity_laps WHERE activity_id IN (SELECT id FROM activities WHERE account_id = :a)")
    suspend fun deleteByAccount(a: Long)
}

/* ---------------- 健康域 ---------------- */
@Dao
interface DailySummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<DailySummaryEntity>)

    @Query("SELECT * FROM daily_summaries WHERE account_id = :a ORDER BY date DESC")
    fun observeByAccount(a: Long): Flow<List<DailySummaryEntity>>

    @Query("SELECT * FROM daily_summaries WHERE account_id = :a AND date = :date")
    suspend fun byDate(a: Long, date: String): DailySummaryEntity?

    @Query(
        """SELECT * FROM daily_summaries
           WHERE steps > 0 OR distance_m > 0 OR calories_active > 0 OR resting_hr > 0
           ORDER BY date DESC, id DESC LIMIT 1"""
    )
    fun observeLatest(): Flow<DailySummaryEntity?>

    @Query("SELECT COUNT(*) FROM daily_summaries")
    fun observeCount(): Flow<Int>

    /** 跨账号合并用：最近 N 天的全部行 */
    @Query("SELECT * FROM daily_summaries ORDER BY date DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DailySummaryEntity>>

    @Query("DELETE FROM daily_summaries WHERE account_id = :a")
    suspend fun deleteByAccount(a: Long)
}

@Dao
interface SleepSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SleepSessionEntity): Long

    @Query("SELECT * FROM sleep_sessions WHERE account_id = :a ORDER BY start_time_ms DESC")
    fun observeByAccount(a: Long): Flow<List<SleepSessionEntity>>

    @Query(
        "SELECT * FROM sleep_sessions WHERE total_sleep_min > 0 ORDER BY end_time_ms DESC LIMIT 1"
    )
    fun observeLatest(): Flow<SleepSessionEntity?>

    @Query(
        "SELECT * FROM sleep_sessions WHERE total_sleep_min > 0 ORDER BY end_time_ms DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<SleepSessionEntity>>

    @Query("DELETE FROM sleep_sessions WHERE account_id = :a")
    suspend fun deleteByAccount(a: Long)
}

@Dao
interface SleepStageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stages: List<SleepStageEntity>)

    @Query("SELECT * FROM sleep_stages WHERE sleep_session_id = :id ORDER BY start_time_ms")
    suspend fun bySession(id: Long): List<SleepStageEntity>

    @Query("DELETE FROM sleep_stages WHERE sleep_session_id = :id")
    suspend fun deleteBySession(id: Long)

    @Query("DELETE FROM sleep_stages WHERE sleep_session_id IN (SELECT id FROM sleep_sessions WHERE account_id = :a)")
    suspend fun deleteByAccount(a: Long)
}

@Dao
interface HeartRateSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<HeartRateSampleEntity>)

    @Query("SELECT * FROM heart_rate_samples WHERE account_id = :a AND ts_ms BETWEEN :from AND :to ORDER BY ts_ms")
    suspend fun range(a: Long, from: Long, to: Long): List<HeartRateSampleEntity>

    @Query("DELETE FROM heart_rate_samples WHERE account_id = :a")
    suspend fun deleteByAccount(a: Long)
}

@Dao
interface DailyHeartRateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<DailyHeartRateEntity>)

    @Query("SELECT * FROM daily_heart_rate WHERE account_id = :a ORDER BY date DESC")
    fun observeByAccount(a: Long): Flow<List<DailyHeartRateEntity>>

    @Query("DELETE FROM daily_heart_rate WHERE account_id = :a")
    suspend fun deleteByAccount(a: Long)
}

@Dao
interface WellnessSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<WellnessSampleEntity>)

    @Query("SELECT * FROM wellness_samples WHERE account_id = :a AND metric = :m AND ts_ms BETWEEN :from AND :to ORDER BY ts_ms")
    suspend fun range(a: Long, m: String, from: Long, to: Long): List<WellnessSampleEntity>

    @Query("DELETE FROM wellness_samples WHERE account_id = :a")
    suspend fun deleteByAccount(a: Long)
}

@Dao
interface BodyMetricDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<BodyMetricEntity>)

    @Query("SELECT * FROM body_metrics WHERE account_id = :a ORDER BY ts_ms DESC")
    suspend fun byAccount(a: Long): List<BodyMetricEntity>

    @Query("DELETE FROM body_metrics WHERE account_id = :a")
    suspend fun deleteByAccount(a: Long)
}

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<DeviceEntity>)

    @Query("SELECT * FROM devices WHERE account_id = :a")
    fun observeByAccount(a: Long): Flow<List<DeviceEntity>>

    @Query("DELETE FROM devices WHERE account_id = :a")
    suspend fun deleteByAccount(a: Long)
}