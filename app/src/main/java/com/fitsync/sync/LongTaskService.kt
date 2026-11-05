package com.fitsync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.fitsync.MainActivity
import com.fitsync.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 长任务前台服务。
 *
 * **只做两件事**：把进程钉在前台优先级、在通知栏显示进度和「停止」。
 * 真正的活儿仍然跑在 [SyncCoordinator] 的协程里——不让 Service 自己持任务，
 * 是因为界面状态都挂在 Coordinator 的 StateFlow 上，Service 再存一份必然两边不同步。
 *
 * 为什么非要它：
 * - 只有 WakeLock 时，屏幕熄灭约 30 分钟后系统进入 Doze，**网络被切断**，
 *   搬运就停在某一格再也不动；前台服务不受 Doze 网络限制；
 * - 后台进程会被 LMK 按内存压力回收，国产 ROM 还有自己的清理策略；
 *   前台服务把进程提到「用户可见」优先级，被杀的顺序排在最后。
 */
@AndroidEntryPoint
class LongTaskService : Service() {

    @Inject
    lateinit var coordinator: SyncCoordinator

    /** 是否已经 startForeground。没有它就无法区分「服务在跑」和「被 startService 唤起又立刻结束」 */
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "FitSync" }
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                ensureChannel()
                // startForeground 必须在 5 秒内调用，这里同步做完，不留协程
                if (!goForeground(buildNotification(title, text, null, null, ongoing = true))) {
                    // 类型权限缺失 / 后台启动被拒：直接退出，任务退回只靠 WakeLock
                    stopSelf()
                    return START_NOT_STICKY
                }
                foreground = true
            }

            ACTION_UPDATE -> {
                // 没有在前台就不是我们的通知，别去 startForeground（会 ANR）
                if (!foreground) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "FitSync" }
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val done = intent.getIntExtra(EXTRA_DONE, -1)
                val total = intent.getIntExtra(EXTRA_TOTAL, -1)
                notify(
                    buildNotification(
                        title = title,
                        text = text,
                        done = done.takeIf { it >= 0 },
                        total = total.takeIf { it > 0 },
                        ongoing = true,
                    ),
                )
            }

            ACTION_FINISH -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "FitSync" }
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (foreground) {
                    // 收尾通知：可滑掉、点开回到 App。用 DETACH 而不是 REMOVE，
                    // 是为了让用户切到别的 App 时仍能看到「搬完了 / 为什么停了」。
                    notify(buildNotification(title, text, null, null, ongoing = false))
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                    foreground = false
                }
                stopSelf()
            }

            ACTION_STOP -> {
                coordinator.cancelLongTask()
                stopSelf()
            }
        }
        // 进程被杀后不自动重启：任务状态在内存里，重启一个空服务只会显示一条僵尸通知
        return START_NOT_STICKY
    }

    /**
     * Android 15 起，`dataSync` 类型的后台运行有配额（24 小时内累计 6 小时，跨 App 共享）。
     *
     * 配额用完后系统会回调这里。**必须**在几秒内退出前台并 stopSelf：不处理的话
     * 系统直接抛 `RemoteServiceException`（"A foreground service of type dataSync
     * did not stop within the given time"），进程当场崩溃，用户连「为什么停了」都看不到。
     *
     * 搬运一年的健康数据 + 回填历史 + 导入写回，反复中断续跑很容易累计到这条线。
     * 停下来不是失败：台账已经记到断点，回到 App 再点一次会接着做。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        coordinator.cancelLongTask(
            reason = "系统的后台时长配额用完了（Android 15 对同步类服务的限制），任务已暂停。" +
                "回到 App 再点一次会接着做，已完成的进度都保留",
        )
        runCatching {
            notify(
                buildNotification(
                    title = "任务已暂停",
                    text = "系统限制了后台运行时长。回到 App 再点一次会接着做，进度不会丢",
                    done = null,
                    total = null,
                    ongoing = false,
                ),
            )
        }
        // DETACH：留下这条说明，用户切到别的 App 也能看到为什么停了
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        foreground = false
        stopSelf()
    }

    private fun goForeground(notification: Notification): Boolean = runCatching {        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }.isSuccess

    private fun notify(notification: Notification) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return@runCatching
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        done: Int?,
        total: Int?,
        ongoing: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LongTaskService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)

        if (ongoing) {
            builder.addAction(0, "停止", stop)
        }
        when {
            done != null && total != null -> builder.setProgress(total, done, false)
            done != null -> builder.setProgress(0, 0, false)
            ongoing -> builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "长任务进度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "搬运健康数据、补齐历史记录这类长任务的进度与停止按钮"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "fitsync_long_task"
        private const val NOTIFICATION_ID = 4101

        private const val ACTION_START = "com.fitsync.action.LONG_TASK_START"
        private const val ACTION_UPDATE = "com.fitsync.action.LONG_TASK_UPDATE"
        private const val ACTION_FINISH = "com.fitsync.action.LONG_TASK_FINISH"
        private const val ACTION_STOP = "com.fitsync.action.LONG_TASK_STOP"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"

        /**
         * 起服务。
         *
         * Android 12 起，App 在后台时启动前台服务会抛
         * `ForegroundServiceStartNotAllowedException`；这里的调用点都是用户点了按钮，
         * 属于允许场景，但仍然兜住失败——退化成「只有 WakeLock」也好过让任务直接崩。
         */
        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, LongTaskService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            context.startForegroundService(intent)
        }

        fun update(context: Context, title: String, text: String, done: Int?, total: Int?) {
            val intent = Intent(context, LongTaskService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_DONE, done ?: -1)
                .putExtra(EXTRA_TOTAL, total ?: -1)
            context.startService(intent)
        }

        fun finish(context: Context, title: String, text: String) {
            val intent = Intent(context, LongTaskService::class.java)
                .setAction(ACTION_FINISH)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            context.startService(intent)
        }
    }
}
