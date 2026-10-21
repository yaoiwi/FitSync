package com.fitgarminsync.data.importer

import com.fitgarminsync.data.db.AccountEntity
import com.fitgarminsync.data.db.ActivityEntity
import com.fitgarminsync.data.db.AccountStatus
import com.fitgarminsync.data.db.DailySummaryEntity
import com.fitgarminsync.data.db.FitDataRepository
import com.fitgarminsync.data.db.SleepSessionEntity
import com.fitgarminsync.data.db.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地数据导入：不依赖任何账号，把「自己导出的运动/健康数据」直接写进本地库。
 *
 * 支持：
 * 1. **Garmin 官方数据导出 zip**：自动遍历包内 JSON（不依赖目录名）；
 * 2. 单个 **JSON**（Garmin 的 UDS / summarizedActivities / sleepData 结构）；
 * 3. **CSV**（表头中文/英文均可，见 [CSV_ALIASES]）。
 *
 * 解析策略是「字段嗅探 + 递归扫描」而不是绑定某个固定文件结构：
 * Garmin 的导出包在不同年份、不同地区的目录名与字段名有差异，绑定结构非常容易整批失败。
 * 认不出字段的对象会被跳过并计入 skipped，不会中断整次导入。
 */
@Singleton
class LocalDataImporter @Inject constructor(
    private val repository: FitDataRepository,
) {

    data class ImportResult(
        val files: Int = 0,
        val daily: Int = 0,
        val sleep: Int = 0,
        val activities: Int = 0,
        val skipped: Int = 0,
        val errors: List<String> = emptyList(),
    ) {
        val total: Int get() = daily + sleep + activities
        val isEmpty: Boolean get() = total == 0
    }

    suspend fun import(bytes: ByteArray, fileName: String): ImportResult = withContext(Dispatchers.IO) {
        val sources = unpack(bytes, fileName)
        if (sources.isEmpty()) {
            return@withContext ImportResult(errors = listOf("文件里没有找到可识别的数据（支持 Garmin 导出 zip / JSON / CSV）"))
        }

        val accountId = ensureLocalAccount()
        var daily = 0
        var sleep = 0
        var activities = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        val dailyRows = linkedMapOf<String, DailySummaryEntity>()
        val sleepRows = linkedMapOf<String, SleepSessionEntity>()
        val activityRows = linkedMapOf<String, ActivityEntity>()

        sources.forEach { (name, text) ->
            runCatching {
                when {
                    name.endsWith(".csv", true) -> parseCsv(text, accountId, dailyRows, sleepRows, activityRows)
                    else -> parseJson(text, accountId, dailyRows, sleepRows, activityRows)
                }
            }.onFailure { e ->
                errors += "$name：${e.message ?: e.javaClass.simpleName}"
            }
        }

        if (dailyRows.isNotEmpty()) {
            repository.upsertDailySummaries(dailyRows.values.toList())
            daily = dailyRows.size
        }
        sleepRows.values.forEach {
            repository.upsertSleepSession(it)
            sleep++
        }
        activityRows.values.forEach {
            repository.upsertActivity(it)
            activities++
        }
        skipped = sources.size - errors.size - (if (daily + sleep + activities > 0) 1 else 0)
            .coerceAtLeast(0)

        ImportResult(
            files = sources.size,
            daily = daily,
            sleep = sleep,
            activities = activities,
            skipped = skipped,
            errors = errors,
        )
    }

    // ============================ 解包 ============================

    private fun unpack(bytes: ByteArray, fileName: String): List<Pair<String, String>> {
        val isZip = bytes.size > 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        if (!isZip) return listOf(fileName to bytes.toString(Charsets.UTF_8))

        val out = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory &&
                    (name.endsWith(".json", true) || name.endsWith(".csv", true)) &&
                    entry.size < MAX_ENTRY_BYTES
                ) {
                    runCatching { out += name to zip.readBytes().toString(Charsets.UTF_8) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    // ============================ JSON ============================

    private fun parseJson(
        text: String,
        accountId: Long,
        daily: MutableMap<String, DailySummaryEntity>,
        sleep: MutableMap<String, SleepSessionEntity>,
        activities: MutableMap<String, ActivityEntity>,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        when (trimmed.first()) {
            '{' -> scan(JSONObject(trimmed), accountId, daily, sleep, activities)
            '[' -> scan(JSONArray(trimmed), accountId, daily, sleep, activities)
            else -> return
        }
    }

    private fun scan(
        node: Any?,
        accountId: Long,
        daily: MutableMap<String, DailySummaryEntity>,
        sleep: MutableMap<String, SleepSessionEntity>,
        activities: MutableMap<String, ActivityEntity>,
    ) {
        when (node) {
            is JSONObject -> {
                readDaily(node, accountId)?.let { daily.putIfAbsent(it.date, it) }
                readSleep(node, accountId)?.let { sleep.putIfAbsent(it.externalId, it) }
                readActivity(node, accountId)?.let { activities.putIfAbsent(it.externalId, it) }
                node.keys().forEach { key -> scan(node.opt(key), accountId, daily, sleep, activities) }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                scan(node.opt(i), accountId, daily, sleep, activities)
            }
        }
    }

    private fun readDaily(o: JSONObject, accountId: Long): DailySummaryEntity? {
        val date = firstString(o, "calendarDate", "calendar_date", "date", "day")
            ?.takeIf { it.matches(DATE_REGEX) } ?: return null
        val steps = firstInt(o, "totalSteps", "steps", "stepCount")
        val distance = firstDouble(o, "totalDistanceMeters", "distanceMeters", "distance")
        val calories = firstDouble(o, "activeKilocalories", "activeCalories", "calories")
        val resting = firstDouble(o, "restingKilocalories", "restingCalories")
        if (steps == 0 && distance == 0.0 && calories == 0.0 && resting == 0.0 &&
            firstInt(o, "restingHeartRate", "restingHr") == 0
        ) {
            return null
        }
        return DailySummaryEntity(
            accountId = accountId,
            date = date,
            steps = steps,
            distanceM = distance,
            floors = firstInt(o, "floorsClimbed", "floors"),
            caloriesActive = calories,
            caloriesResting = resting,
            restingHr = firstInt(o, "restingHeartRate", "restingHr"),
            hrvMs = firstInt(o, "hrv", "lastNightAvgHrv"),
            respiratoryRate = firstInt(o, "avgRespirationRate", "respiratoryRate"),
            stressAvg = firstInt(o, "averageStressLevel", "avgStressLevel"),
            stressMax = firstInt(o, "maxStressLevel", "stressMax"),
            bodyBatteryMin = firstInt(o, "bodyBatteryLowestValue", "bodyBatteryMin"),
            bodyBatteryMax = firstInt(o, "bodyBatteryHighestValue", "bodyBatteryMax"),
            spo2Avg = firstInt(o, "averageSpo2", "avgSpo2", "averageSpo2Value"),
        )
    }

    private fun readSleep(o: JSONObject, accountId: Long): SleepSessionEntity? {
        val dto = o.optJSONObject("dailySleepDTO") ?: o
        val totalSec = firstInt(dto, "sleepTimeSeconds", "totalSleepSeconds", "totalSleepMin")
        if (totalSec == 0) return null
        val unitIsMinutes = dto.has("totalSleepMin")
        val totalMin = if (unitIsMinutes) totalSec else totalSec / 60

        val date = firstString(dto, "calendarDate", "date")
            ?: firstString(o, "calendarDate", "date")
            ?: return null
        val start = firstLong(dto, "sleepStartTimestampGMT", "sleepStartTimestampLocal", "startTimeMs")
        val end = firstLong(dto, "sleepEndTimestampGMT", "sleepEndTimestampLocal", "endTimeMs")
        val awakeSec = firstInt(dto, "awakeSleepSeconds", "awakeDurationSeconds")
        val score = dto.optJSONObject("sleepScores")
            ?.optJSONObject("overall")
            ?.optInt("value", 0)
            ?: firstInt(dto, "sleepScore", "overallSleepScore")

        return SleepSessionEntity(
            accountId = accountId,
            externalId = "sleep-$date",
            startTimeMs = start,
            endTimeMs = end,
            totalSleepMin = totalMin,
            inBedMin = totalMin + awakeSec / 60,
            sleepScore = score,
            avgHr = firstInt(dto, "avgHeartRate", "averageHeartRate"),
            avgSpo2 = firstInt(dto, "averageSpO2Value", "avgSpo2"),
            avgBreathing = firstDouble(dto, "averageRespirationValue", "avgRespiration"),
        )
    }

    private fun readActivity(o: JSONObject, accountId: Long): ActivityEntity? {
        val id = firstString(o, "activityId", "activity_id") ?: return null
        val type = o.optJSONObject("activityType")?.optString("typeKey").orEmpty()
            .ifBlank { firstString(o, "activityType", "sport").orEmpty() }
        val startRaw = firstString(o, "startTimeGMT", "startTimeLocal", "startTime")
            ?: return null
        val startMs = parseTime(startRaw) ?: return null
        return ActivityEntity(
            accountId = accountId,
            externalId = id,
            sport = type.ifBlank { "unknown" },
            activityType = type,
            startTimeMs = startMs,
            durationSec = firstDouble(o, "duration", "durationSeconds").toLong(),
            distanceM = firstDouble(o, "distance", "distanceMeters"),
            avgHr = firstInt(o, "averageHR", "avgHr"),
            maxHr = firstInt(o, "maxHR", "maxHr"),
            calories = firstDouble(o, "calories", "activeKilocalories"),
            avgSpeed = firstDouble(o, "averageSpeed", "avgSpeed"),
            elevationGainM = firstDouble(o, "elevationGain", "elevationGainMeters"),
            hasGps = firstDouble(o, "distance", "distanceMeters") > 0,
        )
    }

    // ============================ CSV ============================

    private fun parseCsv(
        text: String,
        accountId: Long,
        daily: MutableMap<String, DailySummaryEntity>,
        sleep: MutableMap<String, SleepSessionEntity>,
        activities: MutableMap<String, ActivityEntity>,
    ) {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return
        val header = splitCsv(lines.first()).map { it.trim().lowercase(Locale.ROOT) }
        val index = header.withIndex().associate { (i, name) -> name to i }

        lines.drop(1).forEach { line ->
            val cells = splitCsv(line)
            fun cell(vararg aliases: String): String? {
                aliases.forEach { alias ->
                    val i = index[alias] ?: return@forEach
                    cells.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let { return it }
                }
                return null
            }

            val date = cell("date", "日期", "day", "calendar_date")
                ?.let { normalizeDate(it) } ?: return@forEach
            val steps = cell("steps", "步数", "totalsteps")?.toIntOrNull() ?: 0
            val distance = cell("distance", "distance_m", "distancemeters", "距离")?.toDoubleOrNull() ?: 0.0
            val calories = cell("calories", "activecalories", "卡路里", "activekilocalories")?.toDoubleOrNull() ?: 0.0
            val sleepMin = cell("sleep_min", "sleepminutes", "睡眠分钟", "totalSleepMin".lowercase())?.toIntOrNull() ?: 0

            if (steps > 0 || distance > 0 || calories > 0) {
                daily.putIfAbsent(
                    date,
                    DailySummaryEntity(
                        accountId = accountId,
                        date = date,
                        steps = steps,
                        distanceM = distance,
                        caloriesActive = calories,
                        restingHr = cell("resting_hr", "restingheartrate", "静息心率")?.toIntOrNull() ?: 0,
                    ),
                )
            }
            if (sleepMin > 0) {
                sleep.putIfAbsent(
                    "sleep-$date",
                    SleepSessionEntity(
                        accountId = accountId,
                        externalId = "sleep-$date",
                        startTimeMs = 0,
                        endTimeMs = parseTime("$date 23:59:59") ?: 0,
                        totalSleepMin = sleepMin,
                        sleepScore = cell("sleep_score", "睡眠评分")?.toIntOrNull() ?: 0,
                    ),
                )
            }
            val activityId = cell("activityid", "activity_id", "id")
            val sport = cell("sport", "type", "activitytype", "运动类型")
            if (activityId != null && sport != null) {
                activities.putIfAbsent(
                    activityId,
                    ActivityEntity(
                        accountId = accountId,
                        externalId = activityId,
                        sport = sport,
                        activityType = sport,
                        startTimeMs = parseTime(cell("starttimegmt", "starttime", "开始时间") ?: date) ?: 0,
                        durationSec = (cell("duration", "durationseconds", "时长")?.toDoubleOrNull() ?: 0.0).toLong(),
                        distanceM = distance,
                        avgHr = cell("averagehr", "avghr", "平均心率")?.toIntOrNull() ?: 0,
                        maxHr = cell("maxhr", "最大心率")?.toIntOrNull() ?: 0,
                        calories = calories,
                    ),
                )
            }
        }
    }

    /** 极简 CSV 分割：支持双引号包裹与 "" 转义（够用，不引第三方） */
    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += sb.toString(); sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    // ============================ 工具 ============================

    private suspend fun ensureLocalAccount(): Long {
        val existing = repository.getAccountByUniqueKey(
            sourceType = SourceType.FILE.code,
            region = LOCAL_REGION,
            externalUserId = LOCAL_USER_ID,
        )
        val now = System.currentTimeMillis()
        val account = existing?.copy(lastSyncAtMs = now) ?: AccountEntity(
            sourceType = SourceType.FILE.code,
            region = LOCAL_REGION,
            externalUserId = LOCAL_USER_ID,
            displayName = "本地导入",
            fullName = "从导出文件导入的数据",
            status = AccountStatus.NORMAL.code,
            enabled = true,
            addedAtMs = now,
            lastSyncAtMs = now,
        )
        return repository.upsertAccount(account)
    }

    private fun normalizeDate(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.matches(DATE_REGEX)) return trimmed
        val digits = trimmed.replace(Regex("[^0-9]"), "")
        return when {
            digits.length == 8 ->
                "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
            else -> parseTime(trimmed)?.let { format(it) }
        }
    }

    private fun parseTime(raw: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd",
        )
        patterns.forEach { pattern ->
            runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                return fmt.parse(raw)?.time ?: 0L
            }
        }
        raw.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
        return null
    }

    private fun format(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(ms)

    private fun firstString(o: JSONObject, vararg keys: String): String? {
        keys.forEach { key ->
            val v = o.optString(key, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    private fun firstInt(o: JSONObject, vararg keys: String): Int {
        keys.forEach { key ->
            if (o.has(key) && !o.isNull(key)) {
                val v = o.optInt(key, Int.MIN_VALUE)
                if (v != Int.MIN_VALUE) return v
                val d = o.optDouble(key, Double.NaN)
                if (!d.isNaN()) return d.toInt()
            }
        }
        return 0
    }

    private fun firstDouble(o: JSONObject, vararg keys: String): Double {
        keys.forEach { key ->
            if (o.has(key) && !o.isNull(key)) {
                val d = o.optDouble(key, Double.NaN)
                if (!d.isNaN()) return d
            }
        }
        return 0.0
    }

    private fun firstLong(o: JSONObject, vararg keys: String): Long {
        keys.forEach { key ->
            if (o.has(key) && !o.isNull(key)) {
                val l = o.optLong(key, Long.MIN_VALUE)
                if (l != Long.MIN_VALUE) return l
            }
        }
        return 0L
    }

    private companion object {
        const val LOCAL_REGION = "local"
        const val LOCAL_USER_ID = "local-import"
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}
