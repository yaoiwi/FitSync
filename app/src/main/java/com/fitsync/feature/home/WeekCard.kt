package com.fitsync.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.Text

/**
 * 本周 vs 上周。
 *
 * 只对比四项能直接读懂的数：步数、日均睡眠、运动距离、活动次数。
 * 上周还没有数据时环比显示「—」，不拿 0 当基数算出 +100%。
 */
@Composable
fun WeekCard(
    trends: HomeViewModel.Trends,
    modifier: Modifier = Modifier,
) {
    val week = trends.week
    val cur = week.current
    val prev = week.previous

    MiuixCard(modifier) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "本周",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuiText1,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "对比上周", fontSize = 12.sp, color = MiuixSummary)
            }
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                WeekMetric(
                    label = "步数",
                    value = if (cur.steps > 0) "%,d".format(cur.steps) else "—",
                    delta = percentDelta(cur.steps.toDouble(), prev.steps.toDouble()),
                    modifier = Modifier.weight(1f),
                )
                WeekMetric(
                    label = "日均睡眠",
                    value = if (cur.sleepMinutes > 0) "${cur.sleepMinutes / 60}h${cur.sleepMinutes % 60}m" else "—",
                    delta = percentDelta(cur.sleepMinutes.toDouble(), prev.sleepMinutes.toDouble()),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                WeekMetric(
                    label = "运动距离",
                    value = if (cur.distanceKm > 0) "%.1f km".format(cur.distanceKm) else "—",
                    delta = percentDelta(cur.distanceKm, prev.distanceKm),
                    modifier = Modifier.weight(1f),
                )
                WeekMetric(
                    label = "活动次数",
                    value = if (cur.activities > 0) "${cur.activities} 次" else "—",
                    delta = percentDelta(cur.activities.toDouble(), prev.activities.toDouble()),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WeekMetric(
    label: String,
    value: String,
    delta: Int?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = label, fontSize = 12.sp, color = MiuixSummary)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuiText1,
            )
            if (delta != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (delta >= 0) "+$delta%" else "$delta%",
                    fontSize = 11.sp,
                    color = when {
                        delta > 0 -> Color(0xFF34C759)
                        delta < 0 -> Color(0xFFFF9F0A)
                        else -> MiuixSummary
                    },
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/**
 * 环比：上周为 0 或本周本来就是 0 时返回 null。
 *
 * 本周没数据时数值显示「—」，再挂一个 -100% 会让人以为"暴跌"，
 * 其实只是这周还没运动。
 */
private fun percentDelta(current: Double, previous: Double): Int? {
    if (current <= 0.0 || previous <= 0.0) return null
    return ((current - previous) / previous * 100).toInt()
}
