package com.fitsync.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 触感反馈。
 *
 * 分四档强度，映射到系统预置的 haptic 效果（API 29+），低版本退化成定长一次性震动。
 * 用户可在「设置 → 触感反馈」关掉，关掉后所有调用直接返回，不做任何系统调用。
 */
enum class HapticFeedback {
    /** 轻点：列表行、开关、分段控件、按钮 */
    Tick,

    /** 确认：提交、保存、开始同步 */
    Confirm,

    /** 成功：同步完成、连接成功 */
    Success,

    /** 警示：出错、删除、不可用 */
    Warning,
}

object Haptics {
    private const val PREFS = "fitsync_settings"
    private const val KEY_ENABLED = "haptics_enabled"

    /** 默认开启；用户关掉后写进 SharedPreferences */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun perform(context: Context, kind: HapticFeedback = HapticFeedback.Tick) {
        if (!isEnabled(context)) return
        val vibrator = resolveVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(predefinedFor(kind)))
            } else {
                val (durationMs, amplitude) = oneShotFor(kind)
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            }
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun predefinedFor(kind: HapticFeedback): Int = when (kind) {
        HapticFeedback.Tick -> VibrationEffect.EFFECT_CLICK
        HapticFeedback.Confirm -> VibrationEffect.EFFECT_CLICK
        HapticFeedback.Success -> VibrationEffect.EFFECT_DOUBLE_CLICK
        HapticFeedback.Warning -> VibrationEffect.EFFECT_HEAVY_CLICK
    }

    /** 时长与振幅（1–255），给 API 26–28 用 */
    private fun oneShotFor(kind: HapticFeedback): Pair<Long, Int> = when (kind) {
        HapticFeedback.Tick -> 12L to 60
        HapticFeedback.Confirm -> 18L to 90
        HapticFeedback.Success -> 26L to 110
        HapticFeedback.Warning -> 32L to 170
    }
}

/** 组件里拿一个触发函数：`val haptic = rememberHaptics()` */
@Composable
fun rememberHaptics(): (HapticFeedback) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { kind -> Haptics.perform(context, kind) }
    }
}

/** 最常用的轻点，单独给一个，省得每次写枚举 */
@Composable
fun rememberTick(): () -> Unit {
    val haptic = rememberHaptics()
    return remember(haptic) { { haptic(HapticFeedback.Tick) } }
}
