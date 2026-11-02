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
        subtitle = "两个区可互相同步，主账号决定首页以谁为准",
        isRefreshing = syncing,
        stopHeadWhileBusy = !copyHealthRunning,
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
