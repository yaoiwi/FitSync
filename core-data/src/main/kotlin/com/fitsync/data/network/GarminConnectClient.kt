package com.fitsync.data.network

import com.fitsync.data.db.AccountRegion
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** uint32 上限，FIT 的 serialNumber 字段写不进去更大的值 */
private const val FIT_SERIAL_MAX = 4_294_967_294L

/**
 * Garmin Connect 数据接口（只读）。
 *
 * ```
 * GET {api}/usersummary-service/usersummary/daily/?calendarDate=yyyy-MM-dd
 * GET {api}/wellness-service/wellness/dailySleepData/{displayName}?date=yyyy-MM-dd
 * GET {api}/activitylist-service/activities/search/activities?limit=20&start=0
 * ```
 * 注意日汇总**不带用户名**：新网关把 URL 里的用户标识判为越权，回 message 为 null 的 403。
 * 鉴权：`Authorization: Bearer <access_token>`（登录链路已落库）。
 *
 * 解析刻意做得宽松：字段缺失就取默认值，绝不因为 Garmin 加字段就整批失败。
 */
@Singleton
class GarminConnectClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {

    internal var endpoints: (AccountRegion) -> GarminEndpoints = { GarminEndpoints.forRegion(it) }

    /** 账号路径标识缓存：`/usersummary/daily/{user}` 里的 `{user}` 不需要每次同步都重新探测 */
    private data class CachedPathUser(val value: String, val expiresAtMs: Long)
    private val pathUserCache = ConcurrentHashMap<String, CachedPathUser>()

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
        /** 睡眠分期（深睡/浅睡/REM/清醒），为空表示接口没给 */
        val levels: List<SleepLevel> = emptyList(),
    )

    /** 一段睡眠阶段，时间来自 `dailySleepDTO.sleepLevels` */
    data class SleepLevel(
        val startMs: Long,
        val endMs: Long,
        val stage: String,
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
        val json = fetchDailySummaryJson(ep, accessToken, displayName, date) ?: return null
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

    /**
     * 取日汇总的 JSON。
     *
     * **路径里不再带用户标识**：Garmin 的新网关把「从 URL 里指定用户名」当成越权访问，
     * 直接回 `{"message":null,"error":"ForbiddenException"}`（message 为 null 的 403 就是它）。
     * 现在的写法让服务端从令牌自己推断用户：
     *
     * ```
     * GET {api}/usersummary-service/usersummary/daily/?calendarDate=yyyy-MM-dd
     * ```
     *
     * 这个形式来自 garth 0.8.0 的 `data/daily_summary.py`，是目前实测能通的写法。
     * 老网关（中国区早期账号）可能只认带用户名的路径，所以 404/400 时回退一次，
     * 保证不会因为换了端点把原本能用的账号弄坏。
     */
    private fun fetchDailySummaryJson(
        ep: GarminEndpoints,
        accessToken: String,
        displayName: String,
        date: String,
    ): JSONObject? {
        val anonymous = (ep.apiBase + "/usersummary-service/usersummary/daily/").toHttpUrl()
            .newBuilder()
            .addQueryParameter("calendarDate", date)
            .build()

        var status = 0
        val json = getJson(anonymous, accessToken) { status = it }
        if (json != null) return json
        // 400/404/405：这个网关还不认无用户名的形式，退回旧路径
        if (status in intArrayOf(400, 404, 405)) {
            val legacy = (ep.apiBase + "/usersummary-service/usersummary/daily/" +
                encodePath(displayName)).toHttpUrl().newBuilder()
                .addQueryParameter("calendarDate", date)
                .build()
            return getJson(legacy, accessToken)
        }
        return null
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

        // 403 时换新端点：和日汇总同样的收紧逻辑，带用户名的旧路径会被判越权。
        // 新端点的响应结构一样（dailySleepDTO），解析代码不用改。
        val root = try {
            getJson(url, accessToken)
        } catch (e: GarminNetworkException.Forbidden) {
            getJson(
                (ep.apiBase + "/sleep-service/sleep/dailySleepData").toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("date", date)
                    .build(),
                accessToken,
            )
        } ?: return null
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
            levels = parseSleepLevels(dto),
        )
    }

    /**
     * 解析 `sleepLevels`。
     *
     * Garmin 对同一段睡眠给的 activityLevel 约定：
     * 0 = deep、1 = light、2 = rem、3 = awake。
     * 时间字段在不同版本里可能是毫秒数，也可能是 `yyyy-MM-dd'T'HH:mm:ss` 字符串，
     * 这里两种都认，解析不出来的整段跳过，绝不让一条脏数据拖垮整晚睡眠。
     */
    private fun parseSleepLevels(dto: JSONObject): List<SleepLevel> {
        val arr = dto.optJSONArray("sleepLevels") ?: return emptyList()
        val out = ArrayList<SleepLevel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = parseGmtValue(o.opt("startGMT"))
            val end = parseGmtValue(o.opt("endGMT"))
            if (start <= 0L || end <= start) continue
            val stage = when (o.optInt("activityLevel", -1)) {
                0 -> "deep"
                1 -> "light"
                2 -> "rem"
                3 -> "awake"
                else -> continue
            }
            out += SleepLevel(startMs = start, endMs = end, stage = stage)
        }
        return out
    }

    /** sleepLevels 的时间既可能是 epoch 毫秒，也可能是 ISO 字符串 */
    private fun parseGmtValue(raw: Any?): Long = when (raw) {
        null -> 0L
        is Number -> {
            val v = raw.toLong()
            if (v > 10_000_000_000L) v else v * 1000L
        }
        is String -> {
            val text = raw.trim()
            val plain = text.substringBefore('.').replace("Z", "")
            runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(plain)?.time ?: 0L
            }.getOrDefault(0L)
        }
        else -> 0L
    }

    fun fetchActivities(
        region: AccountRegion,
        accessToken: String,
        limit: Int = 20,
        start: Int = 0,
    ): List<Activity> {
        val ep = endpoints(region)
        val url = (ep.apiBase + "/activitylist-service/activities/search/activities").toHttpUrl()
            .newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("start", start.toString())
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

    /**
     * 读 `Retry-After`：可能是秒数，也可能是 HTTP 日期。
     * Garmin 大多数时候不带这个头，拿不到就返回 null，由上层用默认冷却时长。
     */
    private fun retryAfterMs(response: Response): Long? {
        val raw = response.header("Retry-After")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.let { seconds ->
            if (seconds > 0) return seconds * 1000L
        }
        return runCatching {
            val at = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .parse(raw, java.time.ZonedDateTime::from)
            (at.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
        }.getOrNull()
    }

    /**
     * 发一个 GET。
     *
     * 这里**不再**根据 403 切换浏览器 UA：实测「移动端 UA + 设备头」与「浏览器 UA +
     * 设备头」对同一张令牌返回完全相同的状态码，UA 并不是门槛；而一旦切到浏览器模式
     * 就会丢掉 `X-Garmin-*`，反而**永远** 403。身份头固定，问题交给上层（续期 / 报错）。
     */
    private fun getRaw(url: HttpUrl, accessToken: String, accept: String = "application/json"): Response =
        executeGet(url, accessToken, accept)

    private fun executeGet(url: HttpUrl, accessToken: String, accept: String): Response {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", accept)
        // 身份头：与 garth 0.8.0 一致，只发 iOS 的 User-Agent。
        // 1.14.8 试过补一整套 X-Garmin-*，403 一条没少，说明那批头不是门槛。
        GarminUserAgent.deviceHeaders().forEach { (name, value) -> builder.header(name, value) }
        return restClient.newCall(builder.get().build()).execute()
    }

    /**
     * 不跟随重定向的客户端，只给 `connectapi` 用。
     *
     * **这是 403 的真正来源之一**：令牌失效时 Garmin 不会老实地回 401，而是把请求
     * 302 到 SSO 登录页。OkHttp 默认跟随重定向，并且**跨域时会丢掉 `Authorization`**
     * ——最终请求落到登录页上，返回的正是
     * `{"message":"HTTP 403 Forbidden","error":"ForbiddenException"}`。
     *
     * 于是 App 看到的是「403 权限问题」，而真实原因是「令牌失效、需要续期」，
     * 续期逻辑永远等不到 401，用户就一直卡在 403。
     *
     * 关掉跟随之后，3xx 会原样暴露出来，由 [getText] 当成令牌失效处理。
     * 共享原客户端的连接池与线程池，不额外占资源。
     */
    private val restClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * 这个响应是不是「边缘防护拦截」而不是业务结果。
     *
     * 只用于诊断（帮助判断该等冷却还是换网络），**不**触发任何重试。
     * 429 带 `cf-ray` 表示是 Cloudflare 在挡，同样按限流处理：连续硬撞只会更糟。
     */
    private fun isEdgeRejection(response: Response): Boolean =
        response.code == 403 || (response.code == 429 && response.header("cf-ray") != null)

    private fun getText(
        url: HttpUrl,
        accessToken: String,
        onStatus: ((Int) -> Unit)? = null,
    ): String? {
        getRaw(url, accessToken).use { response ->
            onStatus?.invoke(response.code)
            val endpoint = url.encodedPath.substringAfterLast('/').ifBlank { url.encodedPath }
            when {
                // 3xx：Garmin 用「重定向到登录页」来表达「令牌不认了」。
                // 跟随重定向会丢掉 Authorization，最后变成一个看不出所以然的 403，
                // 所以这里必须拦下来，当成令牌失效交给上层续期。
                response.code in 300..399 -> {
                    record(url, accessToken, response, body = null)
                    throw GarminNetworkException.Unauthorized(
                        "令牌已失效（HTTP ${response.code} 跳转到登录页），需要重新换取"
                    )
                }
                // 401 可自愈：由调用方换新令牌后重试，这里只负责准确上报
                response.code == 401 -> {
                    record(url, accessToken, response, body = null)
                    throw GarminNetworkException.Unauthorized()
                }
                // 403：把 Garmin 的原话留下来。看不出原因时，用户至少能把它发出来
                response.code == 403 -> {
                    val body = GarminDiagnostics.snippet(response.body?.string())
                    record(url, accessToken, response, body)
                    throw GarminNetworkException.Forbidden(endpoint, body)
                }
                response.code == 429 -> throw GarminNetworkException.RiskBlocked(
                    IOException("Connect API 限流（429）：${url.encodedPath}"),
                    retryAfterMs = retryAfterMs(response),
                )
                response.code >= 500 -> throw GarminNetworkException.NetworkUnavailable(
                    IOException("Connect API ${response.code}")
                )
                !response.isSuccessful -> return null
            }
            return response.body?.string()
        }
    }

    /** 把一次非成功响应写进诊断记录（不落令牌原文） */
    private fun record(url: HttpUrl, accessToken: String, response: Response, body: String?) {
        GarminDiagnostics.record(
            GarminDiagnostics.Entry(
                atMs = System.currentTimeMillis(),
                endpoint = url.encodedPath,
                method = "GET",
                code = response.code,
                tokenLength = accessToken.length,
                host = url.host,
                cfRay = response.header("cf-ray"),
                location = response.header("Location"),
                bodySnippet = GarminDiagnostics.snippet(body),
            )
        )
    }

    // ============================ 账号路径标识 ============================

    /**
     * 解析 `/usersummary-service/usersummary/daily/{user}` 里的 `{user}`。
     *
     * 背景：部分账号（尤其国际区老账号）`socialProfile.displayName` 是空的，
     * 昵称位置只剩一个 GUID（`371d55ac-80da-47eb-...`）。Garmin 的日汇总接口
     * **不认 GUID**，只认 `displayName` 或数字 `profileId`——拿 GUID 去请求会一直
     * 404，表现出来就是"首页没有步数、搬运找不到源数据"。
     *
     * 顺序：`displayName`（非 GUID）→ `profileId`（数字）→ 传入的 fallback。
     * 结果缓存 30 分钟，避免每拉一天数据就打一次 profile 接口。
     */
    fun resolvePathUser(
        region: AccountRegion,
        accessToken: String,
        fallback: String,
    ): String {
        // 正常的 displayName 直接用，不为每次同步多打一个请求
        if (fallback.isNotBlank() && !looksLikeGuid(fallback)) return fallback
        if (accessToken.isBlank()) return fallback
        val key = "${region.name}|$fallback|${accessToken.takeLast(10)}"
        val now = System.currentTimeMillis()
        pathUserCache[key]?.let { if (it.expiresAtMs > now) return it.value }

        // 404 / 解析失败退回 fallback 即可；403 / 429 必须原样抛出，否则会被误当成
        // 「只是没有昵称」，真正的拦截原因要等到后面几十个 404 才暴露
        val resolved = try {
            fetchPathUser(region, accessToken)
        } catch (e: GarminNetworkException.Forbidden) {
            throw e
        } catch (e: GarminNetworkException.RiskBlocked) {
            throw e
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() && !looksLikeGuid(it) } ?: fallback
        if (resolved.isNotBlank()) {
            pathUserCache[key] = CachedPathUser(resolved, now + 30 * 60_000L)
        }
        return resolved
    }

    /** 只查一次 socialProfile，取能直接放进 URL 的标识 */
    private fun fetchPathUser(region: AccountRegion, accessToken: String): String? {
        val ep = endpoints(region)
        // 走 getText：UA 被边缘拦下时它会自动换浏览器 UA 重试，
        // 403 / 429 也由那里统一抛出，不再被吞成「查不到昵称」
        val text = getText(
            (ep.apiBase + "/userprofile-service/socialProfile").toHttpUrl(),
            accessToken,
        ) ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        // displayName 优先；为空或是 GUID 时退回数字 profileId
        json.optString("displayName").takeIf { it.isNotBlank() && !looksLikeGuid(it) }
            ?.let { return it }
        json.optString("userName").takeIf { it.isNotBlank() && !looksLikeGuid(it) }
            ?.let { return it }
        json.optLong("profileId", 0L).takeIf { it > 0 }?.let { return it.toString() }
        json.optLong("id", 0L).takeIf { it > 0 }?.let { return it.toString() }
        return null
    }

    private fun looksLikeGuid(value: String): Boolean =
        value.length >= 32 && value.count { it == '-' } >= 4 && value.all { it.isLetterOrDigit() || it == '-' }

    /**
     * FIT 的 `serialNumber` 是 uint32z 字段，超过 4294967294 的值写进去会变成协议里的
     * invalid，Garmin 收到后只回一个空 uploadId（真机表现：HTTP 419）。
     * 中国区设备列表对部分设备下发 9223372036854775807，必须挡掉。
     */
    private fun isFitSerial(value: Long): Boolean = value in 1L..FIT_SERIAL_MAX

    // ============================ 设备列表（健康数据搬运的前提） ============================

    data class GarminDevice(
        val name: String,
        val serialNumber: Long,
        val productId: Int,
        val primary: Boolean,
        /** 序列号不是取自 `serialNumber`（该区下发的 serialNumber 是无效值，改用 unitId/deviceId） */
        val serialFromUnitId: Boolean = false,
        /** Garmin 内部设备 ID，排查设备绑定关系时用得上 */
        val deviceId: Long = 0,
        /** 接口原始 serialNumber，可能是 `9223372036854775807` 这类哨兵值 */
        val rawSerialNumber: Long = 0,
    ) {
        /** 供界面/日志展示的身份串 */
        val identity: String
            get() = buildString {
                append(name.ifBlank { "未命名设备" })
                append(" · SN ").append(serialNumber)
                if (productId > 0) append(" · PID ").append(productId)
                if (serialFromUnitId) append("（SN 取自 unitId）")
            }
    }

    /**
     * 查询账号下绑定的设备。
     *
     * 健康数据（monitoring）只有在账号绑定了「主要可穿戴设备」时才会被计入，
     * 所以搬运前必须拿到目标账号的设备身份（serial number / product）。
     */
    fun fetchDevices(region: AccountRegion, accessToken: String): List<GarminDevice> =
        fetchDevicesDetailed(region, accessToken).devices

    /** 设备列表查询结果：失败时 [error] 会说明具体原因，避免一律报“没有绑定设备” */
    data class DeviceFetch(
        val devices: List<GarminDevice> = emptyList(),
        val error: String? = null,
        /** 接口原始返回（截断），排查“读到了设备但字段不对”时用 */
        val rawSnippet: String = "",
    )

    /**
     * 查询设备列表，并把失败原因带出来。
     *
     * 旧实现把 401/403/429 和解析失败都吃掉返回空列表，界面只会显示
     * “目标账号没有绑定设备”，根本无法区分是没绑、令牌过期还是接口被拦。
     */
    fun fetchDevicesDetailed(region: AccountRegion, accessToken: String): DeviceFetch {
        val ep = endpoints(region)
        val url = (ep.apiBase + "/device-service/deviceregistration/devices").toHttpUrl()
        val text = try {
            getText(url, accessToken)
        } catch (e: Exception) {
            return DeviceFetch(error = "设备接口失败：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
        } ?: return DeviceFetch(error = "设备接口返回非 2xx（该区可能不开放此接口）")

        // 顶层可能是对象也可能是数组（中国区返回 [...]），两种都要能吃下
        val trimmed = text.trimStart()
        val root: Any = runCatching {
            if (trimmed.startsWith("[")) JSONArray(text) else JSONObject(text)
        }.getOrNull() ?: return DeviceFetch(error = "设备接口返回无法解析：${text.take(120)}")

        val out = mutableListOf<GarminDevice>()
        /** 有名字但序列号是哨兵值的节点，稍后用设备详情接口补全 */
        val unresolved = mutableListOf<GarminDevice>()
        val seenKeys = linkedSetOf<String>()

        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (seenKeys.size < 24) {
                        node.keys().forEach { if (seenKeys.size < 24) seenKeys += it }
                    }
                    val info = serialOf(node)
                    val name = deviceNameOf(node)
                    val deviceId = jsonLongs(node, "deviceId", "device_id").firstOrNull { it > 0 } ?: 0L
                    // 只有"像设备"的节点才算：必须有名称，
                    // 否则外层容器、设置对象里随便一个 serial 字段都会被误当成设备
                    if (name.isNotBlank()) {
                        val candidate = GarminDevice(
                            name = name,
                            serialNumber = info.serial,
                            productId = productIdOf(node),
                            primary = node.optBoolean("primary") || node.optBoolean("isPrimary"),
                            serialFromUnitId = info.fromUnit,
                            deviceId = deviceId,
                            rawSerialNumber = info.raw,
                        )
                        if (info.serial > 0) {
                            out += candidate
                            // 序列号是兜底字段来的、或者产品 ID 缺失，都值得再问一次
                            // 设备详情，拿 Garmin 自己的权威值
                            if (info.fromUnit || candidate.productId == 0) unresolved += candidate
                        } else {
                            unresolved += candidate
                        }
                    }
                    node.keys().forEach { key -> scan(node.opt(key)) }
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i))
            }
        }
        scan(root)

        // 列表接口只下发哨兵值（9223372036854775807）时，再问一次设备详情，
        // 多数情况下能补到真实序列号；补不到就保持未解析，交给上层报错。
        val sawSentinelSerial = unresolved.firstOrNull { it.rawSerialNumber > 0 }?.rawSerialNumber ?: 0L
        unresolved.forEach { candidate ->
            val fixed = runCatching {
                fetchDeviceDetail(region, accessToken, candidate.deviceId, candidate.name)
            }.getOrNull() ?: return@forEach
            val index = out.indexOfFirst {
                it.serialNumber == candidate.serialNumber ||
                    (candidate.deviceId > 0 && it.deviceId == candidate.deviceId)
            }
            if (index >= 0) out[index] = fixed else out += fixed
        }

        // 主设备优先，其次“产品 ID 齐全”的设备
        val distinct = out.distinctBy { it.serialNumber }
            .sortedWith(
                compareByDescending<GarminDevice> { it.primary && it.productId > 0 }
                    .thenByDescending { it.productId > 0 }
                    .thenByDescending { it.primary }
            )
        return if (distinct.isEmpty()) {
            DeviceFetch(
                error = when {
                    sawSentinelSerial != 0L ->
                        "设备列表里的 serialNumber 是无效值（$sawSentinelSerial，Garmin 用它表示“未设置”），" +
                            "设备详情接口也没补到真实序列号；字段：" +
                            seenKeys.joinToString(",").ifBlank { "无" }
                    else -> "设备列表未解析出序列号（返回 ${text.length} 字符；字段：" +
                        seenKeys.joinToString(",").ifBlank { "无" } + "）"
                },
                rawSnippet = text.take(600),
            )
        } else {
            DeviceFetch(devices = distinct, rawSnippet = text.take(600))
        }
    }

    /** 设备序列号解析结果：`raw` 是接口原值，用于诊断哨兵值 */
    private data class SerialInfo(val serial: Long, val fromUnit: Boolean, val raw: Long)

    private fun serialOf(node: JSONObject): SerialInfo {
        val rawCandidates = jsonLongs(node, "serialNumber", "serialNo", "serial_number")
        val raw = rawCandidates.firstOrNull() ?: 0L
        rawCandidates.firstOrNull { isFitSerial(it) }?.let { return SerialInfo(it, false, raw) }
        jsonLongs(node, "unitId", "unit_id").firstOrNull { isFitSerial(it) }
            ?.let { return SerialInfo(it, true, raw) }
        jsonLongs(node, "deviceId", "device_id").firstOrNull { isFitSerial(it) }
            ?.let { return SerialInfo(it, true, raw) }
        return SerialInfo(0L, false, raw)
    }

    private fun productIdOf(node: JSONObject): Int =
        jsonLongs(node, "productId", "product_id", "productNumber", "product")
            .firstOrNull { it in 1L..65535L }?.toInt() ?: 0

    private fun deviceNameOf(node: JSONObject): String =
        node.optString("productDisplayName")
            .ifBlank { node.optString("displayName") }
            .ifBlank { node.optString("productName") }
            .ifBlank { node.optString("deviceName") }

    /** 数字与字符串两种写法都要吃得下（Garmin 各区域返回不一致） */
    private fun jsonLongs(node: JSONObject, vararg keys: String): List<Long> {
        val values = mutableListOf<Long>()
        for (key in keys) {
            val value = node.opt(key) ?: continue
            val parsed = when (value) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull() ?: 0L
                else -> 0L
            }
            if (parsed > 0) values += parsed
        }
        return values
    }

    /**
     * 用设备详情接口补全单个设备。
     *
     * `/device-service/deviceregistration/devices` 在中国区对部分设备只返回哨兵序列号，
     * 详情接口才会给出真实值。两个候选路径都试，失败就返回 null（不打扰上层）。
     */
    private fun fetchDeviceDetail(
        region: AccountRegion,
        accessToken: String,
        deviceId: Long,
        fallbackName: String,
    ): GarminDevice? {
        if (deviceId <= 0) return null
        val ep = endpoints(region)
        val paths = listOf(
            "/device-service/deviceregistration/device/$deviceId",
            "/device-service/devices/$deviceId",
        )
        for (path in paths) {
            val text = runCatching { getText((ep.apiBase + path).toHttpUrl(), accessToken) }
                .getOrNull() ?: continue
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val info = serialOf(obj)
            if (info.serial <= 0) continue
            return GarminDevice(
                name = deviceNameOf(obj).ifBlank { fallbackName },
                serialNumber = info.serial,
                productId = productIdOf(obj),
                primary = obj.optBoolean("primary") || obj.optBoolean("isPrimary"),
                serialFromUnitId = info.fromUnit,
                deviceId = deviceId,
                rawSerialNumber = info.raw,
            )
        }
        return null
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

    /**
     * 下载某一天的 wellness 原始包（zip，内含设备当天上传的 FIT）。
     *
     * 这是 Garmin 自己的「导出当天健康数据」接口。健康数据搬运优先走这条路：
     * 步数、睡眠分期、压力、身体电量都在原始 FIT 里，不需要本机重新拼 monitoring 文件。
     *
     * 404 表示这天没有 wellness 数据（没戴表/没同步），返回 null；
     * 401 / 429 / 5xx 照常抛，让上层能把"没数据"和"被限流"分开说。
     *
     * 来源说明：接口路径与用法参考了 DailySync
     * （https://github.com/gooin/dailysync-rev，GPL-3.0），未使用其代码，详见
     * THIRD_PARTY_NOTICES.md。
     */
    fun downloadWellnessZip(
        region: AccountRegion,
        accessToken: String,
        date: String,
    ): ByteArray? {
        val ep = endpoints(region)
        val url = (ep.apiBase + "/download-service/files/wellness/" + encodePath(date))
            .toHttpUrl()
        // 走 getRaw：UA 被 Cloudflare 拦下时会自动换浏览器 UA 再试一次
        getRaw(url, accessToken, accept = "*/*").use { response ->
            when {
                // 与 getText 一致：3xx 说明令牌不认了，跟着跳过去只会丢掉 Authorization
                response.code in 300..399 -> {
                    record(url, accessToken, response, body = null)
                    throw GarminNetworkException.Unauthorized(
                        "令牌已失效（HTTP ${response.code} 跳转到登录页），需要重新换取"
                    )
                }
                response.code == 401 -> {
                    record(url, accessToken, response, body = null)
                    throw GarminNetworkException.Unauthorized()
                }
                response.code == 403 -> {
                    // 二进制接口，读一小段就够判断是 JSON 报错还是 HTML 登录页
                    val body = GarminDiagnostics.snippet(response.peekBody(512).string())
                    record(url, accessToken, response, body)
                    throw GarminNetworkException.Forbidden("wellness/$date", body)
                }
                response.code == 429 -> throw GarminNetworkException.RiskBlocked(
                    IOException("wellness 下载 429"),
                    retryAfterMs = retryAfterMs(response),
                )
                response.code >= 500 -> throw GarminNetworkException.NetworkUnavailable(
                    IOException("wellness 下载 ${response.code}")
                )
                !response.isSuccessful -> return null
            }
            return response.body?.bytes()
        }
    }

    private fun unpackActivity(bytes: ByteArray, activityId: String): DownloadedActivity? {        val isZip = bytes.size > 4 &&
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
     * 上传结果。失败时保留 HTTP 状态码和响应片段，便于判断是令牌、风控还是格式问题。
     */
    data class UploadOutcome(
        val ok: Boolean,
        val code: Int,
        val body: String,
        /** Garmin 自己给的失败原因（`detailedImportResult.messages[].content`） */
        val detail: String = "",
        /** 服务端返回的 uploadId，空表示文件没有被真正接收 */
        val uploadId: String = "",
        /** 每条路由的尝试结果，例如 `Bearer/API 419、OAuth1/API 401` */
        val trace: String = "",
        /** 目标账号已有同一份 wellness 文件（HTTP 409 / Duplicate Wellness File）——不是失败 */
        val duplicate: Boolean = false,
        /** 被边缘防护按 UA 拦下（403 / 带 cf-ray 的 429），内部用来决定是否换 UA 重试 */
        val edgeBlocked: Boolean = false,
    ) {
        /** 给用户看的一句话 */
        val errorText: String
            get() {
                if (ok) return ""
                val base = when {
                    code == 401 -> "HTTP 401 认证未通过"
                    code == 403 -> "HTTP 403 被风控拦截"
                    code == 419 -> buildString {
                        append("HTTP 419 上传被拒")
                        when {
                            detail.isNotBlank() -> append("：").append(detail)
                            uploadId.isBlank() -> append("（服务端没有返回 uploadId，文件里的设备身份多半不被接受）")
                            else -> append("（").append(body.take(120)).append("）")
                        }
                    }
                    code == 429 -> "HTTP 429 触发限流"
                    code in 500..599 -> buildString {
                        append("HTTP $code 服务端处理失败")
                        val snippet = body.trim().take(160)
                        if (snippet.isNotBlank()) append("：").append(snippet)
                    }
                    code <= 0 -> "请求未发出：$body"
                    detail.isNotBlank() -> "HTTP $code $detail"
                    else -> "HTTP $code ${body.take(160)}".trim()
                }
                return if (trace.isNotBlank()) "$base（尝试：$trace）" else base
            }
    }

    fun uploadActivityFile(
        region: AccountRegion,
        accessToken: String,
        fileName: String,
        bytes: ByteArray,
    ): String? = uploadActivityFileOutcome(region, accessToken, fileName, bytes)
        .takeIf { it.ok }?.body

    /**
     * 上传 FIT 到 `upload-service`。
     *
     * 几个细节都是真机踩出来的（HTTP 419，响应体 `uploadId` 为空、`uploadUuid` 是 "null"）：
     * 1. 路径必须带 `.fit` 后缀（`/upload-service/upload/.fit`）；
     * 2. 需要 `Origin` / `Referer` / `NK` / `X-Requested-With` 这些移动端同款头；
     * 3. 部分区域的上传路由只认 OAuth1 签名而不是 DI Bearer，所以带上 OAuth1 再试一次；
     * 4. API 域不开放时再换 Web 域（connect.garmin.cn / connect.garmin.com）。
     *
     * 每次都只在"路由 / 认证方式"类错误上继续，401 令牌失效与 429 限流直接返回，
     * 避免把一次失败放大成 6 次请求。
     */
    fun uploadActivityFileOutcome(
        region: AccountRegion,
        accessToken: String,
        fileName: String,
        bytes: ByteArray,
        oauth1Token: String = "",
        oauth1Secret: String = "",
        /** 表单字段名：活动用 `file`，wellness 走 `userfile`（与官方页面一致） */
        fieldName: String = "file",
    ): UploadOutcome {
        val ep = endpoints(region)
        val safeName = if (fileName.endsWith(".fit", ignoreCase = true)) fileName else "$fileName.fit"
        val hasOAuth1 = oauth1Token.isNotBlank() && oauth1Secret.isNotBlank()

        val apiUrl = "${ep.apiBase}/upload-service/upload/.fit"
        val webUrl = "${ep.webBase}/upload-service/upload/.fit"

        val attempts = mutableListOf<Pair<String, UploadOutcome>>()
        fun attempt(label: String, url: String, signed: Boolean, web: Boolean): UploadOutcome {
            val outcome = if (signed) {
                postFit(url, ep, accessToken, safeName, bytes, oauth1Token, oauth1Secret, web, fieldName)
            } else {
                postFit(url, ep, accessToken, safeName, bytes, web = web, fieldName = fieldName)
            }
            attempts += label to outcome
            return outcome
        }

        val bearerApi = attempt("Bearer/API", apiUrl, false, false)
        // 2xx 就说明路由和认证都对：此时"uploadId 为空"是文件内容被拒，
        // 换域名 / 换认证方式都不会变好，直接把这个结果带回去。
        // 409 同理：对方已经有这份文件，换路由也还是 409。
        if (bearerApi.ok || bearerApi.duplicate || bearerApi.code in 200..299 ||
            bearerApi.code == 429
        ) {
            return bearerApi.withTrace(attempts)
        }
        if (bearerApi.code == 401 && !hasOAuth1) return bearerApi.withTrace(attempts)

        if (hasOAuth1) {
            val oauthApi = attempt("OAuth1/API", apiUrl, true, false)
            if (oauthApi.ok || oauthApi.code in 200..299 || oauthApi.code == 429) {
                return oauthApi.withTrace(attempts)
            }
        }

        val bearerWeb = attempt("Bearer/Web", webUrl, false, true)
        if (bearerWeb.ok || bearerWeb.code in 200..299) return bearerWeb.withTrace(attempts)

        if (hasOAuth1) {
            val oauthWeb = attempt("OAuth1/Web", webUrl, true, true)
            if (oauthWeb.ok) return oauthWeb.withTrace(attempts)
        }

        // 都失败时挑信息量最大的一个：有 Garmin 原文 > 5xx > 419 > 第一条（API 域）
        val outcomes = attempts.map { it.second }
        val best = outcomes.firstOrNull { it.detail.isNotBlank() }
            ?: outcomes.firstOrNull { it.code in 500..599 }
            ?: outcomes.firstOrNull { it.code == 419 }
            ?: outcomes.first()
        return best.withTrace(attempts)
    }

    private fun UploadOutcome.withTrace(attempts: List<Pair<String, UploadOutcome>>): UploadOutcome {
        if (attempts.size <= 1) return this
        return copy(trace = attempts.joinToString("、") { "${it.first} ${it.second.code}" })
    }

    private fun postFit(
        url: String,
        ep: GarminEndpoints,
        accessToken: String,
        fileName: String,
        bytes: ByteArray,
        oauth1Token: String = "",
        oauth1Secret: String = "",
        web: Boolean = false,
        fieldName: String = "file",
    ): UploadOutcome {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fieldName,
                fileName,
                bytes.toRequestBody("application/octet-stream".toMediaType()),
            )
            .build()

        // 身份头只有一套，不再按 403 切换（切了会丢 X-Garmin-*，反而一直 403）
        fun newBuilder(): Request.Builder {
            val b = Request.Builder()
                .url(url.toHttpUrl())
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json, text/plain, */*")
            GarminUserAgent.deviceHeaders().forEach { (name, value) -> b.header(name, value) }
            if (web) {
                // Web 域的这条路是按"网页上传"走的，才需要来源头
                b.header("Origin", ep.webBase)
                    .header("Referer", ep.webBase + "/modern/")
                    .header("X-Requested-With", "XMLHttpRequest")
            }
            return b
        }

        var request = newBuilder().post(multipart).build()

        // OAuth1 兜底：某些区域的上传路由只认 `Authorization: OAuth ...`，不认 DI Bearer。
        // 签名不包含 multipart body（OAuth1 规范里 multipart 不参与签名基串）。
        if (oauth1Token.isNotBlank() && oauth1Secret.isNotBlank()) {
            request = OAuth1Signer.sign(request, oauth1Token, oauth1Secret)
        }

        return executeUpload(request)
    }

    private fun executeUpload(request: Request): UploadOutcome =
        try {
            // restClient：上传同样不能用「跟随重定向」，否则令牌过期时的 302 会
            // 变成一个丢掉 Authorization 的 403
            restClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val edge = response.code == 403 ||
                    (response.code == 429 && response.header("cf-ray") != null)
                if (response.code >= 300) {
                    GarminDiagnostics.record(
                        GarminDiagnostics.Entry(
                            atMs = System.currentTimeMillis(),
                            endpoint = request.url.encodedPath,
                            method = "POST",
                            code = response.code,
                            tokenLength = request.header("Authorization")?.length ?: 0,
                            host = request.url.host,
                            cfRay = response.header("cf-ray"),
                            location = response.header("Location"),
                            bodySnippet = GarminDiagnostics.snippet(text),
                        )
                    )
                }
                parseUpload(response.code, text).copy(edgeBlocked = edge)
            }
        } catch (e: Exception) {
            UploadOutcome(ok = false, code = 0, body = e.message ?: e.javaClass.simpleName)
        }

    /**
     * 判成功不能只看 2xx：Garmin 在"文件被接收但内容被拒"时会返回 200/419 且
     * `uploadId` 为空，真正的失败原因在 `detailedImportResult.messages` 里。
     */
    private fun parseUpload(code: Int, text: String): UploadOutcome {
        val root = runCatching { JSONObject(text) }.getOrNull()
        val result = root?.optJSONObject("detailedImportResult")

        val uploadId = result?.optString("uploadId").orEmpty()
        val messages = buildList {
            val arr = result?.optJSONArray("messages")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val content = arr.optJSONObject(i)?.optString("content").orEmpty()
                    if (content.isNotBlank()) add(content)
                }
            }
        }
        val detail = messages.joinToString("；").ifBlank {
            // 少数情况只给一个字符串字段
            result?.optString("message").orEmpty()
        }

        // 409 / "Duplicate Wellness File"：目标账号已有这一天，属于正常结果
        val duplicate = code == 409 || text.contains("Duplicate Wellness File", ignoreCase = true)

        val success = !duplicate && code in 200..299 &&
            messages.isEmpty() &&
            (uploadId.isNotBlank() || result == null)
        return UploadOutcome(
            ok = success,
            code = code,
            body = text,
            detail = detail,
            uploadId = uploadId,
            duplicate = duplicate,
        )
    }

    private fun getJson(
        url: HttpUrl,
        accessToken: String,
        onStatus: ((Int) -> Unit)? = null,
    ): JSONObject? {
        val text = getText(url, accessToken, onStatus) ?: return null
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
