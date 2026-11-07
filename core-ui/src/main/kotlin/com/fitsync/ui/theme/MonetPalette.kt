package com.fitsync.ui.theme

import androidx.compose.material3.ColorScheme

/**
 * 把壁纸色板（M3 动态色）翻译成 App 的语义 token。
 *
 * 只替换「骨架色」：背景、卡片、正文、主色、分隔线。
 * 绿 / 橙 / 红 / 黄 这组是数据语义色（步数、睡眠、心率、告警），
 * 跟着壁纸漂移会让「心率红」变成紫色，所以固定取 MD3 基础色板里的对应值。
 */
fun monetPalette(scheme: ColorScheme, isDark: Boolean): FitPalette {
    val base = if (isDark) Md3DarkPalette else Md3LightPalette
    return base.copy(
        bg = scheme.background,
        canvas = scheme.background,
        // 卡片一档（surfaceContainerLow）：在 background 上刚好分层，
        // 与 MD3 非莫奈模式下的取值口径一致
        card = scheme.surfaceContainerLow,
        cardSurface = scheme.surfaceContainerLow,
        text1 = scheme.onSurface,
        text2 = scheme.onSurfaceVariant,
        text3 = scheme.outline,
        accent = scheme.primary,
        primary = scheme.primary,
        primaryContainer = scheme.primaryContainer,
        summary = scheme.onSurfaceVariant,
        action = scheme.outline,
        divider = scheme.outlineVariant,
        outline = scheme.outline,
        error = scheme.error,
        // 分段控件的槽底、输入框底色
        groupBg = scheme.surfaceContainerHighest,
    )
}
