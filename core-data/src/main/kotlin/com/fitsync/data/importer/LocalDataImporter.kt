package com.fitsync.data.importer

import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.ActivityEntity
import com.fitsync.data.db.AccountStatus
import com.fitsync.data.db.DailySummaryEntity
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.SleepSessionEntity
import com.fitsync.data.db.SourceType
import com.fitsync.data.fit.FitProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
 *
 * **合并而非覆盖**（1.14.12）：解析出的中间态字段全为可空，`null` 表示「文件里没有这个字段」，
 * 与「值为 0」严格区分。写库前先取回同 key 的旧行，只把 **非空字段** 写进去，
 * 旧行里已有的值原样保留——否则导入一份只有步数的旧导出包，会把同步下来的
 * 压力、血氧、身体电量全部清零。真实 0（如卧床那天步数为 0）仍然会被写入。
 */
@Singleton
class LocalDataImporter @Inject constructor(
    private val repository: FitDataRepository,
) {

    data class ImportResult(
        val files: Int = 0,
        /** 本次写入的日汇总天数（新增 + 补全，不含「本地已是最新」的） */
        val daily: Int = 0,
        val sleep: Int = 0,
        val activities: Int = 0,
        /** 本地已有、且这次没有任何新字段可补的天数/条数 */
        val skipped: Int = 0,
        /** 新增的行数（日汇总 + 睡眠 + 活动） */
        val inserted: Int = 0,
        /** 补全的行数：本地已有，这次把空缺字段填上了 */
        val updated: Int = 0,
        val errors: List<String> = emptyList(),
        /** 本次导入涉及的日期（升序），供「导入后上传到目标账号」使用 */
        val dates: List<String> = emptyList(),
        /** 包里识别到的 FIT 文件数（活动 + wellness），用于向用户解释 */
        val fitFiles: Int = 0,
        /** 识别为活动的 FIT 数（内容由 JSON 导入，这里只统计） */
        val activityFits: Int = 0,
        /** 实际写入的账号 */
        val accountId: Long = 0,
    ) {
        val total: Int get() = daily + sleep + activities
        val isEmpty: Boolean get() = total == 0 && skipped == 0
    }

    /**
     * @param targetAccountId 指定时把数据写进这个 Garmin 账号（**不再新建「本地导入」账号**），
     *   调用方随后可以把这些日期上传回该账号；为空时保持旧行为。
     */
    suspend fun import(
        bytes: ByteArray,
        fileName: String,
        targetAccountId: Long? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        val accountId = targetAccountId ?: ensureLocalAccount()

        var parsedEntries = 0
        var fitFiles = 0
        var activityFits = 0
        val errors = mutableListOf<String>()
        val fitDates = linkedSetOf<String>()

        val dailyRows = linkedMapOf<String, PartialDaily>()
        val sleepRows = linkedMapOf<String, PartialSleep>()
        val activityRows = linkedMapOf<String, PartialActivity>()

        walk(bytes, fileName) { entry ->
            when (entry) {
                is Entry.Text -> {
                    parsedEntries++
                    runCatching {
                        if (entry.name.endsWith(".csv", true)) {
                            parseCsv(entry.text, dailyRows, sleepRows, activityRows)
                        } else {
                            parseJson(entry.text, dailyRows, sleepRows, activityRows)
                        }
                    }.onFailure { e ->
                        if (errors.size < MAX_ERRORS) {
                            errors += "${entry.name}：${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }

                is Entry.Fit -> {
                    fitFiles++
                    val info = runCatching { FitProbe.read(entry.bytes) }.getOrNull()
                    if (info?.isActivity == true) {
                        // 活动摘要由 summarizedActivities.json 提供，原始 FIT 只统计不解析：
                        // 一个导出包动辄几千个 FIT，全解一遍会让导入慢十倍
                        activityFits++
                    } else {
                        info?.utcDate?.let { fitDates += it }
                    }
                }
            }
        }

        if (dailyRows.isEmpty() && sleepRows.isEmpty() && activityRows.isEmpty() && fitFiles == 0) {
            return@withContext ImportResult(
                errors = errors.ifEmpty {
                    listOf("文件里没有找到可识别的数据（支持 Garmin 导出 zip / JSON / CSV）")
                },
                accountId = accountId,
            )
        }

        val dailyCount = writeDaily(accountId, dailyRows.values)
        val sleepCount = writeSleep(accountId, sleepRows.values)
        val activityCount = writeActivities(accountId, activityRows.values)

        ImportResult(
            files = parsedEntries,
            daily = dailyCount.written,
            sleep = sleepCount.written,
            activities = activityCount.written,
            skipped = dailyCount.unchanged + sleepCount.unchanged + activityCount.unchanged,
            inserted = dailyCount.inserted + sleepCount.inserted + activityCount.inserted,
            updated = dailyCount.updated + sleepCount.updated + activityCount.updated,
            errors = errors,
            dates = (dailyRows.keys + sleepRows.keys + fitDates).distinct().sorted(),
            fitFiles = fitFiles,
            activityFits = activityFits,
            accountId = accountId,
        )
    }

    // ============================ 合并写入 ============================

    private data class MergeCount(
        val inserted: Int = 0,
        val updated: Int = 0,
        val unchanged: Int = 0,
    ) {
        val written: Int get() = inserted + updated
    }

    private suspend fun writeDaily(
        accountId: Long,
        incoming: Collection<PartialDaily>,
    ): MergeCount {
        if (incoming.isEmpty()) return MergeCount()
        val old = repository
            .getDailySummariesByDates(accountId, incoming.map { it.date })
            .associateBy { it.date }
        var inserted = 0
        var updated = 0
        var unchanged = 0
        val rows = ArrayList<DailySummaryEntity>(incoming.size)
        incoming.forEach { p ->
            val before = old[p.date]
            val merged = dailyEntity(accountId, p, before)
            when {
                before == null -> {
                    inserted++
                    rows += merged
                }
                merged == before -> unchanged++
                else -> {
                    updated++
                    rows += merged
                }
            }
        }
        if (rows.isNotEmpty()) repository.upsertDailySummaries(rows)
        return MergeCount(inserted, updated, unchanged)
    }

    private suspend fun writeSleep(
        accountId: Long,
        incoming: Collection<PartialSleep>,
    ): MergeCount {
        if (incoming.isEmpty()) return MergeCount()
        val old = repository
            .getSleepSessionsByExternalIds(accountId, incoming.map { it.externalId })
            .associateBy { it.externalId }
        var inserted = 0
        var updated = 0
        var unchanged = 0
        incoming.forEach { p ->
            val before = old[p.externalId]
            val merged = sleepEntity(accountId, p, before)
            when {
                before == null -> {
                    repository.upsertSleepSession(merged)
                    inserted++
                }
                merged == before -> unchanged++
                else -> {
                    repository.upsertSleepSession(merged)
                    updated++
                }
            }
        }
        return MergeCount(inserted, updated, unchanged)
    }

    private suspend fun writeActivities(
        accountId: Long,
        incoming: Collection<PartialActivity>,
    ): MergeCount {
        if (incoming.isEmpty()) return MergeCount()
        val old = repository
            .getActivitiesByExternalIds(accountId, incoming.map { it.externalId })
            .associateBy { it.externalId }
        var inserted = 0
        var updated = 0
        var unchanged = 0
        incoming.forEach { p ->
            val before = old[p.externalId]
            val merged = activityEntity(accountId, p, before)
            when {
                before == null -> {
                    repository.upsertActivity(merged)
                    inserted++
                }
                merged == before -> unchanged++
                else -> {
                    repository.upsertActivity(merged)
                    updated++
                }
            }
        }
        return MergeCount(inserted, updated, unchanged)
    }

    /** 字段级合并：新值非空才覆盖，为空一律沿用旧值 */
    private fun dailyEntity(
        accountId: Long,
        p: PartialDaily,
        old: DailySummaryEntity?,
    ) = DailySummaryEntity(
        // 沿用旧主键：REPLACE 时保持行 id 稳定，避免依赖该 id 的引用失效
        id = old?.id ?: 0,
        accountId = accountId,
        date = p.date,
        steps = p.steps ?: old?.steps ?: 0,
        distanceM = p.distanceM ?: old?.distanceM ?: 0.0,
        floors = p.floors ?: old?.floors ?: 0,
        caloriesActive = p.caloriesActive ?: old?.caloriesActive ?: 0.0,
        caloriesResting = p.caloriesResting ?: old?.caloriesResting ?: 0.0,
        standMin = old?.standMin ?: 0,
        restingHr = p.restingHr ?: old?.restingHr ?: 0,
        hrvMs = p.hrvMs ?: old?.hrvMs ?: 0,
        respiratoryRate = p.respiratoryRate ?: old?.respiratoryRate ?: 0,
        stressAvg = p.stressAvg ?: old?.stressAvg ?: 0,
        stressMax = p.stressMax ?: old?.stressMax ?: 0,
        bodyBatteryMin = p.bodyBatteryMin ?: old?.bodyBatteryMin ?: 0,
        bodyBatteryMax = p.bodyBatteryMax ?: old?.bodyBatteryMax ?: 0,
        spo2Avg = p.spo2Avg ?: old?.spo2Avg ?: 0,
        // Fitbit 官方导出字段：导入器暂不解析，但绝不能因为导入一次就被清零
        azmFatBurnMin = old?.azmFatBurnMin ?: 0,
        azmCardioMin = old?.azmCardioMin ?: 0,
        azmPeakMin = old?.azmPeakMin ?: 0,
        dailyReadiness = old?.dailyReadiness ?: 0,
        waterMl = old?.waterMl ?: 0,
    )

    private fun sleepEntity(
        accountId: Long,
        p: PartialSleep,
        old: SleepSessionEntity?,
    ) = SleepSessionEntity(
        id = old?.id ?: 0,
        accountId = accountId,
        externalId = p.externalId,
        startTimeMs = p.startTimeMs ?: old?.startTimeMs ?: 0,
        endTimeMs = p.endTimeMs ?: old?.endTimeMs ?: 0,
        totalSleepMin = p.totalSleepMin ?: old?.totalSleepMin ?: 0,
        inBedMin = p.inBedMin ?: old?.inBedMin ?: 0,
        sleepScore = p.sleepScore ?: old?.sleepScore ?: 0,
        sleepDebtMin = old?.sleepDebtMin ?: 0,
        avgHr = p.avgHr ?: old?.avgHr ?: 0,
        avgSpo2 = p.avgSpo2 ?: old?.avgSpo2 ?: 0,
        avgBreathing = p.avgBreathing ?: old?.avgBreathing ?: 0.0,
        respEventCount = old?.respEventCount ?: 0,
    )

    private fun activityEntity(
        accountId: Long,
        p: PartialActivity,
        old: ActivityEntity?,
    ) = ActivityEntity(
        id = old?.id ?: 0,
        accountId = accountId,
        externalId = p.externalId,
        sport = p.sport ?: old?.sport ?: "unknown",
        activityType = p.activityType ?: old?.activityType ?: "",
        startTimeMs = p.startTimeMs ?: old?.startTimeMs ?: 0,
        durationSec = p.durationSec ?: old?.durationSec ?: 0,
        distanceM = p.distanceM ?: old?.distanceM ?: 0.0,
        avgHr = p.avgHr ?: old?.avgHr ?: 0,
        maxHr = p.maxHr ?: old?.maxHr ?: 0,
        restingHr = old?.restingHr ?: 0,
        calories = p.calories ?: old?.calories ?: 0.0,
        avgSpeed = p.avgSpeed ?: old?.avgSpeed ?: 0.0,
        elevationGainM = p.elevationGainM ?: old?.elevationGainM ?: 0.0,
        hasGps = p.hasGps ?: old?.hasGps ?: false,
        hasSplits = old?.hasSplits ?: false,
        isAllDay = old?.isAllDay ?: false,
    )

    // ============================ 解析中间态 ============================

    /** `null` = 文件里根本没有这个字段；`0` = 有这个字段、值就是 0。两者不能混为一谈 */
    private data class PartialDaily(
        val date: String,
        val steps: Int? = null,
        val distanceM: Double? = null,
        val floors: Int? = null,
        val caloriesActive: Double? = null,
        val caloriesResting: Double? = null,
        val restingHr: Int? = null,
        val hrvMs: Int? = null,
        val respiratoryRate: Int? = null,
        val stressAvg: Int? = null,
        val stressMax: Int? = null,
        val bodyBatteryMin: Int? = null,
        val bodyBatteryMax: Int? = null,
        val spo2Avg: Int? = null,
    ) {
        /**
         * 至少有一个指标字段存在。
         *
         * 刻意**不**要求「非零」：卧床那天步数就是 0，把它当缺失会让真实数据永远进不来。
         * 误判由字段名把住——`readDaily` 只认 UDS 那一套键，活动对象没有 `calendarDate`。
         */
        val hasSignal: Boolean
            get() = steps != null || distanceM != null || floors != null ||
                caloriesActive != null || caloriesResting != null || restingHr != null ||
                hrvMs != null || respiratoryRate != null || stressAvg != null ||
                stressMax != null || bodyBatteryMin != null || bodyBatteryMax != null ||
                spo2Avg != null

        /** 同批次内多份文件（如 UDS + Monitoring）逐字段取并：先到的值优先，空缺才用后来的补 */
        fun absorb(other: PartialDaily): PartialDaily = PartialDaily(
            date = date,
            steps = steps ?: other.steps,
            distanceM = distanceM ?: other.distanceM,
            floors = floors ?: other.floors,
            caloriesActive = caloriesActive ?: other.caloriesActive,
            caloriesResting = caloriesResting ?: other.caloriesResting,
            restingHr = restingHr ?: other.restingHr,
            hrvMs = hrvMs ?: other.hrvMs,
            respiratoryRate = respiratoryRate ?: other.respiratoryRate,
            stressAvg = stressAvg ?: other.stressAvg,
            stressMax = stressMax ?: other.stressMax,
            bodyBatteryMin = bodyBatteryMin ?: other.bodyBatteryMin,
            bodyBatteryMax = bodyBatteryMax ?: other.bodyBatteryMax,
            spo2Avg = spo2Avg ?: other.spo2Avg,
        )
    }

    private data class PartialSleep(
        val externalId: String,
        val startTimeMs: Long? = null,
        val endTimeMs: Long? = null,
        val totalSleepMin: Int? = null,
        val inBedMin: Int? = null,
        val sleepScore: Int? = null,
        val avgHr: Int? = null,
        val avgSpo2: Int? = null,
        val avgBreathing: Double? = null,
    ) {
        fun absorb(other: PartialSleep): PartialSleep = PartialSleep(
            externalId = externalId,
            startTimeMs = startTimeMs ?: other.startTimeMs,
            endTimeMs = endTimeMs ?: other.endTimeMs,
            totalSleepMin = totalSleepMin ?: other.totalSleepMin,
            inBedMin = inBedMin ?: other.inBedMin,
            sleepScore = sleepScore ?: other.sleepScore,
            avgHr = avgHr ?: other.avgHr,
            avgSpo2 = avgSpo2 ?: other.avgSpo2,
            avgBreathing = avgBreathing ?: other.avgBreathing,
        )
    }

    private data class PartialActivity(
        val externalId: String,
        val sport: String? = null,
        val activityType: String? = null,
        val startTimeMs: Long? = null,
        val durationSec: Long? = null,
        val distanceM: Double? = null,
        val avgHr: Int? = null,
        val maxHr: Int? = null,
        val calories: Double? = null,
        val avgSpeed: Double? = null,
        val elevationGainM: Double? = null,
        val hasGps: Boolean? = null,
    ) {
        fun absorb(other: PartialActivity): PartialActivity = PartialActivity(
            externalId = externalId,
            sport = sport ?: other.sport,
            activityType = activityType ?: other.activityType,
            startTimeMs = startTimeMs ?: other.startTimeMs,
            durationSec = durationSec ?: other.durationSec,
            distanceM = distanceM ?: other.distanceM,
            avgHr = avgHr ?: other.avgHr,
            maxHr = maxHr ?: other.maxHr,
            calories = calories ?: other.calories,
            avgSpeed = avgSpeed ?: other.avgSpeed,
            elevationGainM = elevationGainM ?: other.elevationGainM,
            hasGps = hasGps ?: other.hasGps,
        )
    }

    // ============================ 解包 ============================

    private sealed interface Entry {
        val name: String
        class Text(override val name: String, val text: String) : Entry
        class Fit(override val name: String, val bytes: ByteArray) : Entry
    }

    /**
     * 遍历导出包，**边读边处理**，不把整个包展开到内存。
     *
     * Garmin 的导出包能到几个 GB，且活动与 wellness FIT 混在 `UploadedFiles_*.zip`
     * 这种嵌套 zip 里。之前是"全部读成字符串列表再解析"，真实包会直接 OOM。
     */
    private fun walk(bytes: ByteArray, fileName: String, sink: (Entry) -> Unit) {
        val isZip = bytes.size > 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        if (!isZip) {
            when {
                fileName.endsWith(".fit", true) -> sink(Entry.Fit(fileName, bytes))
                bytes.size <= MAX_TEXT_BYTES ->
                    sink(Entry.Text(fileName, bytes.toString(Charsets.UTF_8)))
            }
            return
        }
        walkZip(ZipInputStream(ByteArrayInputStream(bytes)), 0, sink)
    }

    private fun walkZip(zip: ZipInputStream, depth: Int, sink: (Entry) -> Unit) {
        var entry = runCatching { zip.nextEntry }.getOrNull()
        while (entry != null) {
            val name = entry.name
            if (!entry.isDirectory) {
                val size = entry.size
                when {
                    name.endsWith(".json", true) || name.endsWith(".csv", true) -> {
                        if (size < 0 || size <= MAX_TEXT_BYTES) {
                            readLimited(zip, MAX_TEXT_BYTES)?.let {
                                sink(Entry.Text(name, it.toString(Charsets.UTF_8)))
                            }
                        }
                    }
                    name.endsWith(".fit", true) -> {
                        if (size < 0 || size <= MAX_FIT_BYTES) {
                            readLimited(zip, MAX_FIT_BYTES)?.let { sink(Entry.Fit(name, it)) }
                        }
                    }
                    name.endsWith(".zip", true) && depth < MAX_NEST_DEPTH -> {
                        // 嵌套 zip：直接在同一个流上再开一层。
                        // 不能 close 内层，否则会把外层流一起关掉，后面的条目全丢。
                        runCatching { walkZip(ZipInputStream(zip), depth + 1, sink) }
                    }
                }
            }
            runCatching { zip.closeEntry() }
            entry = runCatching { zip.nextEntry }.getOrNull()
        }
    }

    /** 读当前 zip 条目，超过 [max] 就放弃（防止某个超大条目把内存吃光） */
    private fun readLimited(zip: ZipInputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = runCatching { zip.read(buf) }.getOrElse { return null }
            if (n <= 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ============================ JSON ============================

    private fun parseJson(
        text: String,
        daily: MutableMap<String, PartialDaily>,
        sleep: MutableMap<String, PartialSleep>,
        activities: MutableMap<String, PartialActivity>,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        when (trimmed.first()) {
            '{' -> scan(JSONObject(trimmed), daily, sleep, activities)
            '[' -> scan(JSONArray(trimmed), daily, sleep, activities)
            else -> return
        }
    }

    private fun scan(
        node: Any?,
        daily: MutableMap<String, PartialDaily>,
        sleep: MutableMap<String, PartialSleep>,
        activities: MutableMap<String, PartialActivity>,
    ) {
        when (node) {
            is JSONObject -> {
                readDaily(node)?.let { daily.foldIn(it) }
                readSleep(node)?.let { sleep.foldIn(it) }
                readActivity(node)?.let { activities.foldIn(it) }
                node.keys().forEach { key -> scan(node.opt(key), daily, sleep, activities) }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                scan(node.opt(i), daily, sleep, activities)
            }
        }
    }

    /** 同批次内逐字段合并，而不是「先到先得、后来整份丢掉」 */
    private fun MutableMap<String, PartialDaily>.foldIn(row: PartialDaily) {
        put(key = row.date, value = get(row.date)?.absorb(row) ?: row)
    }

    private fun MutableMap<String, PartialSleep>.foldIn(row: PartialSleep) {
        put(key = row.externalId, value = get(row.externalId)?.absorb(row) ?: row)
    }

    private fun MutableMap<String, PartialActivity>.foldIn(row: PartialActivity) {
        put(key = row.externalId, value = get(row.externalId)?.absorb(row) ?: row)
    }

    private fun readDaily(o: JSONObject): PartialDaily? {
        val date = firstString(o, "calendarDate", "calendar_date", "date", "day")
            ?.takeIf { it.matches(DATE_REGEX) } ?: return null
        val row = PartialDaily(
            date = date,
            steps = optInt(o, "totalSteps", "steps", "stepCount"),
            distanceM = optDouble(o, "totalDistanceMeters", "distanceMeters", "distance"),
            floors = optInt(o, "floorsClimbed", "floors"),
            caloriesActive = optDouble(o, "activeKilocalories", "activeCalories", "calories"),
            caloriesResting = optDouble(o, "restingKilocalories", "restingCalories"),
            restingHr = optInt(o, "restingHeartRate", "restingHr"),
            hrvMs = optInt(o, "hrv", "lastNightAvgHrv"),
            respiratoryRate = optInt(o, "avgRespirationRate", "respiratoryRate"),
            stressAvg = optInt(o, "averageStressLevel", "avgStressLevel"),
            stressMax = optInt(o, "maxStressLevel", "stressMax"),
            bodyBatteryMin = optInt(o, "bodyBatteryLowestValue", "bodyBatteryMin"),
            bodyBatteryMax = optInt(o, "bodyBatteryHighestValue", "bodyBatteryMax"),
            spo2Avg = optInt(o, "averageSpo2", "avgSpo2", "averageSpo2Value"),
        )
        return row.takeIf { it.hasSignal }
    }

    private fun readSleep(o: JSONObject): PartialSleep? {
        val dto = o.optJSONObject("dailySleepDTO") ?: o
        val rawTotal = optInt(dto, "sleepTimeSeconds", "totalSleepSeconds", "totalSleepMin")
            ?: return null
        val unitIsMinutes = dto.has("totalSleepMin")
        val totalMin = if (unitIsMinutes) rawTotal else rawTotal / 60
        if (totalMin <= 0) return null

        val date = firstString(dto, "calendarDate", "date")
            ?: firstString(o, "calendarDate", "date")
            ?: return null
        val awakeSec = optInt(dto, "awakeSleepSeconds", "awakeDurationSeconds")
        val score = dto.optJSONObject("sleepScores")
            ?.optJSONObject("overall")
            ?.optInt("value", 0)
            ?.takeIf { it > 0 }
            ?: optInt(dto, "sleepScore", "overallSleepScore")

        return PartialSleep(
            externalId = "sleep-$date",
            startTimeMs = optLong(dto, "sleepStartTimestampGMT", "sleepStartTimestampLocal", "startTimeMs"),
            endTimeMs = optLong(dto, "sleepEndTimestampGMT", "sleepEndTimestampLocal", "endTimeMs"),
            totalSleepMin = totalMin,
            inBedMin = awakeSec?.let { totalMin + it / 60 },
            sleepScore = score,
            avgHr = optInt(dto, "avgHeartRate", "averageHeartRate"),
            avgSpo2 = optInt(dto, "averageSpO2Value", "avgSpo2"),
            avgBreathing = optDouble(dto, "averageRespirationValue", "avgRespiration"),
        )
    }

    private fun readActivity(o: JSONObject): PartialActivity? {
        val id = firstString(o, "activityId", "activity_id") ?: return null
        val type = o.optJSONObject("activityType")?.optString("typeKey").orEmpty()
            .ifBlank { firstString(o, "activityType", "sport").orEmpty() }
        val startRaw = firstString(o, "startTimeGMT", "startTimeLocal", "startTime")
            ?: return null
        val startMs = parseTime(startRaw) ?: return null
        val distance = optDouble(o, "distance", "distanceMeters")
        return PartialActivity(
            externalId = id,
            sport = type.ifBlank { null },
            activityType = type.ifBlank { null },
            startTimeMs = startMs,
            durationSec = optDouble(o, "duration", "durationSeconds")?.toLong(),
            distanceM = distance,
            avgHr = optInt(o, "averageHR", "avgHr"),
            maxHr = optInt(o, "maxHR", "maxHr"),
            calories = optDouble(o, "calories", "activeKilocalories"),
            avgSpeed = optDouble(o, "averageSpeed", "avgSpeed"),
            elevationGainM = optDouble(o, "elevationGain", "elevationGainMeters"),
            hasGps = distance?.let { it > 0 },
        )
    }

    // ============================ CSV ============================

    private fun parseCsv(
        text: String,
        daily: MutableMap<String, PartialDaily>,
        sleep: MutableMap<String, PartialSleep>,
        activities: MutableMap<String, PartialActivity>,
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
            val steps = cell("steps", "步数", "totalsteps")?.toIntOrNull()
            val distance = cell("distance", "distance_m", "distancemeters", "距离")?.toDoubleOrNull()
            val calories = cell("calories", "activecalories", "卡路里", "activekilocalories")
                ?.toDoubleOrNull()
            val sleepMin = cell("sleep_min", "sleepminutes", "睡眠分钟", "totalSleepMin".lowercase())
                ?.toIntOrNull()

            val dailyRow = PartialDaily(
                date = date,
                steps = steps,
                distanceM = distance,
                caloriesActive = calories,
                restingHr = cell("resting_hr", "restingheartrate", "静息心率")?.toIntOrNull(),
            )
            if (dailyRow.hasSignal) daily.foldIn(dailyRow)

            if (sleepMin != null && sleepMin > 0) {
                sleep.foldIn(
                    PartialSleep(
                        externalId = "sleep-$date",
                        endTimeMs = parseTime("$date 23:59:59"),
                        totalSleepMin = sleepMin,
                        sleepScore = cell("sleep_score", "睡眠评分")?.toIntOrNull(),
                    ),
                )
            }
            val activityId = cell("activityid", "activity_id", "id")
            val sport = cell("sport", "type", "activitytype", "运动类型")
            if (activityId != null && sport != null) {
                activities.foldIn(
                    PartialActivity(
                        externalId = activityId,
                        sport = sport,
                        activityType = sport,
                        startTimeMs = parseTime(cell("starttimegmt", "starttime", "开始时间") ?: date),
                        durationSec = cell("duration", "durationseconds", "时长")
                            ?.toDoubleOrNull()?.toLong(),
                        distanceM = distance,
                        avgHr = cell("averagehr", "avghr", "平均心率")?.toIntOrNull(),
                        maxHr = cell("maxhr", "最大心率")?.toIntOrNull(),
                        calories = calories,
                        hasGps = distance?.let { it > 0 },
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

    /** 与 [firstString] 不同：返回 null 表示「字段不存在」，调用方据此决定是否保留旧值 */
    private fun optInt(o: JSONObject, vararg keys: String): Int? {
        keys.forEach { key ->
            if (o.has(key) && !o.isNull(key)) {
                val v = o.optInt(key, Int.MIN_VALUE)
                if (v != Int.MIN_VALUE) return v
                val d = o.optDouble(key, Double.NaN)
                if (!d.isNaN()) return d.toInt()
            }
        }
        return null
    }

    private fun optDouble(o: JSONObject, vararg keys: String): Double? {
        keys.forEach { key ->
            if (o.has(key) && !o.isNull(key)) {
                val d = o.optDouble(key, Double.NaN)
                if (!d.isNaN()) return d
            }
        }
        return null
    }

    private fun optLong(o: JSONObject, vararg keys: String): Long? {
        keys.forEach { key ->
            if (o.has(key) && !o.isNull(key)) {
                val l = o.optLong(key, Long.MIN_VALUE)
                if (l != Long.MIN_VALUE) return l
            }
        }
        return null
    }

    private companion object {
        const val LOCAL_REGION = "local"
        const val LOCAL_USER_ID = "local-import"

        /** 单个 JSON / CSV 条目的上限 */
        const val MAX_TEXT_BYTES = 64 * 1024 * 1024

        /** 单个 FIT 条目的上限：正常 wellness 包几百 KB，给足余量 */
        const val MAX_FIT_BYTES = 32 * 1024 * 1024

        /** 嵌套 zip 层数上限（Garmin 是 外层包 → UploadedFiles_*.zip，两层足够） */
        const val MAX_NEST_DEPTH = 2

        /** 错误信息最多留这么多条，避免几千个坏文件把结果撑爆 */
        const val MAX_ERRORS = 20

        val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}
