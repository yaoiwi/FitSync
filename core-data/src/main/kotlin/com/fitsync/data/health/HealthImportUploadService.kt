package com.fitsync.data.health

import android.content.Context
import com.fitsync.data.db.AccountRegion
import com.fitsync.data.db.SourceType
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.network.CredentialStore
import com.fitsync.data.network.GarminConnectClient
import com.fitsync.data.network.GarminNetworkException
import com.fitsync.data.network.GarminSsoClient
import com.fitsync.data.network.StorableCredential
import com.fitsync.data.network.isTokenFresh
import com.fitsync.data.settings.HealthCopyLedger
import com.fitsync.data.settings.RateLimitGate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把「从官方导出包导入本地库」的健康数据，按日期写回目标 Garmin 账号。
 *
 * **为什么不是直传导出的原始 FIT**：Garmin 的导出包里健康数据主要是 JSON
 * （`DI-Connect-UDS` / `DI-Connect-Wellness`），FIT 是活动与杂项混装；
 * 而且原始 FIT 里的设备身份是当年的手表，目标账号不一定还绑着它，
 * 直传很容易被 419（`Wellness device is not active`）打回。
 *
 * 所以这里用 [MonitoringFitBuilder] 按本地日汇总**重新生成** monitoring 文件，
 * 设备身份取目标账号当前绑定的那块表——这是唯一能稳定落地的写法。
 *
 * 代价：重建出来的是日总量（步数 / 睡眠 / 压力按合理分布铺点），
 * 不含原始秒级曲线。要保留原始曲线只能走「搬运」（直接转发源账号原始包）。
 *
 * 与搬运共用同一个 [rateLimitGate]：两个入口任意一个撞 429，另一个也必须停，
 * 否则会把账号推进 48 小时锁定。
 */
@Singleton
class HealthImportUploadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FitDataRepository,
    private val connectClient: GarminConnectClient,
    private val credentialStore: CredentialStore,
    private val ssoClient: GarminSsoClient,
    private val copyLedger: HealthCopyLedger,
    private val rateLimitGate: RateLimitGate,
) {

    data class Result(
        val uploaded: Int = 0,
        val duplicate: Int = 0,
        val failed: Int = 0,
        val noData: Int = 0,
        val skipped: Int = 0,
        val interrupted: Boolean = false,
        val deviceLabel: String = "",
        val messages: List<String> = emptyList(),
        val error: String? = null,
    ) {
        val summary: String
            get() {
                if (error != null) return "写回目标账号失败：$error"
                if (uploaded == 0 && duplicate == 0 && failed == 0) {
                    return if (skipped > 0) {
                        "这些日期之前已经写回过（$skipped 天），没有重复上传"
                    } else {
                        messages.firstOrNull() ?: "没有可写回的健康数据"
                    }
                }
                return buildString {
                    append("已写回 ").append(uploaded).append(" 天")
                    if (duplicate > 0) append("，").append(duplicate).append(" 天账号内已有")
                    if (noData > 0) append("，").append(noData).append(" 天本地没有数据")
                    if (failed > 0) append("，失败 ").append(failed).append(" 天")
                    if (interrupted) append("。中途因限流停止，再点一次会接着写")
                }
            }
    }

    /**
     * @param dates 需要写回的日期（`yyyy-MM-dd`），来自 [com.fitsync.data.importer.LocalDataImporter.ImportResult.dates]
     */
    suspend fun uploadDates(
        accountId: Long,
        dates: List<String>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val account = repository.getAccount(accountId)
            ?: return@withContext Result(error = "账号不存在")
        if (account.sourceType != SourceType.GARMIN.code) {
            return@withContext Result(error = "只有 Garmin 账号才能写回数据")
        }
        if (rateLimitGate.isBlocked()) {
            return@withContext Result(
                error = "Garmin 限流冷却中：还有 ${rateLimitGate.waitLabel()}，这段时间不会再发请求",
            )
        }

        val credential = runCatching { credentialOf(account) }.getOrElse { e ->
            return@withContext Result(error = e.message ?: "获取账号令牌失败")
        }
        val region = AccountRegion.from(account.region)

        val devices = runCatching {
            connectClient.fetchDevicesDetailed(region, credential.accessToken)
        }.getOrNull()
        val device = devices?.devices
            ?.firstOrNull { it.primary && it.productId > 0 }
            ?: devices?.devices?.firstOrNull { it.productId > 0 }
        if (device == null) {
            return@withContext Result(
                error = "读不到目标账号绑定的设备。请先用 Garmin Express 把这块手表注册到该区账号" +
                    "（Garmin 只接受已绑定设备上传的健康数据）",
            )
        }
        val deviceLabel = buildString {
            append(device.name.ifBlank { "未知设备" })
            if (device.serialNumber > 0L) append(" · SN ").append(device.serialNumber)
            if (device.productId > 0) append(" · PID ").append(device.productId)
        }

        // 本地库里能用来重建 FIT 的只有日汇总与睡眠
        val daily = runCatching { repository.observeDailySummaries(accountId).first() }
            .getOrDefault(emptyList())
            .associateBy { it.date }
        val sleepByDate = runCatching { repository.observeSleepSessions(accountId).first() }
            .getOrDefault(emptyList())
            .groupBy { dateOf(it.startTimeMs) }
            .mapValues { (_, sessions) -> sessions.sumOf { it.totalSleepMin } }

        val done = copyLedger.copiedDates(IMPORT_SOURCE_ID, accountId)
        val pending = dates.distinct().sorted().filter { it !in done }
        val skipped = dates.distinct().size - pending.size

        var uploaded = 0
        var duplicate = 0
        var failed = 0
        var noData = 0
        var processed = 0
        var interrupted = false
        val messages = mutableListOf<String>()

        for (date in pending) {
            val row = daily[date]
            val sleepMin = sleepByDate[date] ?: 0
            if (row == null && sleepMin <= 0) {
                noData++
                processed++
                onProgress?.invoke(processed, pending.size)
                continue
            }

            val file = File(context.cacheDir, "import_${accountId}_$date.fit")
            val fitBytes: ByteArray? = try {
                MonitoringFitBuilder.build(
                    target = file,
                    serialNumber = device.serialNumber,
                    productId = device.productId,
                    dayStartMs = dayStartMs(date),
                    steps = row?.steps ?: 0,
                    restingHr = row?.restingHr ?: 0,
                    sleepMinutes = sleepMin,
                    stressAvg = row?.stressAvg ?: 0,
                )
                file.readBytes()
            } catch (e: Exception) {
                if (messages.size < 6) {
                    messages += "$date：生成 FIT 失败（${e.message ?: e.javaClass.simpleName}）"
                }
                failed++
                processed++
                onProgress?.invoke(processed, pending.size)
                null
            }

            if (fitBytes == null) {
                file.delete()
                continue
            }

            val outcome = try {
                connectClient.uploadActivityFileOutcome(
                    region = region,
                    accessToken = credential.accessToken,
                    fileName = "import_$date.fit",
                    bytes = fitBytes,
                    oauth1Token = credential.oauth1Token,
                    oauth1Secret = credential.oauth1Secret,
                    fieldName = WELLNESS_FORM_FIELD,
                )
            } catch (e: Exception) {
                file.delete()
                if (e is GarminNetworkException.RiskBlocked) {
                    rateLimitGate.noteRateLimited(e.retryAfterMs)
                    interrupted = true
                    messages += "Garmin 限流（429），已停止：还有 ${rateLimitGate.waitLabel()}，这段时间不要再重试"
                    return@withContext Result(
                        uploaded = uploaded,
                        duplicate = duplicate,
                        failed = failed,
                        noData = noData,
                        skipped = skipped,
                        interrupted = true,
                        deviceLabel = deviceLabel,
                        messages = messages.take(6),
                    )
                }
                if (messages.size < 6) {
                    messages += "$date：上传失败（${e.message ?: e.javaClass.simpleName}）"
                }
                failed++
                processed++
                onProgress?.invoke(processed, pending.size)
                delay(UPLOAD_GAP_MS)
                continue
            }

            file.delete()

            when {
                outcome.ok -> {
                    uploaded++
                    copyLedger.markCopied(IMPORT_SOURCE_ID, accountId, date)
                }
                outcome.duplicate -> {
                    duplicate++
                    copyLedger.markCopied(IMPORT_SOURCE_ID, accountId, date)
                }
                outcome.code == 429 -> {
                    rateLimitGate.noteRateLimited()
                    interrupted = true
                    messages += "$date：Garmin 限流（429），已停止：还有 ${rateLimitGate.waitLabel()}，这段时间不要再重试"
                    processed++
                    onProgress?.invoke(processed, pending.size)
                    break
                }
                else -> {
                    failed++
                    if (messages.size < 6) {
                        messages += "$date：${outcome.errorText.ifBlank { "上传未成功（HTTP ${outcome.code}）" }}"
                    }
                }
            }

            processed++
            onProgress?.invoke(processed, pending.size)
            delay(UPLOAD_GAP_MS)
        }

        Result(
            uploaded = uploaded,
            duplicate = duplicate,
            failed = failed,
            noData = noData,
            skipped = skipped,
            interrupted = interrupted,
            deviceLabel = deviceLabel,
            messages = messages.take(6),
        )
    }

    private fun dateOf(tsMs: Long): String =
        Instant.ofEpochMilli(tsMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun dayStartMs(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun credentialOf(account: com.fitsync.data.db.AccountEntity): StorableCredential {
        val credential = credentialStore.load(
            SourceType.GARMIN.code,
            account.region,
            account.externalUserId,
        ) ?: throw IllegalStateException("账号凭据缺失，请重新登录")
        if (credential.isTokenFresh()) return credential
        val refreshed = ssoClient.refreshSession(credential)
        credentialStore.save(refreshed)
        return refreshed
    }

    private companion object {
        /** 导入写回在 ledger 里用的「源账号」哨兵值：负数不会与真实账号 id 冲突 */
        const val IMPORT_SOURCE_ID = -1L

        const val WELLNESS_FORM_FIELD = "userfile"

        /** 比搬运更保守：写回可能一次几百天，宁可慢一点也别把账号写进 48 小时锁定 */
        const val UPLOAD_GAP_MS = 2000L
    }
}
