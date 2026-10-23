package com.fitsync.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 19 张 Room 表（第 4 章数据模型）。
 * 分组落库策略：核心单库 `fitsync.db`；活动/样本量大的表可选「按 accountId 分库」（借鉴 DailySync expo-sqlite 多库隔离）。
 * 唯一键约定：外部 ID 统一带 `externalId`，配合 `UNIQUE(accountId, externalId)` 实现幂等 upsert。
 */

/* ============================== 账号域（3） ============================== */

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["external_user_id"], unique = false),
               Index(value = ["source_type", "region", "external_user_id"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_type") val sourceType: String,          // SourceType.code
    @ColumnInfo(name = "region") val region: String,                   // AccountRegion.code
    @ColumnInfo(name = "external_user_id") val externalUserId: String, // Garmin: profileId / socialProfile
    @ColumnInfo(name = "display_name") val displayName: String,        // 所有业务端点的路径参数，必须落库
    @ColumnInfo(name = "full_name") val fullName: String = "",
    @ColumnInfo(name = "avatar_url") val avatarUrl: String = "",
    @ColumnInfo(name = "device_name") val deviceName: String = "",     // 如 Fenix 7 Pro
    @ColumnInfo(name = "status") val status: String = AccountStatus.NORMAL.code,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "added_at_ms") val addedAtMs: Long = 0,
    @ColumnInfo(name = "last_sync_at_ms") val lastSyncAtMs: Long = 0,
    @ColumnInfo(name = "token_expires_at_ms") val tokenExpiresAtMs: Long = 0,
)

@Entity(tableName = "account_capabilities",
    indices = [Index(value = ["account_id"], unique = true)])
data class AccountCapabilityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    /** 只表示平台本身能力，绝不表示付费等级（1.3 产品原则） */
    @ColumnInfo(name = "supports_mfa") val supportsMfa: Boolean = false,
    @ColumnInfo(name = "supports_sleep") val supportsSleep: Boolean = true,
    @ColumnInfo(name = "supports_hrv") val supportsHrv: Boolean = true,
    @ColumnInfo(name = "supports_spo2") val supportsSpo2: Boolean = false,
    @ColumnInfo(name = "supports_body_battery") val supportsBodyBattery: Boolean = false,
    @ColumnInfo(name = "supports_workouts") val supportsWorkouts: Boolean = false,
    @ColumnInfo(name = "raw_json") val rawJson: String = "",
)

@Entity(tableName = "sync_cursors",
    indices = [Index(value = ["account_id", "data_domain"], unique = true)])
data class SyncCursorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "data_domain") val dataDomain: String,         // sleep/heartRate/activity/...
    @ColumnInfo(name = "last_sync_date") val lastSyncDate: String,     // yyyy-MM-dd
    @ColumnInfo(name = "last_sync_ts_ms") val lastSyncTsMs: Long = 0,
    @ColumnInfo(name = "next_cursor") val nextCursor: String = "",     // 平台游标（若有）
)

/* ============================== 任务域（3） ============================== */

@Entity(tableName = "sync_tasks",
    indices = [Index(value = ["account_id"])])
data class SyncTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "task_type") val taskType: String,             // activity/health/full_backup
    @ColumnInfo(name = "cron_expr") val cronExpr: String,             // "0 20 0 * * ?"
    @ColumnInfo(name = "status") val status: String = SyncTaskStatus.ENABLED.code,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "last_run_at_ms") val lastRunAtMs: Long = 0,
    @ColumnInfo(name = "next_run_at_ms") val nextRunAtMs: Long = 0,
    @ColumnInfo(name = "last_result") val lastResult: String = "",    // ok/failed + 原因
)

@Entity(tableName = "sync_logs",
    indices = [Index(value = ["account_id", "ts_ms"])])
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long = 0,
    @ColumnInfo(name = "trigger") val trigger: String,                // cron/manual/boot/retry
    @ColumnInfo(name = "status") val status: String,                  // success/failed/running
    @ColumnInfo(name = "error_code") val errorCode: String = "",      // 12.2 标准错误码
    @ColumnInfo(name = "error_msg") val errorMsg: String = "",
    @ColumnInfo(name = "records") val records: Int = 0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    @ColumnInfo(name = "ts_ms") val tsMs: Long = 0,
)

@Entity(tableName = "sync_locks",
    indices = [Index(value = ["account_id"], unique = true)])
data class SyncLockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "lock_owner") val lockOwner: String,          // 进程/线程标识
    @ColumnInfo(name = "locked_at_ms") val lockedAtMs: Long = 0,
    @ColumnInfo(name = "ttl_ms") val ttlMs: Long = 0,
)

@Entity(tableName = "backfill_shards",
    indices = [Index(value = ["account_id", "data_domain", "month"], unique = true)])
data class BackfillShardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "data_domain") val dataDomain: String,
    @ColumnInfo(name = "month") val month: String,                    // "2025-07" 按月分片
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "end_date") val endDate: String,
    @ColumnInfo(name = "status") val status: String = BackfillShardStatus.PENDING.code,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long = 0,
)

/* ============================== 运动域（4） ============================== */

@Entity(tableName = "activities",
    indices = [Index(value = ["account_id", "external_id"], unique = true),
               Index(value = ["account_id", "start_time_ms"])])
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "external_id") val externalId: String,
    @ColumnInfo(name = "sport") val sport: String,                   // run/cycle/swim/...
    @ColumnInfo(name = "activity_type") val activityType: String = "",
    @ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
    @ColumnInfo(name = "duration_sec") val durationSec: Long = 0,
    @ColumnInfo(name = "distance_m") val distanceM: Double = 0.0,
    @ColumnInfo(name = "avg_hr") val avgHr: Int = 0,
    @ColumnInfo(name = "max_hr") val maxHr: Int = 0,
    @ColumnInfo(name = "resting_hr") val restingHr: Int = 0,
    @ColumnInfo(name = "calories") val calories: Double = 0.0,
    @ColumnInfo(name = "avg_speed") val avgSpeed: Double = 0.0,
    @ColumnInfo(name = "elevation_gain_m") val elevationGainM: Double = 0.0,
    @ColumnInfo(name = "has_gps") val hasGps: Boolean = false,
    @ColumnInfo(name = "has_splits") val hasSplits: Boolean = false,
    @ColumnInfo(name = "is_all_day") val isAllDay: Boolean = false,
)

@Entity(tableName = "activity_samples",
    indices = [Index(value = ["activity_id"]), Index(value = ["account_id", "ts_ms"])])
data class ActivitySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "activity_id") val activityId: Long,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    @ColumnInfo(name = "hr") val hr: Int = 0,
    @ColumnInfo(name = "cadrance") val cadence: Int = 0,
    @ColumnInfo(name = "power_w") val powerW: Int = 0,
    @ColumnInfo(name = "spo2") val spo2: Int = 0,
    @ColumnInfo(name = "stress") val stress: Int = 0,
    @ColumnInfo(name = "body_battery") val bodyBattery: Int = 0,
    @ColumnInfo(name = "elev_m") val elevM: Double = 0.0,
    @ColumnInfo(name = "temp_c") val tempC: Double = 0.0,
)

@Entity(tableName = "activity_routes",
    indices = [Index(value = ["activity_id"])])
data class ActivityRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "activity_id") val activityId: Long,
    @ColumnInfo(name = "seq") val seq: Int,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lng") val lng: Double,
    @ColumnInfo(name = "elev_m") val elevM: Double = 0.0,
    @ColumnInfo(name = "ts_ms") val tsMs: Long = 0,
    @ColumnInfo(name = "speed_ms") val speedMs: Double = 0.0,
    @ColumnInfo(name = "hr") val hr: Int = 0,
    @ColumnInfo(name = "cadence") val cadence: Int = 0,
    /** 地图渲染 Q5 已定 v1 不做：数据照常入库，v2 接 MapLibre 即可 */
)

@Entity(tableName = "activity_laps",
    indices = [Index(value = ["activity_id"])])
data class ActivityLapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "activity_id") val activityId: Long,
    @ColumnInfo(name = "lap") val lap: Int,
    @ColumnInfo(name = "start_time_ms") val startTimeMs: Long = 0,
    @ColumnInfo(name = "end_time_ms") val endTimeMs: Long = 0,
    @ColumnInfo(name = "duration_sec") val durationSec: Long = 0,
    @ColumnInfo(name = "distance_m") val distanceM: Double = 0.0,
    @ColumnInfo(name = "avg_hr") val avgHr: Int = 0,
    @ColumnInfo(name = "max_hr") val maxHr: Int = 0,
)

/* ============================== 健康域（6） ============================== */

@Entity(tableName = "daily_summaries",
    indices = [Index(value = ["account_id", "date"], unique = true)])
data class DailySummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "date") val date: String,                       // yyyy-MM-dd
    @ColumnInfo(name = "steps") val steps: Int = 0,
    @ColumnInfo(name = "distance_m") val distanceM: Double = 0.0,
    @ColumnInfo(name = "floors") val floors: Int = 0,
    @ColumnInfo(name = "calories_active") val caloriesActive: Double = 0.0,
    @ColumnInfo(name = "calories_resting") val caloriesResting: Double = 0.0,
    @ColumnInfo(name = "stand_min") val standMin: Int = 0,
    @ColumnInfo(name = "resting_hr") val restingHr: Int = 0,
    @ColumnInfo(name = "hrv_ms") val hrvMs: Int = 0,
    @ColumnInfo(name = "respiratory_rate") val respiratoryRate: Int = 0,
    @ColumnInfo(name = "stress_avg") val stressAvg: Int = 0,
    @ColumnInfo(name = "stress_max") val stressMax: Int = 0,
    @ColumnInfo(name = "body_battery_min") val bodyBatteryMin: Int = 0,
    @ColumnInfo(name = "body_battery_max") val bodyBatteryMax: Int = 0,
    @ColumnInfo(name = "spo2_avg") val spo2Avg: Int = 0,
    // Fitbit 官方导出字段（12.5）：Active Zone Minutes + Daily Readiness
    @ColumnInfo(name = "azm_fat_burn_min") val azmFatBurnMin: Int = 0,
    @ColumnInfo(name = "azm_cardio_min") val azmCardioMin: Int = 0,
    @ColumnInfo(name = "azm_peak_min") val azmPeakMin: Int = 0,
    @ColumnInfo(name = "daily_readiness") val dailyReadiness: Int = 0, // 0-100
    @ColumnInfo(name = "water_ml") val waterMl: Int = 0,
)

@Entity(tableName = "sleep_sessions",
    indices = [Index(value = ["account_id", "external_id"], unique = true),
               Index(value = ["account_id", "start_time_ms"])])
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "external_id") val externalId: String,
    @ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
    @ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
    @ColumnInfo(name = "total_sleep_min") val totalSleepMin: Int = 0,
    @ColumnInfo(name = "in_bed_min") val inBedMin: Int = 0,
    @ColumnInfo(name = "sleep_score") val sleepScore: Int = 0,
    @ColumnInfo(name = "sleep_debt_min") val sleepDebtMin: Int = 0,
    @ColumnInfo(name = "avg_hr") val avgHr: Int = 0,
    @ColumnInfo(name = "avg_spo2") val avgSpo2: Int = 0,
    @ColumnInfo(name = "avg_breathing") val avgBreathing: Double = 0.0,
    @ColumnInfo(name = "resp_event_count") val respEventCount: Int = 0,
)

@Entity(tableName = "sleep_stages",
    indices = [Index(value = ["sleep_session_id"])])
data class SleepStageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sleep_session_id") val sleepSessionId: Long,
    @ColumnInfo(name = "stage") val stage: String,                     // awake/light/deep/rem
    @ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
    @ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
    @ColumnInfo(name = "duration_min") val durationMin: Int = 0,
)

@Entity(tableName = "heart_rate_samples",
    indices = [Index(value = ["account_id", "ts_ms"])])
data class HeartRateSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    @ColumnInfo(name = "hr") val hr: Int,
)

@Entity(tableName = "daily_heart_rate",
    indices = [Index(value = ["account_id", "date"], unique = true)])
data class DailyHeartRateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "min_hr") val minHr: Int = 0,
    @ColumnInfo(name = "max_hr") val maxHr: Int = 0,
    @ColumnInfo(name = "avg_hr") val avgHr: Int = 0,
    @ColumnInfo(name = "resting_hr") val restingHr: Int = 0,
)

@Entity(tableName = "wellness_samples",
    indices = [Index(value = ["account_id", "ts_ms"])])
data class WellnessSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    @ColumnInfo(name = "metric") val metric: String,                   // hrv/stress/sleep_score/...
    @ColumnInfo(name = "value") val value: Double = 0.0,
)

@Entity(tableName = "body_metrics",
    indices = [Index(value = ["account_id", "ts_ms"])])
data class BodyMetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    @ColumnInfo(name = "weight_kg") val weightKg: Double = 0.0,
    @ColumnInfo(name = "fat_pct") val fatPct: Double = 0.0,
    @ColumnInfo(name = "vo2max") val vo2max: Double = 0.0,
    @ColumnInfo(name = "bmi") val bmi: Double = 0.0,
)

@Entity(tableName = "devices",
    indices = [Index(value = ["account_id"])])
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "type") val type: String,                       // watch/band/scale/...
    @ColumnInfo(name = "name") val name: String = "",
    @ColumnInfo(name = "model") val model: String = "",
    @ColumnInfo(name = "battery_pct") val batteryPct: Int = 0,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long = 0,
)