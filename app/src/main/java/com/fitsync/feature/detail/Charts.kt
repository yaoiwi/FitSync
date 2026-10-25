package com.fitsync.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitsync.data.db.ActivityRouteEntity
import com.fitsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.max
import kotlin.math.min

/**
 * 轨迹与曲线绘制。
 *
 * 不引地图 SDK：轨迹按经纬度包围盒等比缩放后画折线，
 * 曲线把采样点降采样到几百个再连，足够看出趋势且不会拖慢滚动。
 */

@Composable
fun RouteTrack(
    routes: List<ActivityRouteEntity>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (routes.size < 2) {
        EmptyChartHint("这次活动没有 GPS 轨迹", modifier)
        return
    }
    val points = remember(routes) {
        // 均匀抽样，最多 400 个点，避免超大轨迹拖慢绘制
        val step = max(1, routes.size / 400)
        routes.filterIndexed { index, _ -> index % step == 0 || index == routes.lastIndex }
    }

    Canvas(modifier = modifier) {
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }

        val spanLat = (maxLat - minLat).takeIf { it > 1e-7 } ?: 1e-7
        val spanLng = (maxLng - minLng).takeIf { it > 1e-7 } ?: 1e-7

        // 等比缩放并居中，保留轨迹形状
        val scale = min(size.width / spanLng, size.height / spanLat)
        val offsetX = ((size.width - spanLng * scale) / 2.0).toFloat()
        val offsetY = ((size.height - spanLat * scale) / 2.0).toFloat()

        fun toOffset(lat: Double, lng: Double): Offset {
            val x = ((lng - minLng) * scale).toFloat() + offsetX
            // 纬度越大越靠北，屏幕 y 越小
            val y = ((maxLat - lat) * scale).toFloat() + offsetY
            return Offset(x, y)
        }

        val path = Path()
        points.forEachIndexed { index, p ->
            val o = toOffset(p.lat, p.lng)
            if (index == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
        // 起终点
        val start = toOffset(points.first().lat, points.first().lng)
        val end = toOffset(points.last().lat, points.last().lng)
        drawCircle(color = color, radius = 4.dp.toPx(), center = start)
        drawCircle(color = color.copy(alpha = 0.45f), radius = 4.dp.toPx(), center = end)
    }
}

@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val clean = remember(values) { values.filter { it.isFinite() } }
    if (clean.size < 2) {
        EmptyChartHint("没有足够的数据", modifier)
        return
    }
    val step = remember(clean) { max(1, clean.size / 300) }
    val sampled = remember(clean, step) {
        clean.filterIndexed { index, _ -> index % step == 0 || index == clean.lastIndex }
    }
    val minV = sampled.min()
    val maxV = sampled.max()
    val span = (maxV - minV).takeIf { it > 1e-4f } ?: 1f

    Canvas(modifier = modifier) {
        fun point(index: Int): Offset {
            val x = size.width * index / (sampled.size - 1).toFloat()
            val normalized = (sampled[index] - minV) / span
            val y = size.height - normalized * size.height * 0.92f - size.height * 0.04f
            return Offset(x, y)
        }

        val line = Path()
        for (i in sampled.indices) {
            val p = point(i)
            if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
        }

        // 折线下方渐变填充
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.02f)),
            ),
        )
        drawPath(
            path = line,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun EmptyChartHint(text: String, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(text = text, fontSize = 12.sp, color = MiuixSummary)
    }
}

private fun List<Float>.min(): Float = this.minOrNull() ?: 0f
private fun List<Float>.max(): Float = this.maxOrNull() ?: 0f
