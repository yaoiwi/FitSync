package com.fitsync.data.exporter

import com.fitsync.data.db.FitSyncDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地数据导出。
 *
 * 两个格式刻意设计成"能被本应用的导入功能再吃回去"：
 * - JSON 的字段名对齐 [com.fitsync.data.importer.LocalDataImporter] 认的键（date / activityId / startTime ...）
 * - CSV 是给人看的，列名固定，不做花哨合并
 *
 * 凭据（access token / 密码）永远不导出——那些在 CredentialVault 里，跟业务表无关。
 */
@Singleton
class DataExporter @Inject constructor(
    private val db: FitSyncDatabase,
) {

    data class Summary(
        val accounts: Int,
        val activities: Int,
        val days: Int,
        val nights: Int,
    )

    suspend fun exportJson(): Pair<String, Summary> = withContext(Dispatchers.IO) {
        val accounts = db.accountDao().all()
        val activities = db.activityDao().all()
        val daily = db.dailySummaryDao().all()
        val sleep = db.sleepSessionDao().all()

        val root = JSONObject().apply {
            put("app", "FitSync")
            put("schema", 1)
            put("exportedAtMs", System.currentTimeMillis())
            put("accounts", JSONArray(accounts.map { a ->
                JSONObject().apply {
                    put("displayName", a.displayName)
                    put("region", a.region)
                    put("sourceType", a.sourceType)
                    put("deviceName", a.deviceName)
                }
            }))
            put("activities", JSONArray(activities.map { a ->
                JSONObject().apply {
                    // activityId 用 externalId：导入时按这个去重，不能用本地自增 id
                    put("activityId", a.externalId)
                    put("activityType", a.activityType.ifBlank { a.sport })
                    put("startTime", a.startTimeMs)
                    put("duration", a.durationSec)
                    put("distance", a.distanceM)
                    put("averageHR", a.avgHr)
                    put("maxHR", a.maxHr)
                    put("calories", a.calories)
                    put("averageSpeed", a.avgSpeed)
                    put("elevationGain", a.elevationGainM)
                }
            }))
            put("daily", JSONArray(daily.map { d ->
                JSONObject().apply {
                    put("date", d.date)
                    put("steps", d.steps)
                    put("distanceMeters", d.distanceM)
                    put("floorsClimbed", d.floors)
                    put("activeKilocalories", d.caloriesActive)
                    put("restingKilocalories", d.caloriesResting)
                    put("restingHeartRate", d.restingHr)
                    put("hrv", d.hrvMs)
                    put("avgRespirationRate", d.respiratoryRate)
                    put("averageStressLevel", d.stressAvg)
                    put("maxStressLevel", d.stressMax)
                    put("bodyBatteryLowestValue", d.bodyBatteryMin)
                    put("bodyBatteryHighestValue", d.bodyBatteryMax)
                    put("averageSpo2", d.spo2Avg)
                }
            }))
            put("sleep", JSONArray(sleep.map { s ->
                JSONObject().apply {
                    put("date", dayOf(s.startTimeMs, s.endTimeMs))
                    put("totalSleepMin", s.totalSleepMin)
                    put("startTimeMs", s.startTimeMs)
                    put("endTimeMs", s.endTimeMs)
                    put("sleepScore", s.sleepScore)
                    put("avgHeartRate", s.avgHr)
                    put("averageSpO2Value", s.avgSpo2)
                    put("averageRespirationValue", s.avgBreathing)
                }
            }))
        }
        root.toString(2) to Summary(accounts.size, activities.size, daily.size, sleep.size)
    }

    suspend fun exportCsvZip(): Pair<ByteArray, Summary> = withContext(Dispatchers.IO) {
        val accounts = db.accountDao().all()
        val activities = db.activityDao().all()
        val daily = db.dailySummaryDao().all()
        val sleep = db.sleepSessionDao().all()

        val bytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("activities.csv"))
                zip.write(
                    buildCsv(
                        header = listOf(
                            "activity_id", "sport", "type", "start_time", "duration_sec",
                            "distance_m", "avg_hr", "max_hr", "calories", "elevation_gain_m",
                        ),
                        rows = activities.map { a ->
                            listOf(
                                a.externalId, a.sport, a.activityType, isoTime(a.startTimeMs),
                                a.durationSec.toString(), "%.1f".format(a.distanceM),
                                a.avgHr.toString(), a.maxHr.toString(),
                                "%.1f".format(a.calories), "%.1f".format(a.elevationGainM),
                            )
                        },
                    ).toByteArray()
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("daily.csv"))
                zip.write(
                    buildCsv(
                        header = listOf(
                            "date", "steps", "distance_m", "floors", "calories_active",
                            "calories_resting", "resting_hr", "hrv_ms", "stress_avg", "spo2_avg",
                        ),
                        rows = daily.map { d ->
                            listOf(
                                d.date, d.steps.toString(), "%.1f".format(d.distanceM),
                                d.floors.toString(), "%.1f".format(d.caloriesActive),
                                "%.1f".format(d.caloriesResting), d.restingHr.toString(),
                                d.hrvMs.toString(), d.stressAvg.toString(), d.spo2Avg.toString(),
                            )
                        },
                    ).toByteArray()
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("sleep.csv"))
                zip.write(
                    buildCsv(
                        header = listOf(
                            "date", "sleep_min", "in_bed_min", "score", "avg_hr",
                            "avg_spo2", "avg_breathing",
                        ),
                        rows = sleep.map { s ->
                            listOf(
                                dayOf(s.startTimeMs, s.endTimeMs), s.totalSleepMin.toString(),
                                s.inBedMin.toString(), s.sleepScore.toString(), s.avgHr.toString(),
                                s.avgSpo2.toString(), "%.1f".format(s.avgBreathing),
                            )
                        },
                    ).toByteArray()
                )
                zip.closeEntry()
            }
            out.toByteArray()
        }
        bytes to Summary(accounts.size, activities.size, daily.size, sleep.size)
    }

    private fun buildCsv(header: List<String>, rows: List<List<String>>): String =
        buildString {
            append(header.joinToString(",") { escape(it) })
            append("\r\n")
            rows.forEach { row ->
                append(row.joinToString(",") { escape(it) })
                append("\r\n")
            }
        }

    /** CSV 转义：含逗号/引号/换行就整体加引号，内部引号翻倍 */
    private fun escape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun isoTime(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(ms))

    /** 睡眠跨零点时按"入睡那天"归属，和趋势卡的口径保持一致 */
    private fun dayOf(startMs: Long, endMs: Long): String {
        val anchor = if (startMs > 0) startMs else endMs
        return java.time.Instant.ofEpochMilli(anchor)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }
}
