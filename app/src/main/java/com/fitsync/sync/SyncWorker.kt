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

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * 每 6 小时同步一次；已有任务则更新参数，不重复排队。
     *
     * 用 runCatching 包住：WorkManager 初始化失败（Manifest 合并异常、测试环境等）
     * 不应该让 Application.onCreate 崩掉——那会导致 App 每次启动都闪退。
     */
    fun schedulePeriodic(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
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

}
