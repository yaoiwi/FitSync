package com.fitsync.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.fitsync.data.backup.DatabaseBackup
import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.ActivityEntity
import com.fitsync.data.db.DailySummaryEntity
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.SleepSessionEntity
import com.fitsync.data.db.SleepStageEntity
import com.fitsync.data.db.SyncLogEntity
import com.fitsync.data.exporter.DataExporter
import com.fitsync.data.settings.AccountAliasStore
import com.fitsync.data.settings.CopyPacer
import com.fitsync.data.settings.HealthCopyLedger
import com.fitsync.data.settings.PrimaryAccountStore
import com.fitsync.sync.SyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    private val exporter: DataExporter,
    private val databaseBackup: DatabaseBackup,
    private val copyLedger: HealthCopyLedger,
    private val copyPacer: CopyPacer,
) : ViewModel() {

    /**
     * 账号表的唯一订阅。
     *
     * 之前 [accounts]、[accountsLoaded]、[effectivePrimaryId] 各自 `observeEnabledAccounts()`，
     * 同一条 SQL 每次变更要跑三遍、三条 Flow 各自唤醒。这里收成一条共享流：
     * 用 `null` 当「还没读出来」的哨兵，下游再派生成列表与「已加载」标志。
     */
    private val accountRows: StateFlow<List<AccountEntity>?> = repository.observeEnabledAccounts()
        .map<List<AccountEntity>, List<AccountEntity>?> { it }
        // 查询出错就退回空列表：宁可显示「还没有账号」，也不能让 accountsLoaded 永远是 false
        // —— 那会让首帧一直卡在加载态，连「添加账号」都进不去
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 用 StateFlow 而不是裸 Flow：
     * - UI 侧直接 `collectAsState()`，避开 lifecycle-runtime-compose 2.8.x 与 Compose 1.6.x
     *   的 LocalLifecycleOwner 不兼容问题；
     * - 屏幕离开 5 秒后停止上游查询。
     */
    val accounts: StateFlow<List<AccountEntity>> = accountRows
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 账号列表是否已经读出第一帧。
     *
     * 冷启动时 [accounts] 的初始值是空列表，界面会先渲染"还没有账号"，等 Room 查完再切回主页，
     * 看起来就像"打开先进了添加账号页"。用这个标志把首帧挡掉，等结果回来再决定显示什么。
     */
    val accountsLoaded: StateFlow<Boolean> = accountRows
        .map { it != null }
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
        // 复用同一条账号流，不再单独订阅一次 observeEnabledAccounts()
        accountRows,
    ) { primary, accounts -> primary ?: accounts?.firstOrNull()?.id }

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
                // 取 500 条：概览页只展示前 5 条，活动页要能翻更久的历史
                repository.observeMergedRecentActivities(primaryId, limit = 500),
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

    /** 只有「搬运健康数据」在跑：这期间用细线、列表保持可滑，其余同步用刷新头停住 */
    val copyHealthRunning: StateFlow<Boolean> = syncCoordinator.copyHealthRunning

    /** 设置页「同步记录」：跨账号按时间倒序 */
    val syncLogs: StateFlow<List<SyncLogEntity>> =
        repository.observeAllRecentSyncLogs(limit = 30)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 最近一晚的睡眠分期（没有分期数据时是空列表） */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sleepStages: StateFlow<List<SleepStageEntity>> =
        repository.observeLatestSleep()
            .flatMapLatest { session ->
                if (session == null) flowOf(emptyList())
                else repository.observeSleepStages(session.id)
            }
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ==================== 趋势 ====================

    /** 一天一行；缺数据的字段是 0，绘制时自行过滤 */
    data class TrendDay(
        val date: String,
        val steps: Int,
        val restingHr: Int,
        val sleepMinutes: Int,
        val distanceKm: Double,
        val activities: Int = 0,
    )

    /** 一周的汇总：睡眠取日均，其余取合计 */
    data class WeekStat(
        val steps: Int = 0,
        val sleepMinutes: Int = 0,
        val distanceKm: Double = 0.0,
        val activities: Int = 0,
    )

    data class WeekCompare(
        val current: WeekStat = WeekStat(),
        val previous: WeekStat = WeekStat(),
    )

    data class Trends(val days: List<TrendDay> = emptyList()) {
        val hasData: Boolean get() = days.any {
            it.steps > 0 || it.restingHr > 0 || it.sleepMinutes > 0 || it.distanceKm > 0
        }

        /** 最近 7 天 vs 前 7 天 */
        val week: WeekCompare
            get() = WeekCompare(
                statOf(days.takeLast(7)),
                statOf(days.dropLast(7).takeLast(7)),
            )

        private fun statOf(list: List<TrendDay>): WeekStat {
            val sleepDays = list.map { it.sleepMinutes }.filter { it > 0 }
            return WeekStat(
                steps = list.sumOf { it.steps },
                sleepMinutes = if (sleepDays.isEmpty()) 0 else sleepDays.average().toInt(),
                distanceKm = list.sumOf { it.distanceKm },
                activities = list.sumOf { it.activities },
            )
        }
    }

    /**
     * 近 30 天趋势（UI 再按 7/30 截取）。
     *
     * 只取主账号：两个区的日汇总按日期可能各有一份，混在一起会把同一天算两次。
     *
     * 查询带时间范围：以前三个 `observe*` 都是整表读出来再在内存里筛 30 天，
     * 攒到一两年数据后每次进首页都要搬几千行。现在交给 SQLite 走索引，
     * 多留 2 天余量，跨时区的深夜活动/睡眠不会因为边界被切掉。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val trends: StateFlow<Trends> = effectivePrimaryId
        .flatMapLatest { primaryId ->
            if (primaryId == null) {
                flowOf(Trends())
            } else {
                val sinceDate = LocalDate.now().minusDays(TREND_LOOKBACK_DAYS.toLong())
                val sinceMs = sinceDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                combine(
                    repository.observeDailySummariesSince(primaryId, sinceDate.toString()),
                    repository.observeSleepSessionsSince(primaryId, sinceMs),
                    repository.observeActivitiesSince(primaryId, sinceMs),
                ) { daily, sleep, activities ->
                    buildTrends(daily, sleep, activities)
                }
            }
        }
        .catch { emit(Trends()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Trends())

    private fun buildTrends(
        daily: List<DailySummaryEntity>,
        sleep: List<SleepSessionEntity>,
        activities: List<ActivityEntity>,
    ): Trends {
        val today = LocalDate.now()
        val dates = (29 downTo 0).map { today.minusDays(it.toLong()) }
        val dailyByDate = daily.associateBy { it.date }
        // 同一晚可能有多条（午睡/分段），取最长的那条作为当晚睡眠
        val sleepByDate = sleep.groupBy { dayOf(it.startTimeMs) }
            .mapValues { (_, list) -> list.maxOf { it.totalSleepMin } }
        val distanceByDate = activities
            .filter { it.distanceM > 0 }
            .groupBy { dayOf(it.startTimeMs) }
            .mapValues { (_, list) -> list.sumOf { it.distanceM } / 1000.0 }
        val countByDate = activities.groupBy { dayOf(it.startTimeMs) }
            .mapValues { (_, list) -> list.size }

        return Trends(
            days = dates.map { date ->
                val key = date.toString()
                TrendDay(
                    date = key,
                    steps = dailyByDate[key]?.steps ?: 0,
                    restingHr = dailyByDate[key]?.restingHr ?: 0,
                    sleepMinutes = sleepByDate[key] ?: 0,
                    distanceKm = distanceByDate[key] ?: 0.0,
                    activities = countByDate[key] ?: 0,
                )
            }
        )
    }

    private fun dayOf(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()


    fun syncNow() = syncCoordinator.syncNow()

    /** 本地限流冷却的剩余时间 / 连击次数，设置页用来显示「还有多久」 */
    fun rateLimitRemainingMs() = syncCoordinator.rateLimitRemainingMs()
    fun rateLimitStrikes() = syncCoordinator.rateLimitStrikes()

    /** 换过网络或已过锁定时间后，手动解除本地冷却并重试一次 */
    fun retryAfterRateLimit() = syncCoordinator.retryAfterRateLimit()

    /** 「打开即同步」开关 */
    val autoSyncOnOpen: StateFlow<Boolean> = syncCoordinator.autoSyncOnOpenEnabled
    fun setAutoSyncOnOpen(enabled: Boolean) = syncCoordinator.setAutoSyncOnOpen(enabled)

    /** 后台同步间隔（小时），0 = 仅手动 */
    val syncIntervalHours: StateFlow<Long> = syncCoordinator.syncIntervalHours
    fun setSyncInterval(hours: Long) = syncCoordinator.setSyncInterval(hours)

    /** 仅 Wi-Fi 自动同步 */
    val wifiOnly: StateFlow<Boolean> = syncCoordinator.wifiOnly
    fun setWifiOnly(enabled: Boolean) = syncCoordinator.setWifiOnly(enabled)

    /** 回填默认天数 */
    val backfillDays: StateFlow<Int> = syncCoordinator.backfillDays
    fun setBackfillDays(days: Int) = syncCoordinator.setBackfillDays(days)

    /** 回填过去一年的日汇总/睡眠 + 全量活动 */
    fun backfillHistory() = syncCoordinator.backfillHistory()

    fun importData(bytes: ByteArray, fileName: String, targetAccountId: Long? = null) =
        syncCoordinator.importData(bytes, fileName, targetAccountId)

    /** 直接从 SAF 拿到的 uri 导入：读文件在协调器的 IO 线程里做，不占主线程 */
    fun importFromUri(uri: Uri) = syncCoordinator.importFromUri(uri)

    fun importFailed(reason: String) = syncCoordinator.importFailed(reason)

    fun copyActivitiesToOtherAccount() = syncCoordinator.copyActivitiesToOtherAccount()

    val copyHealthDays: StateFlow<Int> = syncCoordinator.copyHealthDays
    fun setCopyHealthDays(days: Int) = syncCoordinator.setCopyHealthDays(days)

    /** 搬运风险说明是否已勾选「不再提示」 */
    val copyRiskAcknowledged: StateFlow<Boolean> = syncCoordinator.copyRiskAcknowledged
    fun acknowledgeCopyRisk() = syncCoordinator.acknowledgeCopyRisk()

    fun copyHealthToOtherAccount() =
        syncCoordinator.copyHealthToOtherAccount(syncCoordinator.copyHealthDays.value)

    /**
     * 补齐最近 3 天：先把这 3 天的记账作废，再重新下载核对一遍。
     *
     * 给「手表拖了几天才补传睡眠」兜底——正常流程里这段日期在 14 天窗口内本来就会复查，
     * 但用户可能刚搬完就想再确认一次，这时 needsCopy 会判成「已经搬过」，
     * 必须先把记录删掉才会真的重新下载。
     */
    fun recheckRecentCopy() =
        syncCoordinator.copyHealthToOtherAccount(days = 3, forgetRecentDays = 3)

    /**
     * 上一次搬运停在哪（最老的一天）。
     *
     * 搬运页用它告诉用户「这次会从哪天接着往前搬」，而不是笼统地说「再点一次会接着做」——
     * 一年期搬运要中断好几次，用户根本不知道断点在哪。
     */
    fun copyProgressOf(fromAccountId: Long?, toAccountId: Long?): HealthCopyLedger.Progress? {
        if (fromAccountId == null || toAccountId == null) return null
        return copyLedger.progress(fromAccountId, toAccountId)
    }

    /**
     * 上一次没跑完的搬运。
     *
     * 进程被清掉时协程的 finally 不会执行，「在跑」的标记就一直留在盘上——
     * 这正好是我们要的断点凭据：有标记 = 上次没搬完。
     */
    fun interruptedCopy(): HealthCopyLedger.RunMark? = copyLedger.interruptedRun()

    /** 用户表示「不继续了」，把标记清掉，别再提示 */
    fun dismissInterruptedCopy() = copyLedger.dismissAllRunMarks()

    /** 当前并发档位的一句话说明（自适应提速的解释） */
    fun copyPaceLabel(): String = copyPacer.label()

    fun setPrimaryAccount(accountId: Long) = primaryAccountStore.setPrimary(accountId)

    /**
     * 账号页拖动排序：整表按 [ids] 的新顺序重写 `sort_index`。
     *
     * 不交换两行的值，而是整表写成 1..n：从没排过时所有值都是 0，
     * 交换两个 0 等于什么都没做，只有整表重写才能得到确定顺序。
     * 拖动过程中 UI 只改本地顺序，松手才调这里落库一次，避免拖动卡顿。
     */
    fun reorderAccounts(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch { repository.reorderAccounts(ids) }
    }

    /** 传空字符串表示恢复 Garmin 原昵称 */
    fun renameAccount(accountId: Long, nickname: String) =
        aliasStore.setAlias(accountId, nickname)

    fun clearMessage() = syncCoordinator.clearMessage()

    // ==================== 导出 ====================

    enum class ExportFormat(val mime: String, val ext: String) {
        JSON("application/json", "json"),
        CSV("application/zip", "zip"),
    }

    data class ExportFile(
        val name: String,
        val bytes: ByteArray,
        val summary: DataExporter.Summary,
    )

    /** 备份整个数据库到用户选择的位置，返回字节数 */
    suspend fun backupDatabase(uri: Uri): Long = databaseBackup.backupTo(uri)

    /** 暂存备份文件，下次启动时替换（运行中不能直接换库） */
    suspend fun restoreDatabase(uri: Uri) = databaseBackup.stageRestore(uri)

    /** 生成导出文件（不落盘，写由页面通过 SAF 完成） */
    suspend     fun buildExport(format: ExportFormat): ExportFile {
        val stamp = TimeFmt.fileStamp()
        return when (format) {
            ExportFormat.JSON -> {
                val (text, summary) = exporter.exportJson()
                ExportFile("fitsync-$stamp.json", text.toByteArray(Charsets.UTF_8), summary)
            }
            ExportFormat.CSV -> {
                val (bytes, summary) = exporter.exportCsvZip()
                ExportFile("fitsync-$stamp.zip", bytes, summary)
            }
        }
    }

    fun deleteAccount(accountId: Long) {
        viewModelScope.launch {
            val wasPrimary = primaryAccountId.value == accountId
            // 级联清理该账号的活动、采样、日汇总、同步游标等，避免留下孤儿行
            repository.purgeAccount(accountId)
            aliasStore.setAlias(accountId, "")
            if (wasPrimary) primaryAccountStore.clear()
        }
    }

    private companion object {
        /**
         * 趋势查询往前读多少天。
         *
         * 图上画 30 天，多读 2 天是为了兜住边界：按 `start_time_ms` 筛的活动/睡眠
         * 若刚好落在窗口第一天的凌晨（时区差），少读一天就会缺柱子。
         */
        const val TREND_LOOKBACK_DAYS = 32
    }
}
