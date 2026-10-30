package com.fitsync.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.fitsync.ui.theme.MiuixAction
import com.fitsync.ui.theme.MiuixError
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.StatusGreen
import java.util.Locale

/**
 * 展示层的格式化与状态映射。
 *
 * 原先这些函数写在 HomeScreen 里且是 private，新页面（账号详情、活动详情、
 * 数据操作页）都要用，所以提到 core-ui 作为公共实现。
 */

fun formatDuration(minutes: Int): String = when {
    minutes <= 0 -> "—"
    minutes < 60 -> "${minutes} 分钟"
    else -> "%d 小时 %d 分".format(minutes / 60, minutes % 60)
}

/** 时长秒数 → "1:23:45" / "23:45" */
fun formatDurationSeconds(seconds: Long): String {
    if (seconds <= 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 运动类型 → emoji（无图标资源时的轻量表示） */
fun sportGlyph(sport: String): String = when (sport.lowercase(Locale.US)) {
    "running", "run", "trail_running", "treadmill_running", "track_running" -> "🏃"
    "cycling", "bike", "road_biking", "mountain_biking",
    "indoor_cycling", "gravel_cycling" -> "🚴"
    "swimming", "swim", "lap_swimming", "open_water_swimming" -> "🏊"
    "walking", "walk", "casual_walking", "speed_walking" -> "🚶"
    "hiking", "mountaineering" -> "🥾"
    "strength_training", "strength", "indoor_cardio", "hiit" -> "🏋"
    "elliptical" -> "🚴"
    "rowing", "indoor_rowing" -> "🚣"
    "yoga" -> "🧘"
    "pilates" -> "🤸"
    else -> "🏅"
}

/**
 * 运动类型 → 中文名。
 *
 * 入参可以是 Garmin 的原始 typeKey（`trail_running`），也可以是同步时归一化后的 `running`。
 * 未收录的类型把下划线换成空格原样返回，不会出现整串英文代码混在中文列表里。
 */
fun sportLabel(sport: String): String = when (sport.lowercase(Locale.US)) {
    "running", "run" -> "跑步"
    "trail_running" -> "越野跑"
    "treadmill_running" -> "跑步机"
    "track_running" -> "场地跑"
    "cycling", "bike" -> "骑行"
    "road_biking" -> "公路骑行"
    "mountain_biking" -> "山地骑行"
    "indoor_cycling" -> "室内骑行"
    "gravel_cycling" -> "砾石骑行"
    "swimming", "swim" -> "游泳"
    "lap_swimming" -> "泳池游泳"
    "open_water_swimming" -> "公开水域游泳"
    "walking", "walk" -> "步行"
    "casual_walking" -> "散步"
    "speed_walking" -> "快走"
    "hiking" -> "徒步"
    "mountaineering" -> "登山"
    "strength_training", "strength" -> "力量训练"
    "indoor_cardio" -> "室内有氧"
    "hiit" -> "高强度间歇"
    "elliptical" -> "椭圆机"
    "rowing", "indoor_rowing" -> "划船"
    "yoga" -> "瑜伽"
    "pilates" -> "普拉提"
    "other", "unknown" -> "其他运动"
    else -> sport.ifBlank { "运动" }.replace('_', ' ')
}

fun regionLabel(region: String): String = when (region) {
    "cn" -> "中国区"
    "com" -> "国际区"
    else -> region
}

/**
 * 账号显示名。
 *
 * 旧版本登录时 `socialProfile.displayName` 可能是空的，落库的只剩一串 GUID
 * （`371d55ac-80da-47eb-a856-87246836ee69`）。界面上不该出现这种东西，
 * 所以看起来像 GUID 的名字一律退回「Garmin 账号 · 区域」。
 */
fun accountTitle(displayName: String, region: String): String {
    val name = displayName.trim()
    if (name.isEmpty() || looksLikeGuid(name)) return "Garmin 账号 · ${regionLabel(region)}"
    return name
}

fun looksLikeGuid(value: String): Boolean =
    value.length >= 32 && value.count { it == '-' } >= 4 &&
        value.all { it.isLetterOrDigit() || it == '-' }

fun statusLabel(status: String): String = when (status) {
    "normal" -> "正常"
    "syncing" -> "同步中"
    "mfa" -> "需验证"
    "expired" -> "已过期"
    else -> status
}

@Composable
fun statusColor(status: String): Color = when (status) {
    "normal" -> StatusGreen
    "syncing" -> MiuixPrimary
    "mfa" -> MiuixPrimary
    "expired" -> MiuixError
    else -> MiuixAction
}
