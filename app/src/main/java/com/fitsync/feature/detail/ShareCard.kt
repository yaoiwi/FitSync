package com.fitsync.feature.detail

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.Toast
import androidx.core.content.FileProvider
import com.fitsync.data.db.ActivityEntity
import com.fitsync.ui.components.formatDurationSeconds
import com.fitsync.ui.components.sportLabel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 成绩卡片。
 *
 * 用原生 Canvas 画一张 1080×1350 的竖版图，再走系统分享。
 * 不走 Compose 截图：截图依赖把内容先渲染到屏幕上，详情页滚到一半时尺寸不稳，
 * 而且深色/浅色主题会让卡片颜色不可控。
 */
object ShareCard {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    fun share(context: Context, activity: ActivityEntity) {
        runCatching {
            val bitmap = render(activity)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "fitsync-${activity.id}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
            bitmap.recycle()

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "分享成绩").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(context, "生成分享图失败：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun render(activity: ActivityEntity): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawRect(
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, HEIGHT.toFloat(),
                    intArrayOf(0xFF0E1116.toInt(), 0xFF1B2533.toInt()),
                    null,
                    Shader.TileMode.CLAMP,
                )
            },
        )

        val primary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val secondary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AA4B2.toInt()
            typeface = Typeface.DEFAULT
        }
        val hairline = Paint().apply { color = 0x33FFFFFF }

        // 顶部品牌
        primary.textSize = 36f
        canvas.drawText("FitSync", 80f, 120f, primary)
        secondary.textSize = 28f
        canvas.drawText("运动记录", 80f, 168f, secondary)

        // 运动类型 + 时间
        val title = sportLabel(activity.sport)
        primary.textSize = 76f
        canvas.drawText(title, 80f, 320f, primary)
        secondary.textSize = 30f
        canvas.drawText(
            SimpleDateFormat("yyyy 年 M 月 d 日 HH:mm", Locale.CHINA)
                .format(Date(activity.startTimeMs)),
            80f, 372f, secondary,
        )

        // 主数字：有距离显示公里数，没有就显示时长
        val hasDistance = activity.distanceM > 0
        val mainText = if (hasDistance) "%.2f".format(activity.distanceM / 1000.0)
        else formatDurationSeconds(activity.durationSec)
        primary.textSize = 180f
        canvas.drawText(mainText, 80f, 620f, primary)
        if (hasDistance) {
            secondary.textSize = 46f
            canvas.drawText("公里", 80f + primary.measureText(mainText) + 18f, 620f, secondary)
        }

        canvas.drawRect(80f, 700f, WIDTH - 80f, 702f, hairline)

        // 2×2 指标
        val metrics = listOf(
            "平均配速" to paceOf(activity),
            "时长" to formatDurationSeconds(activity.durationSec),
            "平均心率" to if (activity.avgHr > 0) "${activity.avgHr} bpm" else "—",
            "消耗" to if (activity.calories > 0) "%.0f kcal".format(activity.calories) else "—",
        )
        metrics.forEachIndexed { index, (label, value) ->
            val x = if (index % 2 == 0) 80f else 580f
            val y = if (index < 2) 820f else 990f
            secondary.textSize = 28f
            canvas.drawText(label, x, y - 52f, secondary)
            primary.textSize = 56f
            canvas.drawText(value, x, y, primary)
        }

        canvas.drawRect(80f, 1160f, WIDTH - 80f, 1162f, hairline)

        secondary.textSize = 26f
        canvas.drawText(
            "由 FitSync 生成 · 数据来自 Garmin",
            80f, 1230f, secondary,
        )

        return bitmap
    }

    private fun paceOf(activity: ActivityEntity): String {
        if (activity.distanceM <= 1.0 || activity.durationSec <= 0) return "—"
        val secPerKm = activity.durationSec / (activity.distanceM / 1000.0)
        return "%d'%02d\"".format((secPerKm / 60).toInt(), (secPerKm % 60).toInt())
    }
}
