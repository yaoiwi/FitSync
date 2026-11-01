package com.fitsync.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 通知权限闸门（Android 13+）。
 *
 * 为什么在「点开始」这一刻问、而不是一进 App 就问：
 * 权限的用途是长任务进度通知，用户在这一刻才真正需要它，同意率更高，
 * 也符合「不要一启动就弹权限」的基本要求。
 *
 * **没授权也照样继续**：前台服务本身的合法性不依赖通知权限，
 * 缺的只是「看得见进度」和「能点停止」。不能因为用户拒绝就拦住任务。
 */
@Composable
fun rememberNotificationPermissionGate(): (block: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val action = pending
        pending = null
        action?.invoke()
    }
    return remember(context, launcher) {
        { block: () -> Unit ->
            val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            if (needed) {
                pending = block
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                block()
            }
        }
    }
}
