package com.fitsync.data.health

import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.AccountRegion
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.SourceType
import com.fitsync.data.network.CredentialStore
import com.fitsync.data.network.GarminConnectClient
import com.fitsync.data.network.GarminNetworkException
import com.fitsync.data.network.GarminSsoClient
import com.fitsync.data.network.StorableCredential
import com.fitsync.data.network.isTokenFresh
import com.fitsync.data.settings.HealthCopyLedger
import com.fitsync.data.settings.RateLimitGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Singleton

/**
 * 健康数据跨账号搬运（中国区 ↔ 国际区）。
 *
 * **做法**：直接把源账号当天的 wellness 原始包转给目标账号，不自己生成 FIT。
 * ```
 * GET  {源}/download-service/files/wellness/{date}    → zip（内含设备上传的 FIT）
 * POST {目标}/upload-service/upload/.fit              → 表单字段 userfile
 * ```
 * Garmin 没有"写健康数据"的 REST 接口，唯一被接受的入口就是设备上传。
 * 原始包里是设备自己写的 FIT：时间戳、时区、设备身份、睡眠分期、压力结构全都正确，
 * 比在本机重新拼一个 monitoring 文件可靠得多（后者参数语义稍错就会被 500/419 打回）。
 *
 * **前提**：
 * 1. 目标账号要绑定过这块手表并激活 wellness（否则 419 `Wellness device is not active`）；
 * 2. 目标账号当天已有文件时服务端返回 409 `Duplicate Wellness File`——这是"已存在"，不是失败。
 */
@Singleton
class HealthSyncService @Inject constructor(
    private val repository: FitDataRepository,
    private val connectClient: GarminConnectClient,
    private val credentialStore: CredentialStore,
    private val ssoClient: GarminSsoClient,
    private val copyLedger: HealthCopyLedger,
    private val rateLimitGate: RateLimitGate,
) {

    data class Result(
        /** 成功上传的天数 */
        val uploaded: Int = 0,
        /** 目标账号已有同样文件的天数（409） */
        val duplicateDays: Int = 0,
        /** 上传或读取失败的天数 */
        val failed: Int = 0,
        /** 源账号这天没有 wellness 包（没戴表/没同步） */
        val noDataDays: Int = 0,
        /** 请求搬运的天数 */
        val requestedDays: Int = 0,
        /** 实际处理过的天数（含跳过、失败、无数据） */
        val doneDays: Int = 0,
        /** 因限流或冷却中途停下；已搬的天数仍然有效，再点一次会从剩下的继续 */
        val interrupted: Boolean = false,
        /** 目标账号本地已有数据而跳过 */
        val skippedExisting: Int = 0,
        /** 之前已经搬过（本地有记录）而跳过 */
        val skippedCopied: Int = 0,
        val uploadedDates: List<String> = emptyList(),
        val deviceName: String = "",
        val deviceSerial: Long = 0L,
        val deviceProductId: Int = 0,
        /** 产品 ID 是接口给的、另一区补的、还是按型号推断的 */
        val deviceProductIdSource: String = "",
        /** 序列号是从 unitId/deviceId 取的（该区下发的 serialNumber 是哨兵值） */
        val deviceSerialFromUnit: Boolean = false,
        val messages: List<String> = emptyList(),
        val error: String? = null,
        /** 设备接口原始返回，失败时贴给用户定位字段问题 */
        val deviceRaw: String = "",
    ) {

        /** 设备身份：排查 419 时最需要的一行 */
        val deviceLabel: String
            get() = when {
                deviceName.isBlank() && deviceSerial <= 0L -> ""
                else -> buildString {
                    append(deviceName.ifBlank { "未知设备" })
                    if (deviceSerial > 0L) {
                        append(" · SN ").append(deviceSerial)
                        if (deviceSerialFromUnit) append("（取自 unitId）")
                    }
                    if (deviceProductId > 0) {
                        append(" · PID ").append(deviceProductId)
                        if (deviceProductIdSource.isNotBlank()) {
                            append("（").append(deviceProductIdSource).append("）")
                        }
                    }
                }
            }

        /**
         * 「为什么只搬了几天」的原因。
         * 用户看到「已搬运 1 天」时的第一反应是功能坏了，必须把每种情况写清楚。
         */
        private val skipNotes: List<String>
            get() = buildList {
                if (skippedExisting > 0) add("目标账号这 $skippedExisting 天本地已有数据")
                if (skippedCopied > 0) add("另外 $skippedCopied 天之前已经搬过")
                if (duplicateDays > 0) add("$duplicateDays 天目标账号已有同样文件")
                if (noDataDays > 0) add("$noDataDays 天源账号没有数据")
            }

        private fun datesLabel(): String =
            uploadedDates.take(7).joinToString("、") { it.takeLast(5) } +
                if (uploadedDates.size > 7) "…" else ""

        val summary: String
            get() {
                val base = when {
                    error != null -> "搬运失败：$error"
                    uploaded == 0 && failed == 0 -> {
                        val notes = skipNotes
                        when {
                            notes.isNotEmpty() -> "没有新日期可搬：" + notes.joinToString("，")
                            else -> messages.firstOrNull() ?: "源账号最近没有可搬运的健康数据"
                        }
                    }
                    uploaded == 0 -> buildString {
                        append("全部失败：")
                        append(messages.firstOrNull() ?: "$failed 天被拒绝")
                    }
                    else -> buildString {
                        append("已搬运 ").append(uploaded).append(" 天")
                        if (uploadedDates.isNotEmpty()) {
                            append("（").append(datesLabel()).append("）")
                        }
                        if (failed > 0) append("，失败 ").append(failed).append(" 天")
                        val notes = skipNotes
                        if (notes.isNotEmpty()) append("；").append(notes.joinToString("，"))
                        if (interrupted) append("。中途停止，再点一次会接着搬剩下的")
                    }
                }
                return base
            }
    }

    /**
     * @param days 搬运跨度。允许到 [MAX_COPY_DAYS]；内部按 [MAX_DAYS_PER_SHARD] 天分片，
     *   片与片之间强制休息，避免连续请求把账号推进 48 小时锁定。
     * @param onProgress 每处理完一天回调一次（已处理, 总数），给界面显示进度用。
     */
    suspend fun copyDailyHealth(
        fromAccountId: Long,
        toAccountId: Long,
        days: Int = 7,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        if (fromAccountId == toAccountId) {
            return@withContext Result(error = "源账号和目标账号相同")
        }
        val from = repository.getAccount(fromAccountId)
            ?: return@withContext Result(error = "源账号不存在")
        val to = repository.getAccount(toAccountId)
            ?: return@withContext Result(error = "目标账号不存在")
        // 超出 Garmin 回溯窗口的部分要不到数据，直接截断，不要白花请求
        val span = days.coerceIn(1, MAX_COPY_DAYS)
        if (from.sourceType != SourceType.GARMIN.code || to.sourceType != SourceType.GARMIN.code) {
            return@withContext Result(error = "搬运只能在同一数据源类型之间进行（Garmin → Garmin）")
        }

        val fromRegion = AccountRegion.from(from.region)
        val toRegion = AccountRegion.from(to.region)
        val sourceCredential = runCatching { credentialOf(from) }.getOrElse { e ->
            return@withContext Result(error = e.message ?: "获取源账号令牌失败")
        }
        val targetCredential = runCatching { credentialOf(to) }.getOrElse { e ->
            return@withContext Result(error = e.message ?: "获取目标账号令牌失败")
        }

        // 冷却期由一个全局闸门统一管：同步 / 搬运 / 自动同步共用一个窗口，
        // 否则各点各的，每点一次都会把 Garmin 的限制往后推
        if (rateLimitGate.isBlocked()) {
            return@withContext Result(
                requestedDays = span,
                error = "Garmin 限流冷却中：还有 ${rateLimitGate.waitLabel()}，" +
                    "这段时间不会再发请求（同步也会跳过）",
            )
        }

        val devices = connectClient.fetchDevicesDetailed(toRegion, targetCredential.accessToken)
        if (devices.devices.isEmpty()) {
            return@withContext Result(
                requestedDays = span,
                deviceRaw = devices.rawSnippet,
                error = devices.error?.let { reason ->
                    buildString {
                        append("读不到目标账号的设备：").append(reason)
                        devices.rawSnippet.takeIf { it.isNotBlank() }?.let {
                            append("\nGarmin 原始返回：").append(it.take(180))
                        }
                    }
                } ?: ("目标账号没有绑定设备。请先用 Garmin Express 把这块手表注册到该区账号" +
                    "（Garmin 只接受已绑定设备上传的健康数据）")
            )
        }
        val device = devices.devices.firstOrNull { it.primary && it.productId > 0 }
            ?: devices.devices.firstOrNull { it.productId > 0 }
            ?: devices.devices.first()
        // 产品 ID 兜底：目标账号没下发时，先用同一块表在另一个区的登记补，
        // 再退到 FIT SDK 的型号表（Forerunner 255 这类常见表）。
        // 注意：走原始包转发时这个值只用于展示，服务端认的是 FIT 里设备自己写的身份。
        var fitProductId = device.productId
        var productSource = ""
        if (fitProductId <= 0) {
            val peerDevices = runCatching {
                connectClient.fetchDevicesDetailed(fromRegion, sourceCredential.accessToken)
            }.getOrNull()?.devices.orEmpty()
            val matched = peerDevices.firstOrNull {
                it.productId > 0 && it.name.equals(device.name, ignoreCase = true)
            } ?: peerDevices.firstOrNull { it.productId > 0 }
            if (matched != null) {
                fitProductId = matched.productId
                productSource = "取自另一区同款设备"
            }
        }
        if (fitProductId <= 0) {
            val guessed = DeviceProducts.byName(device.name)
            if (guessed > 0) {
                fitProductId = guessed
                productSource = "按型号推断"
            }
        }

        // 目标账号本地已有数据的日期先跳过：能省掉一次下载 + 一次上传，
        // 也减少碰撞限流的机会。真正是否重复由服务端 409 判定。
        val targetDates = repository.observeDailySummaries(toAccountId).first()
            .filter { it.hasHealthData() }
            .map { it.date }
            .toSet()
        val copiedDates = copyLedger.copiedDates(fromAccountId, toAccountId)
        val wantedDates = (0 until span)
            .map { LocalDate.now().minusDays(it.toLong()).toString() }
        val pending = wantedDates.filter { it !in targetDates && it !in copiedDates }
        val skippedExisting = wantedDates.count { it in targetDates }
        val skippedCopied = wantedDates.count { it !in targetDates && it in copiedDates }

        if (pending.isEmpty()) {
            return@withContext Result(
                requestedDays = span,
                skippedExisting = skippedExisting,
                skippedCopied = skippedCopied,
                deviceName = device.name,
                deviceSerial = device.serialNumber,
                deviceProductId = fitProductId,
                deviceProductIdSource = productSource,
                deviceSerialFromUnit = device.serialFromUnitId,
            )
        }

        var uploadedDays = 0
        var duplicateDays = 0
        var noDataDays = 0
        var failedDays = 0
        var processedDays = 0
        var interrupted = false
        val uploadedDates = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var rateLimited = false

        // 分片跑：连续请求密度是触发账号级锁定的关键，不是总请求数。
        // 每片最多 MAX_DAYS_PER_SHARD 天，片间休息 SHARD_REST_MS，期间一旦发现冷却立刻停。
        val shards = pending.chunked(MAX_DAYS_PER_SHARD)
        // 有一天撞上 429，同组还没开工的那一天就不再发请求
        val stop = AtomicBoolean(false)

        shardLoop@ for ((shardIndex, shard) in shards.withIndex()) {
            if (shardIndex > 0) {
                delay(SHARD_REST_MS)
                if (rateLimitGate.isBlocked()) {
                    interrupted = true
                    messages += "第 ${shardIndex + 1} 片开始前仍在冷却，已停止"
                    break@shardLoop
                }
            }
            // 两天一组并行：一天里的「下载 zip」和「上传 FIT」等待时间互相重叠，
            // 请求总数不变、墙钟时间接近减半。再往上加会在同一账号上形成突发流量，
            // 更容易触发 429——那是 48 小时锁定，比慢一点亏得多。
            for (group in shard.chunked(PARALLEL_DAYS)) {
                if (stop.get()) break@shardLoop
                val outcomes = coroutineScope {
                    group.map { date ->
                        async {
                            if (stop.get()) {
                                DayOutcome(date, DayOutcome.Kind.CANCELLED)
                            } else {
                                copyOneDay(
                                    date = date,
                                    fromRegion = fromRegion,
                                    sourceToken = sourceCredential.accessToken,
                                    toRegion = toRegion,
                                    target = targetCredential,
                                )
                            }
                        }
                    }.awaitAll()
                }
                for (day in outcomes) {
                    when (day.kind) {
                        DayOutcome.Kind.UPLOADED -> {
                            uploadedDays++
                            uploadedDates += day.date
                            copyLedger.markCopied(fromAccountId, toAccountId, day.date)
                        }
                        DayOutcome.Kind.DUPLICATE -> {
                            // 409 也算搬完了：目标账号本来就有这天的文件
                            duplicateDays++
                            uploadedDates += day.date
                            copyLedger.markCopied(fromAccountId, toAccountId, day.date)
                        }
                        DayOutcome.Kind.NO_DATA -> noDataDays++
                        DayOutcome.Kind.FAILED -> {
                            failedDays++
                            if (messages.size < MAX_MESSAGES) messages += day.message
                            if (day.message.contains("419") && messages.size < MAX_MESSAGES) {
                                messages += "419 表示目标账号没有活跃的 wellness 设备：先在手机 Garmin Connect " +
                                    "打开一次设备页、确认能看到步数，再回来重试"
                            }
                        }
                        DayOutcome.Kind.RATE_LIMITED -> {
                            failedDays++
                            rateLimited = true
                            stop.set(true)
                            messages += day.message
                        }
                        DayOutcome.Kind.CANCELLED -> Unit
                    }
                    // 被取消的那天没发过请求，不该计入进度
                    if (day.kind == DayOutcome.Kind.CANCELLED) continue
                    processedDays++
                    onProgress?.invoke(processedDays, pending.size)
                }
                if (rateLimited) break@shardLoop
            }
        }

        Result(
            uploaded = uploadedDays,
            duplicateDays = duplicateDays,
            failed = failedDays,
            noDataDays = noDataDays,
            requestedDays = span,
            doneDays = processedDays,
            interrupted = interrupted || rateLimited,
            skippedExisting = skippedExisting,
            skippedCopied = skippedCopied,
            uploadedDates = uploadedDates,
            deviceName = device.name,
            deviceSerial = device.serialNumber,
            deviceProductId = fitProductId,
            deviceProductIdSource = productSource,
            deviceSerialFromUnit = device.serialFromUnitId,
            messages = messages.take(5),
        )
    }

    /** 界面上不出现 GUID：旧账号的 displayName 可能只剩 profileId 的 GUID 形式 */
    private fun labelOf(account: AccountEntity): String {
        val name = account.displayName.trim()
        val guidLike = name.length >= 32 && name.count { it == '-' } >= 4 &&
            name.all { it.isLetterOrDigit() || it == '-' }
        if (name.isNotEmpty() && !guidLike) return name
        val region = when (account.region) {
            "cn" -> "中国区"
            "com" -> "国际区"
            else -> account.region
        }
        return "Garmin 账号（$region）"
    }

    /** 取（必要时刷新）账号凭据。上传既要用 accessToken，也要用 OAuth1 兜底 */
    private data class DayOutcome(
        val date: String,
        val kind: Kind,
        val message: String = "",
    ) {
        enum class Kind { UPLOADED, DUPLICATE, NO_DATA, FAILED, RATE_LIMITED, CANCELLED }
    }

    /**
     * 搬一天：下载源账号当天的 wellness 包，再逐个 FIT 上传到目标账号。
     *
     * 抽成独立函数是为了让外层能两天并行；串行调用时行为和以前完全一致。
     * 每天的间隔（[UPLOAD_GAP_MS] / [DAY_GAP_MS]）留在函数内部，
     * 并行时两个工人各自等，不会把请求挤成一簇。
     */
    private suspend fun copyOneDay(
        date: String,
        fromRegion: AccountRegion,
        sourceToken: String,
        toRegion: AccountRegion,
        target: StorableCredential,
    ): DayOutcome {
        // 一天一个 zip，里面通常 1~3 个 FIT（monitoring / sleep / stress）
        val zip = try {
            connectClient.downloadWellnessZip(fromRegion, sourceToken, date)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: GarminNetworkException.RiskBlocked) {
            rateLimitGate.noteRateLimited(e.retryAfterMs)
            return DayOutcome(date, DayOutcome.Kind.RATE_LIMITED, limitNote())
        } catch (e: Exception) {
            delay(DAY_GAP_MS)
            return DayOutcome(
                date,
                DayOutcome.Kind.FAILED,
                "$date：读取源账号数据失败（${e.message ?: e.javaClass.simpleName}）",
            )
        } ?: run {
            delay(DAY_GAP_MS)
            return DayOutcome(date, DayOutcome.Kind.NO_DATA, "$date：源账号没有这天的 wellness 数据")
        }

        val fits = WellnessPackage.extract(zip)
        if (fits.isEmpty()) {
            delay(DAY_GAP_MS)
            return DayOutcome(date, DayOutcome.Kind.NO_DATA, "$date：包内没有 FIT 文件")
        }

        var dayOk = false
        var dayDuplicate = false
        var dayError = ""

        for (fit in fits) {
            val outcome = connectClient.uploadActivityFileOutcome(
                region = toRegion,
                accessToken = target.accessToken,
                fileName = fit.name,
                bytes = fit.bytes,
                oauth1Token = target.oauth1Token,
                oauth1Secret = target.oauth1Secret,
                fieldName = WELLNESS_FORM_FIELD,
            )
            when {
                outcome.ok -> dayOk = true
                outcome.duplicate -> dayDuplicate = true
                else -> {
                    dayError = outcome.errorText
                    if (outcome.code == 429) {
                        rateLimitGate.noteRateLimited()
                        return DayOutcome(date, DayOutcome.Kind.RATE_LIMITED, "$date：${limitNote()}")
                    }
                }
            }
            delay(UPLOAD_GAP_MS)
        }

        delay(DAY_GAP_MS)
        return when {
            dayOk -> DayOutcome(date, DayOutcome.Kind.UPLOADED)
            dayDuplicate -> DayOutcome(date, DayOutcome.Kind.DUPLICATE)
            else -> DayOutcome(
                date,
                DayOutcome.Kind.FAILED,
                "$date：${dayError.ifBlank { "上传未成功" }}",
            )
        }
    }

    private fun limitNote(): String =
        "Garmin 限流（429），已停止：还有 ${rateLimitGate.waitLabel()}，这段时间不要再重试"

    private suspend fun credentialOf(account: AccountEntity): StorableCredential {
        val credential = credentialStore.load(
            SourceType.GARMIN.code,
            account.region,
            account.externalUserId,
        ) ?: throw IllegalStateException("账号 ${labelOf(account)} 的凭据缺失，请重新登录")
        // 只判 isNotBlank 不够：本地令牌一小时后必过期，复用就会 401
        if (credential.isTokenFresh()) return credential
        val refreshed = ssoClient.refreshSession(credential)
        credentialStore.save(refreshed)
        return refreshed
    }

    private companion object {
        /** wellness 上传的表单字段名，与 Garmin 官网一致 */
        const val WELLNESS_FORM_FIELD = "userfile"

        /**
         * 单次运行最多搬多少天。
         *
         * 一天大约 3 个请求（1 下载 + 1~3 上传），两天并行后
         * 200 天 ≈ 600 个请求 ≈ 14 分钟。再往上分片就没意义了——
         * 真正起保护作用的是片间休息，不是片长。
         */
        const val MAX_DAYS_PER_SHARD = 200

        /** 分片之间强制休息：连续请求密度才是触发账号级锁定的原因 */
        const val SHARD_REST_MS = 20_000L

        /**
         * 单次调用允许的最大跨度。
         *
         * Garmin 对第三方只开放约 2 年的健康指标回溯（活动 5 年），再往前要不到数据，
         * 请求也白花——所以超过这个值直接截断。
         */
        const val MAX_COPY_DAYS = 730

        /**
         * 同时处理几天。
         *
         * 2 天：请求速率约 0.7 次/秒，仍低于 Garmin 的突发阈值，但把「等下载」和
         * 「等上传」重叠掉了，一年期搬运从 55 分钟降到 25 分钟上下。
         */
        const val PARALLEL_DAYS = 2

        /** 同一天多个 FIT 之间的间隔。并行后整体密度已经翻倍，这里缩短到 0.7 秒 */
        const val UPLOAD_GAP_MS = 700L

        /** 不同日期之间的间隔（两天并行时各自计） */
        const val DAY_GAP_MS = 400L

        /** 失败原因最多留这么多条，365 天全失败时不要撑大结果对象 */
        const val MAX_MESSAGES = 20
    }
}

/**
 * 判断一天是否值得搬运。
 *
 * Garmin 会给每一天都写一条汇总行，没戴表的日子全是 0；
 * 用这个过滤掉"空白天"，避免为了没数据的日期白白下载一次。
 */
private fun com.fitsync.data.db.DailySummaryEntity.hasHealthData(): Boolean =
    steps > 0 || distanceM > 0 || stressAvg > 0 || restingHr > 0
