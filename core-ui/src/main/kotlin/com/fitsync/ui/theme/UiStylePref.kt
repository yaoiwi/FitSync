package com.fitsync.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 界面风格：HyperOS（Miuix）或 Material Design 3。
 *
 * 只影响「长什么样」，不影响深浅色 —— 两种风格各有自己的浅色/深色色板，
 * 深色模式仍然由 [ThemeModePref] 单独决定，两者正交。
 */
enum class UiStyle {
    /** HyperOS / Miuix：16dp 圆角卡片、分组标题、胶囊导航 */
    Miuix,

    /** Material 3：surface 分层、列表项、Filled 按钮、标准导航栏 */
    Md3,
}

/**
 * 界面风格的持久化。
 *
 * 与 [ThemeModePref] 同一份 SharedPreferences，换机恢复备份时不用单独迁移。
 */
object UiStylePref {

    const val MIUIX = "miuix"
    const val MD3 = "md3"

    private const val FILE = "fitsync_prefs"
    private const val KEY = "ui_style"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun current(context: Context): UiStyle =
        if (prefs(context).getString(KEY, MIUIX) == MD3) UiStyle.Md3 else UiStyle.Miuix

    fun set(context: Context, style: UiStyle) {
        prefs(context).edit()
            .putString(KEY, if (style == UiStyle.Md3) MD3 else MIUIX)
            .apply()
    }

    fun store(context: Context, value: String) {
        set(context, if (value == MD3) UiStyle.Md3 else UiStyle.Miuix)
    }

    /** 风格变化会推送新值，供 Composable 用 collectAsState 订阅 */
    fun flow(context: Context): Flow<UiStyle> = callbackFlow {
        val sp = prefs(context)
        trySend(current(context))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) trySend(current(context))
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/**
 * 当前风格。默认 Miuix，预览/测试里不提供也不会崩。
 *
 * 组件读它来决定用哪套实现：`if (LocalUiStyle.current == UiStyle.Md3)`。
 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.Miuix }

/** 语义糖：在 Composable 里写 `isMd3` 比每次比枚举短 */
val isMd3: Boolean
    @androidx.compose.runtime.Composable
    get() = LocalUiStyle.current == UiStyle.Md3
