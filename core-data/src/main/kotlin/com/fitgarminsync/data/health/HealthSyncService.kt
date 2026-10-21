package com.fitgarminsync.data.health

import android.content.Context
import com.fitgarminsync.data.db.AccountEntity
import com.fitgarminsync.data.db.AccountRegion
import com.fitgarminsync.data.db.FitDataRepository
import com.fitgarminsync.data.db.SourceType
import com.fitgarminsync.data.network.CredentialStore
import com.fitgarminsync.data.network.GarminConnectClient
import com.fitgarminsync.data.network.GarminSsoClient
import com.fitgarminsync.data.network.isTokenFresh
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 健康数据跨账号搬运（中国区 ↔ 国际区）。
 *
 * 原理（与 DailySync 那类服务一致）：
 * Garmin 没有"写入健康数据"的 REST 接口，但设备上传的 **monitoring FIT** 会被计入日汇总。
 * 所以这里把源账号的日汇总/睡眠/压力**重新打包成 monitoring FIT**，
 * 以目标账号**已绑定设备**的身份上传（serial number / product 必须匹配）。
 *
 * 前提（缺一不可）：
 * 1. 目标账号必须绑定「主要可穿戴设备」，且这块设备就是你在用的那块手表；
 * 2. 两个区的手表昵称建议保持一致（Garmin 侧按设备归集）；
 * 3. 睡眠 / HRV 类数据 Garmin 只允许回溯约一个月，更早的搬不过去。
 */
@Singleton
class HealthSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FitDataRepository,
    private val connectClient: GarminConnectClient,
    private val credentialStore: CredentialStore,
    private val ssoClient: GarminSsoClient,
) {

    data class Result(
        val uploaded: Int = 0,
        val failed: Int = 0,
        val deviceName: String = "",
        val messages: List<String> = emptyList(),
        val error: String? = null,
    ) {
        val summary: String
            get() = when {
                error != null -> "搬运失败：$error"
                uploaded == 0 && failed == 0 -> "源账号最近没有可搬运的健康数据"
                else -> buildString {
                    append("已搬运 $uploaded 天健康数据")
                    if (deviceName.isNotBlank()) append("（设备：$deviceName）")
                    if (failed > 0) append("，失败 $failed 天")
                }
            }
    }

    suspend fun copyDailyHealth(
        fromAccountId: Long,
        toAccountId: Long,
        days: Int = 7,
    ): Result = withContext(Dispatchers.IO) {
        if (fromAccountId == toAccountId) {
            return@withContext Result(error = "源账号和目标账号相同")
        }
        val from = repository.getAccount(fromAccountId)
            ?: return@withContext Result(error = "源账号不存在")
        val to = repository.getAccount(toAccountId)
            ?: return@withContext Result(error = "目标账号不存在")
        if (to.sourceType != SourceType.GARMIN.code) {
            return@withContext Result(error = "目标账号不是 Garmin 账号")
        }

        val toRegion = AccountRegion.from(to.region)
        val targetToken = runCatching { tokenOf(to) }.getOrElse { e ->
            return@withContext Result(error = e.message ?: "获取目标账号令牌失败")
        }

        val devices = runCatching { connectClient.fetchDevices(toRegion, targetToken) }
            .getOrDefault(emptyList())
        if (devices.isEmpty()) {
            return@withContext Result(
                error = "目标账号没有绑定设备。请先用 Garmin Express 把这块手表注册到该区账号" +
                    "（Garmin 只接受已绑定设备上传的健康数据）"
            )
        }
        val device = devices.firstOrNull { it.primary } ?: devices.first()

        val summaries = repository.observeDailySummaries(fromAccountId).first()
            .filter { it.steps > 0 || it.distanceM > 0 || it.stressAvg > 0 || it.restingHr > 0 }
            .sortedByDescending { it.date }
            .take(days)
        if (summaries.isEmpty()) {
            return@withContext Result(deviceName = device.name)
        }

        val sleeps = repository.observeSleepSessions(fromAccountId).first()
            .associateBy { it.externalId }

        var uploaded = 0
        var failed = 0
        val messages = mutableListOf<String>()

        summaries.forEach { summary ->
            val dayStart = runCatching {
                LocalDate.parse(summary.date)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull() ?: return@forEach

            val sleep = sleeps["sleep-${summary.date}"]
            val file = File(context.cacheDir, "health-${summary.date}.fit")

            val built = runCatching {
                MonitoringFitBuilder.build(
                    target = file,
                    serialNumber = device.serialNumber,
                    productId = device.productId,
                    dayStartMs = dayStart,
                    steps = summary.steps,
                    restingHr = summary.restingHr,
                    sleepMinutes = sleep?.totalSleepMin ?: 0,
                    stressAvg = summary.stressAvg,
                )
            }

            if (built.isFailure || !file.exists()) {
                failed++
                messages += "${summary.date}：生成 FIT 失败（${built.exceptionOrNull()?.message}）"
                file.delete()
                return@forEach
            }

            val response = runCatching {
                connectClient.uploadActivityFile(
                    region = toRegion,
                    accessToken = targetToken,
                    fileName = file.name,
                    bytes = file.readBytes(),
                )
            }.getOrNull()
            file.delete()

            if (response != null) {
                uploaded++
            } else {
                failed++
                messages += "${summary.date}：上传被拒绝"
            }
        }

        Result(
            uploaded = uploaded,
            failed = failed,
            deviceName = device.name,
            messages = messages.take(5),
        )
    }

    private suspend fun tokenOf(account: AccountEntity): String {
        val credential = credentialStore.load(
            SourceType.GARMIN.code,
            account.region,
            account.externalUserId,
        ) ?: throw IllegalStateException("账号 ${account.displayName} 的凭据缺失，请重新登录")
        // 只判 isNotBlank 不够：本地令牌一小时后必过期，复用就会 401
        if (credential.isTokenFresh()) return credential.accessToken
        val refreshed = ssoClient.refreshSession(credential)
        credentialStore.save(refreshed)
        return refreshed.accessToken
    }
}
