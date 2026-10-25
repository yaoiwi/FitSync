package com.fitsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColors
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColors

/**
 * 全站色板。
 *
 * 之前这些 token 是一组写死的浅色 `val`，`darkTheme` 参数虽然存在，但页面直接引用常量，
 * 夜里只会把 Scaffold 底色刷黑、卡片和文字仍然是白底黑字。现在改成
 * 「浅色/深色两套 + CompositionLocal」，token 名字不变，调用点无需改动。
 */
@Immutable
data class FitPalette(
    val bg: Color,
    val card: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val yellow: Color,
    val primary: Color,
    val primaryContainer: Color,
    val canvas: Color,
    val cardSurface: Color,
    val summary: Color,
    val action: Color,
    val divider: Color,
    val outline: Color,
    val error: Color,
    val groupBg: Color,
    val statusGreen: Color,
)

private val LightPalette = FitPalette(
    bg = Color(0xFFF5F7FA),
    card = Color(0xFFFFFFFF),
    text1 = Color(0xFF1F2733),
    text2 = Color(0xFF646A73),
    text3 = Color(0xFF9AA3AD),
    accent = Color(0xFF4C8BF5),
    green = Color(0xFF3DBE6B),
    orange = Color(0xFFFF8A3D),
    red = Color(0xFFFF4F4F),
    yellow = Color(0xFFF5B645),
    primary = Color(0xFF3482FF),
    primaryContainer = Color(0xFF5D9BFF),
    canvas = Color(0xFFF7F7F7),
    cardSurface = Color(0xFFFFFFFF),
    summary = Color(0x99000000),
    action = Color(0x66000000),
    divider = Color(0xFFE0E0E0),
    outline = Color(0xFFD9D9D9),
    error = Color(0xFFE94634),
    groupBg = Color(0xFFF0F0F0),
    statusGreen = Color(0xFF34C759),
)

/**
 * 深色取值参考 HyperOS / Miuix 夜间样式：
 * 画布纯黑、卡片比画布亮一档、正文不用纯白（#F2F2F7 更耐看），
 * 主色在深底上提亮一档，否则 4C8BF5 会显得脏。
 */
private val DarkPalette = FitPalette(
    bg = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    text1 = Color(0xFFF2F2F7),
    text2 = Color(0xFFA1A1A6),
    text3 = Color(0xFF6E6E73),
    accent = Color(0xFF5D9BFF),
    green = Color(0xFF4CD964),
    orange = Color(0xFFFF9F4A),
    red = Color(0xFFFF6B5B),
    yellow = Color(0xFFFFC65C),
    primary = Color(0xFF4C8DFF),
    primaryContainer = Color(0xFF2B4C7E),
    canvas = Color(0xFF000000),
    cardSurface = Color(0xFF1C1C1E),
    summary = Color(0x99FFFFFF),
    action = Color(0x66FFFFFF),
    divider = Color(0xFF2C2C2E),
    outline = Color(0xFF3A3A3C),
    error = Color(0xFFFF6B5B),
    groupBg = Color(0xFF2C2C2E),
    statusGreen = Color(0xFF30D158),
)

private val LocalFitPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun FitSyncTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // 不传 darkTheme 时读用户设置（跟随系统 / 浅色 / 深色），设置一改全站立即重绘
    val context = androidx.compose.ui.platform.LocalContext.current
    val mode by remember(context) { ThemeModePref.flow(context) }
        .collectAsState(initial = ThemeModePref.FOLLOW)
    val isDark = darkTheme ?: resolveDarkMode(mode, isSystemInDarkTheme())

    val palette = if (isDark) DarkPalette else LightPalette
    val scheme = if (isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.canvas,
            surface = palette.cardSurface,
            onBackground = palette.text1,
            onSurface = palette.text1,
            surfaceVariant = palette.groupBg,
            onSurfaceVariant = palette.text2,
            outline = palette.divider,
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF4C8BF5),
            background = Color(0xFFF5F7FA),
            surface = Color.White,
            onPrimary = Color.White,
            onBackground = Color(0xFF1F2733),
            onSurface = Color(0xFF1F2733),
        )
    }

    CompositionLocalProvider(LocalFitPalette provides palette) {
        // 官方 Miuix 主题（与 HyperIsland 3.2.1 同版本 0.9.4）：
        // 官方组件（Card / BasicComponent / Switch / Text…）从这里取色，
        // 液态玻璃的 surface 采样色也与它保持一致。
        MiuixTheme(
            colors = if (isDark) miuixDarkColors() else miuixLightColors(),
            textStyles = defaultTextStyles(),
        ) {
            MaterialTheme(colorScheme = scheme, content = content)
        }
    }
}

// ============================ 语义 token（随主题变化） ============================
// 用法不变：`color = MiuixCanvas`，但值会跟随浅色/深色切换。

val MiuiBg: Color @Composable get() = LocalFitPalette.current.bg
val MiuiCard: Color @Composable get() = LocalFitPalette.current.card
val MiuiText1: Color @Composable get() = LocalFitPalette.current.text1
val MiuiText2: Color @Composable get() = LocalFitPalette.current.text2
val MiuiText3: Color @Composable get() = LocalFitPalette.current.text3
val MiuiAccent: Color @Composable get() = LocalFitPalette.current.accent
val MiuiGreen: Color @Composable get() = LocalFitPalette.current.green
val MiuiOrange: Color @Composable get() = LocalFitPalette.current.orange
val MiuiRed: Color @Composable get() = LocalFitPalette.current.red
val MiuiYellow: Color @Composable get() = LocalFitPalette.current.yellow

val MiuixPrimary: Color @Composable get() = LocalFitPalette.current.primary
val MiuixPrimaryContainer: Color @Composable get() = LocalFitPalette.current.primaryContainer
val MiuixCanvas: Color @Composable get() = LocalFitPalette.current.canvas
val MiuixCardSurface: Color @Composable get() = LocalFitPalette.current.cardSurface
val MiuixSummary: Color @Composable get() = LocalFitPalette.current.summary
val MiuixAction: Color @Composable get() = LocalFitPalette.current.action
val MiuixDivider: Color @Composable get() = LocalFitPalette.current.divider
val MiuixOutline: Color @Composable get() = LocalFitPalette.current.outline
val MiuixError: Color @Composable get() = LocalFitPalette.current.error
val MiuixGroupBg: Color @Composable get() = LocalFitPalette.current.groupBg
val StatusGreen: Color @Composable get() = LocalFitPalette.current.statusGreen

// Apple HIG 一组（Dashboard 早期用，保留给需要固定系统色的地方）
val AppleBg = Color(0xFFF2F2F7)
val AppleCard = Color(0xFFFFFFFF)
val AppleLabel = Color(0xFF1C1C1E)
val AppleSecondary = Color(0xFF8E8E93)
val AppleSeparator = Color(0x1F3C3C43)
val AppleBlue = Color(0xFF007AFF)
val AppleGreen = Color(0xFF34C759)
val AppleOrange = Color(0xFFFF9500)
val ApplePink = Color(0xFFFF2D55)
val ApplePurple = Color(0xFFAF52DE)
val AppleTeal = Color(0xFF30B0C7)

/** 账号卡左侧状态色条的 4 态映射（原型 Screen E） */
@Composable
fun statusBarColor(status: String): Color = when (status) {
    "normal" -> MiuiGreen
    "syncing" -> MiuiAccent
    "mfa" -> MiuiYellow
    "expired" -> MiuiRed
    else -> MiuiAccent
}
