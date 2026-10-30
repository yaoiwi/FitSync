package com.fitsync.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.SourceType
import com.fitsync.data.health.HealthSyncService
import com.fitsync.data.importer.LocalDataImporter
import com.fitsync.data.settings.AccountAliasStore
import com.fitsync.data.settings.PrimaryAccountStore
import com.fitsync.data.settings.SyncSettings
import com.fitsync.data.sync.ActivityCopyService
import com.fitsync.data.sync.GarminSyncEngine
import com.fitsync.ui.components.accountTitle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步协调器：把「同步 / 导入 / 跨账号复制」集中到一处。
 *
 * 为什么不在 ViewModel 里：**打开 App 就要同步**，而 ViewModel 只在首页可见时才存在；
 * 这里用 Application 级的协程作用域，Activity 启动时就能触发，UI 只订阅状态。
 *
 * 自动同步做了节流：同一进程内 5 分钟内不重复，避免切前后台就反复拉数据。
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
    private val aliasStore: AccountAliasStore,
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

    /** 回填默认天数 */
    private val _backfillDays = MutableStateFlow(SyncSettings.backfillDays(context))
    val backfillDays: StateFlow<Int> = _backfillDays.asStateFlow()

    fun setBackfillDays(days: Int) {
        _backfillDays.value = days
        SyncSettings.setBackfillDays(context, days)
    }

    /**
     * App 启动 / 回到前台时调用。
     * 静默执行：没有账号、失败都不打扰用户；成功后写入"最近同步"时间。
     */
    fun autoSyncOnOpen() {
        if (!_autoSyncOnOpen.value) return
        val last = SyncSettings.lastAutoSyncMs(context)
        if (System.currentTimeMillis() - last < AUTO_SYNC_INTERVAL_MS) return
        SyncSettings.markAutoSync(context, System.currentTimeMillis())

        scope.launch {
            if (repository.observeEnabledAccounts().first()
                    .none { it.sourceType == SourceType.GARMIN.code }
            ) {
                return@launch
            }
            runSync(manual = false)
        }
    }

    /** 用户点「同步」 */
    fun syncNow() {
        scope.launch { runSync(manual = true) }
    }

    private suspend fun runSync(manual: Boolean) {
        if (_state.value.syncing) return
        // 手动同步不受限制；自动同步在「仅 Wi-Fi」开启且当前是移动网络时直接跳过
        if (!manual && SyncSettings.wifiOnly(context) && !isOnWifi()) {
            _state.value = UiState(syncing = false, message = null)
            return
        }
        _state.value = UiState(
            syncing = true,
            message = if (manual) null else "正在自动同步…",
            silent = !manual,
        )

        val results = runCatching {
            syncEngine.syncAll(primaryFirstId = primaryAccountStore.primaryAccountId.value)
        }.getOrElse { error ->
            _state.value = UiState(
                syncing = false,
                message = if (manual) "同步失败：${error.message ?: error.javaClass.simpleName}"
                else null,
            )
            return
        }

        val records = results.sumOf { it.second.records }
        val failed = results.filter { !it.second.ok }
        _state.value = UiState(
            syncing = false,
            message = when {
                failed.isNotEmpty() ->
                    "同步失败（${failed.size} 个账号）：${failed.first().second.message}"
                results.isEmpty() -> if (manual) "还没有可同步的账号" else null
                else -> if (manual) "已同步 $records 条记录" else null
            },
        )
    }

    /**
     * 用户点「回填历史」。
     *
     * 这是慢任务（一年约 730 次请求），只在用户主动触发时跑；
     * 进度直接写进 [UiState.message]，首页/设置页都能看到。
     */
    fun backfillHistory(days: Int = SyncSettings.backfillDays(context)) {
        scope.launch {
            if (_state.value.syncing) return@launch
            val accounts = repository.observeEnabledAccounts().first()
                .filter { it.sourceType == SourceType.GARMIN.code }
            if (accounts.isEmpty()) {
                _state.value = UiState(syncing = false, message = "还没有可回填的 Garmin 账号")
                return@launch
            }

            _state.value = UiState(syncing = true, message = "正在回填历史…")
            var total = 0
            var failed = 0
            accounts.forEach { account ->
                val result = syncEngine.backfillHistory(account.id, days) { done, all, label ->
                    _state.value = UiState(
                        syncing = true,
                        message = "${labelOf(account)}：$label（$done/$all 天）",
                    )
                }
                if (result.ok) total += result.records else failed++
            }
            _state.value = UiState(
                syncing = false,
                message = if (failed > 0) {
                    "回填结束：$failed 个账号失败，成功写入 $total 条"
                } else {
                    "回填完成，共 $total 条记录"
                },
            )
        }
    }

    fun importData(bytes: ByteArray, fileName: String) {        scope.launch {
            _state.value = UiState(syncing = true, message = "正在导入…")
            val result = runCatching { localImporter.import(bytes, fileName) }
                .getOrElse { error ->
                    _state.value = UiState(
                        syncing = false,
                        message = "导入失败：${error.message ?: error.javaClass.simpleName}",
                    )
                    return@launch
                }
            _state.value = UiState(
                syncing = false,
                message = when {
                    result.isEmpty -> "没有从文件里识别出数据（支持 Garmin 导出 zip / JSON / CSV）"
                    else -> buildString {
                        append("导入完成：")
                        append("日汇总 ${result.daily}、睡眠 ${result.sleep}、活动 ${result.activities}")
                        if (result.errors.isNotEmpty()) {
                            append("；${result.errors.size} 个文件失败：${result.errors.first()}")
                        }
                    }
                },
            )
        }
    }

    fun importFailed(reason: String) {
        _state.value = UiState(syncing = false, message = "导入失败：$reason")
    }

    fun copyActivitiesToOtherAccount() {
        scope.launch {
            val pair = pickTwoAccounts("复制活动") ?: return@launch
            val (from, to) = pair
            _state.value = UiState(
                syncing = true,
                message = "正在复制活动：${labelOf(from)} → ${labelOf(to)}…",
            )
            val result = runCatching { activityCopyService.copyRecent(from.id, to.id) }
                .getOrElse { e ->
                    _state.value = UiState(
                        syncing = false,
                        message = "复制失败：${e.message ?: e.javaClass.simpleName}",
                    )
                    return@launch
                }
            _state.value = UiState(
                syncing = false,
                message = "${labelOf(from)} → ${labelOf(to)}：${result.summary}" +
                    if (result.messages.isNotEmpty()) "（${result.messages.first()}）" else "",
            )
        }
    }

    fun copyHealthToOtherAccount() {
        scope.launch {
            val pair = pickTwoAccounts("搬运") ?: return@launch
            val (from, to) = pair
            _state.value = UiState(
                syncing = true,
                message = "正在搬运健康数据：${labelOf(from)} → ${labelOf(to)}…",
            )
            val result = runCatching { healthSyncService.copyDailyHealth(from.id, to.id, days = 7) }
                .getOrElse { e ->
                    _state.value = UiState(
                        syncing = false,
                        message = "搬运失败：${e.message ?: e.javaClass.simpleName}",
                    )
                    return@launch
                }
            _state.value = UiState(
                syncing = false,
                message = buildString {
                    append(labelOf(from)).append(" → ").append(labelOf(to)).append("：")
                    append(result.summary)
                    if (result.deviceLabel.isNotBlank()) {
                        append("\n设备：").append(result.deviceLabel)
                    }
                    // 服务端已收下，但目标账号的本地库要等一次同步才有这几天
                    if (result.uploaded > 0) {
                        append("\n在目标账号下同步一次，首页就能看到这几天")
                    }
                },
            )
        }
    }

    /** 消息里优先用用户自定义别名，其次才是 Garmin 昵称 */
    private fun labelOf(account: com.fitsync.data.db.AccountEntity): String =
        aliasStore.displayName(account.id, accountTitle(account.displayName, account.region))

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
        const val AUTO_SYNC_INTERVAL_MS = 5 * 60_000L
    }
}
