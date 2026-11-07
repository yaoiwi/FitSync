package com.fitsync.feature.home

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitsync.data.db.AccountEntity
import com.fitsync.ui.components.accountTitle
import com.fitsync.ui.components.GlassHost
import com.fitsync.ui.components.HapticFeedback
import com.fitsync.ui.components.Haptics
import com.fitsync.ui.components.LiquidGlassNavigationBar
import com.fitsync.ui.components.LiquidGlassNavigationItem
import com.fitsync.ui.components.MiuixActionDialog
import com.fitsync.ui.components.MiuixBottomNav
import com.fitsync.ui.components.MiuixInputDialog
import com.fitsync.ui.components.MiuixNavItem
import com.fitsync.ui.components.MiuixPrimaryButton
import com.fitsync.ui.components.MiuixActionDialog
import com.fitsync.ui.theme.FitSyncTheme
import com.fitsync.ui.theme.FloatingNavBarPref
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixCanvas
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.UiStyle
import com.fitsync.ui.theme.UiStylePref
import com.fitsync.ui.theme.MiuixSummary
import com.fitsync.ui.theme.MonetPref
import com.fitsync.ui.theme.ThemeModePref

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
    // 界面风格：MD3 时底部换成标准贴底导航栏，不再做液态玻璃
    val uiStyle by remember(context) { UiStylePref.flow(context) }
        .collectAsState(initial = UiStylePref.current(context))
    // 莫奈取色：开关一改，FitSyncTheme 里的动态色板立即重算
    val monetEnabled by remember(context) { MonetPref.flow(context) }
        .collectAsState(initial = MonetPref.isEnabled(context))
    val accounts by viewModel.accounts.collectAsState()
    val accountsLoaded by viewModel.accountsLoaded.collectAsState()
    val dashboard by viewModel.dashboard.collectAsState()
    val trends by viewModel.trends.collectAsState()
    val sleepStages by viewModel.sleepStages.collectAsState()
    val autoSyncOnOpen by viewModel.autoSyncOnOpen.collectAsState()
    val syncIntervalHours by viewModel.syncIntervalHours.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()
    val backfillDays by viewModel.backfillDays.collectAsState()
    val activityLimit by viewModel.activityLimit.collectAsState()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 这里还在主线程：以前直接 readBytes()，30 MB 的导出包会卡住界面甚至 ANR。
        // 现在只把 uri 交出去，读取交给协调器的 IO 线程，超限会自己停下。
        if (uri != null) viewModel.importFromUri(uri)
    }
    val syncState by viewModel.syncState.collectAsState()
    // 搬运健康数据运行中：只有当它用细线（要能一边等一边翻数据）
    val copyHealthRunning by viewModel.copyHealthRunning.collectAsState()
    val primaryAccountId by viewModel.primaryAccountId.collectAsState()
    val aliases by viewModel.aliases.collectAsState()
    var renameTarget by remember { mutableStateOf<AccountEntity?>(null) }
    // 恢复的备份已经落进 pending 文件，等下次启动才替换数据库
    var restoreReady by remember { mutableStateOf(false) }
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
                    bottomScrimHeight = if (uiStyle == UiStyle.Md3) 0.dp else 140.dp,
                    overlay = {
                        if (uiStyle == UiStyle.Md3) {
                            // MD3：标准导航栏贴底，不做 backdrop 采样
                            MiuixBottomNav(
                                items = listOf(
                                    MiuixNavItem(label = "概览", icon = Icons.Filled.Home),
                                    MiuixNavItem(label = "活动", icon = Icons.Filled.List),
                                    MiuixNavItem(label = "账号", icon = Icons.Filled.Person),
                                    MiuixNavItem(label = "设置", icon = Icons.Filled.Settings),
                                ),
                                selectedIndex = tab,
                                onSelect = { tab = it },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        } else {
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
                        }
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
                                message = syncState.message,
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
                                onSyncHealth = viewModel::syncHealthNow,
                                onSyncActivities = viewModel::syncActivitiesNow,
                                activityLimit = activityLimit,
                                onActivityLimitChange = viewModel::setActivityLimit,
                            )
                            else -> SettingsTab(
                                themeMode = themeMode,
                                onThemeModeChange = { ThemeModePref.set(context, it) },
                                uiStyle = uiStyle,
                                onUiStyleChange = { UiStylePref.set(context, it) },
                                monetEnabled = monetEnabled,
                                onMonetEnabledChange = { MonetPref.set(context, it) },
                                floatingNavBar = floatingNavBar,
                                onFloatingNavBarChange = { FloatingNavBarPref.set(context, it) },
                                onOpenChangelog = onOpenChangelog,
                                onOpenAcknowledgements = onOpenAcknowledgements,
                                onBackupDatabase = { uri ->
                                    Toast.makeText(context, "正在备份…", Toast.LENGTH_SHORT).show()
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
                                        runCatching { viewModel.restoreDatabase(uri) }
                                            .onSuccess { restoreReady = true }
                                            .onFailure {
                                                Toast.makeText(
                                                    context,
                                                    "恢复失败：${it.message ?: "未知错误"}",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                    }
                                },
                            )
                    }
                }
            }
        }

        if (restoreReady) {
            MiuixActionDialog(
                title = "恢复已就位",
                confirmText = "立即重启",
                cancelText = "稍后",
                onConfirm = {
                    restoreReady = false
                    restartApp(context)
                },
                onDismiss = { restoreReady = false },
            ) {
                Column {
                    Text(
                        text = "备份已经取出来了，要重启一次才会替换成备份里的数据。",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MiuiText1,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "登录凭据不会随备份迁移（token 的密钥留在原来的手机上），" +
                            "重启后每个账号都需要重新登录一次，历史数据都在。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MiuixSummary,
                    )
                }
            }
        }
    }
}

/**
 * 重启自己。
 *
 * 恢复数据库只能靠「进程重启后、Room 打开之前替换文件」，所以必须真的重来一遍。
 * 直接 `exitProcess` 会把用户丢回桌面，得先用 AlarmManager 挂一个拉起自己的意图
 * （延迟 150ms，留出退出的时间）。Android 12 起精确闹钟要权限，拿不到就退回松散闹钟，
 * 反正前台场景下也是立刻触发。
 */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = android.app.PendingIntent.getActivity(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(android.app.AlarmManager::class.java)
        val at = System.currentTimeMillis() + 150
        runCatching { am.setExact(android.app.AlarmManager.RTC, at, pending) }
            .recoverCatching { am.set(android.app.AlarmManager.RTC, at, pending) }
    }
    (context as? android.app.Activity)?.finishAffinity()
    android.os.Process.killProcess(android.os.Process.myPid())
    kotlin.system.exitProcess(0)
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
