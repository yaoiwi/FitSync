package com.fitsync.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitsync.ui.components.HyperPage
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.formatDuration
import com.fitsync.ui.components.rememberTick
import com.fitsync.ui.components.MiuixLeadingIcon
import com.fitsync.ui.components.MiuixRow
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixAction
import com.fitsync.ui.theme.MiuixError
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary
import com.fitsync.ui.components.MiuixSegmentedControl

// ============================ 概览 ============================

@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun OverviewTab(
    dashboard: HomeViewModel.Dashboard,
    trends: HomeViewModel.Trends,
    sleepStages: List<com.fitsync.data.db.SleepStageEntity>,
    syncing: Boolean,
    silent: Boolean = false,
    /** 搬运健康数据在跑：这期间用细线，列表保持可滑 */
    copyHealthRunning: Boolean = false,
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
        subtitle = TimeFmt.dayTitle(System.currentTimeMillis()),
        // 自动同步不转圈：页面一打开就转十几秒，很像卡死
        isRefreshing = syncing && !silent,
        // 只有搬运健康数据用细线；普通同步、回填、导入写回都用刷新头停住
        stopHeadWhileBusy = !copyHealthRunning,
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
