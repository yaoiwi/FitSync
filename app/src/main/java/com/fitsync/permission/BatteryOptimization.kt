package com.fitsync.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化白名单。
 *
 * 为什么长任务需要它：前台服务能保住进程和网络，但系统仍会把 App 放进
 * 「受限待机桶」，网络调度、CPU 频率、Wi-Fi 省电策略都会按后台应用算。
 * 搬一年数据要发上千个请求，被降速后体感就是「后台比前台慢一截」。
 *
 * 国产 ROM 更直接：不加白名单，锁屏后可能直接冻结进程，通知还在、进度不动。
 */
object BatteryOptimization {

    /** 已加入白名单（不限制电池）时为 true；系统不支持查询时按 true 处理，不打扰用户 */
    fun isIgnoring(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(true)
    }

    /**
     * 弹系统的「允许后台运行」确认框。
     *
     * 部分 ROM 裁掉了这个页面，跳转失败就退到电池优化列表，让用户自己找。
     */
    fun requestIgnore(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        val launched = runCatching {
            context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!launched) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
