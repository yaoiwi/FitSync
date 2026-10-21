package com.fitgarminsync.feature.detail

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitgarminsync.data.db.AccountEntity
import com.fitgarminsync.data.db.ActivityEntity
import com.fitgarminsync.feature.home.HomeViewModel
import com.fitgarminsync.ui.components.GlassHost
import com.fitgarminsync.ui.components.HyperPage
import com.fitgarminsync.ui.components.MiuixCard
import com.fitgarminsync.ui.components.MiuixDivider
import com.fitgarminsync.ui.components.MiuixPrimaryButton
import com.fitgarminsync.ui.components.MiuixRow
import com.fitgarminsync.ui.components.MiuixSectionTitle
import com.fitgarminsync.ui.components.MiuixTextButton
import com.fitgarminsync.ui.components.formatDurationSeconds
import com.fitgarminsync.ui.components.regionLabel
import com.fitgarminsync.ui.components.sportGlyph
import com.fitgarminsync.ui.components.sportLabel
import com.fitgarminsync.ui.components.statusColor
import com.fitgarminsync.ui.components.statusLabel
import com.fitgarminsync.ui.theme.FitGarminSyncTheme
import com.fitgarminsync.ui.theme.MiuiText1
import com.fitgarminsync.ui.theme.MiuixCardSurface
import com.fitgarminsync.ui.theme.MiuixPrimary
import com.fitgarminsync.ui.theme.MiuixSummary
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

    FitGarminSyncTheme {
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
                                    ?: account.displayName.ifBlank { account.externalUserId },
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

    FitGarminSyncTheme {
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
                                            ?: account.displayName.ifBlank { account.externalUserId },
                                        fontSize = 15.sp,
                                        color = MiuixSummary,
                                    )
                                },
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
                                showArrow = true,
                                onClick = { },
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
                            onClick = { },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
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
) {
    val dashboard by viewModel.dashboard.collectAsState()
    val activity = dashboard.activities.firstOrNull { it.id == activityId }

    FitGarminSyncTheme {
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
                            DetailLine("类型代码", activity.activityType.ifBlank { activity.sport })
                            MiuixDivider()
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

private fun paceText(activity: ActivityEntity): String {
    if (activity.distanceM <= 0 || activity.durationSec <= 0) return "—"
    val metersPerSecond = activity.distanceM / activity.durationSec
    if (metersPerSecond <= 0) return "—"
    val secondsPerKm = 1000.0 / metersPerSecond
    val m = (secondsPerKm / 60).toInt()
    val s = (secondsPerKm % 60).toInt()
    return "%d'%02d\"/km".format(m, s)
}
