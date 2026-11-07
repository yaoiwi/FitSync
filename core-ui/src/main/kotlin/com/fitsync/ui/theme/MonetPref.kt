package com.fitsync.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 莫奈取色（Material You / Monet）。
 *
 * 只对 Material 3 风格有效：HyperOS 那套是固定的蓝灰体系 + 液态玻璃，
 * 让壁纸色渗进来反而会破坏它的一致性，所以开关也只在 MD3 下出现。
 *
 * 系统要求 Android 12（API 31）：动态色板读的是 `system_accent1_*` 这组资源，
 * 低版本没有。低版本即使存了 true 也一律按关闭处理，避免「开关是开的但没颜色」。
 */
object MonetPref {

    /** 与 ThemeModePref / UiStylePref 共用一份，换机恢复时不用单独迁移 */
    private const val FILE = "fitsync_prefs"
    private const val KEY = "monet_enabled"

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun isEnabled(context: Context): Boolean =
        isSupported() && prefs(context).getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
    }

    /** 壁纸取色在系统里变化（换壁纸、改主题色）会重建 Activity，这里只跟开关本身 */
    fun flow(context: Context): Flow<Boolean> = callbackFlow {
        val sp = prefs(context)
        trySend(isEnabled(context))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) trySend(isEnabled(context))
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
