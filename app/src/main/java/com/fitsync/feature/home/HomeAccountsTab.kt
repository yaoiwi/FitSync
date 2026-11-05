package com.fitsync.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fitsync.copyToClipboard
import com.fitsync.data.db.AccountEntity
import com.fitsync.data.network.GarminDiagnostics
import com.fitsync.data.sync.ActivityPaging
import com.fitsync.permission.BatteryOptimization
import com.fitsync.permission.rememberNotificationPermissionGate
import com.fitsync.ui.components.accountTitle
import com.fitsync.ui.components.HapticFeedback
import com.fitsync.ui.components.Haptics
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.MiuixActionDialog
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixConfirmDialog
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixSegmentedControl
import com.fitsync.ui.components.MiuixSwitch
import com.fitsync.ui.components.rememberTick
import com.fitsync.ui.components.regionLabel
import com.fitsync.ui.components.statusColor
import com.fitsync.ui.components.statusLabel
import com.fitsync.ui.components.MiuixRow
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixAction
import com.fitsync.ui.theme.MiuixCardSurface
import com.fitsync.ui.theme.MiuixError
import com.fitsync.ui.theme.MiuixGroupBg
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary
import kotlinx.coroutines.delay

// ============================ 账号 ============================

@Composable
internal fun AccountsTab(
    accounts: List<AccountEntity>,
    primaryAccountId: Long?,
    aliases: Map<Long, String>,
    syncing: Boolean,
    copyHealthRunning: Boolean = false,
    onSync: () -> Unit,
    onSetPrimary: (Long) -> Unit,
    onReorderAccounts: (List<Long>) -> Unit = {},
    onRename: (AccountEntity) -> Unit,
    onAddAccount: () -> Unit,
    onImport: () -> Unit,
    canCopyActivities: Boolean,
    onCopyActivities: () -> Unit,
    canCopyHealth: Boolean,
    onCopyHealth: () -> Unit,
    onExport: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    message: String? = null,
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
    onSyncHealth: () -> Unit = {},
    onSyncActivities: () -> Unit = {},
    activityLimit: Int = ActivityPaging.DEFAULT_LIMIT,
    onActivityLimitChange: (Int) -> Unit = {},
) {
    val tick = rememberTick()
    val context = LocalContext.current
    val density = LocalDensity.current

    // 拖动排序：拖动过程中只改这份本地顺序，松手才落库一次。
    // 每挪一格就写一次 Room 的话，DB 回写与重组会插在手指和行之间，手感是顿的。
    var order by remember { mutableStateOf(accounts.map { it.id }) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    /** 手指相对「起拖那一格」的累计位移，向下为正 */
    var dragDy by remember { mutableFloatStateOf(0f) }
    /** 起拖时这一行在第几格；拖动中行本身会被重排，只有它是不动的参照 */
    var dragFromIndex by remember { mutableIntStateOf(0) }
    /** 行高（不含分隔线），拖动跨格要靠它换算 */
    var rowHeightPx by remember { mutableIntStateOf(0) }

    val ids = accounts.map { it.id }
    // 账号增删、其他页面改了顺序时同步进来；拖动中不打断，免得拽着拽着顺序被外面重置
    LaunchedEffect(ids) {
        if (draggingId == null && order != ids) order = ids
    }
    val orderedAccounts = remember(accounts, order) {
        if (order.size == accounts.size && order.toSet() == ids.toSet()) {
            val byId = accounts.associateBy { it.id }
            order.mapNotNull { byId[it] }
        } else {
            accounts
        }
    }

    val dividerPx = with(density) { 0.5.dp.toPx() }
    val slotPx = rowHeightPx + dividerPx
    val canReorder = accounts.size >= 2
    // 拖动的手势协程是长按那一刻起的，里面捕获的 ids 会是旧快照；
    // 用 State 兜一层，松手时比较的永远是当前账号列表
    val latestIds = rememberUpdatedState(ids)

    fun startDrag(accountId: Long) {
        val index = order.indexOf(accountId)
        if (index < 0) return
        dragFromIndex = index
        draggingId = accountId
        dragDy = 0f
        tick()
    }

    fun dragBy(deltaY: Float) {
        // slot 每次重算：手势协程是长按那一刻起的，捕获的会是当时的行高
        if (rowHeightPx <= 0) return
        val slot = rowHeightPx + dividerPx
        val id = draggingId ?: return
        dragDy = AccountDrag.clampOffset(dragFromIndex, dragDy + deltaY, slot, order.lastIndex)
        val from = order.indexOf(id)
        if (from < 0) return
        val landing = AccountDrag.landingIndex(dragFromIndex, dragDy, slot, order.lastIndex)
        if (landing != from) {
            order = AccountDrag.move(order, from, landing)
            Haptics.perform(context, HapticFeedback.Tick)
        }
    }

    fun endDrag() {
        val moved = draggingId != null && order != latestIds.value
        val finalOrder = order
        draggingId = null
        dragDy = 0f
        if (moved) onReorderAccounts(finalOrder)
    }

    HyperPage(
        title = "账号",
        subtitle = "两个区可互相同步；同步策略、补齐历史与诊断都在这一页",
        isRefreshing = syncing,
        stopHeadWhileBusy = !copyHealthRunning,
        onRefresh = onSync,
        actions = {
            Text(
                text = if (syncing) "同步中…" else "全部同步",
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已登录",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixSummary,
                    modifier = Modifier.weight(1f),
                )
                if (canReorder) {
                    Text(
                        text = if (draggingId != null) "松手保存顺序" else "长按拖动排序",
                        fontSize = 12.sp,
                        color = if (draggingId != null) MiuixPrimary else MiuixSummary.copy(alpha = 0.7f),
                    )
                }
            }
            MiuixCard {
                orderedAccounts.forEachIndexed { index, account ->
                    // 必须按账号 id 上 key：拖动中行会换位置，没有 key 的话
                    // Compose 按位置复用节点，正在拖的那一行的 pointerInput 会被重启，手一挪就断
                    key(account.id) {
                        val isDragging = draggingId == account.id
                        AccountRow(
                            account = account,
                            isPrimary = account.id == primaryAccountId,
                            alias = aliases[account.id],
                            canDrag = canReorder,
                            dragging = isDragging,
                            // 行被重排后会瞬移到新格子，这里反向补回差值，视觉上一直跟着手指
                            dragTranslationY = if (isDragging) dragDy - (index - dragFromIndex) * slotPx else 0f,
                            onMeasuredHeight = { h -> if (h > 0 && h != rowHeightPx) rowHeightPx = h },
                            onDragStart = { startDrag(account.id) },
                            onDrag = { dragBy(it) },
                            onDragEnd = { endDrag() },
                            onRename = { onRename(account) },
                            onSetPrimary = { onSetPrimary(account.id) },
                            onClick = onOpenAccount,
                        )
                    }
                    if (index != orderedAccounts.lastIndex) MiuixDivider(startIndent = 18)
                }
            }
        }

        item {
            SyncSection(
                message = message,
                onOpenSyncLogs = onOpenSyncLogs,
                onBackfill = onBackfill,
                autoSyncOnOpen = autoSyncOnOpen,
                onAutoSyncChange = onAutoSyncChange,
                syncIntervalHours = syncIntervalHours,
                onSyncIntervalChange = onSyncIntervalChange,
                wifiOnly = wifiOnly,
                onWifiOnlyChange = onWifiOnlyChange,
                backfillDays = backfillDays,
                onBackfillDaysChange = onBackfillDaysChange,
                rateLimitRemainingMs = rateLimitRemainingMs,
                rateLimitStrikes = rateLimitStrikes,
                onRetryAfterRateLimit = onRetryAfterRateLimit,
                onSyncHealth = onSyncHealth,
                onSyncActivities = onSyncActivities,
                activityLimit = activityLimit,
                onActivityLimitChange = onActivityLimitChange,
            )
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

/**
 * 同步小节。
 *
 * 这一整块以前在「设置」页最上方，而它管的全是账号相关的事：什么时候拉、拉多久、
 * 被限流了怎么办、拉失败了看哪儿。放到账号页之后，「账号列表 → 这个账号的同步策略」
 * 是同一屏，不用在两个 tab 之间来回跳。
 */
@Composable
private fun SyncSection(
    message: String?,
    onOpenSyncLogs: () -> Unit,
    onBackfill: () -> Unit,
    autoSyncOnOpen: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    syncIntervalHours: Long,
    onSyncIntervalChange: (Long) -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    backfillDays: Int,
    onBackfillDaysChange: (Int) -> Unit,
    rateLimitRemainingMs: () -> Long,
    rateLimitStrikes: () -> Int,
    onRetryAfterRateLimit: () -> Unit,
    onSyncHealth: () -> Unit,
    onSyncActivities: () -> Unit,
    activityLimit: Int,
    onActivityLimitChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val intervalValues = listOf(6L, 12L, 24L, 0L)
    val intervalIndex = intervalValues.indexOf(syncIntervalHours).let { if (it < 0) 0 else it }
    val backfillValues = listOf(30, 90, 365)
    val backfillIndex = backfillValues.indexOf(backfillDays).let { if (it < 0) 2 else it }
    // 回填是长任务：先要通知权限，再交给前台服务跑
    val ensureNotification = rememberNotificationPermissionGate()
    // 电池优化白名单：跳系统设置回来要重新读一次，否则开关状态是旧的
    var batteryExempt by remember {
        mutableStateOf(BatteryOptimization.isIgnoring(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = BatteryOptimization.isIgnoring(context)
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
    // 这里把证据摆在页面上，用户能直接复制发出来
    var lastFailure by remember { mutableStateOf<GarminDiagnostics.Entry?>(null) }
    // 409「目标账号已有」不是失败：没有真失败时用它当摘要，
    // 否则搬完一次、12 个文件全 409，这里会写着「最近一次失败」吓人
    var dupNote by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            cooldownMs = rateLimitRemainingMs()
            strikes = rateLimitStrikes()
            lastFailure = GarminDiagnostics.latest()
            dupNote = GarminDiagnostics.expectedNote()
            delay(1_000L)
        }
    }

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
        // 健康和活动在 Garmin 是两个接口族：健康按天、活动按条。
        // 分成两个入口之后，只想补跑步记录的人不必再顺带重拉 7 天日汇总。
        MiuixRow(
            title = "同步健康数据",
            summary = "日汇总、睡眠、心率，按天增量；最近 7 天会再核对一次",
            showArrow = true,
            onClick = onSyncHealth,
        )
        MiuixDivider()
        MiuixRow(
            title = "同步运动活动",
            summary = "按条拉取，当前一次 $activityLimit 条；不会动健康数据",
            showArrow = true,
            onClick = onSyncActivities,
        )
        MiuixDivider()
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(text = "活动条数", fontSize = 16.sp, color = MiuiText1)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "20 条 = 1 个请求，100 条 = 5 个请求。只影响手动「同步运动活动」；" +
                    "后台定时同步固定拉最近 20 条，避免在你看不见的时候占配额",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MiuixSummary,
            )
            Spacer(Modifier.height(12.dp))
            MiuixSegmentedControl(
                options = ActivityPaging.OPTIONS.map { "$it 条" },
                selectedIndex = ActivityPaging.OPTIONS.indexOf(activityLimit).coerceAtLeast(0),
                onSelect = { index ->
                    onActivityLimitChange(ActivityPaging.OPTIONS[index])
                    Haptics.perform(context, HapticFeedback.Confirm)
                },
            )
        }
        MiuixDivider()
        MiuixRow(
            title = "同步诊断",
            summary = lastFailure?.let {
                "最近一次失败：HTTP ${it.code} · ${it.endpoint}" +
                    if (it.tokenLength == 0) "（请求没带令牌）" else ""
            } ?: dupNote ?: "暂无失败记录",
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
                        Haptics.perform(context, HapticFeedback.Confirm)
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
                    Haptics.perform(context, HapticFeedback.Confirm)
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
                        Haptics.perform(context, HapticFeedback.Confirm)
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
                "允许后搬运、补齐历史在后台不会被系统降速或冻结"
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
                ({ BatteryOptimization.requestIgnore(context) })
            },
        )
        MiuixDivider()
        MiuixRow(
            title = "补齐历史记录",
            summary = "下载更早的睡眠、步数与运动记录，让图表不再只有最近几天",
            showArrow = true,
            onClick = { ensureNotification(onBackfill) },
        )
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(text = "补多久以前", fontSize = 12.sp, color = MiuixSummary)
            Spacer(Modifier.height(8.dp))
            MiuixSegmentedControl(
                options = listOf("30 天", "90 天", "1 年"),
                selectedIndex = backfillIndex,
                onSelect = { index ->
                    onBackfillDaysChange(backfillValues[index])
                    Haptics.perform(context, HapticFeedback.Confirm)
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "只补本地还缺的部分，已有记录不会重复下载。" +
                    "1 年约需半小时，中途停掉也不白跑，下次点会接着补。",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MiuixSummary,
            )
        }
        MiuixDivider()
        MiuixRow(
            title = "同步记录",
            summary = "每次同步的时间、条数与失败原因",
            showArrow = true,
            onClick = onOpenSyncLogs,
        )
    }

    if (message != null) {
        Text(
            text = message,
            fontSize = 12.sp,
            color = MiuixSummary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
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
                    context,
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
private fun AccountRow(
    account: AccountEntity,
    isPrimary: Boolean,
    alias: String?,
    onRename: () -> Unit,
    onSetPrimary: () -> Unit,
    onClick: () -> Unit = {},
    /** 只有两个账号以上才让拖，一个账号拖动没有意义 */
    canDrag: Boolean = false,
    /** 正在被拖动：浮起来、跟着手指走，且不再响应点击 */
    dragging: Boolean = false,
    dragTranslationY: Float = 0f,
    onMeasuredHeight: (Int) -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val tick = rememberTick()
    // 拖动的 up 与 clickable 的 tap 是同一个手指抬起：长按晃一下再松手，
    // clickable 那边会当成一次点击跳进账号详情。用一个短时间窗把这一次吃掉。
    var suppressClickUntil by remember { mutableStateOf(0L) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // zIndex + 平移要在最外层：放到背景/阴影后面的话，浮起来的行会被卡片圆角裁掉
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = dragTranslationY }
            .then(
                if (dragging) {
                    Modifier
                        .shadow(10.dp, RoundedCornerShape(12.dp))
                        .background(MiuixCardSurface, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .onSizeChanged { onMeasuredHeight(it.height) }
            .pointerInput(account.id, canDrag) {
                if (!canDrag) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        suppressClickUntil = System.currentTimeMillis() + 250
                        onDragStart()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.y)
                    },
                    onDragEnd = {
                        suppressClickUntil = System.currentTimeMillis() + 250
                        onDragEnd()
                    },
                    onDragCancel = {
                        suppressClickUntil = System.currentTimeMillis() + 250
                        onDragEnd()
                    },
                )
            }
            .clickable(enabled = !dragging) {
                if (System.currentTimeMillis() < suppressClickUntil) return@clickable
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
                text = (alias ?: accountTitle(account.displayName, account.region)),
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
                            append(TimeFmt.clockShort(account.lastSyncAtMs))
                        }
                    },
                    fontSize = 12.sp,
                    color = MiuixSummary,
                    maxLines = 1,
                )
            }
        }
        if (dragging) {
            // 拖起来的时候把行尾两个按钮换成纯把手：此刻唯一有意义的事是决定落点
            DragHandle(active = true)
        } else {
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
}

/**
 * 拖动把手：两列小圆点，六个点就是「可以抓」的通用符号。
 *
 * 自己画而不是引图标库：整个 App 的图标都是自绘或 emoji，这里没必要为一个把手加依赖。
 */
@Composable
private fun DragHandle(active: Boolean) {
    val dot = if (active) MiuixPrimary else MiuixSummary.copy(alpha = 0.45f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(3.dp).clip(CircleShape).background(dot))
                Box(Modifier.size(3.dp).clip(CircleShape).background(dot))
            }
        }
    }
}
