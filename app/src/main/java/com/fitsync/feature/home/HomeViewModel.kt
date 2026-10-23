package com.fitsync.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.ActivityEntity
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.settings.AccountAliasStore
import com.fitsync.data.settings.PrimaryAccountStore
import com.fitsync.feature.update.UpdateChecker
import com.fitsync.sync.SyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页 ViewModel：只负责「展示」与「把用户动作转交给 [SyncCoordinator]」。
 *
 * 同步逻辑放在协调器里，是因为**打开 App 就要同步**——那一刻首页可能还没被创建。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: FitDataRepository,
    private val primaryAccountStore: PrimaryAccountStore,
    private val syncCoordinator: SyncCoordinator,
    private val aliasStore: AccountAliasStore,
    // 带默认值：JVM 冒烟测试直接 new 这个 ViewModel 时不必构造它
    private val updateChecker: UpdateChecker = UpdateChecker(),
) : ViewModel() {

    /**
     * 用 StateFlow 而不是裸 Flow：
     * - UI 侧直接 `collectAsState()`，避开 lifecycle-runtime-compose 2.8.x 与 Compose 1.6.x
     *   的 LocalLifecycleOwner 不兼容问题；
     * - 屏幕离开 5 秒后停止上游查询。
     */
    val accounts: StateFlow<List<AccountEntity>> = repository.observeEnabledAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 账号列表是否已经读出第一帧。
     *
     * 冷启动时 [accounts] 的初始值是空列表，界面会先渲染"还没有账号"，等 Room 查完再切回主页，
     * 看起来就像"打开先进了添加账号页"。用这个标志把首帧挡掉，等结果回来再决定显示什么。
     */
    val accountsLoaded: StateFlow<Boolean> = repository.observeEnabledAccounts()
        .map { true }
        .catch { emit(true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    data class Dashboard(
        val steps: Int = 0,
        val distanceKm: Double = 0.0,
        val activeCalories: Double = 0.0,
        val floors: Int = 0,
        val restingHr: Int = 0,
        val sleepMinutes: Int = 0,
        val sleepScore: Int = 0,
        val sleepAvgHr: Int = 0,
        val activities: List<ActivityEntity> = emptyList(),
        val activityCount: Int = 0,
        val dailyCount: Int = 0,
    )

    /** 主账号：首页合并数据的基准 */
    val primaryAccountId: StateFlow<Long?> = primaryAccountStore.primaryAccountId

    /** 用户自定义昵称：只影响显示，不改 Garmin 的真实 displayName */
    val aliases: StateFlow<Map<Long, String>> = aliasStore.aliases

    /**
     * 实际用于取数的账号：优先主账号，没设主账号就退回列表里的第一个。
     *
     * 活动这类"按账号独有"的数据不能跨账号合并——两个区各自有一份活动 ID，
     * 同一场运动会以两条记录出现。健康汇总仍按主账号优先、缺失字段由另一区补齐。
     */
    private val effectivePrimaryId: Flow<Long?> = combine(
        primaryAccountStore.primaryAccountId,
        repository.observeEnabledAccounts(),
    ) { primary, accounts -> primary ?: accounts.firstOrNull()?.id }

    /**
     * 首页数据：健康汇总取主账号优先、缺失由另一区补齐；活动与计数只按 [effectivePrimaryId]。
     * 切换主账号只需要重新订阅，不需要重新同步。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dashboard: StateFlow<Dashboard> = effectivePrimaryId
        .flatMapLatest { primaryId ->
            combine(
                repository.observeMergedLatestDaily(primaryId),
                repository.observeMergedLatestSleep(primaryId),
                // 取 50 条：概览页只展示前 5 条，活动页展示全部
                repository.observeMergedRecentActivities(primaryId, limit = 50),
                repository.observeActivityCount(primaryId),
                repository.observeDailyCount(),
            ) { summary, sleep, activities, count, dailyCount ->
                Dashboard(
                    steps = summary?.steps ?: 0,
                    distanceKm = (summary?.distanceM ?: 0.0) / 1000.0,
                    activeCalories = summary?.caloriesActive ?: 0.0,
                    floors = summary?.floors ?: 0,
                    restingHr = summary?.restingHr ?: 0,
                    sleepMinutes = sleep?.totalSleepMin ?: 0,
                    sleepScore = sleep?.sleepScore ?: 0,
                    sleepAvgHr = sleep?.avgHr ?: 0,
                    activities = activities,
                    activityCount = count,
                    dailyCount = dailyCount,
                )
            }
        }
        // 任何一条查询出问题都不该让整个流终止——否则首页会永远停在初始的全 0 状态
        .catch { emit(Dashboard()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Dashboard())

    /** 同步/导入/复制的状态统一来自协调器，打开 App 时的自动同步也能在这里看到 */
    val syncState: StateFlow<SyncCoordinator.UiState> = syncCoordinator.state

    /** 检查更新的状态，设置页「检查更新」一行直接读它 */
    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object UpToDate : UpdateState
        data class Available(val version: String, val url: String) : UpdateState
        data object NoRelease : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    fun checkUpdate(currentVersion: String) {
        if (_updateState.value is UpdateState.Checking) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            _updateState.value = when (val result = updateChecker.check(currentVersion)) {
                is UpdateChecker.Result.Available ->
                    UpdateState.Available(result.version, result.url)
                UpdateChecker.Result.UpToDate -> UpdateState.UpToDate
                UpdateChecker.Result.NoRelease -> UpdateState.NoRelease
                is UpdateChecker.Result.Failed -> UpdateState.Failed(result.message)
            }
        }
    }

    fun syncNow() = syncCoordinator.syncNow()

    fun importData(bytes: ByteArray, fileName: String) =
        syncCoordinator.importData(bytes, fileName)

    fun importFailed(reason: String) = syncCoordinator.importFailed(reason)

    fun copyActivitiesToOtherAccount() = syncCoordinator.copyActivitiesToOtherAccount()

    fun copyHealthToOtherAccount() = syncCoordinator.copyHealthToOtherAccount()

    fun setPrimaryAccount(accountId: Long) = primaryAccountStore.setPrimary(accountId)

    /** 传空字符串表示恢复 Garmin 原昵称 */
    fun renameAccount(accountId: Long, nickname: String) =
        aliasStore.setAlias(accountId, nickname)

    fun clearMessage() = syncCoordinator.clearMessage()

    fun deleteAccount(accountId: Long) {
        viewModelScope.launch {
            val wasPrimary = primaryAccountId.value == accountId
            // 级联清理该账号的活动、采样、日汇总、同步游标等，避免留下孤儿行
            repository.purgeAccount(accountId)
            aliasStore.setAlias(accountId, "")
            if (wasPrimary) primaryAccountStore.clear()
        }
    }
}
