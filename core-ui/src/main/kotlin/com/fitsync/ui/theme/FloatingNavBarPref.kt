package com.fitsync.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 底部导航栏形态：开启时使用液态玻璃悬浮导航栏，关闭后改为贴底标准导航栏。
 */
object FloatingNavBarPref {

    private const val FILE = "fitsync_prefs"
    private const val KEY = "floating_nav_bar"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
    }

    fun flow(context: Context): Flow<Boolean> = callbackFlow {
        val sp = prefs(context)
        trySend(isEnabled(context))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) trySend(isEnabled(context))
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
