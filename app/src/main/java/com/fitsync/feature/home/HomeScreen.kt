package com.fitsync.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitsync.BuildConfig
import com.fitsync.copyToClipboard
import com.fitsync.data.network.GarminDiagnostics
import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.ActivityEntity
import com.fitsync.permission.BatteryOptimization
import com.fitsync.permission.rememberNotificationPermissionGate
import com.fitsync.ui.components.accountTitle
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
import com.fitsync.ui.components.MiuixActionDialog
import com.fitsync.ui.components.MiuixConfirmDialog
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
import com.fitsync.ui.theme.MiuixGroupBg
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary
import com.fitsync.ui.components.MiuixSegmentedControl
import com.fitsync.ui.theme.StatusGreen
import com.fitsync.ui.theme.ThemeModePref
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
    // 搬运健康数据运行中：只有当它用细线（要能一边等一边翻数据）
    val copyHealthRunning by viewModel.copyHealthRunning.collectAsState()
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
                        + accountTitle(target.displayName, target.region),
                    placeholder = accountTitle(target.displayName, target.region),
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
                                copyHealthRunning = copyHealthRunning,
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
                                copyHealthRunning = copyHealthRunning,
                                onSync = viewModel::syncNow,
                                onSetPrimary = viewModel::setPrimaryAccount,
                                onReorderAccounts = viewModel::reorderAccounts,
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
                                rateLimitRemainingMs = viewModel::rateLimitRemainingMs,
                                rateLimitStrikes = viewModel::rateLimitStrikes,
                                onRetryAfterRateLimit = viewModel::retryAfterRateLimit,
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
