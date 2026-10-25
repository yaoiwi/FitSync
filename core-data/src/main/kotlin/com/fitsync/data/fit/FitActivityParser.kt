package com.fitsync.data.fit

import com.garmin.fit.Decode
import com.garmin.fit.Mesg
import com.garmin.fit.MesgListener
import com.garmin.fit.LapMesg
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import java.io.ByteArrayInputStream

/**
 * 用 Garmin 官方 FIT SDK 解析活动文件。
 *
 * 只取两类消息：
 * - `RECORD`：逐条采样（心率、速度、海拔、经纬度、步频、功率、温度）
 * - `SESSION`：汇总（总距离、总热量、平均/最大心率）
 *
 * 坐标在 FIT 里是 semicircles（有符号 32 位），需要乘 180/2^31 转成度。
 * 智能记录的轨迹不是等间隔的（半马 21km 可能只有 1693 个点），画图时按序号连接即可。
 */
object FitActivityParser {

    data class Parsed(
        val samples: List<SamplePoint>,
        val routes: List<RoutePoint>,
        val laps: List<LapPoint> = emptyList(),
        val totalDistanceM: Double = 0.0,
        val totalCalories: Double = 0.0,
        val avgHr: Int = 0,
        val maxHr: Int = 0,
    )

    data class SamplePoint(
        val tsMs: Long,
        val hr: Int,
        val cadence: Int,
        val powerW: Int,
        val speedMs: Double,
        val elevM: Double,
        val tempC: Double,
    )

    /** FIT 的 LAP 消息：每公里自动计圈或手动按圈 */
    data class LapPoint(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val durationSec: Long,
        val distanceM: Double,
        val avgHr: Int,
        val maxHr: Int,
    )

    data class RoutePoint(
        val seq: Int,
        val lat: Double,
        val lng: Double,
        val elevM: Double,
        val tsMs: Long,
        val speedMs: Double,
        val hr: Int,
        val cadence: Int,
    )

    fun parse(bytes: ByteArray): Parsed {
        val samples = ArrayList<SamplePoint>(2048)
        val routes = ArrayList<RoutePoint>(2048)
        val laps = ArrayList<LapPoint>(64)
        var seq = 0
        var totalDistance = 0.0
        var totalCalories = 0.0
        var avgHr = 0
        var maxHr = 0

        val listener = MesgListener { mesg: Mesg ->
            when (mesg.num) {
                MesgNum.RECORD -> {
                    val r = RecordMesg(mesg)
                    val seconds = r.timestamp?.timestamp?.toLong() ?: 0L
                    if (seconds <= 0L) return@MesgListener
                    val tsMs = seconds * 1000L

                    val hr = r.heartRate?.toInt() ?: 0
                    val cadence = r.cadence?.toInt() ?: 0
                    val power = r.power ?: 0
                    val speed = r.speed?.toDouble() ?: 0.0
                    val elev = r.altitude?.toDouble() ?: 0.0
                    val temp = r.temperature?.toDouble() ?: 0.0

                    samples += SamplePoint(
                        tsMs = tsMs,
                        hr = hr.coerceIn(0, 255),
                        cadence = cadence.coerceIn(0, 255),
                        powerW = power.coerceAtLeast(0),
                        speedMs = speed,
                        elevM = elev,
                        tempC = temp,
                    )

                    val latRaw = r.positionLat
                    val lngRaw = r.positionLong
                    if (latRaw != null && lngRaw != null) {
                        val lat = semicirclesToDegrees(latRaw)
                        val lng = semicirclesToDegrees(lngRaw)
                        // 手表未定位时会给 (0,0) 或越界值，丢弃避免轨迹画到几内亚湾
                        if (lat in -90.0..90.0 && lng in -180.0..180.0 &&
                            !(lat == 0.0 && lng == 0.0)
                        ) {
                            routes += RoutePoint(
                                seq = seq++,
                                lat = lat,
                                lng = lng,
                                elevM = elev,
                                tsMs = tsMs,
                                speedMs = speed,
                                hr = hr,
                                cadence = cadence,
                            )
                        }
                    }
                }

                MesgNum.LAP -> {
                    val l = LapMesg(mesg)
                    val startMs = (l.startTime?.timestamp ?: 0L).toLong() * 1000L
                    val endMs = (l.timestamp?.timestamp ?: 0L).toLong() * 1000L
                    // 起止时间缺失的圈直接丢，否则界面上会出现"时长 0 秒"的空行
                    if (startMs > 0L && endMs >= startMs) {
                        laps += LapPoint(
                            index = laps.size + 1,
                            startMs = startMs,
                            endMs = endMs,
                            durationSec = l.totalTimerTime?.toDouble()?.toLong()
                                ?: ((endMs - startMs) / 1000L),
                            distanceM = l.totalDistance?.toDouble() ?: 0.0,
                            avgHr = l.avgHeartRate?.toInt() ?: 0,
                            maxHr = l.maxHeartRate?.toInt() ?: 0,
                        )
                    }
                }

                MesgNum.SESSION -> {
                    val s = SessionMesg(mesg)
                    s.totalDistance?.let { totalDistance = it.toDouble() }
                    s.totalCalories?.let { totalCalories = it.toDouble() }
                    s.avgHeartRate?.let { avgHr = it.toInt() }
                    s.maxHeartRate?.let { maxHr = it.toInt() }
                }

                else -> Unit
            }
        }

        val ok = runCatching {
            Decode().read(ByteArrayInputStream(bytes), listener)
        }.getOrDefault(false)

        return Parsed(
            samples = if (ok) samples else emptyList(),
            routes = if (ok) routes else emptyList(),
            laps = if (ok) laps else emptyList(),
            totalDistanceM = totalDistance,
            totalCalories = totalCalories,
            avgHr = avgHr,
            maxHr = maxHr,
        )
    }

    private fun semicirclesToDegrees(value: Int): Double =
        value * (180.0 / 2147483648.0)
}
