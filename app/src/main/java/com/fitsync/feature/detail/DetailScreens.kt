package com.fitsync.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.ActivityLapEntity
import com.fitsync.data.db.ActivityEntity
import com.fitsync.feature.home.HomeViewModel
import com.fitsync.ui.components.accountTitle
import com.fitsync.ui.components.GlassHost
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixConfirmDialog
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixInputDialog
import com.fitsync.ui.components.MiuixPrimaryButton
import com.fitsync.ui.components.MiuixRow
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.components.MiuixTextButton
import com.fitsync.ui.components.formatDurationSeconds
import com.fitsync.ui.components.regionLabel
import com.fitsync.ui.components.sportGlyph
import com.fitsync.ui.components.sportLabel
import com.fitsync.ui.components.statusColor
import com.fitsync.ui.components.statusLabel
import com.fitsync.ui.theme.FitSyncTheme
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixCardSurface
import com.fitsync.ui.theme.MiuixError
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账号 / 活动的详情页面。
 *
 * 统一走 HyperPage（官方 Scaffold + 大标题折叠），与首页观感一致。
 */

// ============================ 账号列表 ============================

@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onAccountClick: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsState()
    val aliases by viewModel.aliases.collectAsState()
    val primaryAccountId by viewModel.primaryAccountId.collectAsState()

    FitSyncTheme {
        GlassHost(modifier = Modifier.fillMaxSize()) {
            HyperPage(
                title = "已连接账号",
                subtitle = "共 ${accounts.size} 个 · 点击查看单个账号",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            ) {
                item {
                    MiuixSectionTitle("全部账号")
                    MiuixCard {
                        accounts.forEachIndexed { index, account ->
                            MiuixRow(
                                title = aliases[account.id]
                                    ?: accountTitle(account.displayName, account.region),
                                summary = buildString {
                                    append(regionLabel(account.region))
                                    append(" · ")
                                    append(statusLabel(account.status))
                                    if (account.id == primaryAccountId) append(" · 主账号")
                                },
                                leading = { RegionBadge(account.region) },
                                showArrow = true,
                                onClick = { onAccountClick(account.id) },
                            )
                            if (index != accounts.lastIndex) MiuixDivider()
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// ============================ 账号详情 ============================

@Composable
fun AccountDetailScreen(
    accountId: Long,
    onBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsState()
    val aliases by viewModel.aliases.collectAsState()
    val primaryAccountId by viewModel.primaryAccountId.collectAsState()
    val account = accounts.firstOrNull { it.id == accountId }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    FitSyncTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            GlassHost(modifier = Modifier.fillMaxSize()) {
                HyperPage(
                    title = "账号详情",
                    subtitle = account?.let { regionLabel(it.region) } ?: "账号不存在",
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                ) {
                    if (account == null) {
                        item {
                            MiuixCard {
                                Text(
                                    text = "找不到这个账号，可能已被移除。",
                                    fontSize = 14.sp,
                                    color = MiuixSummary,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                                )
                            }
                        }
                    } else {
                    item {
                        MiuixSectionTitle("基本信息")
                        MiuixCard {
                            MiuixRow(
                                title = "昵称",
                                summary = "点击可修改显示名",
                                trailing = {
                                    Text(
                                        text = aliases[account.id]
                                            ?: accountTitle(account.displayName, account.region),
                                        fontSize = 15.sp,
                                        color = MiuixSummary,
                                    )
                                },
                                showArrow = true,
                                onClick = { showRename = true },
                            )
                            MiuixDivider()
                            DetailLine("数据源", if (account.sourceType == "garmin") "Garmin Connect" else account.sourceType)
                            MiuixDivider()
                            DetailLine("区域", regionLabel(account.region))
                            MiuixDivider()
                            DetailLine("用户 ID", account.externalUserId)
                            if (account.deviceName.isNotBlank()) {
                                MiuixDivider()
                                DetailLine("设备", account.deviceName)
                            }
                        }
                    }

                    item {
                        MiuixSectionTitle("同步状态")
                        MiuixCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor(account.status)),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(statusLabel(account.status), fontSize = 15.sp, color = MiuiText1)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = if (account.lastSyncAtMs > 0) {
                                            "上次同步 " + SimpleDateFormat(
                                                "yyyy-MM-dd HH:mm",
                                                Locale.CHINA,
                                            ).format(Date(account.lastSyncAtMs))
                                        } else {
                                            "还没有同步过"
                                        },
                                        fontSize = 12.sp,
                                        color = MiuixSummary,
                                    )
                                }
                            }
                            MiuixDivider()
                            DetailLine(
                                "凭据有效期",
                                if (account.tokenExpiresAtMs > 0) {
                                    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(
                                        Date(account.tokenExpiresAtMs),
                                    )
                                } else {
                                    "—"
                                },
                            )
                        }
                    }

                    item {
                        MiuixSectionTitle("操作")
                        MiuixCard {
                            if (account.id != primaryAccountId) {
                                MiuixRow(
                                    title = "设为主账号",
                                    summary = "首页与今日数据以该账号为准",
                                    showArrow = true,
                                    onClick = { viewModel.setPrimaryAccount(account.id) },
                                )
                                MiuixDivider()
                            }
                            MiuixRow(
                                title = "修改昵称",
                                summary = "只改本应用显示，不影响同步",
                                showArrow = true,
                                onClick = { showRename = true },
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(8.dp))
                        MiuixPrimaryButton(
                            text = "同步这个账号",
                            onClick = viewModel::syncNow,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        MiuixTextButton(
                            text = "移除账号",
                            onClick = { showDelete = true },
                            color = MiuixError,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
            }

            account?.let { acc ->
                val customAlias = aliases[acc.id]
                if (showRename) {
                    MiuixInputDialog(
                        title = "修改昵称",
                        initialValue = customAlias ?: "",
                        message = "只改本应用的显示名，不会动 Garmin 账号。Garmin 原昵称："
                            + accountTitle(acc.displayName, acc.region),
                        placeholder = accountTitle(acc.displayName, acc.region),
                        confirmText = "保存",
                        neutralText = if (!customAlias.isNullOrBlank()) "恢复默认" else null,
                        onNeutral = {
                            viewModel.renameAccount(acc.id, "")
                            showRename = false
                        },
                        onConfirm = { value ->
                            viewModel.renameAccount(acc.id, value.trim())
                            showRename = false
                        },
                        onDismiss = { showRename = false },
                    )
                }
                if (showDelete) {
                    MiuixConfirmDialog(
                        title = "移除账号",
                        message = "会从本应用删除「${customAlias ?: accountTitle(acc.displayName, acc.region)}」"
                            + "及其在本机同步的缓存。Garmin 账号本身不受影响，之后可以重新添加。",
                        confirmText = "移除",
                        destructive = true,
                        onConfirm = {
                            viewModel.deleteAccount(acc.id)
                            showDelete = false
                            onBack()
                        },
                        onDismiss = { showDelete = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = MiuiText1, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 14.sp, color = MiuixSummary)
    }
}

@Composable
private fun RegionBadge(region: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MiuixPrimary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (region == "cn") "CN" else "GL",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixPrimary,
        )
    }
}

// ============================ 活动详情 ============================

@Composable
fun ActivityDetailScreen(
    activityId: Long,
    onBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    detailViewModel: ActivityDetailViewModel = hiltViewModel(),
) {
    val dashboard by viewModel.dashboard.collectAsState()
    val activity = dashboard.activities.firstOrNull { it.id == activityId }
    val samples by detailViewModel.samples.collectAsState()
    val routes by detailViewModel.routes.collectAsState()
    val laps by detailViewModel.laps.collectAsState()
    val shareContext = LocalContext.current
    val loadingSamples by detailViewModel.loading.collectAsState()
    val sampleNote by detailViewModel.note.collectAsState()

    FitSyncTheme {
        GlassHost(modifier = Modifier.fillMaxSize()) {
            HyperPage(
                title = activity?.let { sportLabel(it.sport) } ?: "活动详情",
                subtitle = activity?.let {
                    SimpleDateFormat("M 月 d 日 HH:mm", Locale.CHINA).format(Date(it.startTimeMs))
                } ?: "活动不存在",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    if (activity != null) {
                        Text(
                            text = "分享",
                            fontSize = 15.sp,
                            color = MiuixPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { ShareCard.share(shareContext, activity) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                },
            ) {
                if (activity == null) {
                    item {
                        MiuixCard {
                            Text(
                                text = "找不到这条活动记录。",
                                fontSize = 14.sp,
                                color = MiuixSummary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                            )
                        }
                    }
                } else {
                    item { ActivityHero(activity) }

                    // 采样点是进页面时按需下载的：给出进度与失败原因，别让空白图表自己说话
                    if (loadingSamples) {
                        item {
                            MiuixCard {
                                Text(
                                    text = "正在获取原始数据…",
                                    fontSize = 13.sp,
                                    color = MiuixSummary,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                )
                            }
                        }
                    } else if (sampleNote != null) {
                        item {
                            MiuixCard {
                                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                                    Text(
                                        text = sampleNote ?: "",
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        color = MiuixSummary,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    MiuixTextButton(
                                        text = "重试",
                                        onClick = { detailViewModel.retry() },
                                    )
                                }
                            }
                        }
                    }

                    // 有 FIT 数据才显示轨迹与曲线（旧数据来自摘要接口，没有采样点）
                    if (routes.isNotEmpty()) {
                        item {
                            MiuixSectionTitle("轨迹")
                            MiuixCard {
                                RouteTrack(
                                    routes = routes,
                                    color = MiuixPrimary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .padding(12.dp),
                                )
                            }
                        }
                    }

                    val hrValues = samples.map { it.hr.toFloat() }.filter { it > 0f }
                    if (hrValues.size >= 2) {
                        item {
                            ChartCard(
                                title = "心率",
                                range = "${hrValues.minOrNull()?.toInt()} – ${hrValues.maxOrNull()?.toInt()} bpm",
                                values = hrValues,
                                color = Color(0xFFFF4F4F),
                            )
                        }
                    }

                    // 配速优先用采样表里的瞬时速度；老数据（FIT 导入前的轨迹）退回 route 里的速度
                    val sampleSpeeds = samples.map { it.speedMs.toFloat() }.filter { it > 0.3f }
                    val speedValues = sampleSpeeds.ifEmpty {
                        routes.map { it.speedMs.toFloat() }.filter { it > 0.3f }
                    }
                    if (speedValues.size >= 2) {
                        item {
                            ChartCard(
                                title = "速度",
                                range = "%.1f – %.1f km/h".format(
                                    (speedValues.minOrNull() ?: 0f) * 3.6f,
                                    (speedValues.maxOrNull() ?: 0f) * 3.6f,
                                ),
                                values = speedValues,
                                color = Color(0xFF3482FF),
                            )
                        }
                    }

                    if (hrValues.size >= 20) {
                        item {
                            MiuixSectionTitle("心率区间")
                            MiuixCard {
                                HrZoneBlock(hrValues, activity.maxHr)
                            }
                        }
                    }

                    val elevValues = samples.map { it.elevM.toFloat() }.filter { it != 0f }
                    if (elevValues.size >= 2) {
                        item {
                            ChartCard(
                                title = "海拔",
                                range = "%.0f – %.0f m".format(
                                    elevValues.minOrNull() ?: 0f,
                                    elevValues.maxOrNull() ?: 0f,
                                ),
                                values = elevValues,
                                color = Color(0xFF34C759),
                            )
                        }
                    }

                    val cadenceValues = samples.map { it.cadence.toFloat() }.filter { it > 0f }
                    if (cadenceValues.size >= 2) {
                        item {
                            ChartCard(
                                title = "步频",
                                range = "%.0f – %.0f 步/分".format(
                                    cadenceValues.minOrNull() ?: 0f,
                                    cadenceValues.maxOrNull() ?: 0f,
                                ),
                                values = cadenceValues,
                                color = Color(0xFF34C759),
                            )
                        }
                    }

                    val powerValues = samples.map { it.powerW.toFloat() }.filter { it > 0f }
                    if (powerValues.size >= 2) {
                        item {
                            ChartCard(
                                title = "功率",
                                range = "%.0f – %.0f W".format(
                                    powerValues.minOrNull() ?: 0f,
                                    powerValues.maxOrNull() ?: 0f,
                                ),
                                values = powerValues,
                                color = Color(0xFFFF9F0A),
                            )
                        }
                    }

                    if (laps.isNotEmpty()) {
                        item {
                            MiuixSectionTitle("分段")
                            MiuixCard {
                                val shown = laps.take(12)
                                shown.forEachIndexed { index, lap ->
                                    LapRow(lap)
                                    if (index != shown.lastIndex) MiuixDivider()
                                }
                                if (laps.size > shown.size) {
                                    Text(
                                        text = "共 ${laps.size} 段，这里显示前 ${shown.size} 段",
                                        fontSize = 12.sp,
                                        color = MiuixSummary,
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }

                    item {
                        MiuixSectionTitle("运动数据")
                        MiuixCard {
                            DetailLine("时长", formatDurationSeconds(activity.durationSec))
                            MiuixDivider()
                            DetailLine(
                                "距离",
                                if (activity.distanceM > 0) "%.2f km".format(activity.distanceM / 1000) else "—",
                            )
                            MiuixDivider()
                            DetailLine("平均配速", paceText(activity))
                            MiuixDivider()
                            DetailLine(
                                "消耗",
                                if (activity.calories > 0) "%.0f kcal".format(activity.calories) else "—",
                            )
                            MiuixDivider()
                            DetailLine(
                                "爬升",
                                if (activity.elevationGainM > 0) "%.0f m".format(activity.elevationGainM) else "—",
                            )
                        }
                    }

                    item {
                        MiuixSectionTitle("心率")
                        MiuixCard {
                            DetailLine("平均心率", if (activity.avgHr > 0) "${activity.avgHr} bpm" else "—")
                            MiuixDivider()
                            DetailLine("最高心率", if (activity.maxHr > 0) "${activity.maxHr} bpm" else "—")
                            if (activity.restingHr > 0) {
                                MiuixDivider()
                                DetailLine("静息心率", "${activity.restingHr} bpm")
                            }
                        }
                    }

                    item {
                        MiuixSectionTitle("记录信息")
                        MiuixCard {
                            // 原始 typeKey 只在和中文名不同时才展示，避免「跑步 / running」看起来像两条数据
                            val rawType = activity.activityType.ifBlank { activity.sport }
                            if (rawType.isNotBlank() && sportLabel(rawType) != rawType) {
                                DetailLine("原始类型", rawType)
                                MiuixDivider()
                            }
                            DetailLine("GPS 轨迹", if (activity.hasGps) "有" else "无")
                            MiuixDivider()
                            DetailLine("分段数据", if (activity.hasSplits) "有" else "无")
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ActivityHero(activity: ActivityEntity) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixCardSurface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixPrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(sportGlyph(activity.sport), fontSize = 24.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sportLabel(activity.sport),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuiText1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(
                        Date(activity.startTimeMs),
                    ),
                    fontSize = 12.5.sp,
                    color = MiuixSummary,
                )
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    range: String,
    values: List<Float>,
    color: Color,
) {
    MiuixSectionTitle(title)
    MiuixCard {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, color = MiuiText1)
                Spacer(Modifier.weight(1f))
                Text(range, fontSize = 12.sp, color = MiuixSummary)
            }
            Spacer(Modifier.height(10.dp))
            Sparkline(
                values = values,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
        }
    }
}

private fun paceText(activity: ActivityEntity): String {
    if (activity.distanceM <= 0 || activity.durationSec <= 0) return "—"
    val metersPerSecond = activity.distanceM / activity.durationSec
    if (metersPerSecond <= 0) return "—"
    val secondsPerKm = 1000.0 / metersPerSecond
    val m = (secondsPerKm / 60).toInt()
    val s = (secondsPerKm % 60).toInt()
    return "%d'%02d\"/km".format(m, s)
}

// ============================ 分段与心率区间 ============================

@Composable
private fun LapRow(lap: ActivityLapEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${lap.lap}",
            fontSize = 13.sp,
            color = MiuixSummary,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = if (lap.distanceM > 0) "%.2f km".format(lap.distanceM / 1000) else "—",
            fontSize = 14.sp,
            color = MiuiText1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = lapPace(lap.durationSec, lap.distanceM),
            fontSize = 13.sp,
            color = MiuixPrimary,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = if (lap.avgHr > 0) "${lap.avgHr} bpm" else "—",
            fontSize = 13.sp,
            color = MiuixSummary,
            modifier = Modifier.width(66.dp),
        )
    }
}

private fun lapPace(durationSec: Long, distanceM: Double): String {
    if (distanceM <= 1.0 || durationSec <= 0) return "—"
    val secPerKm = durationSec / (distanceM / 1000.0)
    return "%d'%02d\"".format((secPerKm / 60).toInt(), (secPerKm % 60).toInt())
}

/**
 * 心率区间分布。
 *
 * 用样本数量近似时长（FIT 采样间隔基本固定），最大心率优先取这条活动记录到的值；
 * 没有记录就用样本峰值，至少保证比例是自洽的。
 */
@Composable
private fun HrZoneBlock(values: List<Float>, recordedMax: Int) {
    val maxHr = if (recordedMax > 0) recordedMax.toFloat() else (values.maxOrNull() ?: 0f)
    if (maxHr <= 0f) return

    val counts = IntArray(5)
    values.forEach { hr ->
        val ratio = hr / maxHr
        val index = when {
            ratio < 0.6f -> 0
            ratio < 0.7f -> 1
            ratio < 0.8f -> 2
            ratio < 0.9f -> 3
            else -> 4
        }
        counts[index]++
    }
    val total = counts.sum().coerceAtLeast(1)

    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            counts.forEachIndexed { index, count ->
                if (count > 0) {
                    Box(
                        modifier = Modifier
                            .weight(count.toFloat())
                            .height(12.dp)
                            .background(zoneColor(index)),
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Z1", "Z2", "Z3", "Z4", "Z5").forEachIndexed { index, label ->
                val pct = counts[index] * 100 / total
                if (pct > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(zoneColor(index)),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "$label $pct%",
                            fontSize = 10.5.sp,
                            color = MiuixSummary,
                        )
                    }
                }
            }
        }
    }
}

private fun zoneColor(index: Int): Color = when (index) {
    0 -> Color(0xFF8FB8FF)
    1 -> Color(0xFF4F9BFF)
    2 -> Color(0xFF34C759)
    3 -> Color(0xFFFF9F0A)
    else -> Color(0xFFFF4F4F)
}
