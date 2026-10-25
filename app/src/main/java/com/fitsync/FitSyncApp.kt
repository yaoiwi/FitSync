package com.fitsync

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fitsync.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获与持久化。
 *
 * v1 阶段没有接入 APM，真机上拿不到 logcat，所以这里把崩溃栈**写进内部存储**
 * （崩溃后进程会立刻被杀，只放内存里必然丢失），下次冷启动由 [MainActivity] 直接展示，
 * 支持复制与系统分享（可以直接发到微信/邮件）。
 */
object CrashStore {

    private const val TAG = "FitSync"
    private const val LAST_FILE = "crash-last.txt"

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    fun record(context: Context, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("发生时间：${stamp()}")
            appendLine("机型/系统：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / API ${android.os.Build.VERSION.SDK_INT}")
            appendLine(throwable.toString())
            append(stack)
        }
        try {
            context.openFileOutput(LAST_FILE, Context.MODE_PRIVATE).use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
        } catch (io: Exception) {
            Log.w(TAG, "崩溃日志落盘失败", io)
        }
        Log.e(TAG, "捕获到未处理异常", throwable)
    }

    fun load(context: Context): String? =
        try {
            val file = java.io.File(context.filesDir, LAST_FILE)
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            null
        }

    fun consume(context: Context) {
        try {
            java.io.File(context.filesDir, LAST_FILE).delete()
        } catch (e: Exception) {
            Log.w(TAG, "清理崩溃日志失败", e)
        }
    }

    fun shareIntent(context: Context, trace: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FitSync 崩溃堆栈")
            putExtra(Intent.EXTRA_TEXT, trace)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

@HiltAndroidApp
class FitSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashStore.record(applicationContext, throwable)
            previous?.uncaughtException(thread, throwable)
        }
        SyncScheduler.schedulePeriodic(this)
    }
}
