package com.fitsync.feature.detail

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fitsync.data.health.CopyHealthReport
import com.fitsync.feature.home.HomeViewModel
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.components.MiuixSegmentedControl
import com.fitsync.ui.theme.MiuiGreen
import com.fitsync.ui.theme.MiuiOrange
import com.fitsync.ui.theme.MiuiRed
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuixSummary
import java.time.LocalDate
import java.time.format.DateTimeParseException
import top.yukonga.miuix.kmp.basic.Text

/**
 * 搬运体检：按天列出「哪天数据齐了、哪天还缺睡眠、为什么缺」。
 *
 * 搬运页的结果只有一行汇总，用户看不出具体缺口。这页把台账、本地日汇总、
 * 本地睡眠、14 天等待窗口拼在一起，一天一行，直接回答「我到底搬全了没有」。
 *
 * 只读，不发任何请求；打开时读一次本地库和台账。
 */
@Composable
fun CopyHealthReportScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsState()
    val primaryAccountId by viewModel.primaryAccountId.collectAsState()
    val reportUi by viewModel.copyReport.collectAsState()

    val garminAccounts = accounts.filter { it.sourceType == "garmin" }
    val source = garminAccounts.firstOrNull { it.id == primaryAccountId } ?: garminAccounts.firstOrNull()
    val target = garminAccounts.firstOrNull { it.id != source?.id }

    // 体检只看最近，最多 90 天：再往前的问题不是「漏了一天」，而是整段没搬
    val spans = remember { listOf(7, 30, 90) }
    var spanIndex by remember { mutableIntStateOf(1) }
    val span = spans[spanIndex]

    LaunchedEffect(source?.id, target?.id, span) {
        viewModel.loadCopyReport(source?.id, target?.id, span)
    }

    CopyHealthReportContent(
        targetName = target?.displayName,
        spans = spans,
        spanIndex = spanIndex,
        onSelectSpan = { spanIndex = it },
        report = reportUi.report,
        loading = reportUi.loading,
        error = reportUi.error,
        onBack = onBack,
    )
}

/**
 * 纯展示层：不依赖 ViewModel，方便用 Robolectric 直接渲染各种状态。
 */
@Composable
internal fun CopyHealthReportContent(
    targetName: String?,
    spans: List<Int>,
    spanIndex: Int,
    onSelectSpan: (Int) -> Unit,
    report: CopyHealthReport.Summary?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
) {
    val span = spans[spanIndex]

    DataActionPage(
        title = "搬运体检",
        subtitle = if (targetName != null) {
            "$targetName 的最近 $span 天"
        } else {
            "按天核对健康数据是否到位"
        },
        onBack = onBack,
    ) {
        if (error != null) {
            item {
                MiuixCard {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Text("读不出体检结果", fontSize = 16.sp, color = MiuiText1)
                        Spacer(Modifier.height(6.dp))
                        Text(error, fontSize = 12.sp, lineHeight = 18.sp, color = MiuixSummary)
                    }
                }
            }
        }

        item {
            MiuixSectionTitle("检查范围")
            MiuixCard {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    MiuixSegmentedControl(
                        options = spans.map { "$it 天" },
                        selectedIndex = spanIndex,
                        onSelect = onSelectSpan,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "只读本地记录，不发请求。这里看到的是「已经确认躺在${targetName ?: "目标账号"}上的数据」，不是 Garmin 云端此刻的状态。",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = MiuixSummary.copy(alpha = 0.9f),
                    )
                }
            }
        }

        if (report != null) {
            item {
                MiuixSectionTitle("结论")
                MiuixCard {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Text(
                            text = report.headline,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 21.sp,
                            color = MiuiText1,
                        )
                        Spacer(Modifier.height(10.dp))
                        StatRow(
                            items = listOf(
                                "有睡眠" to report.complete,
                                "等补传" to report.waitingSleep,
                                "空包" to report.emptyPackage,
                                "已定稿" to report.stopped,
                            ),
                            total = report.days.size,
                        )
                        if (report.localOnly > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "另有 ${report.localOnly} 天目标账号本地本来就有数据，不需要搬",
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                color = MiuixSummary,
                            )
                        }
                    }
                }
            }

            item {
                MiuixSectionTitle("逐日明细")
                MiuixCard {
                    report.days.forEachIndexed { index, day ->
                        if (index > 0) MiuixDivider(startIndent = 18)
                        DayRow(day = day)
                    }
                }
            }

            item {
                MiuixSectionTitle("这些状态是什么意思")
                MiuixCard {
                    LegendRow(MiuiGreen, "有睡眠", "台账里这天有 sleep 文件，数据是齐的")
                    MiuixDivider(startIndent = 18)
                    LegendRow(
                        MiuiOrange,
                        "等补传",
                        "只有步数/压力，还没看到睡眠。下次搬运会重新核对，最多盯 14 天——手表放抽屉里一周再连手机也接得住",
                    )
                    MiuixDivider(startIndent = 18)
                    LegendRow(
                        MiuixSummary,
                        "空包",
                        "这天的包只有几百字节，是没戴表、只连了一下手机的日子，本来就没什么可搬",
                    )
                    MiuixDivider(startIndent = 18)
                    LegendRow(
                        MiuiRed,
                        "已定稿",
                        "超过 14 天仍然没有睡眠，搬运不再复查。多半是那天真的没戴表睡，源账号本来就没有",
                    )
                    MiuixDivider(startIndent = 18)
                    LegendRow(
                        MiuixSummary,
                        "没搬过",
                        "台账里没有这天，目标账号本地也没有。下次点搬运会处理",
                    )
                }
            }
        } else if (loading) {
            item {
                MiuixCard {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
                        Text("正在核对…", fontSize = 13.sp, color = MiuixSummary)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(items: List<Pair<String, Int>>, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, value) ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (total > 0) "$value" else "–",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuiText1,
                )
                Text(label, fontSize = 11.sp, color = MiuixSummary)
            }
        }
    }
}

@Composable
private fun DayRow(day: CopyHealthReport.Day) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor(day.kind)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateLabel(day.date),
                    fontSize = 14.sp,
                    color = MiuiText1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusLabel(day.kind),
                    fontSize = 11.sp,
                    color = statusColor(day.kind),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = detailOf(day),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MiuixSummary,
            )
        }
    }
}

@Composable
private fun LegendRow(color: Color, title: String, text: String) {
    Row(modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.sp, color = MiuiText1)
            Spacer(Modifier.height(2.dp))
            Text(text, fontSize = 11.5.sp, lineHeight = 16.sp, color = MiuixSummary)
        }
    }
}

@Composable
private fun statusColor(kind: CopyHealthReport.Kind) = when (kind) {
    CopyHealthReport.Kind.COMPLETE -> MiuiGreen
    CopyHealthReport.Kind.WAITING_SLEEP, CopyHealthReport.Kind.EMPTY_PACKAGE -> MiuiOrange
    CopyHealthReport.Kind.STOPPED -> MiuiRed
    CopyHealthReport.Kind.LOCAL_ONLY, CopyHealthReport.Kind.NOT_COPIED -> MiuixSummary
}

private fun statusLabel(kind: CopyHealthReport.Kind): String = when (kind) {
    CopyHealthReport.Kind.COMPLETE -> "有睡眠"
    CopyHealthReport.Kind.WAITING_SLEEP -> "等补传"
    CopyHealthReport.Kind.STOPPED -> "已定稿"
    CopyHealthReport.Kind.EMPTY_PACKAGE -> "空包"
    CopyHealthReport.Kind.LOCAL_ONLY -> "本地已有"
    CopyHealthReport.Kind.NOT_COPIED -> "没搬过"
}

private fun detailOf(day: CopyHealthReport.Day): String = when (day.kind) {
    CopyHealthReport.Kind.COMPLETE ->
        if (day.fileCount > 0) "${day.fileCount} 个文件都在，睡眠已确认" else "已确认搬完"
    CopyHealthReport.Kind.WAITING_SLEEP -> buildString {
        if (day.fileCount == 0) {
            append("旧记录没有文件明细，下次复查会补全")
        } else {
            append("只有步数/压力（${day.fileCount} 个文件），睡眠还没出现")
        }
        append("；还会再核对 ").append(day.waitingDaysLeft).append(" 天")
    }
    CopyHealthReport.Kind.STOPPED ->
        "没有睡眠，且已超过 14 天等待窗口，不再复查"
    CopyHealthReport.Kind.EMPTY_PACKAGE ->
        "空包（${day.totalBytes} 字节，没戴表或只连了一下手机）"
    CopyHealthReport.Kind.LOCAL_ONLY -> "目标账号本地本来就有，不需要搬"
    CopyHealthReport.Kind.NOT_COPIED -> "还没搬过，下次搬运会处理"
}

/** 2026-11-04 → 11-04 周二 */
private fun dateLabel(date: String): String {
    val parsed = try {
        LocalDate.parse(date)
    } catch (e: DateTimeParseException) {
        return date
    }
    val week = when (parsed.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
    return "%02d-%02d %s".format(parsed.monthValue, parsed.dayOfMonth, week)
}
