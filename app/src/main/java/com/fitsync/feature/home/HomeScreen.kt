package com.fitsync.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.LiquidGlassNavigationBar
import com.fitsync.ui.components.LiquidGlassNavigationItem
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixLargeHeader
import com.fitsync.ui.components.formatDuration
import com.fitsync.ui.components.regionLabel
import com.fitsync.ui.components.sportGlyph
import com.fitsync.ui.components.statusColor
import com.fitsync.ui.components.statusLabel
import com.fitsync.ui.components.MiuixLeadingIcon
import com.fitsync.ui.components.MiuixNavItem
import com.fitsync.ui.components.MiuixRow
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.theme.FitSyncTheme
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
    onOpenPersonalApi: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // 外观模式：设置页改了之后这里会重读，整页立刻切色板
    val themeMode by remember(context) { ThemeModePref.flow(context) }
        .collectAsState(initial = ThemeModePref.FOLLOW)
    val accounts by viewModel.accounts.collectAsState()
    val dashboard by viewModel.dashboard.collectAsState()
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
    var showCopyConfirm by remember { mutableStateOf(false) }
    var showHealthConfirm by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AccountEntity?>(null) }
    var tab by remember { mutableStateOf(0) }
    // 今日大数字当前展示哪个指标：0 步数 / 1 距离 / 2 活动热量 / 3 楼层
    var todayMetric by remember { mutableStateOf(0) }
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
                var draft by remember(target.id) {
                    mutableStateOf(aliases[target.id] ?: "")
                }
                AlertDialog(
                    onDismissRequest = { renameTarget = null },
                    title = { Text("修改昵称") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                label = { Text("显示昵称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Garmin 原昵称：${target.displayName}（改昵称只影响本应用显示，不影响同步）",
                                fontSize = 11.sp,
                                color = MiuixSummary,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.renameAccount(target.id, draft)
                            renameTarget = null
                        }) { Text("保存") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.renameAccount(target.id, "")
                            renameTarget = null
                        }) { Text("恢复默认") }
                    },
                )
            }
            if (showHealthConfirm) {
                AlertDialog(
                    onDismissRequest = { showHealthConfirm = false },
                    title = { Text("搬运健康数据到另一账号") },
                    text = {
                        Text(
                            "会把主账号最近 7 天的步数、睡眠、压力以 monitoring FIT 上传到另一个账号。\n\n" +
                                "前提：目标账号必须已绑定这块手表（Garmin 只接受已绑定设备上传的健康数据）。" +
                                "若目标账号没有设备标签，请先用 Garmin Express 把手表注册到该区。\n\n" +
                                "限制：睡眠/HRV 类数据 Garmin 只允许回溯约 1 个月。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showHealthConfirm = false
                            viewModel.copyHealthToOtherAccount()
                        }) { Text("开始搬运") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showHealthConfirm = false }) { Text("取消") }
                    },
                )
            }
            if (showCopyConfirm) {
                AlertDialog(
                    onDismissRequest = { showCopyConfirm = false },
                    title = { Text("复制活动到另一账号") },
                    text = {
                        Text(
                            "会把主账号最近 10 条活动（跑步、骑行、健身等）上传到另一个 Garmin 账号。\n\n" +
                                "注意：心率、睡眠、喝水、步数这些健康数据 Garmin 不提供写入接口，" +
                                "无法复制到对方账号，只能在本应用内合并展示。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showCopyConfirm = false
                            viewModel.copyActivitiesToOtherAccount()
                        }) { Text("开始复制") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCopyConfirm = false }) { Text("取消") }
                    },
                )
            }

            if (accounts.isEmpty()) {
                EmptyState(onAddAccount = onAddAccount, onImport = pickFile)
            } else {
                // 玻璃宿主：页面内容录成 backdrop，导航栏放进 overlay（不参与采样）
                GlassHost(
                    modifier = Modifier.fillMaxSize(),
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
                        )
                    },
                ) {
                    when (tab) {
                            0 -> OverviewTab(
                                dashboard = dashboard,
                                accounts = accounts,
                                primaryAccountId = primaryAccountId,
                                syncing = syncState.syncing,
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
                                onOpenAccount = onOpenAccounts,
                            )
                            else -> SettingsTab(
                                accountCount = accounts.size,
                                message = syncState.message,
                                themeMode = themeMode,
                                onThemeModeChange = { ThemeModePref.set(context, it) },
                                onOpenAccounts = onOpenAccounts,
                                onOpenChangelog = onOpenChangelog,
                                onOpenAcknowledgements = onOpenAcknowledgements,
                                onOpenPersonalApi = onOpenPersonalApi,
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
    HyperPage(
        title = "活动",
        subtitle = when {
            syncing -> "正在同步…"
            activities.isEmpty() -> "还没有运动记录"
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
                            text = "只拉最近 20 条；两个区的记录按时间合并。",
                            fontSize = 12.5.sp,
                            color = MiuixSummary,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        } else {
            val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val titleFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
            val grouped = activities
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

// ============================ 概览 ============================

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun OverviewTab(
    dashboard: HomeViewModel.Dashboard,
    accounts: List<AccountEntity>,
    primaryAccountId: Long?,
    syncing: Boolean,
    message: String?,
    onSync: () -> Unit,
    onSeeAccounts: () -> Unit,
    onOpenActivity: (Long) -> Unit = {},
    todayMetricIndex: Int = 0,
    onTodayMetricSelect: (Int) -> Unit = {},
) {
    val primaryName = accounts.firstOrNull { it.id == primaryAccountId }
        ?.let { it.displayName.ifBlank { it.externalUserId } }

    HyperPage(
        title = "概览",
        subtitle = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date()) +
            if (primaryName != null) " · 主账号 $primaryName" else " · 未设主账号",
        isRefreshing = syncing,
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
                    .clickable(enabled = !syncing) { onSync() }
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

            if (dashboard.activities.isNotEmpty()) {
                item {
                    MiuixSectionTitle("最近活动")
                    MiuixCard {
                        dashboard.activities.forEachIndexed { index, activity ->
                            ActivityRow(activity) { onOpenActivity(activity.id) }
                            if (index != dashboard.activities.lastIndex) MiuixDivider(startIndent = 72)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ActivityRow(activity: ActivityEntity, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                text = activity.activityType.ifBlank { activity.sport },
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
    onOpenAccount: () -> Unit = {},
) {
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
                    .clickable(enabled = !syncing) { onSync() }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                    .clickable(enabled = !isPrimary) { onSetPrimary() }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "改昵称",
                fontSize = 12.sp,
                color = MiuixPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRename() }
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
    onOpenAccounts: () -> Unit = {},
    onOpenChangelog: () -> Unit = {},
    onOpenAcknowledgements: () -> Unit = {},
    onOpenPersonalApi: () -> Unit = {},
) {
    val themeOptions = listOf("跟随系统", "浅色", "深色")
    val themeCodes = listOf(ThemeModePref.FOLLOW, ThemeModePref.LIGHT, ThemeModePref.DARK)
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
                    trailing = { Text("已开启", fontSize = 14.sp, color = MiuixSummary) },
                )
                MiuixDivider()
                MiuixRow(
                    title = "同步范围",
                    summary = "最近 7 天回填，之后按日增量",
                )
                MiuixDivider()
                MiuixRow(
                    title = "已连接账号",
                    summary = "点击查看详情与同步状态",
                    trailing = { Text("$accountCount 个", fontSize = 14.sp, color = MiuixSummary) },
                    showArrow = true,
                    onClick = onOpenAccounts,
                )
                MiuixDivider()
                MiuixRow(
                    title = "Garmin 官方 API",
                    summary = "只读 · 用 Personal API Key，不需要账号密码",
                    showArrow = true,
                    onClick = onOpenPersonalApi,
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
        Button(
            onClick = onAddAccount,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MiuixPrimary),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("添加数据源", fontSize = 16.sp)
        }
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
