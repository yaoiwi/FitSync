package com.fitsync.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 外观模式的持久化（跟随系统 / 浅色 / 深色）。
 *
 * 用普通 SharedPreferences 而非 DataStore：就是一个开关值，
 * 不值得为一个字段引入额外依赖；监听用 OnSharedPreferenceChangeListener 转成 Flow。
 */
object ThemeModePref {

    const val FOLLOW = "follow"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val FILE = "fitsync_prefs"
    private const val KEY = "theme_mode"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun current(context: Context): String =
        prefs(context).getString(KEY, FOLLOW) ?: FOLLOW

    fun set(context: Context, mode: String) {
        prefs(context).edit().putString(KEY, mode).apply()
    }

    /** 模式变化会推送新值，供 Composable 用 collectAsState 订阅 */
    fun flow(context: Context): Flow<String> = callbackFlow {
        val sp = prefs(context)
        trySend(current(context))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) trySend(current(context))
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/** 把模式字符串解析成「是否深色」，[systemDark] 是系统当前值 */
fun resolveDarkMode(mode: String, systemDark: Boolean): Boolean = when (mode) {
    ThemeModePref.DARK -> true
    ThemeModePref.LIGHT -> false
    else -> systemDark
}
