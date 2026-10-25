package com.fitsync.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitsync.feature.detail.Sparkline
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixSegmentedControl
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.Text

/**
 * 概览页的趋势卡：近 7 / 30 天的四组变化。
 *
 * 只画折线不画坐标轴——这里要的是"在变好还是变差"，精确数值看详情页。
 * 缺数据的天直接跳过（filter > 0），否则折线会掉到 0 再弹回来，像心电图的故障。
 */
@Composable
fun TrendCard(
    trends: HomeViewModel.Trends,
    modifier: Modifier = Modifier,
) {
    var range by remember { mutableStateOf(7) }
    val days = remember(trends, range) { trends.days.takeLast(range) }

    MiuixCard(modifier) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "趋势",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuiText1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (range == 7) "最近 7 天" else "最近 30 天",
                    fontSize = 12.sp,
                    color = MiuixSummary,
                )
            }
            Spacer(Modifier.height(14.dp))

            if (!trends.hasData) {
                Text(
                    text = "同步几天之后，这里会显示步数、心率、睡眠和运动量的变化。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MiuixSummary,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    MetricBlock(
                        label = "步数",
                        valueText = averageText(days.map { it.steps }) { "%,d".format(it.toInt()) } + " 步",
                        values = days.map { it.steps.toFloat() }.filter { it > 0f },
                        color = Color(0xFF3482FF),
                        modifier = Modifier.weight(1f),
                    )
                    MetricBlock(
                        label = "静息心率",
                        valueText = averageText(days.map { it.restingHr }) { "${it.toInt()}" } + " bpm",
                        values = days.map { it.restingHr.toFloat() }.filter { it > 0f },
                        color = Color(0xFFFF4F4F),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    MetricBlock(
                        label = "睡眠",
                        valueText = sleepText(days.map { it.sleepMinutes }),
                        values = days.map { it.sleepMinutes.toFloat() }.filter { it > 0f },
                        color = Color(0xFF8E7BFF),
                        modifier = Modifier.weight(1f),
                    )
                    MetricBlock(
                        label = "运动距离",
                        valueText = averageText(days.map { it.distanceKm }) { "%.1f".format(it) } + " km",
                        values = days.map { it.distanceKm.toFloat() }.filter { it > 0f },
                        color = Color(0xFF34C759),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            MiuixSegmentedControl(
                options = listOf("7 天", "30 天"),
                selectedIndex = if (range == 7) 0 else 1,
                onSelect = { range = if (it == 0) 7 else 30 },
            )
        }
    }
}

@Composable
private fun MetricBlock(
    label: String,
    valueText: String,
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = label, fontSize = 12.sp, color = MiuixSummary)
        Spacer(Modifier.height(3.dp))
        Text(
            text = valueText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuiText1,
        )
        Spacer(Modifier.height(6.dp))
        if (values.size >= 2) {
            Sparkline(
                values = values,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(text = "数据不足", fontSize = 11.sp, color = MiuixSummary)
            }
        }
    }
}

/** 只对有效值求平均；没有有效值就给个破折号，别显示 0 误导用户 */
private fun averageText(values: List<Number>, format: (Double) -> String): String {
    val valid = values.map { it.toDouble() }.filter { it > 0.0 }
    if (valid.isEmpty()) return "—"
    return format(valid.average())
}

private fun sleepText(values: List<Int>): String {
    val valid = values.filter { it > 0 }
    if (valid.isEmpty()) return "—"
    val avg = valid.average().toInt()
    return "${avg / 60} 小时 ${avg % 60} 分"
}
