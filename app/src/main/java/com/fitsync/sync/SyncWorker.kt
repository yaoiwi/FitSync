package com.fitsync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fitsync.data.settings.SyncSettings
import com.fitsync.data.sync.GarminSyncEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * 定时同步 Worker。
 *
 * 没用 `@HiltWorker`（那需要改 Manifest 关闭 WorkManager 默认初始化），
 * 而是用 EntryPoint 从 Hilt 图里取引擎，少动基础设施。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            SyncEntryPoint::class.java,
        )
        return try {
            val results = entryPoint.syncEngine().syncAll()
            if (results.isEmpty() || results.all { it.second.ok }) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncEntryPoint {
        fun syncEngine(): GarminSyncEngine
    }
}

object SyncScheduler {

    private const val PERIODIC_NAME = "garmin-periodic-sync"

    /**
     * 按设置里的间隔登记周期任务；`hours <= 0` 表示关闭后台同步。
     *
     * 「仅 Wi-Fi」直接映射成 WorkManager 的 UNMETERED 约束——
     * 让系统在移动网络下根本不启动 Worker，比在 Worker 里判断更省电。
     */
    fun schedulePeriodic(context: Context, hours: Long = SyncSettings.intervalHours(context)) {
        if (hours <= 0L) {
            cancelPeriodic(context)
            return
        }
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (SyncSettings.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }.onFailure {
            android.util.Log.w("SyncScheduler", "定时同步登记失败（不影响 App 使用）", it)
        }
    }

    fun cancelPeriodic(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME) }
            .onFailure { android.util.Log.w("SyncScheduler", "取消定时同步失败", it) }
    }
}
