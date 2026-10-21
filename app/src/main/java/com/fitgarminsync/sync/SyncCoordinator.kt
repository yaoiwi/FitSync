package com.fitgarminsync.sync

import android.content.Context
import com.fitgarminsync.data.db.FitDataRepository
import com.fitgarminsync.data.db.SourceType
import com.fitgarminsync.data.health.HealthSyncService
import com.fitgarminsync.data.importer.LocalDataImporter
import com.fitgarminsync.data.settings.PrimaryAccountStore
import com.fitgarminsync.data.sync.ActivityCopyService
import com.fitgarminsync.data.sync.GarminSyncEngine
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
) {

    data class UiState(
        val syncing: Boolean = false,
        val message: String? = null,
    )

    private val prefs = context.getSharedPreferences("fgs_sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * App 启动 / 回到前台时调用。
     * 静默执行：没有账号、失败都不打扰用户；成功后写入"最近同步"时间。
     */
    fun autoSyncOnOpen() {
        val last = prefs.getLong(KEY_LAST_AUTO_SYNC, 0L)
        if (System.currentTimeMillis() - last < AUTO_SYNC_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_AUTO_SYNC, System.currentTimeMillis()).apply()

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
        _state.value = UiState(syncing = true, message = if (manual) null else "正在自动同步…")

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
                else -> "已同步 $records 条记录"
            },
        )
    }

    fun importData(bytes: ByteArray, fileName: String) {
        scope.launch {
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
                message = "正在复制活动：${from.displayName} → ${to.displayName}…",
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
                message = "${from.displayName} → ${to.displayName}：${result.summary}" +
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
                message = "正在搬运健康数据：${from.displayName} → ${to.displayName}…",
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
                message = "${from.displayName} → ${to.displayName}：${result.summary}" +
                    if (result.messages.isNotEmpty()) "（${result.messages.first()}）" else "",
            )
        }
    }

    /** 取「主账号 + 另一个 Garmin 账号」；不足两个时给出提示并返回 null */
    private suspend fun pickTwoAccounts(
        action: String,
    ): Pair<com.fitgarminsync.data.db.AccountEntity, com.fitgarminsync.data.db.AccountEntity>? {
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

    private companion object {
        const val KEY_LAST_AUTO_SYNC = "last_auto_sync_ms"
        const val AUTO_SYNC_INTERVAL_MS = 5 * 60_000L
    }
}
