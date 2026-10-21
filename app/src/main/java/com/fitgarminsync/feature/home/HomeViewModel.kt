package com.fitgarminsync.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitgarminsync.data.db.AccountEntity
import com.fitgarminsync.data.db.ActivityEntity
import com.fitgarminsync.data.db.FitDataRepository
import com.fitgarminsync.data.settings.AccountAliasStore
import com.fitgarminsync.data.settings.PrimaryAccountStore
import com.fitgarminsync.sync.SyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
) : ViewModel() {

    /**
     * 用 StateFlow 而不是裸 Flow：
     * - UI 侧直接 `collectAsState()`，避开 lifecycle-runtime-compose 2.8.x 与 Compose 1.6.x
     *   的 LocalLifecycleOwner 不兼容问题；
     * - 屏幕离开 5 秒后停止上游查询。
     */
    val accounts: StateFlow<List<AccountEntity>> = repository.observeEnabledAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
     * 中国区 + 国际区的合并视图：主账号优先，缺失字段由另一区补齐。
     * 切换主账号只需要重新订阅，不需要重新同步。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dashboard: StateFlow<Dashboard> = primaryAccountStore.primaryAccountId
        .flatMapLatest { primaryId ->
            combine(
                repository.observeMergedLatestDaily(primaryId),
                repository.observeMergedLatestSleep(primaryId),
                repository.observeMergedRecentActivities(primaryId, limit = 5),
                repository.observeActivityCount(),
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
        viewModelScope.launch { repository.deleteAccount(accountId) }
    }
}
