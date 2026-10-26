package com.fitsync.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitsync.BuildConfig
import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.ActivityEntity
import com.fitsync.ui.components.GlassHost
import com.fitsync.ui.components.HapticFeedback
import com.fitsync.ui.components.Haptics
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.LiquidGlassNavigationBar
import com.fitsync.ui.components.LiquidGlassNavigationItem
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixInputDialog
import com.fitsync.ui.components.MiuixLargeHeader
import com.fitsync.ui.components.formatDuration
import com.fitsync.ui.components.rememberTick
import com.fitsync.ui.components.regionLabel
import com.fitsync.ui.components.sportGlyph
import com.fitsync.ui.components.sportLabel
import com.fitsync.ui.components.statusColor
import com.fitsync.ui.components.statusLabel
import com.fitsync.ui.components.MiuixLeadingIcon
import com.fitsync.ui.components.MiuixNavItem
import com.fitsync.ui.components.MiuixPrimaryButton
import com.fitsync.ui.components.MiuixRow
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.components.MiuixSwitch
import com.fitsync.ui.theme.FitSyncTheme
import com.fitsync.ui.theme.FloatingNavBarPref
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixAction
import com.fitsync.ui.theme.MiuixCanvas
import com.fitsync.ui.theme.MiuixCardSurface
import com.fitsync.ui.theme.MiuixError
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary
import com.fitsync.ui.components.MiuixSegmentedControl
import com.fitsync.ui.theme.StatusGreen
import com.fitsync.ui.theme.ThemeModePref
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页。
 *
 * 版式对齐 HyperIsland 3.2.1：
 * - 左对齐大标题 + 副标题，右侧放当前页唯一的主操作；
 * - 内容按「分组标题 + 零间隙卡片」排布，行高 64dp、左右 18dp；
 * - 底部悬浮胶囊导航栏，三页：概览 / 账号 / 设置。
 */
@Composable
fun HomeScreen(
    onAddAccount: () -> Unit,
    onOpenChangelog: () -> Unit = {},
    onOpenAcknowledgements: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
    onOpenActivity: (Long) -> Unit = {},
    onOpenImport: () -> Unit = {},
    onOpenCopyActivities: () -> Unit = {},
    onOpenCopyHealth: () -> Unit = {},
    onOpenSyncLogs: () -> Unit = {},
    onOpenExport: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 外观模式：设置页改了之后这里会重读，整页立刻切色板
    val themeMode by remember(context) { ThemeModePref.flow(context) }
        .collectAsState(initial = ThemeModePref.FOLLOW)
    val floatingNavBar by remember(context) { FloatingNavBarPref.flow(context) }
        .collectAsState(initial = FloatingNavBarPref.isEnabled(context))
    val accounts by viewModel.accounts.collectAsState()
    val accountsLoaded by viewModel.accountsLoaded.collectAsState()
    val dashboard by viewModel.dashboard.collectAsState()
    val trends by viewModel.trends.collectAsState()
    val sleepStages by viewModel.sleepStages.collectAsState()
    val autoSyncOnOpen by viewModel.autoSyncOnOpen.collectAsState()
    val syncIntervalHours by viewModel.syncIntervalHours.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()
    val backfillDays by viewModel.backfillDays.collectAsState()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment ?: "import"
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            viewModel.importFailed("无法读取所选文件（可能没有访问权限）")
        } else {
            viewModel.importData(bytes, name)
        }
    }
    val syncState by viewModel.syncState.collectAsState()
    val primaryAccountId by viewModel.primaryAccountId.collectAsState()
    val aliases by viewModel.aliases.collectAsState()
    var renameTarget by remember { mutableStateOf<AccountEntity?>(null) }
    var tab by remember { mutableStateOf(0) }
    // 今日大数字当前展示哪个指标：0 步数 / 1 距离 / 2 活动热量 / 3 楼层
    var todayMetric by remember { mutableStateOf(0) }
    // 同步从"进行中"落到"结束"时给一次触感：成功双击、失败重震
    var wasSyncing by remember { mutableStateOf(false) }
    LaunchedEffect(syncState.syncing) {
        if (wasSyncing && !syncState.syncing) {
            val failed = syncState.message?.contains("失败") == true
            Haptics.perform(
                context,
                if (failed) HapticFeedback.Warning else HapticFeedback.Success,
            )
        }
        wasSyncing = syncState.syncing
    }
    val pickFile: () -> Unit = {
        importLauncher.launch(
            arrayOf(
                "application/zip",
                "application/json",
                "text/csv",
                "text/comma-separated-values",
                "application/octet-stream",
            )
        )
    }

    FitSyncTheme {
        Box(modifier = Modifier.fillMaxSize().background(MiuixCanvas)) {
            renameTarget?.let { target ->
                val customAlias = aliases[target.id]
                MiuixInputDialog(
                    title = "修改昵称",
                    initialValue = customAlias ?: "",
                    message = "只影响本应用的显示名，不会改动 Garmin 账号。原昵称："
                        + target.displayName.ifBlank { target.externalUserId },
                    placeholder = target.displayName.ifBlank { target.externalUserId },
                    neutralText = if (!customAlias.isNullOrBlank()) "恢复默认" else null,
                    onNeutral = {
                        viewModel.renameAccount(target.id, "")
                        renameTarget = null
                    },
                    onConfirm = { value ->
                        viewModel.renameAccount(target.id, value.trim())
                        renameTarget = null
                    },
                    onDismiss = { renameTarget = null },
                )
            }

            if (!accountsLoaded) {
                // 等 Room 返回第一帧。直接画空状态会先闪一下"还没有账号"，
                // 看起来就像启动时先去了一趟添加账号页。
                Box(modifier = Modifier.fillMaxSize().background(MiuixCanvas))
            } else if (accounts.isEmpty()) {
                EmptyState(onAddAccount = onAddAccount, onImport = pickFile)
            } else {
                // 玻璃宿主：页面内容录成 backdrop，导航栏放进 overlay（不参与采样）
                GlassHost(
                    modifier = Modifier.fillMaxSize(),
                    // 覆盖悬浮导航栏及其底部安全区，避免下拉刷新时正文移动导致玻璃变色
                    bottomScrimHeight = 140.dp,
                    overlay = {
                        LiquidGlassNavigationBar(
                            selectedTabIndex = { tab },
                            onTabSelected = { tab = it },
                            items = listOf(
                                LiquidGlassNavigationItem(Icons.Filled.Home, "概览"),
                                LiquidGlassNavigationItem(Icons.Filled.List, "活动"),
                                LiquidGlassNavigationItem(Icons.Filled.Person, "账号"),
                                LiquidGlassNavigationItem(Icons.Filled.Settings, "设置"),
                            ),
                            modifier = Modifier.align(Alignment.BottomCenter),
                            floating = floatingNavBar,
                        )
                    },
                ) {
                    when (tab) {
                            0 -> OverviewTab(
                                dashboard = dashboard,
                                trends = trends,
                                sleepStages = sleepStages,
                                syncing = syncState.syncing,
                                silent = syncState.silent,
                                message = syncState.message,
                                onSync = viewModel::syncNow,
                                onSeeAccounts = { tab = 2 },
                                onOpenActivity = onOpenActivity,
                                todayMetricIndex = todayMetric,
                                onTodayMetricSelect = { todayMetric = it },
                            )
                            1 -> ActivityTab(
                                activities = dashboard.activities,
                                syncing = syncState.syncing,
                                onOpenActivity = onOpenActivity,
                            )
                            2 -> AccountsTab(
                                accounts = accounts,
                                primaryAccountId = primaryAccountId,
                                aliases = aliases,
                                syncing = syncState.syncing,
                                onSync = viewModel::syncNow,
                                onSetPrimary = viewModel::setPrimaryAccount,
                                onRename = { renameTarget = it },
                                onAddAccount = onAddAccount,
                                onImport = onOpenImport,
                                canCopyActivities = accounts.count { it.sourceType == "garmin" } >= 2,
                                onCopyActivities = onOpenCopyActivities,
                                canCopyHealth = accounts.count { it.sourceType == "garmin" } >= 2,
                                onCopyHealth = onOpenCopyHealth,
                                onExport = onOpenExport,
                                onOpenAccount = onOpenAccounts,
                            )
                            else -> SettingsTab(
                                accountCount = accounts.size,
                                message = syncState.message,
                                themeMode = themeMode,
                                onThemeModeChange = { ThemeModePref.set(context, it) },
                                floatingNavBar = floatingNavBar,
                                onFloatingNavBarChange = { FloatingNavBarPref.set(context, it) },
                                onOpenAccounts = onOpenAccounts,
                                onOpenChangelog = onOpenChangelog,
                                onOpenAcknowledgements = onOpenAcknowledgements,
                                onOpenSyncLogs = onOpenSyncLogs,
                                onBackfill = viewModel::backfillHistory,
                                autoSyncOnOpen = autoSyncOnOpen,
                                onAutoSyncChange = viewModel::setAutoSyncOnOpen,
                                syncIntervalHours = syncIntervalHours,
                                onSyncIntervalChange = viewModel::setSyncInterval,
                                wifiOnly = wifiOnly,
                                onWifiOnlyChange = viewModel::setWifiOnly,
                                backfillDays = backfillDays,
                                onBackfillDaysChange = viewModel::setBackfillDays,
                                onBackupDatabase = { uri ->
                                    scope.launch {
                                        val msg = runCatching { viewModel.backupDatabase(uri) }
                                            .fold(
                                                { "已备份 ${it / 1024} KB" },
                                                { "备份失败：${it.message ?: "未知错误"}" },
                                            )
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                },
                                onRestoreDatabase = { uri ->
                                    scope.launch {
                                        val msg = runCatching { viewModel.restoreDatabase(uri) }
                                            .fold(
                                                { "已准备恢复，重启 App 后生效" },
                                                { "恢复失败：${it.message ?: "未知错误"}" },
                                            )
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                },
                            )
                    }
                }
            }
        }
    }
}

// ============================ 活动 ============================

@Composable
private fun ActivityTab(
    activities: List<ActivityEntity>,
    syncing: Boolean,
    onOpenActivity: (Long) -> Unit = {},
) {
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var monthFilter by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val monthFormat = remember { SimpleDateFormat("yyyy-MM", Locale.CHINA) }
    val types = remember(activities) { activities.map { sportGroup(it.sport) }.distinct() }
    val months = remember(activities) {
        activities.map { monthFormat.format(Date(it.startTimeMs)) }
            .distinct().sortedDescending().take(6)
    }

    val filtered = remember(activities, typeFilter, monthFilter, query) {
        activities.filter { a ->
            val matchType = typeFilter == null || sportGroup(a.sport) == typeFilter
            val matchMonth = monthFilter == null ||
                monthFormat.format(Date(a.startTimeMs)) == monthFilter
            val matchQuery = query.isBlank() ||
                a.activityType.contains(query, ignoreCase = true) ||
                sportLabel(a.sport).contains(query, ignoreCase = true) ||
                sportLabel(a.activityType).contains(query, ignoreCase = true)
            matchType && matchMonth && matchQuery
        }
    }

    HyperPage(
        title = "活动",
        subtitle = when {
            syncing -> "正在同步…"
            activities.isEmpty() -> "还没有运动记录"
            filtered.size != activities.size -> "${filtered.size} / ${activities.size} 条"
            else -> "${activities.size} 条 · 跑步 / 骑行 / 健身"
        },
    ) {
        if (activities.isEmpty()) {
            item {
                MiuixSectionTitle("全部记录")
                MiuixCard {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp)) {
                        Text(
                            text = "同步后这里会显示活动",
                            fontSize = 16.sp,
                            color = MiuiText1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "只拉最近 20 条；想要更早的记录，去设置里点「回填历史」。",
                            fontSize = 12.5.sp,
                            color = MiuixSummary,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        } else {
            item {
                SearchBox(value = query, onValueChange = { query = it })
            }
            if (types.size > 1) {
                item {
                    FilterChipRow(
                        options = listOf("全部运动" to null) + types.map { sportGroupLabel(it) to it },
                        selected = typeFilter,
                        onSelect = { typeFilter = it },
                    )
                }
            }
            if (months.size > 1) {
                item {
                    FilterChipRow(
                        options = listOf("全部月份" to null) + months.map { it to it },
                        selected = monthFilter,
                        onSelect = { monthFilter = it },
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    MiuixCard {
                        Text(
                            text = "没有符合条件的记录",
                            fontSize = 14.sp,
                            color = MiuixSummary,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                        )
                    }
                }
            } else {
                val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                val titleFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
                val grouped = filtered
                    .groupBy { dayFormat.format(Date(it.startTimeMs)) }
                    .toSortedMap(compareByDescending { it })

                grouped.forEach { (day, list) ->
                    item {
                        val dayTitle = runCatching {
                            titleFormat.format(dayFormat.parse(day)!!)
                        }.getOrDefault(day)
                        MiuixSectionTitle(dayTitle)
                        MiuixCard {
                            list.forEachIndexed { index, activity ->
                                ActivityRow(activity) { onOpenActivity(activity.id) }
                                if (index != list.lastIndex) MiuixDivider(startIndent = 72)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixCardSurface)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        if (value.isEmpty()) {
            Text(text = "搜索运动类型或名称", fontSize = 13.5.sp, color = MiuixSummary)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = MiuiText1),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixPrimary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FilterChipRow(
    options: List<Pair<String, String?>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val tick = rememberTick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            // 类型行与月份行是两个 item，没有上下间距会紧贴在一起
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, value) ->
            val active = selected == value
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) MiuixPrimary else MiuixCardSurface)
                    .clickable {
                        tick()
                        onSelect(value)
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 12.5.sp,
                    color = if (active) Color.White else MiuiText1,
                )
            }
        }
    }
}

/** 把 Garmin 的一大串 typeKey 归成六类，筛选才有意义 */
private fun sportGroup(sport: String): String = when (sport.lowercase(Locale.US)) {
    "running", "run", "trail_running", "treadmill_running", "track_running" -> "run"
    "cycling", "biking", "road_biking", "mountain_biking", "indoor_cycling" -> "ride"
    "swimming", "lap_swimming", "open_water_swimming" -> "swim"
    "walking", "hiking" -> "walk"
    "strength_training", "indoor_cardio", "yoga", "pilates", "hiit" -> "strength"
    else -> "other"
}

private fun sportGroupLabel(group: String): String = when (group) {
    "run" -> "跑步"
    "ride" -> "骑行"
    "swim" -> "游泳"
    "walk" -> "步行"
    "strength" -> "力量"
    else -> "其它"
}

// ============================ 概览 ============================

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun OverviewTab(
    dashboard: HomeViewModel.Dashboard,
    trends: HomeViewModel.Trends,
    sleepStages: List<com.fitsync.data.db.SleepStageEntity>,
    syncing: Boolean,
    silent: Boolean = false,
    message: String?,
    onSync: () -> Unit,
    onSeeAccounts: () -> Unit,
    onOpenActivity: (Long) -> Unit = {},
    todayMetricIndex: Int = 0,
    onTodayMetricSelect: (Int) -> Unit = {},
) {
    val tick = rememberTick()

    HyperPage(
        title = "概览",
        subtitle = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date()),
        // 自动同步不转圈：页面一打开就转十几秒，很像卡死
        isRefreshing = syncing && !silent,
        onRefresh = onSync,
        listModifier = Modifier.testTag("dashboard"),
        actions = {
            Text(
                text = if (syncing) "同步中…" else "同步",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (syncing) MiuixAction else MiuixPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !syncing) {
                        tick()
                        onSync()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    ) {
            if (message != null) {
                item {
                    Text(
                        text = message,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = if (message.startsWith("同步失败")) MiuixError else MiuixSummary,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                }
            }

            item {
                MiuixSectionTitle("今日")
                MiuixCard {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        // 大数字区可切换：步数 / 距离 / 活动热量 / 楼层
                        // Triple(标签, 数值, 单位)
                        val metrics = listOf(
                            Triple(
                                "步数",
                                if (dashboard.steps > 0) "%,d".format(dashboard.steps) else "—",
                                "步",
                            ),
                            Triple(
                                "距离",
                                if (dashboard.distanceKm > 0) "%.1f".format(dashboard.distanceKm) else "—",
                                "公里",
                            ),
                            Triple(
                                "活动热量",
                                if (dashboard.activeCalories > 0) "%.0f".format(dashboard.activeCalories) else "—",
                                "千卡",
                            ),
                            Triple(
                                "楼层",
                                if (dashboard.floors > 0) "${dashboard.floors}" else "—",
                                "层",
                            ),
                        )
                        val metricIndex = todayMetricIndex.coerceIn(0, metrics.lastIndex)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = metrics[metricIndex].second,
                                fontSize = 46.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuiText1,
                                letterSpacing = (-1).sp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = metrics[metricIndex].third,
                                fontSize = 14.sp,
                                color = MiuixSummary,
                                modifier = Modifier.padding(bottom = 9.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (dashboard.steps > 0 || dashboard.distanceKm > 0) {
                                "%.1f 公里 · %.0f 千卡 · %d 层 · %,d 步".format(
                                    dashboard.distanceKm,
                                    dashboard.activeCalories,
                                    dashboard.floors,
                                    dashboard.steps,
                                )
                            } else {
                                "今天还没有数据，点右上角同步"
                            },
                            fontSize = 12.5.sp,
                            color = MiuixSummary,
                        )
                        Spacer(Modifier.height(14.dp))
                        MiuixSegmentedControl(
                            options = metrics.map { it.first },
                            selectedIndex = metricIndex,
                            onSelect = { onTodayMetricSelect(it) },
                        )
                    }
                }
            }

            if (trends.hasData) {
                item { WeekCard(trends = trends) }
            }

            item {
                MiuixSectionTitle("健康")
                MiuixCard {
                    MiuixRow(
                        title = "睡眠时长",
                        summary = if (dashboard.sleepScore > 0) "睡眠评分 ${dashboard.sleepScore}" else "昨晚没有数据",
                        leading = { MiuixLeadingIcon(Icons.Filled.DateRange, MiuixPrimary, MiuixPrimary.copy(alpha = 0.12f)) },
                        trailing = {
                            Text(
                                text = if (dashboard.sleepMinutes > 0) formatDuration(dashboard.sleepMinutes) else "—",
                                fontSize = 15.sp,
                                color = MiuixSummary,
                            )
                        },
                    )
                    if (sleepStages.isNotEmpty()) {
                        SleepStageBar(sleepStages)
                    }
                    MiuixDivider(startIndent = 72)
                    MiuixRow(
                        title = "睡眠平均心率",
                        trailing = {
                            Text(
                                text = if (dashboard.sleepAvgHr > 0) "${dashboard.sleepAvgHr} bpm" else "—",
                                fontSize = 15.sp,
                                color = MiuixSummary,
                            )
                        },
                    )
                    MiuixDivider(startIndent = 72)
                    MiuixRow(
                        title = "静息心率",
                        summary = "来自最近一次日汇总",
                        trailing = {
                            Text(
                                text = if (dashboard.restingHr > 0) "${dashboard.restingHr} bpm" else "—",
                                fontSize = 15.sp,
                                color = MiuixSummary,
                            )
                        },
                    )
                }
            }

            item {
                TrendCard(trends = trends)
            }

            if (dashboard.activities.isNotEmpty()) {
                item {
                    MiuixSectionTitle("最近活动")
                    val recent = dashboard.activities.take(5)
                    MiuixCard {
                        recent.forEachIndexed { index, activity ->
                            ActivityRow(activity) { onOpenActivity(activity.id) }
                            if (index != recent.lastIndex) MiuixDivider(startIndent = 72)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ActivityRow(activity: ActivityEntity, onClick: () -> Unit = {}) {
    val tick = rememberTick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                tick()
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MiuixPrimary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(sportGlyph(activity.sport), fontSize = 18.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // activityType 是 Garmin 原始 typeKey（如 trail_running），比归一化的 sport 更具体
                text = sportLabel(activity.activityType.ifBlank { activity.sport }),
                fontSize = 16.sp,
                color = MiuiText1,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(activity.startTimeMs)) +
                    if (activity.avgHr > 0) " · ${activity.avgHr} bpm" else "",
                fontSize = 12.sp,
                color = MiuixSummary,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (activity.distanceM > 0) "%.2f km".format(activity.distanceM / 1000) else "—",
                fontSize = 15.sp,
                color = MiuiText1,
            )
            Text(
                text = formatDuration((activity.durationSec / 60).toInt()),
                fontSize = 12.sp,
                color = MiuixSummary,
            )
        }
    }
}

// ============================ 账号 ============================

@Composable
private fun AccountsTab(
    accounts: List<AccountEntity>,
    primaryAccountId: Long?,
    aliases: Map<Long, String>,
    syncing: Boolean,
    onSync: () -> Unit,
    onSetPrimary: (Long) -> Unit,
    onRename: (AccountEntity) -> Unit,
    onAddAccount: () -> Unit,
    onImport: () -> Unit,
    canCopyActivities: Boolean,
    onCopyActivities: () -> Unit,
    canCopyHealth: Boolean,
    onCopyHealth: () -> Unit,
    onExport: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
) {
    val tick = rememberTick()
    HyperPage(
        title = "账号",
        subtitle = "两个区可互相同步，主账号决定首页以谁为准",
        isRefreshing = syncing,
        onRefresh = onSync,
        actions = {
            Text(
                text = if (syncing) "同步中…" else "同步",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (syncing) MiuixAction else MiuixPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !syncing) {
                        tick()
                        onSync()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    ) {
        item {
            MiuixSectionTitle("已登录")
            MiuixCard {
                accounts.forEachIndexed { index, account ->
                    AccountRow(
                        account = account,
                        isPrimary = account.id == primaryAccountId,
                        alias = aliases[account.id],
                        onRename = { onRename(account) },
                        onSetPrimary = { onSetPrimary(account.id) },
                        onClick = onOpenAccount,
                    )
                    if (index != accounts.lastIndex) MiuixDivider(startIndent = 18)
                }
            }
        }

        item {
            MiuixSectionTitle("数据")
            MiuixCard {
                MiuixRow(
                    title = "添加账号",
                    summary = "登录 Garmin 中国区或国际区",
                    showArrow = true,
                    onClick = onAddAccount,
                )
                MiuixDivider()
                MiuixRow(
                    title = "导入本地数据",
                    summary = "支持 zip / JSON / CSV 导出包",
                    showArrow = true,
                    onClick = onImport,
                )
                MiuixDivider()
                MiuixRow(
                    title = "导出数据",
                    summary = "保存为 JSON 或 CSV（ZIP）",
                    showArrow = true,
                    onClick = onExport,
                )
                if (canCopyActivities) {
                    MiuixDivider()
                    MiuixRow(
                        title = "复制活动到另一账号",
                        summary = "跑步、骑行、健身等运动记录",
                        showArrow = true,
                        onClick = onCopyActivities,
                    )
                }
                if (canCopyHealth) {
                    MiuixDivider()
                    MiuixRow(
                        title = "搬运健康数据",
                        summary = "需要目标账号已绑定设备 · 实验功能",
                        showArrow = true,
                        onClick = onCopyHealth,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: AccountEntity,
    isPrimary: Boolean,
    alias: String?,
    onRename: () -> Unit,
    onSetPrimary: () -> Unit,
    onClick: () -> Unit = {},
) {
    val tick = rememberTick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                tick()
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MiuixPrimary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (account.region == "cn") "CN" else "GL",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixPrimary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = (alias ?: account.displayName.ifBlank { account.externalUserId }),
                fontSize = 16.sp,
                color = MiuiText1,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor(account.status)))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = buildString {
                        append(regionLabel(account.region))
                        append(" · ")
                        append(statusLabel(account.status))
                        if (account.lastSyncAtMs > 0) {
                            append(" · ")
                            append(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(account.lastSyncAtMs)))
                        }
                    },
                    fontSize = 12.sp,
                    color = MiuixSummary,
                    maxLines = 1,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (isPrimary) "主账号" else "设为主账号",
                fontSize = 12.sp,
                color = if (isPrimary) MiuixPrimary else MiuixAction,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isPrimary) {
                        tick()
                        onSetPrimary()
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "改昵称",
                fontSize = 12.sp,
                color = MiuixPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        tick()
                        onRename()
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

// ============================ 设置 ============================

@Composable
private fun SettingsTab(
    accountCount: Int,
    message: String?,
    themeMode: String = ThemeModePref.FOLLOW,
    onThemeModeChange: (String) -> Unit = {},
    floatingNavBar: Boolean = true,
    onFloatingNavBarChange: (Boolean) -> Unit = {},
    onOpenAccounts: () -> Unit = {},
    onOpenChangelog: () -> Unit = {},
    onOpenAcknowledgements: () -> Unit = {},
    onOpenSyncLogs: () -> Unit = {},
    onBackfill: () -> Unit = {},
    autoSyncOnOpen: Boolean = true,
    onAutoSyncChange: (Boolean) -> Unit = {},
    syncIntervalHours: Long = 6L,
    onSyncIntervalChange: (Long) -> Unit = {},
    wifiOnly: Boolean = false,
    onWifiOnlyChange: (Boolean) -> Unit = {},
    backfillDays: Int = 365,
    onBackfillDaysChange: (Int) -> Unit = {},
    onBackupDatabase: (android.net.Uri) -> Unit = {},
    onRestoreDatabase: (android.net.Uri) -> Unit = {},
) {
    val themeOptions = listOf("跟随系统", "浅色", "深色")
    val themeCodes = listOf(ThemeModePref.FOLLOW, ThemeModePref.LIGHT, ThemeModePref.DARK)
    val settingsContext = LocalContext.current
    var hapticsOn by remember { mutableStateOf(Haptics.isEnabled(settingsContext)) }
    val intervalValues = listOf(6L, 12L, 24L, 0L)
    val intervalIndex = intervalValues.indexOf(syncIntervalHours).let { if (it < 0) 0 else it }
    val backfillValues = listOf(30, 90, 365)
    val backfillIndex = backfillValues.indexOf(backfillDays).let { if (it < 0) 2 else it }

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(onBackupDatabase) }
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onRestoreDatabase) }

    HyperPage(
        title = "设置",
        subtitle = "外观、同步策略与版本信息",
    ) {
        item {
            MiuixSectionTitle("同步")
            MiuixCard {
                MiuixRow(
                    title = "打开即同步",
                    summary = "进入前台自动拉取，5 分钟内不重复",
                    trailing = {
                        MiuixSwitch(
                            checked = autoSyncOnOpen,
                            onCheckedChange = { enabled ->
                                onAutoSyncChange(enabled)
                                Haptics.perform(settingsContext, HapticFeedback.Confirm)
                            },
                        )
                    },
                )
                MiuixDivider()
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(text = "后台同步", fontSize = 16.sp, color = MiuiText1)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "选「仅手动」就不再后台联网，只在你点同步时拉取",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MiuixSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    MiuixSegmentedControl(
                        options = listOf("6 小时", "12 小时", "24 小时", "仅手动"),
                        selectedIndex = intervalIndex,
                        onSelect = { index ->
                            onSyncIntervalChange(intervalValues[index])
                            Haptics.perform(settingsContext, HapticFeedback.Confirm)
                        },
                    )
                }
                MiuixDivider()
                MiuixRow(
                    title = "仅 Wi-Fi 自动同步",
                    summary = "移动网络下跳过自动同步，手动同步不受限",
                    trailing = {
                        MiuixSwitch(
                            checked = wifiOnly,
                            onCheckedChange = { enabled ->
                                onWifiOnlyChange(enabled)
                                Haptics.perform(settingsContext, HapticFeedback.Confirm)
                            },
                        )
                    },
                )
                MiuixDivider()
                MiuixRow(
                    title = "回填历史",
                    summary = "拉取过去一年的日汇总、睡眠与全部活动（较慢）",
                    showArrow = true,
                    onClick = onBackfill,
                )
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text(text = "回填范围", fontSize = 12.sp, color = MiuixSummary)
                    Spacer(Modifier.height(8.dp))
                    MiuixSegmentedControl(
                        options = listOf("30 天", "90 天", "1 年"),
                        selectedIndex = backfillIndex,
                        onSelect = { index ->
                            onBackfillDaysChange(backfillValues[index])
                            Haptics.perform(settingsContext, HapticFeedback.Confirm)
                        },
                    )
                }
                MiuixDivider()
                MiuixRow(
                    title = "同步记录",
                    summary = "每次同步的时间、条数与失败原因",
                    showArrow = true,
                    onClick = onOpenSyncLogs,
                )
                MiuixDivider()
                MiuixRow(
                    title = "已连接账号",
                    summary = "点击查看详情与同步状态",
                    trailing = { Text("$accountCount 个", fontSize = 14.sp, color = MiuixSummary) },
                    showArrow = true,
                    onClick = onOpenAccounts,
                )
            }
        }

        item {
            MiuixSectionTitle("数据")
            MiuixCard {
                MiuixRow(
                    title = "备份数据库",
                    summary = "完整导出，换机时可直接恢复",
                    showArrow = true,
                    onClick = { backupLauncher.launch("fitsync-backup.db") },
                )
                MiuixDivider()
                MiuixRow(
                    title = "恢复数据库",
                    summary = "会覆盖当前数据，完成后需重启 App",
                    showArrow = true,
                    onClick = {
                        restoreLauncher.launch(
                            arrayOf("application/octet-stream", "application/x-sqlite3")
                        )
                    },
                )
            }
        }

        item {
            MiuixSectionTitle("外观")
            MiuixCard {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(
                        text = "深色模式",
                        fontSize = 16.sp,
                        color = MiuiText1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "切换后全站立即生效；跟随系统会随系统设置自动变",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MiuixSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    MiuixSegmentedControl(
                        options = themeOptions,
                        selectedIndex = themeCodes.indexOf(themeMode).coerceAtLeast(0),
                        onSelect = { onThemeModeChange(themeCodes[it]) },
                    )
                }
                MiuixDivider()
                MiuixRow(
                    title = "悬浮导航栏",
                    summary = if (floatingNavBar) {
                        "液态玻璃悬浮样式，可左右拖动切换"
                    } else {
                        "当前为贴底标准导航栏"
                    },
                    trailing = {
                        MiuixSwitch(
                            checked = floatingNavBar,
                            onCheckedChange = { enabled ->
                                onFloatingNavBarChange(enabled)
                                Haptics.perform(settingsContext, HapticFeedback.Confirm)
                            },
                        )
                    },
                )
                MiuixDivider()
                MiuixRow(
                    title = "触感反馈",
                    summary = "点击、切换、完成时轻震一下",
                    trailing = {
                        MiuixSwitch(
                            checked = hapticsOn,
                            onCheckedChange = { enabled ->
                                hapticsOn = enabled
                                Haptics.setEnabled(settingsContext, enabled)
                                if (enabled) Haptics.perform(settingsContext, HapticFeedback.Confirm)
                            },
                        )
                    },
                )
            }
        }

        item {
            MiuixSectionTitle("关于")
            MiuixCard {
                MiuixRow(
                    title = "版本",
                    trailing = {
                        Text(
                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            fontSize = 14.sp,
                            color = MiuixSummary,
                        )
                    },
                )
                MiuixDivider()
                MiuixRow(
                    title = "数据来源",
                    summary = "Garmin Connect 中国区 / 国际区，本地加密存储",
                )
                MiuixDivider()
                MiuixRow(
                    title = "项目主页",
                    summary = "GitHub 上的 FitSync 仓库",
                    showArrow = true,
                    onClick = { openExternal(settingsContext, PROJECT_URL) },
                )
                MiuixDivider()
                MiuixRow(
                    title = "问题反馈",
                    summary = "遇到问题去提 Issue",
                    showArrow = true,
                    onClick = { openExternal(settingsContext, ISSUES_URL) },
                )
                MiuixDivider()
                MiuixRow(
                    title = "更新日志",
                    summary = "每个版本改了什么",
                    showArrow = true,
                    onClick = onOpenChangelog,
                )
                MiuixDivider()
                MiuixRow(
                    title = "开源致谢",
                    summary = "用到的所有开源项目与许可证",
                    showArrow = true,
                    onClick = onOpenAcknowledgements,
                )
            }
        }

        if (message != null) {
            item {
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = MiuixSummary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ============================ 空状态 ============================

@Composable
private fun EmptyState(onAddAccount: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有账号",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuiText1,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "登录 Garmin 账号，或直接导入导出的数据文件。",
            fontSize = 13.5.sp,
            color = MiuixSummary,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(24.dp))
        MiuixPrimaryButton(
            text = "添加数据源",
            onClick = onAddAccount,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "导入本地数据（zip / JSON / CSV）",
            fontSize = 14.sp,
            color = MiuixPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onImport() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

// ============================ 工具 ============================
// 格式化与状态映射统一放在 core-ui 的 Formatters.kt，账号详情 / 活动详情等页面共用。

/** 打开外部链接；没有浏览器时静默失败，不崩应用 */
private fun openExternal(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private const val PROJECT_URL = "https://github.com/yaoiwi/FitSync"
private const val ISSUES_URL = "https://github.com/yaoiwi/FitSync/issues"

// ============================ 睡眠分期条 ============================

/**
 * 一晚睡眠的阶段分布：按各段时长占比拼一条彩色带，下面配一行图例。
 *
 * 只画相对时长，不画具体时钟刻度——首页要的是"深睡够不够、有没有醒"，
 * 精确时间点留给以后的睡眠详情页。
 */
@Composable
private fun SleepStageBar(stages: List<com.fitsync.data.db.SleepStageEntity>) {
    val totalMs = stages.sumOf { (it.endTimeMs - it.startTimeMs).coerceAtLeast(0L) }
    if (totalMs <= 0L) return

    val summary = stages.groupBy { it.stage }
        .mapValues { (_, list) -> list.sumOf { (it.endTimeMs - it.startTimeMs).coerceAtLeast(0L) } / 60_000L }

    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        ) {
            stages.forEach { stage ->
                val weight = ((stage.endTimeMs - stage.startTimeMs).toFloat() / totalMs)
                    .coerceAtLeast(0.002f)
                Box(
                    modifier = Modifier
                        .weight(weight)
                        .height(10.dp)
                        .background(stageColor(stage.stage)),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                "deep" to "深睡",
                "light" to "浅睡",
                "rem" to "REM",
                "awake" to "清醒",
            ).forEach { (key, label) ->
                val minutes = summary[key] ?: 0L
                if (minutes > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(stageColor(key)),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "$label ${minutes}分",
                            fontSize = 10.5.sp,
                            color = MiuixSummary,
                        )
                    }
                }
            }
        }
    }
}

private fun stageColor(stage: String): Color = when (stage) {
    "deep" -> Color(0xFF2C4A8A)
    "rem" -> Color(0xFF8E7BFF)
    "awake" -> Color(0xFFFFB340)
    else -> Color(0xFF6FA8FF)
}
