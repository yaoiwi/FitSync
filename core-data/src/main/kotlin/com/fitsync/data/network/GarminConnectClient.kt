package com.fitsync.data.network

import com.fitsync.data.db.AccountRegion
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Garmin Connect 数据接口（只读）。
 *
 * 三个端点就够撑起 v1：
 * ```
 * GET {api}/usersummary-service/usersummary/daily/{displayName}?calendarDate=yyyy-MM-dd
 * GET {api}/wellness-service/wellness/dailySleepData/{displayName}?date=yyyy-MM-dd
 * GET {api}/activitylist-service/activities/search/activities?limit=20&start=0
 * ```
 * 鉴权：`Authorization: Bearer <access_token>`（登录链路已落库）。
 *
 * 解析刻意做得宽松：字段缺失就取默认值，绝不因为 Garmin 加字段就整批失败。
 */
@Singleton
class GarminConnectClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {

    internal var endpoints: (AccountRegion) -> GarminEndpoints = { GarminEndpoints.forRegion(it) }

    // ============================ 数据模型（网络层） ============================

    data class DailySummary(
        val date: String,
        val steps: Int,
        val distanceM: Double,
        val floors: Int,
        val caloriesActive: Double,
        val caloriesResting: Double,
        val restingHr: Int,
        val minHr: Int,
        val maxHr: Int,
        val stressAvg: Int,
        val stressMax: Int,
        val bodyBatteryMin: Int,
        val bodyBatteryMax: Int,
        val spo2Avg: Int,
    )

    data class SleepData(
        val date: String,
        val externalId: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val totalSleepMin: Int,
        val inBedMin: Int,
        val sleepScore: Int,
        val avgHr: Int,
        val avgSpo2: Int,
        val avgBreathing: Double,
    )

    data class Activity(
        val externalId: String,
        val sport: String,
        val name: String,
        val startTimeMs: Long,
        val durationSec: Long,
        val distanceM: Double,
        val avgHr: Int,
        val maxHr: Int,
        val calories: Double,
        val elevationGainM: Double,
    )

    // ============================ 接口 ============================

    fun fetchDailySummary(
        region: AccountRegion,
        accessToken: String,
        displayName: String,
        date: String,
    ): DailySummary? {
        val ep = endpoints(region)
        val url = (ep.apiBase + "/usersummary-service/usersummary/daily/" +
            encodePath(displayName)).toHttpUrl().newBuilder()
            .addQueryParameter("calendarDate", date)
            .build()

        val json = getJson(url, accessToken) ?: return null
        val summary = DailySummary(
            date = json.optString("calendarDate", date),
            steps = json.optInt("totalSteps"),
            distanceM = json.optDouble("totalDistanceMeters", 0.0),
            floors = json.optInt("floorsClimbed"),
            caloriesActive = json.optDouble("activeKilocalories", 0.0),
            caloriesResting = json.optDouble("restingKilocalories", 0.0),
            restingHr = json.optInt("restingHeartRate"),
            minHr = json.optInt("minHeartRate"),
            maxHr = json.optInt("maxHeartRate"),
            stressAvg = json.optInt("averageStressLevel"),
            stressMax = json.optInt("maxStressLevel"),
            bodyBatteryMin = json.optInt("bodyBatteryLowestValue"),
            bodyBatteryMax = json.optInt("bodyBatteryHighestValue"),
            spo2Avg = json.optInt("averageSpo2"),
        )
        // Garmin 对"还没有数据的日期"（通常是今天）会返回 200 + 全 0 字段。
        // 这种记录写进库会把首页的"最新一天"顶成全 0，看起来就像数据消失了。
        val hasAnyData = summary.steps > 0 || summary.distanceM > 0.0 ||
            summary.caloriesActive > 0.0 || summary.restingHr > 0 ||
            summary.stressAvg > 0 || summary.spo2Avg > 0
        return if (hasAnyData) summary else null
    }

    fun fetchSleep(
        region: AccountRegion,
        accessToken: String,
        displayName: String,
        date: String,
    ): SleepData? {
        val ep = endpoints(region)
        val url = (ep.apiBase + "/wellness-service/wellness/dailySleepData/" +
            encodePath(displayName)).toHttpUrl().newBuilder()
            .addQueryParameter("date", date)
            .addQueryParameter("nonSleepBufferMinutes", "60")
            .build()

        val root = getJson(url, accessToken) ?: return null
        val dto = root.optJSONObject("dailySleepDTO") ?: return null
        val start = dto.optLong("sleepStartTimestampGMT", 0L)
        val end = dto.optLong("sleepEndTimestampGMT", 0L)
        if (start <= 0L && end <= 0L) return null

        val sleepSec = dto.optInt("sleepTimeSeconds")
        val inBedSec = sleepSec + dto.optInt("awakeSleepSeconds")
        val score = dto.optJSONObject("sleepScores")
            ?.optJSONObject("overall")
            ?.optInt("value")
            ?: 0

        return SleepData(
            date = dto.optString("calendarDate", date),
            externalId = "sleep-$date",
            startTimeMs = start,
            endTimeMs = end,
            totalSleepMin = sleepSec / 60,
            inBedMin = inBedSec / 60,
            sleepScore = score,
            avgHr = dto.optInt("avgHeartRate"),
            avgSpo2 = dto.optInt("averageSpO2Value"),
            avgBreathing = dto.optDouble("averageRespirationValue", 0.0),
        )
    }

    fun fetchActivities(
        region: AccountRegion,
        accessToken: String,
        limit: Int = 20,
    ): List<Activity> {
        val ep = endpoints(region)
        val url = (ep.apiBase + "/activitylist-service/activities/search/activities").toHttpUrl()
            .newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("start", "0")
            .build()

        val text = getText(url, accessToken) ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("activityId").ifBlank { "act-$i" }
                add(
                    Activity(
                        externalId = id,
                        sport = o.optJSONObject("activityType")?.optString("typeKey")
                            ?.ifBlank { "unknown" } ?: "unknown",
                        name = o.optString("activityName"),
                        startTimeMs = parseGmt(o.optString("startTimeGMT")),
                        durationSec = o.optDouble("duration", 0.0).toLong(),
                        distanceM = o.optDouble("distance", 0.0),
                        avgHr = o.optInt("averageHR"),
                        maxHr = o.optInt("maxHR"),
                        calories = o.optDouble("calories", 0.0),
                        elevationGainM = o.optDouble("elevationGain", 0.0),
                    )
                )
            }
        }
    }

    // ============================ HTTP ============================

    private fun getText(url: HttpUrl, accessToken: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("User-Agent", "GCM-Android-5.23")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            when {
                // 401 是可自愈的：由调用方换新令牌后重试，这里只负责准确上报
                response.code == 401 -> throw GarminNetworkException.Unauthorized()
                response.code == 403 -> throw GarminNetworkException.RiskBlocked(
                    IOException("Connect API 403")
                )
                response.code == 429 -> throw GarminNetworkException.RiskBlocked(
                    IOException("Connect API 限流（429）")
                )
                response.code >= 500 -> throw GarminNetworkException.NetworkUnavailable(
                    IOException("Connect API ${response.code}")
                )
                !response.isSuccessful -> return null
            }
            return response.body?.string()
        }
    }

    // ============================ 设备列表（健康数据搬运的前提） ============================

    data class GarminDevice(
        val name: String,
        val serialNumber: Long,
        val productId: Int,
        val primary: Boolean,
    )

    /**
     * 查询账号下绑定的设备。
     *
     * 健康数据（monitoring）只有在账号绑定了「主要可穿戴设备」时才会被计入，
     * 所以搬运前必须拿到目标账号的设备身份（serial number / product）。
     */
    fun fetchDevices(region: AccountRegion, accessToken: String): List<GarminDevice> {
        val ep = endpoints(region)
        val url = (ep.apiBase + "/device-service/deviceregistration/devices").toHttpUrl()
        val text = getText(url, accessToken) ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()

        val out = mutableListOf<GarminDevice>()
        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val serial = node.optLong("serialNumber", 0L)
                    if (serial > 0) {
                        out += GarminDevice(
                            name = node.optString("productDisplayName")
                                .ifBlank { node.optString("displayName") }
                                .ifBlank { node.optString("productName") },
                            serialNumber = serial,
                            productId = node.optInt("productId"),
                            primary = node.optBoolean("primary") || node.optBoolean("isPrimary"),
                        )
                    }
                    node.keys().forEach { key -> scan(node.opt(key)) }
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i))
            }
        }
        scan(root)
        return out.distinctBy { it.serialNumber }
    }

    // ============================ 活动复制（跨账号） ============================

    data class DownloadedActivity(val fileName: String, val bytes: ByteArray)

    /**
     * 下载活动的原始文件。
     *
     * Garmin 的 `/download-service/files/activity/{id}` 返回的是 zip（内含 .fit），
     * 这里解压出第一个可上传的文件；如果不是 zip 就原样返回。
     */
    fun downloadActivityFile(
        region: AccountRegion,
        accessToken: String,
        activityId: String,
    ): DownloadedActivity? {
        val ep = endpoints(region)
        val url = (ep.apiBase + "/download-service/files/activity/" + encodePath(activityId))
            .toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "*/*")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            return unpackActivity(bytes, activityId)
        }
    }

    private fun unpackActivity(bytes: ByteArray, activityId: String): DownloadedActivity? {
        val isZip = bytes.size > 4 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) return DownloadedActivity("activity-$activityId.fit", bytes)

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                val lower = name.lowercase(Locale.US)
                if (!entry.isDirectory &&
                    (lower.endsWith(".fit") || lower.endsWith(".tcx") || lower.endsWith(".gpx"))
                ) {
                    return DownloadedActivity(name, zip.readBytes())
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    /**
     * 上传活动文件到目标账号。
     *
     * `POST /upload-service/upload`（multipart，字段名 `file`）就是 Garmin 网页版
     * 「导入数据」用的接口，支持 FIT / TCX / GPX / zip。
     */
    fun uploadActivityFile(
        region: AccountRegion,
        accessToken: String,
        fileName: String,
        bytes: ByteArray,
    ): String? {
        val ep = endpoints(region)
        val url = (ep.apiBase + "/upload-service/upload").toHttpUrl()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                bytes.toRequestBody("application/octet-stream".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return if (response.isSuccessful) text else null
        }
    }

    private fun getJson(url: HttpUrl, accessToken: String): JSONObject? {
        val text = getText(url, accessToken) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun encodePath(segment: String): String =
        segment.replace("/", "%2F")

    private fun parseGmt(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }
}
