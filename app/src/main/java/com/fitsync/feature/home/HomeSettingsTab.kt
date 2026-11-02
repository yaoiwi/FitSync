package com.fitsync.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.fitsync.BuildConfig
import com.fitsync.copyToClipboard
import com.fitsync.data.network.GarminDiagnostics
import com.fitsync.permission.BatteryOptimization
import com.fitsync.permission.rememberNotificationPermissionGate
import com.fitsync.ui.components.HapticFeedback
import com.fitsync.ui.components.Haptics
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixActionDialog
import com.fitsync.ui.components.MiuixConfirmDialog
import com.fitsync.ui.components.MiuixRow
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.components.MiuixSwitch
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixError
import com.fitsync.ui.theme.MiuixGroupBg
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary
import com.fitsync.ui.components.MiuixSegmentedControl
import com.fitsync.ui.theme.ThemeModePref

// ============================ 设置 ============================

/** 冷却剩余时间的中文文案；超过一天要带天数，避免「23 小时」被理解成今晚就好 */
private fun cooldownLabel(ms: Long): String {
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        ms < minute -> "${(ms / 1000).coerceAtLeast(1)} 秒"
        ms < hour -> "${ms / minute} 分钟"
        ms < day -> {
            val h = ms / hour
            val m = (ms % hour) / minute
            if (m > 0) "$h 小时 $m 分" else "$h 小时"
        }
        else -> {
            val d = ms / day
            val h = (ms % day) / hour
            if (h > 0) "$d 天 $h 小时" else "$d 天"
        }
    }
}

@Composable
internal fun SettingsTab(
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
    rateLimitRemainingMs: () -> Long = { 0L },
    rateLimitStrikes: () -> Int = { 0 },
    onRetryAfterRateLimit: () -> Unit = {},
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
    // 回填同样是长任务：先要通知权限，再交给前台服务跑
    val ensureNotification = rememberNotificationPermissionGate()
    // 电池优化白名单：跳系统设置回来要重新读一次，否则开关状态是旧的
    var batteryExempt by remember {
        mutableStateOf(BatteryOptimization.isIgnoring(settingsContext))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = BatteryOptimization.isIgnoring(settingsContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 冷却倒计时：每秒刷新一次，值没变时 Compose 不会重组，开销可以忽略
    var cooldownMs by remember { mutableStateOf(0L) }
    var strikes by remember { mutableStateOf(0) }
    var showRetryConfirm by remember { mutableStateOf(false) }
    // 最近一次失败的请求快照：403 光看状态码分不清是「没带令牌」还是「网络被拦」，
    // 这里把证据摆在设置页，用户能直接复制发出来
    var lastFailure by remember { mutableStateOf<GarminDiagnostics.Entry?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            cooldownMs = rateLimitRemainingMs()
            strikes = rateLimitStrikes()
            lastFailure = GarminDiagnostics.latest()
            delay(1_000L)
        }
    }

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
                if (cooldownMs > 0L) {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "同步已暂停",
                                fontSize = 16.sp,
                                color = MiuixError,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (strikes > 0) "Garmin 限流 · 连续第 $strikes 次" else "Garmin 限流",
                                fontSize = 12.sp,
                                color = MiuixSummary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "还有 ${cooldownLabel(cooldownMs)}。冷却期间不会发出任何请求，" +
                                "手动点同步也不会联网——这是为了避免把账号推进 48 小时锁定。",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MiuixSummary,
                        )
                        TextButton(
                            onClick = { showRetryConfirm = true },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                text = "已换网络 / 已过锁定时间，立即重试",
                                fontSize = 13.sp,
                                color = MiuixPrimary,
                            )
                        }
                    }
                    MiuixDivider()
                }
                MiuixRow(
                    title = "同步诊断",
                    summary = lastFailure?.let {
                        "最近一次失败：HTTP ${it.code} · ${it.endpoint}" +
                            if (it.tokenLength == 0) "（请求没带令牌）" else ""
                    } ?: "暂无失败记录",
                    showArrow = true,
                    onClick = { showDiagnostics = true },
                )
                MiuixDivider()
                MiuixRow(
                    title = "打开即同步",
                    summary = "进入前台自动拉取，30 分钟内不重复",
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
                    title = "后台加速",
                    summary = if (batteryExempt) {
                        "已允许后台不受电池限制，长任务不会被降速"
                    } else {
                        "允许后搬运、回填在后台不会被系统降速或冻结"
                    },
                    showArrow = !batteryExempt,
                    trailing = {
                        if (batteryExempt) {
                            Text(text = "已开启", fontSize = 12.sp, color = MiuixPrimary)
                        }
                    },
                    onClick = if (batteryExempt) {
                        null
                    } else {
                        ({ BatteryOptimization.requestIgnore(settingsContext) })
                    },
                )
                MiuixDivider()
                MiuixRow(
                    title = "回填历史",
                    summary = "拉取过去一年的日汇总、睡眠与全部活动（较慢）",
                    showArrow = true,
                    onClick = { ensureNotification(onBackfill) },
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
                    summary = "导出到本地文件，换机可恢复；不含登录凭据",
                    showArrow = true,
                    onClick = { backupLauncher.launch("FitSync-${TimeFmt.fileStamp()}.db") },
                )
                MiuixDivider()
                MiuixRow(
                    title = "恢复数据库",
                    summary = "覆盖当前数据，重启后生效；账号需重新登录",
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

    if (showRetryConfirm) {
        // 与「搬运」「导入」共用同一套 Miuix 外壳：Material3 的弹窗过渡和按钮排布
        // 跟 HyperOS 差得远，一弹出来就出戏
        MiuixConfirmDialog(
            title = "立即重试同步？",
            message = "只有在换过网络（流量 / 另一个 Wi-Fi），或者已经过了上面提示的恢复时间后再点。\n\n" +
                "如果账号仍在 Garmin 的锁定窗口里，这次重试会立刻再收到 429，冷却会从当前档位重新计时。",
            confirmText = "仍然重试",
            cancelText = "取消",
            onConfirm = {
                showRetryConfirm = false
                onRetryAfterRateLimit()
            },
            onDismiss = { showRetryConfirm = false },
        )
    }

    if (showDiagnostics) {
        MiuixActionDialog(
            title = "同步诊断",
            confirmText = "复制",
            cancelText = "关闭",
            onConfirm = {
                copyToClipboard(
                    settingsContext,
                    "FitSync 同步诊断",
                    GarminDiagnostics.dump(),
                )
                showDiagnostics = false
            },
            onDismiss = { showDiagnostics = false },
        ) {
            // 诊断文本可能有几十行：放进浅色圆角块里限高滚动，
            // 卡片不会顶到状态栏，也不会把「复制」按钮挤出去
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixGroupBg),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = GarminDiagnostics.dump(),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MiuixSummary,
                    )
                }
            }
        }
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
