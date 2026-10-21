package com.fitgarminsync.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.fitgarminsync.ui.theme.MiuixAction
import com.fitgarminsync.ui.theme.MiuixError
import com.fitgarminsync.ui.theme.MiuixPrimary
import com.fitgarminsync.ui.theme.StatusGreen
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
    "running" -> "🏃"
    "cycling" -> "🚴"
    "swimming" -> "🏊"
    "walking" -> "🚶"
    "hiking" -> "🥾"
    "strength_training", "strength" -> "🏋"
    "elliptical" -> "🚴"
    "rowing" -> "🚣"
    else -> "🏅"
}

/** 运动类型 → 中文名 */
fun sportLabel(sport: String): String = when (sport.lowercase(Locale.US)) {
    "running" -> "跑步"
    "cycling" -> "骑行"
    "swimming" -> "游泳"
    "walking" -> "步行"
    "hiking" -> "徒步"
    "strength_training", "strength" -> "力量训练"
    "elliptical" -> "椭圆机"
    "rowing" -> "划船"
    else -> sport.ifBlank { "运动" }
}

fun regionLabel(region: String): String = when (region) {
    "cn" -> "中国区"
    "com" -> "国际区"
    else -> region
}

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
