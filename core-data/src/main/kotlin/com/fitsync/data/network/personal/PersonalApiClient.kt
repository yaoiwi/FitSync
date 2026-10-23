package com.fitsync.data.network.personal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Garmin 官方 Personal API 客户端（2026-10 开放的免费个人层）。
 *
 * 与项目里原有的 SSO 私有接口完全独立：
 * - 认证：用户自己在 Garmin Connect 网页创建 API Key，App 只拿 key 换 token；
 * - 只读：Personal 层没有任何写接口；
 * - token 是**裸 JWT**（不是 JSON），24 小时有效、没有 refresh，过期重新换；
 * - 文档没写的坑（来自实测）：活动搜索超过约 50 条会 500、空区间返回 204、
 *   FIT 下载必须是三合一 Accept 头，否则 406。
 */
@Singleton
class PersonalApiClient @Inject constructor(
    private val client: OkHttpClient,
) {

    companion object {
        const val BASE = "https://personal.connectapi.garmin.com"
        private const val TOKEN_PATH =
            "/personalapitoken-service/personal-api/personal-api-key/token"

        /** 实测 0.8 秒间隔、单线程数百次请求不会触发 429，这里留一点余量 */
        private const val MIN_INTERVAL_MS = 800L

        private const val FIT_ACCEPT =
            "application/zip, application/x-zip-compressed, application/octet-stream"
    }

    private val throttle = Mutex()
    private var lastRequestAt = 0L

    private suspend fun pace() {
        throttle.withLock {
            val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    // ============================ 认证 ============================

    /** API Key → access token（裸 JWT 字符串） */
    suspend fun exchangeToken(apiKey: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BASE + TOKEN_PATH)
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", "ApiKey ${apiKey.trim()}")
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful) {
                throw PersonalApiException(
                    code = response.code,
                    message = "换取 token 失败（HTTP ${response.code}）：${body.take(300)}",
                )
            }
            if (body.isEmpty() || body.count { it == '.' } != 2) {
                throw PersonalApiException(
                    code = response.code,
                    message = "token 响应格式异常：${body.take(200)}",
                )
            }
            body
        }
    }

    /** 解析 JWT 的 exp（秒）；解析失败返回 0 */
    fun tokenExpirySeconds(token: String): Long = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return 0L
        val json = String(decodeBase64Url(payload))
        JSONObject(json).optLong("exp", 0L)
    }.getOrDefault(0L)

    // ============================ 日汇总 ============================

    /**
     * 日汇总（一次最多一年）。
     * 返回的每天包含步数、距离、楼层、热量、静息心率、压力、Body Battery。
     */
    suspend fun dailySummaries(
        token: String,
        startDate: String,
        endDate: String,
    ): List<DailySummaryDto> = withContext(Dispatchers.IO) {
        val json = getJsonArray(
            token,
            "/usersummary-service/v2/dailysummaries" +
                "?startDate=$startDate&endDate=$endDate",
        ) ?: return@withContext emptyList()

        buildList {
            for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                val date = (o.optString("calendarDate").ifBlank { o.optString("date") })
                    .take(10)
                if (date.length != 10) continue   // 实测偶发 calendarDate 为 null，跳过
                add(
                    DailySummaryDto(
                        date = date,
                        steps = o.optInt("totalSteps", 0),
                        distanceM = o.optDouble("totalDistanceMeters", 0.0),
                        floors = o.optInt("floorsClimbed", 0),
                        activeCalories = o.optDouble("activeKilocalories", 0.0),
                        restingCalories = o.optDouble("bmrKilocalories", 0.0),
                        restingHr = o.optInt("restingHeartRate", 0).takeIf { it > 0 }
                            ?: o.optJSONObject("heartRate")?.optInt("restingValue", 0) ?: 0,
                        stressAvg = o.optInt("averageStressLevel", -1).takeIf { it >= 0 }
                            ?: o.optJSONObject("stress")?.optInt("avgLevel", 0) ?: 0,
                        stressMax = o.optInt("maxStressLevel", 0),
                        bodyBatteryMin = o.optInt("bodyBatteryLowestValue", 0),
                        bodyBatteryMax = o.optInt("bodyBatteryHighestValue", 0),
                    ),
                )
            }
        }
    }

    /** HRV 日序列（含基线），失败返回空 */
    suspend fun hrvDaily(token: String, startDate: String, endDate: String): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val json = getJsonObject(token, "/hrv-service/hrv/daily/$startDate/$endDate")
                ?: return@withContext emptyMap()
            val arr = json.optJSONArray("hrvSummaries") ?: return@withContext emptyMap()
            val out = mutableMapOf<String, Int>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val date = o.optString("calendarDate").take(10)
                val avg = o.optInt("lastNightAvg", 0)
                if (date.length == 10 && avg > 0) out[date] = avg
            }
            out
        }

    // ============================ 活动 ============================

    /**
     * 活动列表。
     *
     * 服务端是先把整个结果集建好再分页，超过约 50 条直接 500；
     * 所以按 30 天窗口遍历，窗口失败就对半拆到单日（实测能拉到两年无缺口）。
     */
    suspend fun searchActivities(
        token: String,
        startDate: String,
        endDate: String,
    ): List<ActivityDto> = withContext(Dispatchers.IO) {
        val start = parseDate(startDate)
        val end = parseDate(endDate)
        val collected = LinkedHashMap<String, ActivityDto>()

        var cursor = start
        while (!cursor.after(end)) {
            var chunkEnd = addDays(cursor, 30)
            if (chunkEnd.after(end)) chunkEnd = end
            runCatching { fetchWindow(token, cursor, chunkEnd) }
                .onSuccess { list -> list.forEach { collected[it.externalId] = it } }
                .onFailure { e ->
                    // 整个窗口失败，拆成两半重试
                    if (e is PersonalApiException && e.code >= 500) {
                        splitWindow(token, cursor, chunkEnd, collected, depth = 0)
                    } else {
                        throw e
                    }
                }
            cursor = addDays(chunkEnd, 1)
        }
        collected.values.sortedByDescending { it.startTimeMs }
    }

    private suspend fun splitWindow(
        token: String,
        start: Date,
        end: Date,
        into: MutableMap<String, ActivityDto>,
        depth: Int,
    ) {
        if (depth > 6) return
        runCatching { fetchWindow(token, start, end) }
            .onSuccess { list -> list.forEach { into[it.externalId] = it } }
            .onFailure {
                val mid = Date((start.time + end.time) / 2)
                if (mid == start || mid == end) return
                splitWindow(token, start, mid, into, depth + 1)
                splitWindow(token, addDays(mid, 1), end, into, depth + 1)
            }
    }

    private suspend fun fetchWindow(
        token: String,
        start: Date,
        end: Date,
    ): List<ActivityDto> {
        val path = "/activitylist-service/activities/search/activities" +
            "?startDate=${formatDate(start)}&endDate=${formatDate(end)}" +
            "&start=0&limit=50"
        // 204 = 空区间，正常返回空列表
        val arr = getJsonArray(token, path) ?: return emptyList()
        val out = ArrayList<ActivityDto>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("activityId", 0L)
            if (id <= 0) continue
            val typeKey = o.optJSONObject("activityType")?.optString("typeKey").orEmpty()
            out += ActivityDto(
                externalId = "personal:$id",
                activityId = id,
                name = o.optString("activityName").orEmpty(),
                sport = mapSport(typeKey),
                activityType = typeKey,
                startTimeMs = parseLocalTime(
                    o.optString("startTimeLocal").ifBlank { o.optString("startTimeGmt") },
                ),
                durationSec = o.optDouble("duration", 0.0).roundToInt().toLong(),
                distanceM = o.optDouble("distance", 0.0),
                avgHr = o.optInt("averageHR", 0),
                maxHr = o.optInt("maxHR", 0),
                calories = o.optDouble("calories", 0.0),
                elevationGainM = o.optDouble("elevationGain", 0.0),
            )
        }
        return out
    }

    /**
     * 下载活动原始 FIT。
     *
     * 接口返回的是 ZIP（内含一个 .fit），Accept 必须三合一，只写 application/zip 会 406。
     */
    suspend fun downloadFit(token: String, activityId: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            pace()
            val request = Request.Builder()
                .url("$BASE/download-service/files/activity/$activityId")
                .header("Authorization", "Bearer $token")
                .header("Accept", FIT_ACCEPT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 404) return@withContext null
                    throw PersonalApiException(
                        code = response.code,
                        message = "下载 FIT 失败（HTTP ${response.code}）",
                    )
                }
                val bytes = response.body?.bytes() ?: return@withContext null
                unzipSingleFit(bytes)
            }
        }

    private fun unzipSingleFit(zipBytes: ByteArray): ByteArray? {
        // 有的活动直接回 FIT（不是 ZIP），先看文件头第 8-11 字节是不是 ".FIT"
        val dot = '.'.code.toByte()
        val f = 'F'.code.toByte()
        if (zipBytes.size > 12 && zipBytes[8] == dot && zipBytes[9] == f) return zipBytes
        return runCatching {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".fit", ignoreCase = true)) {
                        val out = ByteArrayOutputStream()
                        zis.copyTo(out)
                        return out.toByteArray()
                    }
                    entry = zis.nextEntry
                }
            }
            null
        }.getOrNull()
    }

    // ============================ 底层请求 ============================

    private suspend fun getJsonArray(token: String, path: String): JSONArray? {
        val body = get(token, path) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private suspend fun getJsonObject(token: String, path: String): JSONObject? {
        val body = get(token, path) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    /** 204 / 空 body 统一返回 null */
    private suspend fun get(token: String, path: String): String? = withContext(Dispatchers.IO) {
        pace()
        val request = Request.Builder()
            .url(BASE + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 204) return@withContext null
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw PersonalApiException(
                    code = response.code,
                    message = "请求失败（HTTP ${response.code}）：${body.take(200)}",
                )
            }
            if (body.isBlank()) null else body
        }
    }

    // ============================ 工具 ============================

    private fun decodeBase64Url(input: String): ByteArray =
        android.util.Base64.decode(input, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)

    private fun parseDate(text: String): Date =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(text) ?: Date()

    private fun formatDate(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(date)

    private fun addDays(date: Date, days: Int): Date =
        Date(date.time + days * 24L * 60L * 60L * 1000L)

    /** 搜索接口给的是 "2026-10-22 04:50:01"（无时区），按本地时区解析 */
    private fun parseLocalTime(text: String): Long {
        if (text.isBlank()) return 0L
        val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss")
        for (p in patterns) {
            runCatching {
                return SimpleDateFormat(p, Locale.US).parse(text)?.time ?: 0L
            }
        }
        return 0L
    }

    /** Garmin 的 typeKey → 本项目的 sport 枚举 */
    private fun mapSport(typeKey: String): String = when (typeKey.lowercase(Locale.US)) {
        "running", "trail_running", "treadmill_running", "track_running" -> "running"
        "cycling", "road_biking", "mountain_biking", "indoor_cycling", "gravel_cycling" -> "cycling"
        "swimming", "lap_swimming", "open_water_swimming" -> "swimming"
        "walking", "casual_walking", "speed_walking" -> "walking"
        "hiking", "mountaineering" -> "hiking"
        "strength_training", "indoor_cardio", "hiit" -> "strength_training"
        "rowing", "indoor_rowing" -> "rowing"
        "elliptical" -> "elliptical"
        else -> typeKey.ifBlank { "other" }
    }
}

class PersonalApiException(val code: Int, override val message: String) : Exception(message)

// ============================ DTO ============================

data class DailySummaryDto(
    val date: String,
    val steps: Int,
    val distanceM: Double,
    val floors: Int,
    val activeCalories: Double,
    val restingCalories: Double,
    val restingHr: Int,
    val stressAvg: Int,
    val stressMax: Int,
    val bodyBatteryMin: Int,
    val bodyBatteryMax: Int,
)

data class ActivityDto(
    val externalId: String,
    val activityId: Long,
    val name: String,
    val sport: String,
    val activityType: String,
    val startTimeMs: Long,
    val durationSec: Long,
    val distanceM: Double,
    val avgHr: Int,
    val maxHr: Int,
    val calories: Double,
    val elevationGainM: Double,
)
