package com.fitsync.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.fitsync.data.backup.RestoreHelper
import com.fitsync.data.db.AccountStatus
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.SourceType
import com.fitsync.data.health.HealthSyncService
import com.fitsync.data.importer.LocalDataImporter
import com.fitsync.data.settings.AccountAliasStore
import com.fitsync.data.settings.HealthCopyLedger
import com.fitsync.data.settings.PrimaryAccountStore
import com.fitsync.data.settings.RateLimitGate
import com.fitsync.data.settings.SyncSettings
import com.fitsync.data.sync.ActivityCopyService
import com.fitsync.data.sync.ActivityPaging
import com.fitsync.data.sync.GarminSyncEngine
import com.fitsync.data.sync.SyncScope
import com.fitsync.ui.components.accountTitle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步协调器：把「同步 / 导入 / 跨账号复制」集中到一处。
 *
 * 为什么不在 ViewModel 里：**打开 App 就要同步**，而 ViewModel 只在首页可见时才存在；
 * 这里用 Application 级的协程作用域，Activity 启动时就能触发，UI 只订阅状态。
 *
 * 自动同步做了节流：30 分钟内不重复（见 [AUTO_SYNC_INTERVAL_MS]），避免切前后台就反复拉数据。
 */
@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FitDataRepository,
    private val syncEngine: GarminSyncEngine,
    private val primaryAccountStore: PrimaryAccountStore,
    private val localImporter: LocalDataImporter,
    private val activityCopyService: ActivityCopyService,
    private val healthSyncService: HealthSyncService,
    private val importUploadService: com.fitsync.data.health.HealthImportUploadService,
    private val aliasStore: AccountAliasStore,
    private val rateLimitGate: RateLimitGate,
    private val copyLedger: HealthCopyLedger,
) {

    data class UiState(
        val syncing: Boolean = false,
        val message: String? = null,
        /** 自动同步：不显示下拉刷新动画，避免一打开应用就转圈 */
        val silent: Boolean = false,
    )

    private val prefs = context.getSharedPreferences("fitsync_sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 当前长任务（搬运 / 回填 / 导入写回）。
     *
     * 只留一个引用：这几件事共用 Garmin 的配额和本地库，本来就不该并行。
     * 通知栏的「停止」取消的也是它。
     */
    private var longTaskJob: Job? = null

    /** 通知进度节流用：上次推送的时刻与天数 */
    private var lastProgressNotifyMs = 0L
    private var lastProgressNotifyDone = -1

    private val _longTaskRunning = MutableStateFlow(false)

    /**
     * 搬运 / 回填 / 导入写回是否正在跑。
     *
     * 界面据此决定是否让下拉刷新头「停住转圈」：长任务动辄十几分钟，
     * 用户更需要「它还在跑」的确认；短同步则只用一条静止细线，列表照常能滑。
     */
    val longTaskRunning: StateFlow<Boolean> = _longTaskRunning.asStateFlow()

    private val _copyHealthRunning = MutableStateFlow(false)

    /**
     * 只有「搬运健康数据」在跑。
     *
     * 这一年期的任务要二十多分钟，用户很可能一边等一边翻数据，
     * 所以它单独用细线、列表保持可滑；其余同步与长任务都用刷新头停住。
     */
    val copyHealthRunning: StateFlow<Boolean> = _copyHealthRunning.asStateFlow()

    fun isLongTaskRunning(): Boolean = longTaskJob?.isActive == true

    /** 统一登记长任务：置位状态流，任务结束（含取消、异常）自动复位 */
    private fun launchLongTask(
        copyingHealth: Boolean = false,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val job = scope.launch(block = block)
        longTaskJob = job
        _longTaskRunning.value = true
        if (copyingHealth) _copyHealthRunning.value = true
        job.invokeOnCompletion {
            _longTaskRunning.value = false
            if (copyingHealth) _copyHealthRunning.value = false
        }
    }

    /**
     * 通知栏「停止」的入口。
     *
     * 取消只影响这一次任务：`delay` 与 `withContext` 都是可取消的，
     * 已写进 Garmin 的数据不会回滚（本来也没有删除接口）。
     *
     * [reason] 给系统配额用尽这类「不是用户点的停止」留一个说明，否则界面会显示
     * 「已停止」，用户以为自己误触了。
     */
    fun cancelLongTask(reason: String? = null) {
        val job = longTaskJob
        if (job?.isActive != true) return
        job.cancel()
        _state.value = UiState(
            syncing = false,
            message = reason ?: "已停止：已完成的进度都保留，再点一次会接着做",
        )
    }

    /** 「打开即同步」开关 */
    private val _autoSyncOnOpen = MutableStateFlow(SyncSettings.autoSyncOnOpen(context))
    val autoSyncOnOpenEnabled: StateFlow<Boolean> = _autoSyncOnOpen.asStateFlow()

    fun setAutoSyncOnOpen(enabled: Boolean) {
        _autoSyncOnOpen.value = enabled
        SyncSettings.setAutoSyncOnOpen(context, enabled)
    }

    /** 后台同步间隔（小时），0 = 仅手动 */
    private val _syncIntervalHours = MutableStateFlow(SyncSettings.intervalHours(context))
    val syncIntervalHours: StateFlow<Long> = _syncIntervalHours.asStateFlow()

    fun setSyncInterval(hours: Long) {
        _syncIntervalHours.value = hours
        SyncSettings.setIntervalHours(context, hours)
        SyncScheduler.schedulePeriodic(context, hours)
    }

    /** 仅 Wi-Fi 自动同步 */
    private val _wifiOnly = MutableStateFlow(SyncSettings.wifiOnly(context))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    fun setWifiOnly(enabled: Boolean) {
        _wifiOnly.value = enabled
        SyncSettings.setWifiOnly(context, enabled)
        // 约束变了，重新登记一次 Worker
        SyncScheduler.schedulePeriodic(context)
    }

    /** 一次「同步活动」拉多少条 */
    private val _activityLimit = MutableStateFlow(SyncSettings.activityLimit(context))
    val activityLimit: StateFlow<Int> = _activityLimit.asStateFlow()

    fun setActivityLimit(limit: Int) {
        val normalized = ActivityPaging.normalize(limit)
        _activityLimit.value = normalized
        SyncSettings.setActivityLimit(context, normalized)
    }

    /** 回填默认天数 */
    private val _backfillDays = MutableStateFlow(SyncSettings.backfillDays(context))
    val backfillDays: StateFlow<Int> = _backfillDays.asStateFlow()

    fun setBackfillDays(days: Int) {
        _backfillDays.value = days
        SyncSettings.setBackfillDays(context, days)
    }

    /** 搬运健康数据的默认跨度（7 / 30 / 90 / 365 / 730 天） */
    private val _copyHealthDays = MutableStateFlow(SyncSettings.copyHealthDays(context))
    val copyHealthDays: StateFlow<Int> = _copyHealthDays.asStateFlow()

    fun setCopyHealthDays(days: Int) {
        _copyHealthDays.value = days
        SyncSettings.setCopyHealthDays(context, days)
    }

    /** 搬运风险说明是否已勾选「不再提示」 */
    private val _copyRiskAcknowledged = MutableStateFlow(SyncSettings.copyRiskAcknowledged(context))
    val copyRiskAcknowledged: StateFlow<Boolean> = _copyRiskAcknowledged.asStateFlow()

    fun acknowledgeCopyRisk() {
        _copyRiskAcknowledged.value = true
        SyncSettings.setCopyRiskAcknowledged(context, true)
    }

    /**
     * App 启动 / 回到前台时调用。
     * 静默执行：没有账号、失败都不打扰用户；成功后写入"最近同步"时间。
     */
    fun autoSyncOnOpen() {
        if (!_autoSyncOnOpen.value) return
        // 冷却期内自动同步一次都不发：每次打开 App 的重试都会把限流窗口往后推
        if (rateLimitGate.isBlocked()) return
        val last = SyncSettings.lastAutoSyncMs(context)
        if (System.currentTimeMillis() - last < AUTO_SYNC_INTERVAL_MS) return
        SyncSettings.markAutoSync(context, System.currentTimeMillis())

        scope.launch {
            if (repository.observeEnabledAccounts().first()
                    .none { it.sourceType == SourceType.GARMIN.code }
            ) {
                return@launch
            }
            runSync(manual = false, scope = SyncScope.ALL)
        }
    }

    /** 用户点「同步」：健康数据 + 运动活动一起拉 */
    fun syncNow() {
        scope.launch { runSync(manual = true, scope = SyncScope.ALL) }
    }

    /** 只同步健康数据（日汇总、睡眠、心率） */
    fun syncHealthNow() {
        scope.launch { runSync(manual = true, scope = SyncScope.HEALTH) }
    }

    /** 只同步运动活动（按设置的条数翻页） */
    fun syncActivitiesNow() {
        scope.launch { runSync(manual = true, scope = SyncScope.ACTIVITY) }
    }

    /** 设置页展示用：冷却剩余毫秒，0 表示当前可以请求 */
    fun rateLimitRemainingMs(): Long = rateLimitGate.remainingMs()

    /** 设置页展示用：这是连续第几次被限流 */
    fun rateLimitStrikes(): Int = rateLimitGate.strikes()

    /**
     * 用户确认「已换网络 / 已过提示的锁定时间」后，手动解除本地冷却并立刻重试一次。
     *
     * 为什么需要这个入口：冷却时间是本地估算的（阶梯 + `Retry-After`），
     * 有可能比真实锁定更长；也可能用户换了出口 IP，账号已经能用。没有手动解除，
     * App 就会一直显示「账号限制」却一个请求都不发，看起来像同步坏了。
     *
     * 只清本地状态，不动连击计数以外的任何数据；如果账号真的还在锁定，
     * 这次试探会立刻再收到 429，冷却会重新开始计时。
     */
    fun retryAfterRateLimit() {
        scope.launch {
            rateLimitGate.clear()
            _state.value = UiState(syncing = false, message = "已清除本地冷却，正在重试…")
            // 「立即重试」是兜底动作，按全部同步来：用户点它是想恢复正常，不是只想拉一类
            runSync(manual = true, scope = SyncScope.ALL)
        }
    }

    private suspend fun runSync(manual: Boolean, scope: SyncScope) {
        if (_state.value.syncing) return
        // 手动同步不受限制；自动同步在「仅 Wi-Fi」开启且当前是移动网络时直接跳过
        if (!manual && SyncSettings.wifiOnly(context) && !isOnWifi()) {
            _state.value = UiState(syncing = false, message = null)
            return
        }
        val cooldown = rateLimitGate.remainingMs()
        if (cooldown > 0L) {
            if (manual) {
                _state.value = UiState(
                    syncing = false,
                    message = "Garmin 限流冷却中：还有 ${rateLimitGate.waitLabel()}。" +
                        "这段时间不会发出任何请求，越点恢复越慢；" +
                        "如果已经换了网络或过了锁定时间，到「设置 → 同步」点「立即重试」",
                )
            }
            return
        }

        _state.value = UiState(
            syncing = true,
            message = if (manual) null else "正在自动同步…",
            silent = !manual,
        )

        val batch = runCatching {
            syncEngine.syncAll(
                primaryFirstId = primaryAccountStore.primaryAccountId.value,
                scope = scope,
                // 只有用户主动点的那一刻才用他选的条数。自动同步（打开 App、后台定时）
                // 固定一页 20 条：那几次是「顺手更新」，不该在用户没看着的时候
                // 把请求量放大 5 倍。
                activityLimit = if (manual) _activityLimit.value else ActivityPaging.DEFAULT_LIMIT,
            )
        }.getOrElse { error ->
            _state.value = UiState(
                syncing = false,
                message = if (manual) "同步失败：${error.message ?: error.javaClass.simpleName}"
                else null,
            )
            return
        }

        // 后台 Worker 或另一个入口正在同步：这次一个请求都没发出去。
        // 不能说「已同步 0 条」，那看起来像账号没数据。
        if (batch.busy) {
            _state.value = UiState(
                syncing = false,
                message = if (manual) "已经在同步了，等这一轮结束再看" else null,
            )
            return
        }
        val results = batch.results

        val records = results.sumOf { it.second.records }
        val failed = results.filter { !it.second.ok }
        val limited = results.firstOrNull { it.second.rateLimited }

        val message = when {
            // 429 单独说：这不是「同步坏了」，而是账号侧的冷却，且必须先停下来。
            // Garmin 的规则是「10 分钟内第二次 429 就锁账号 48–72 小时」，所以
            // 这里必须把「再点会更久」写在提示里，否则用户一定会再点一次。
            limited != null -> {
                rateLimitGate.noteRateLimited(limited.second.retryAfterMs)
                val strikes = rateLimitGate.strikes()
                buildString {
                    append("Garmin 限流（HTTP 429）：已停止全部请求，还有 ")
                    append(rateLimitGate.waitLabel())
                    append("。")
                    if (strikes >= 2) {
                        append("这已经是连续第 ").append(strikes).append(" 次，账号可能进入 48–72 小时锁定，")
                        append("期间再点只会把锁定时间往后推；到点再试一次。")
                    } else {
                        append("10 分钟内再撞一次 429 会触发 48 小时账号锁定，所以这段时间别再点了；")
                        append("也可以在浏览器登录 connect.garmin.cn 确认账号本身能正常打开。")
                    }
                }
            }
            failed.isNotEmpty() ->
                "同步失败（${failed.size} 个账号）：${failed.first().second.message}"
            results.isEmpty() -> if (manual) "还没有可同步的账号" else null
            else -> if (manual) syncDoneLabel(scope, records) else null
        }

        if (limited == null && failed.isEmpty() && results.isNotEmpty()) {
            // 完整成功一次，说明账号已恢复：清掉连击计数，下次再撞也是从 30 分钟起步
            rateLimitGate.noteSuccess()
        }

        _state.value = UiState(syncing = false, message = message)
    }

    /**
     * 三种同步的完成文案。
     *
     * 分开写是为了让用户一眼看出这次到底拉了哪一类：「已同步 20 条」在只拉活动时
     * 会让人以为健康数据也更新了，而实际上一条日汇总都没请求。
     */
    private fun syncDoneLabel(scope: SyncScope, records: Int): String = when (scope) {
        SyncScope.HEALTH -> "已同步健康数据 $records 条"
        SyncScope.ACTIVITY -> "已同步活动 $records 条"
        SyncScope.ALL -> "已同步 $records 条记录"
    }

    /**
     * 用户点「补齐历史记录」。
     *
     * 这是慢任务（一年约 730 次请求），只在用户主动触发时跑；
     * 进度直接写进 [UiState.message]，首页/设置页都能看到。
     */
    fun backfillHistory(days: Int = SyncSettings.backfillDays(context)) {
        if (isLongTaskRunning()) {
            _state.value = UiState(syncing = false, message = "已经有一个长任务在跑，等它结束再试")
            return
        }
        launchLongTask {
            if (_state.value.syncing) return@launchLongTask
            if (rateLimitGate.isBlocked()) {
                _state.value = UiState(
                    message = "Garmin 限流冷却中：还有 ${rateLimitGate.waitLabel()}，现在补也只会一直撞 429",
                )
                return@launchLongTask
            }
            val accounts = repository.observeEnabledAccounts().first()
                .filter { it.sourceType == SourceType.GARMIN.code }
            if (accounts.isEmpty()) {
                _state.value = UiState(syncing = false, message = "还没有可补齐历史的 Garmin 账号")
                return@launchLongTask
            }

            // 补一年 ≈ 半小时，必须先进前台：否则锁屏几分钟后网络就被 Doze 掐了
            startLongTaskService("补齐历史记录", "准备中…")
            var total = 0
            var failed = 0
            var limited: GarminSyncEngine.SyncOutcome? = null
            try {
                _state.value = UiState(syncing = true, message = "正在补齐历史记录…")
                for (account in accounts) {
                    // 前台服务保住进程和网络，CPU 还要靠 WakeLock——锁屏后没有 CPU，
                    // delay 到点也不会恢复执行，进度会停在某一格
                    val result = withCpuAwake {
                        syncEngine.backfillHistory(account.id, days) { done, all, label ->
                            val text = "${labelOf(account)}：$label（$done/$all 天）"
                            _state.value = UiState(syncing = true, message = text)
                            updateLongTaskService("补齐历史记录", text, done, all)
                        }
                    }
                    if (result.ok) total += result.records else failed++
                    if (result.rateLimited) {
                        limited = result
                        break
                    }
                }
                if (limited != null) {
                    rateLimitGate.noteRateLimited(limited.retryAfterMs)
                }
                val summary = when {
                    limited != null ->
                        "补齐历史被 Garmin 限流：已停止，还有 ${rateLimitGate.waitLabel()}。" +
                            "已下载 $total 条，到点后可以接着补"
                    failed > 0 -> "补齐结束：$failed 个账号失败，成功下载 $total 条"
                    else -> "补齐完成，共下载 $total 条记录"
                }
                _state.value = UiState(syncing = false, message = summary)
                finishLongTaskService(
                    title = if (limited == null && failed == 0) "补齐完成" else "补齐中断",
                    text = summary,
                )
            } catch (e: CancellationException) {
                // 用户点了通知栏的「停止」：已写入的不会回滚，再点一次从断点继续
                finishLongTaskService("补齐已停止", "已下载 $total 条，再点一次会接着补")
                throw e
            } catch (e: Exception) {
                val text = "补齐历史失败：${e.message ?: e.javaClass.simpleName}"
                _state.value = UiState(syncing = false, message = text)
                finishLongTaskService("补齐历史失败", text)
            }
        }
    }

    /**
     * 从 SAF 选中的文件导入。
     *
     * 以前这一步在 `ActivityResult` 回调里直接 `readBytes()`——那是主线程。
     * 导入包上限 32 MB，读一次要几百毫秒，慢机型够触发一次明显卡顿甚至 ANR；
     * 大文件还会把整份内容连同解压结果一起堆在内存里。现在先流式读、边读边计数，
     * 超过 [MAX_IMPORT_BYTES] 立刻放弃，剩下的交给 IO 线程。
     */
    fun importFromUri(uri: Uri) {
        if (isLongTaskRunning()) {
            _state.value = UiState(syncing = false, message = "已经有一个长任务在跑，等它结束再试")
            return
        }
        scope.launch {
            when (val read = readCapped(uri)) {
                is ImportSource.Ready -> importData(read.bytes, read.name)
                is ImportSource.TooLarge ->
                    importFailed("文件有 ${read.bytes / 1024 / 1024} MB，单个导入包不要超过 64 MB")
                ImportSource.Unreadable -> importFailed("无法读取所选文件（可能没有访问权限）")
            }
        }
    }

    private sealed interface ImportSource {
        data class Ready(val bytes: ByteArray, val name: String) : ImportSource
        data class TooLarge(val bytes: Long) : ImportSource
        data object Unreadable : ImportSource
    }

    private suspend fun readCapped(uri: Uri): ImportSource = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    // 边读边数：不先读满再判断，否则超大文件还没报错就已经把内存吃掉了
                    if (total > MAX_IMPORT_BYTES) return@use ImportSource.TooLarge(total)
                    out.write(buf, 0, n)
                }
                if (total == 0L) {
                    ImportSource.Unreadable
                } else {
                    ImportSource.Ready(out.toByteArray(), uri.lastPathSegment ?: "import")
                }
            } ?: ImportSource.Unreadable
        }.getOrDefault(ImportSource.Unreadable)
    }

    /**
     * 启动时的一次性善后。
     *
     * 恢复备份后 Keystore 里的密钥已经换了一套，备份里那些 token 一个也解不开。
     * 但库里 `status` 还是备份那天的「正常」，界面上看不出任何异常，
     * 用户点同步只会得到一句与「没登录」无关的报错。这里统一标成「需重新登录」。
     */
    fun consumeRestoreFlags() {
        scope.launch {
            if (!RestoreHelper.consumeCredentialsReset(context)) return@launch
            runCatching { repository.markAllAccountsStatus(AccountStatus.EXPIRED.code) }
                .onFailure { android.util.Log.w("FitSync", "恢复后重置账号状态失败", it) }
            _state.value = UiState(
                syncing = false,
                message = "已从备份恢复。登录凭据不会随备份迁移，请重新登录各账号",
            )
        }
    }

    /**
     * @param targetAccountId 选择账号时，数据写进该账号并在导入后自动写回 Garmin；
     *   传 null 则落进独立的「本地导入」账号，只留在本机。
     */
    fun importData(bytes: ByteArray, fileName: String, targetAccountId: Long? = null) {
        if (isLongTaskRunning()) {
            _state.value = UiState(syncing = false, message = "已经有一个长任务在跑，等它结束再试")
            return
        }
        launchLongTask {
            // 导入本身（解压 + 解析几千天）就可能跑几分钟，写回还要更久，
            // 整段都放在前台服务里，锁屏不会把网络掐掉
            startLongTaskService("导入数据", "正在解析文件…")
            _state.value = UiState(syncing = true, message = "正在导入…")
            try {
                val result = localImporter.import(bytes, fileName, targetAccountId)

                val importMessage = when {
                    result.isEmpty && result.fitFiles == 0 ->
                        "没有从文件里识别出数据（支持 Garmin 导出 zip / JSON / CSV）"
                    else -> buildString {
                        append("导入完成：")
                        append("日汇总 ${result.daily}、睡眠 ${result.sleep}、活动 ${result.activities}")
                        // 合并式写入：新增是整行、补全是只填了空缺字段，两者要分开说，
                        // 否则用户会以为「1204 天」是又灌了一遍
                        if (result.inserted > 0 || result.updated > 0) {
                            append("（新增 ${result.inserted}、补全 ${result.updated}）")
                        }
                        if (result.skipped > 0) {
                            append("，另有 ${result.skipped} 项本地已是最新，未改动")
                        }
                        if (result.fitFiles > 0) {
                            append("；包内 ${result.fitFiles} 个 FIT")
                        }
                        if (result.errors.isNotEmpty()) {
                            append("；${result.errors.size} 个文件失败：${result.errors.first()}")
                        }
                    }
                }

                // 没选账号、或者没解析出日期：到此为止，不碰网络
                if (targetAccountId == null || result.dates.isEmpty()) {
                    _state.value = UiState(syncing = false, message = importMessage)
                    finishLongTaskService("导入完成", importMessage)
                    return@launchLongTask
                }

                // 写回目标账号：一天 1 个请求，比搬运省钱（搬运要额外下载原始包）
                _state.value = UiState(
                    syncing = true,
                    message = "$importMessage\n正在写回目标账号（共 ${result.dates.size} 天）…",
                )
                updateLongTaskService(
                    title = "写回目标账号",
                    text = "共 ${result.dates.size} 天，正在准备…",
                    done = 0,
                    total = result.dates.size,
                )
                val upload = withCpuAwake {
                    importUploadService.uploadDates(targetAccountId, result.dates) { done, total ->
                        _state.value = UiState(
                            syncing = true,
                            message = "正在写回目标账号：$done/$total 天",
                        )
                        updateLongTaskService(
                            title = "写回目标账号",
                            text = "正在写回：$done/$total 天",
                            done = done,
                            total = total,
                        )
                    }
                }

                val text = buildString {
                    append(importMessage).append("\n").append(upload.summary)
                    if (upload.deviceLabel.isNotBlank()) {
                        append("\n设备：").append(upload.deviceLabel)
                    }
                    if (upload.uploaded > 0) {
                        append("\n同步一次，首页就能看到这些天")
                    }
                }
                _state.value = UiState(syncing = false, message = text)
                finishLongTaskService("导入完成", text)
            } catch (e: CancellationException) {
                finishLongTaskService("导入已停止", "已写进本地的数据都保留，写回可以再点一次")
                throw e
            } catch (e: Exception) {
                val text = "导入失败：${e.message ?: e.javaClass.simpleName}"
                _state.value = UiState(syncing = false, message = text)
                finishLongTaskService("导入失败", text)
            }
        }
    }

    fun importFailed(reason: String) {
        _state.value = UiState(syncing = false, message = "导入失败：$reason")
    }

    fun copyActivitiesToOtherAccount() {
        if (isLongTaskRunning()) {
            _state.value = UiState(syncing = false, message = "已经有一个长任务在跑，等它结束再试")
            return
        }
        launchLongTask {
            if (rateLimitGate.isBlocked()) {
                _state.value = UiState(
                    message = "Garmin 限流冷却中：还有 ${rateLimitGate.waitLabel()}，稍后再复制",
                )
                return@launchLongTask
            }
            val pair = pickTwoAccounts("复制活动") ?: return@launchLongTask
            val (from, to) = pair
            val label = "${labelOf(from)} → ${labelOf(to)}"
            // 复制 10 条活动是「下载 FIT + 上传 FIT」×10，锁屏后照样会被 Doze 掐网；
            // 而且它和搬运一样在打 Garmin，必须走同一个长任务通道，不能另开一路
            startLongTaskService("复制活动", "准备中：$label")
            _state.value = UiState(syncing = true, message = "正在复制活动：$label…")
            try {
                val result = withCpuAwake { activityCopyService.copyRecent(from.id, to.id) }
                val text = "$label：${result.summary}" +
                    if (result.messages.isNotEmpty()) "（${result.messages.first()}）" else ""
                _state.value = UiState(syncing = false, message = text)
                finishLongTaskService("复制活动完成", text)
            } catch (e: CancellationException) {
                // 通知栏「停止」：已经传上去的活动留在对方账号里，没有删除接口
                finishLongTaskService("复制活动已停止", "已复制的活动不会回滚")
                throw e
            } catch (e: Exception) {
                val text = "复制失败：${e.message ?: e.javaClass.simpleName}"
                _state.value = UiState(syncing = false, message = text)
                finishLongTaskService("复制活动失败", text)
            }
        }
    }

    fun copyHealthToOtherAccount(days: Int = 7, forgetRecentDays: Int = 0) {
        if (isLongTaskRunning()) {
            _state.value = UiState(syncing = false, message = "已经有一个长任务在跑，等它结束再试")
            return
        }
        launchLongTask(copyingHealth = true) {
            if (rateLimitGate.isBlocked()) {
                _state.value = UiState(
                    message = "Garmin 限流冷却中：还有 ${rateLimitGate.waitLabel()}，稍后再搬运",
                )
                return@launchLongTask
            }
            val pair = pickTwoAccounts("搬运") ?: return@launchLongTask
            val (from, to) = pair
            // 「补齐最近几天」：先作废这几天的记账，否则 needsCopy 会判成「已经搬过」，
            // 连下载都省了，等于什么都没补
            if (forgetRecentDays > 0) {
                copyLedger.forgetRecent(from.id, to.id, forgetRecentDays)
            }
            val label = "${labelOf(from)} → ${labelOf(to)}"
            // 一年期搬运要 55 分钟：进前台服务，锁屏后 Doze 不会把网络掐掉
            startLongTaskService("搬运健康数据", "正在准备：$label")
            // 先立个「在跑」的牌子。进程要是被系统清掉，这个牌子没人摘，
            // 下次启动就能据此提示「上次没搬完」——协程的 finally 在这种情况下根本不会执行
            copyLedger.markRunning(from.id, to.id, days)
            try {
                _state.value = UiState(
                    syncing = true,
                    message = "正在搬运最近 $days 天的健康数据：$label…",
                )
                val result = withCpuAwake {
                    healthSyncService.copyDailyHealth(from.id, to.id, days = days) { done, total ->
                        // 长跨度搬运要几分钟到几十分钟，没有进度用户会以为卡死
                        _state.value = UiState(
                            syncing = true,
                            message = "正在搬运：$done/$total 天（$label）",
                        )
                        updateLongTaskService(
                            title = "搬运健康数据",
                            text = "正在搬运：$done/$total 天 · $label",
                            done = done,
                            total = total,
                        )
                    }
                }
                // 只有「没出错、也没有剩余天数」才算真的搬完；中断和部分失败都留着标记，
                // 下次启动才能提醒用户上次停在哪
                if (result.error == null && !result.interrupted) {
                    copyLedger.clearRunning(from.id, to.id)
                }
                val text = buildString {
                    append(labelOf(from)).append(" → ").append(labelOf(to)).append("：")
                    append(result.summary)
                    if (result.deviceLabel.isNotBlank()) {
                        append("\n设备：").append(result.deviceLabel)
                    }
                    // 服务端已收下，但目标账号的本地库要等一次同步才有这几天
                    if (result.uploaded > 0) {
                        append("\n在目标账号下同步一次，首页就能看到这几天")
                        // 上传没有删除接口，必须在用户还没后悔时就说明出口
                        append("\n想撤销只能到 Garmin Connect 网页端逐条删除这些记录")
                    }
                    // 部分成功的那几天没有写搬运记录，再点一次会自动重试；
                    // 已成功的文件服务端返回 409 直接跳过，不会再占配额
                    if (result.partiallyDone > 0) {
                        append("\n有 ").append(result.partiallyDone)
                            .append(" 天只传上去一部分（通常是睡眠被拒），再点一次搬运会自动补上")
                    }
                }
                _state.value = UiState(syncing = false, message = text)
                finishLongTaskService(
                    title = when {
                        result.error != null -> "搬运失败"
                        result.interrupted -> "搬运中断"
                        else -> "搬运完成"
                    },
                    text = text.lineSequence().first(),
                )
            } catch (e: CancellationException) {
                finishLongTaskService("搬运已停止", "已搬的天数都保留，再点一次会接着搬")
                throw e
            } catch (e: Exception) {
                val text = "搬运失败：${e.message ?: e.javaClass.simpleName}"
                _state.value = UiState(syncing = false, message = text)
                finishLongTaskService("搬运失败", text)
            }
        }
    }

    /** 消息里优先用用户自定义别名，其次才是 Garmin 昵称 */
    private fun labelOf(account: com.fitsync.data.db.AccountEntity): String =
        aliasStore.displayName(account.id, accountTitle(account.displayName, account.region))

    /**
     * 起前台服务。
     *
     * 失败只算降级：Android 12+ 在后台启动前台服务会抛
     * `ForegroundServiceStartNotAllowedException`，部分 ROM 也会拦。
     * 这时候任务仍靠 [withCpuAwake] 的 WakeLock 跑，只是锁屏久了可能被 Doze 断网——
     * 比让整件事直接崩掉好。
     */
    private fun startLongTaskService(title: String, text: String) {
        runCatching { LongTaskService.start(context, title, text) }
    }

    private fun updateLongTaskService(title: String, text: String, done: Int?, total: Int?) {
        // 通知是跨进程调用，365 天逐天刷新既费电，也会被系统限流，进度反而看着一顿一顿的。
        // 每 5% 或每 3 秒推一次就够顺滑了。
        if (done != null && total != null) {
            val step = maxOf(1, total / 20)
            val now = android.os.SystemClock.elapsedRealtime()
            if (done - lastProgressNotifyDone < step && now - lastProgressNotifyMs < 3_000L) return
            lastProgressNotifyMs = now
            lastProgressNotifyDone = done
        }
        runCatching { LongTaskService.update(context, title, text, done, total) }
    }

    private fun finishLongTaskService(title: String, text: String) {
        runCatching { LongTaskService.finish(context, title, text) }
    }

    /**
     * 长任务期间持有 CPU 唤醒锁。
     *
     * 一年期搬运要五十分钟上下、批量写回也要十几分钟，用户必然会把屏幕锁上。
     * 前台服务负责「进程不被回收、网络不被 Doze 切断」，但**不保证 CPU 不休眠**，
     * 所以两者要一起用：没有 CPU，`delay` 到点也不会恢复执行，进度就停在某一格。
     */
    private suspend fun <T> withCpuAwake(block: suspend () -> T): T {
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val lock = power?.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "FitSync:longTask",
        )?.apply { runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) } }
        // 锁屏后 Wi-Fi 会切到省电模式（PS-Poll），往返延迟从几十毫秒涨到几百毫秒；
        // 搬运一天 3 个请求，累起来就是「后台明显比前台慢」。
        // 高性能锁只在长任务期间持有，屏幕亮着时系统本来就不会省电，影响可以忽略。
        val wifiLock = runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifi?.createWifiLock(mode, "FitSync:longTask")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        return try {
            block()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
            runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
        }
    }

    /** 取「主账号 + 另一个 Garmin 账号」；不足两个时给出提示并返回 null */
    private suspend fun pickTwoAccounts(
        action: String,
    ): Pair<com.fitsync.data.db.AccountEntity, com.fitsync.data.db.AccountEntity>? {
        val accounts = repository.observeEnabledAccounts().first()
            .filter { it.sourceType == SourceType.GARMIN.code }
        if (accounts.size < 2) {
            _state.value = UiState(
                syncing = false,
                message = "需要两个 Garmin 账号（如中国区 + 国际区）才能$action",
            )
            return null
        }
        val primaryId = primaryAccountStore.primaryAccountId.value
        val from = accounts.firstOrNull { it.id == primaryId } ?: accounts.first()
        val to = accounts.first { it.id != from.id }
        return from to to
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** 当前是否在 Wi-Fi / 有线网络 */
    private fun isOnWifi(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(true)

    private companion object {
        /**
         * 单个导入包上限。
         *
         * 导入器本身按 32 MB 限单个 FIT；这里限的是「用户选中的那个文件」，
         * 可能是包含千余个 FIT 的导出 zip，给到 64 MB 足够，再大多半是选错了。
         */
        const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

        /**
         * 打开 App 时最多多久自动同步一次。
         *
         * 原来是 5 分钟——相当于只要用户一天开十几次 App，就已经在不停地
         * 打 Garmin 的接口。python-garminconnect 维护者给出的底线是
         * **轮询间隔不低于 15 分钟**，这里取 30 分钟更稳：一天最多几十个请求，
         * 和参考项目（DailySync 每 3 小时跑一次）在同一量级。
         */
        const val AUTO_SYNC_INTERVAL_MS = 30 * 60_000L

        /**
         * 唤醒锁超时。
         *
         * 一年期约 55 分钟，两年期要翻倍；再算上撞 429 后退回单天并行、分片之间的
         * 强制休息，最坏能拖到两个小时。留到 180 分钟，超时自动释放，
         * 避免异常路径把 CPU 一直按着不放导致耗电。
         */
        const val WAKE_LOCK_TIMEOUT_MS = 180 * 60_000L
    }
}
