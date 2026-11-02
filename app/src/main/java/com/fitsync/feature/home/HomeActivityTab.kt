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

// ============================ 活动 ============================

/** 活动页按天分组后的一组：标题在组合期就算好，滚动时不再格式化 */
private data class ActivityDay(
    val title: String,
    val items: List<ActivityEntity>,
)

@Composable
internal fun ActivityTab(
    activities: List<ActivityEntity>,
    syncing: Boolean,
    onOpenActivity: (Long) -> Unit = {},
) {
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var monthFilter by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val types = remember(activities) { activities.map { sportGroup(it.sport) }.distinct() }
    val months = remember(activities) {
        activities.map { TimeFmt.month(it.startTimeMs) }
            .distinct().sortedDescending().take(6)
    }

    val filtered = remember(activities, typeFilter, monthFilter, query) {
        activities.filter { a ->
            val matchType = typeFilter == null || sportGroup(a.sport) == typeFilter
            val matchMonth = monthFilter == null || TimeFmt.month(a.startTimeMs) == monthFilter
            val matchQuery = query.isBlank() ||
                a.activityType.contains(query, ignoreCase = true) ||
                sportLabel(a.sport).contains(query, ignoreCase = true) ||
                sportLabel(a.activityType).contains(query, ignoreCase = true)
            matchType && matchMonth && matchQuery
        }
    }

    // 分组在组合期做一次就够了：以前每次重组都要把几百条活动重新日期格式化、排序、
    // 再对每一天的标题做一次 parse+format。搜索框每敲一个字都会触发一次，越用越卡。
    val dayGroups = remember(filtered) {
        filtered.groupBy { TimeFmt.day(it.startTimeMs) }
            .toSortedMap(compareByDescending { it })
            .map { (day, list) -> ActivityDay(title = TimeFmt.dayTitleOf(day), items = list) }
    }

    HyperPage(
        title = "活动",
        subtitle = when {
            syncing -> "正在同步…"
            activities.isEmpty() -> "还没有运动记录"
            filtered.size != activities.size -> "${filtered.size} / ${activities.size} 条"
            else -> "${activities.size} 条 · 跑步 / 骑行 / 健身"
        },
    ) {
        if (activities.isEmpty()) {
            item {
                MiuixSectionTitle("全部记录")
                MiuixCard {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp)) {
                        Text(
                            text = "同步后这里会显示活动",
                            fontSize = 16.sp,
                            color = MiuiText1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "只拉最近 20 条；想要更早的记录，去设置里点「回填历史」。",
                            fontSize = 12.5.sp,
                            color = MiuixSummary,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        } else {
            item {
                SearchBox(value = query, onValueChange = { query = it })
            }
            if (types.size > 1) {
                item {
                    FilterChipRow(
                        options = listOf("全部运动" to null) + types.map { sportGroupLabel(it) to it },
                        selected = typeFilter,
                        onSelect = { typeFilter = it },
                    )
                }
            }
            if (months.size > 1) {
                item {
                    FilterChipRow(
                        options = listOf("全部月份" to null) + months.map { it to it },
                        selected = monthFilter,
                        onSelect = { monthFilter = it },
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    MiuixCard {
                        Text(
                            text = "没有符合条件的记录",
                            fontSize = 14.sp,
                            color = MiuixSummary,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                        )
                    }
                }
            } else {
                dayGroups.forEach { group ->
                    item {
                        MiuixSectionTitle(group.title)
                        MiuixCard {
                            group.items.forEachIndexed { index, activity ->
                                ActivityRow(activity) { onOpenActivity(activity.id) }
                                if (index != group.items.lastIndex) MiuixDivider(startIndent = 72)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixCardSurface)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        if (value.isEmpty()) {
            Text(text = "搜索运动类型或名称", fontSize = 13.5.sp, color = MiuixSummary)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = MiuiText1),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixPrimary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FilterChipRow(
    options: List<Pair<String, String?>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val tick = rememberTick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            // 类型行与月份行是两个 item，没有上下间距会紧贴在一起
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, value) ->
            val active = selected == value
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) MiuixPrimary else MiuixCardSurface)
                    .clickable {
                        tick()
                        onSelect(value)
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 12.5.sp,
                    color = if (active) Color.White else MiuiText1,
                )
            }
        }
    }
}

/** 把 Garmin 的一大串 typeKey 归成六类，筛选才有意义 */
private fun sportGroup(sport: String): String = when (sport.lowercase(Locale.US)) {
    "running", "run", "trail_running", "treadmill_running", "track_running" -> "run"
    "cycling", "biking", "road_biking", "mountain_biking", "indoor_cycling" -> "ride"
    "swimming", "lap_swimming", "open_water_swimming" -> "swim"
    "walking", "hiking" -> "walk"
    "strength_training", "indoor_cardio", "yoga", "pilates", "hiit" -> "strength"
    else -> "other"
}

private fun sportGroupLabel(group: String): String = when (group) {
    "run" -> "跑步"
    "ride" -> "骑行"
    "swim" -> "游泳"
    "walk" -> "步行"
    "strength" -> "力量"
    else -> "其它"
}

@Composable
internal fun ActivityRow(activity: ActivityEntity, onClick: () -> Unit = {}) {
    val tick = rememberTick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                tick()
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MiuixPrimary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(sportGlyph(activity.sport), fontSize = 18.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // activityType 是 Garmin 原始 typeKey（如 trail_running），比归一化的 sport 更具体
                text = sportLabel(activity.activityType.ifBlank { activity.sport }),
                fontSize = 16.sp,
                color = MiuiText1,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = TimeFmt.clockShort(activity.startTimeMs) +
                    if (activity.avgHr > 0) " · ${activity.avgHr} bpm" else "",
                fontSize = 12.sp,
                color = MiuixSummary,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (activity.distanceM > 0) "%.2f km".format(activity.distanceM / 1000) else "—",
                fontSize = 15.sp,
                color = MiuiText1,
            )
            Text(
                text = formatDuration((activity.durationSec / 60).toInt()),
                fontSize = 12.sp,
                color = MiuixSummary,
            )
        }
    }
}
