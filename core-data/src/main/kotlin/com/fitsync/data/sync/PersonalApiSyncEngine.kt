package com.fitsync.data.sync

import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.ActivityEntity
import com.fitsync.data.db.ActivityRouteEntity
import com.fitsync.data.db.ActivitySampleEntity
import com.fitsync.data.db.DailySummaryEntity
import com.fitsync.data.db.FitSyncDatabase
import com.fitsync.data.network.personal.FitActivityParser
import com.fitsync.data.network.personal.PersonalApiClient
import com.fitsync.data.network.personal.PersonalApiStore
import com.fitsync.data.settings.PrimaryAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Garmin 官方 Personal API 的同步编排。
 *
 * 与原来的 [GarminSyncEngine]（私有接口）互不干扰：
 * - 这里只读，不会向 Garmin 写任何东西；
 * - 数据挂在本地一个固定账号 `garmin_personal` 下，避免和多账号混在一起；
 * - 每条活动下载原始 FIT，解析成采样点和轨迹后入库，供详情页画曲线与轨迹。
 */
@Singleton
class PersonalApiSyncEngine @Inject constructor(
    private val client: PersonalApiClient,
    private val store: PersonalApiStore,
    private val db: FitSyncDatabase,
    private val primaryAccountStore: PrimaryAccountStore,
) {

    data class UiState(
        val connected: Boolean = false,
        val running: Boolean = false,
        val phase: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val message: String? = null,
        val lastSyncAtMs: Long = 0L,
        val hasData: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(connected = store.apiKey() != null) }
    }

    /** 用 API Key 换一次 token，验证有效性后保存 */
    suspend fun connect(apiKey: String) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext
        _state.update { it.copy(running = true, phase = "验证 API Key", message = null) }
        runCatching {
            val token = client.exchangeToken(apiKey)
            val exp = client.tokenExpirySeconds(token)
            require(exp > 0) { "token 里没有 exp" }
            store.saveApiKey(apiKey)
            store.saveToken(token, exp)
        }.onSuccess {
            _state.update {
                it.copy(
                    connected = true, running = false, phase = null,
                    message = "已连接。点「开始同步」拉取最近 30 天数据。",
                )
            }
        }.onFailure { e ->
            _state.update {
                it.copy(running = false, phase = null, message = "连接失败：${e.message}")
            }
        }
    }

    fun disconnect() {
        store.clear()
        _state.update { UiState(connected = false) }
    }

    /**
     * 同步最近 [days] 天。
     *
     * 步骤：换 token → 日汇总 + HRV → 活动列表 → 每条活动下载 FIT 并解析。
     * 已有采样点的活动会跳过 FIT 下载，所以重复同步很快。
     */
    suspend fun sync(days: Int = 30) = withContext(Dispatchers.IO) {
        if (_state.value.running) return@withContext
        _state.update { it.copy(running = true, message = null, phase = "验证凭据", done = 0, total = 0) }

        try {
            val token = ensureToken()
            val accountId = ensureAccount()
            val endDate = formatDate(Date())
            val startDate = formatDate(daysAgo(days))

            _state.update { it.copy(phase = "拉取每日汇总") }
            val summaries = runCatching { client.dailySummaries(token, startDate, endDate) }
                .getOrDefault(emptyList())
            val hrv = runCatching { client.hrvDaily(token, startDate, endDate) }
                .getOrDefault(emptyMap())

            if (summaries.isNotEmpty()) {
                db.dailySummaryDao().upsertAll(
                    summaries.map { d ->
                        DailySummaryEntity(
                            accountId = accountId,
                            date = d.date,
                            steps = d.steps,
                            distanceM = d.distanceM,
                            floors = d.floors,
                            caloriesActive = d.activeCalories,
                            caloriesResting = d.restingCalories,
                            restingHr = d.restingHr,
                            hrvMs = hrv[d.date] ?: 0,
                            stressAvg = d.stressAvg,
                            stressMax = d.stressMax,
                            bodyBatteryMin = d.bodyBatteryMin,
                            bodyBatteryMax = d.bodyBatteryMax,
                        )
                    },
                )
            }

            _state.update { it.copy(phase = "拉取活动列表") }
            val activities = client.searchActivities(token, startDate, endDate)
            _state.update { it.copy(total = activities.size) }

            var sampleTotal = 0
            var fitTotal = 0

            activities.forEachIndexed { index, dto ->
                _state.update {
                    it.copy(phase = "活动 ${index + 1}/${activities.size}", done = index)
                }
                val existing = db.activityDao().byExternalId(accountId, dto.externalId)
                val entity = ActivityEntity(
                    id = existing?.id ?: 0L,
                    accountId = accountId,
                    externalId = dto.externalId,
                    sport = dto.sport,
                    activityType = dto.activityType.ifBlank { dto.sport },
                    startTimeMs = dto.startTimeMs,
                    durationSec = dto.durationSec,
                    distanceM = dto.distanceM,
                    avgHr = dto.avgHr,
                    maxHr = dto.maxHr,
                    calories = dto.calories,
                    elevationGainM = dto.elevationGainM,
                    hasGps = existing?.hasGps ?: false,
                )
                val localId = db.activityDao().upsert(entity)

                // 已经有采样点：只更新摘要，不再下载 FIT
                if (db.activitySampleDao().byActivity(localId).isNotEmpty()) {
                    return@forEachIndexed
                }

                val fit = runCatching { client.downloadFit(token, dto.activityId) }
                    .getOrNull() ?: return@forEachIndexed
                val parsed = FitActivityParser.parse(fit)
                if (parsed.samples.isEmpty()) return@forEachIndexed

                db.activitySampleDao().deleteByActivity(localId)
                db.activitySampleDao().upsertAll(
                    parsed.samples.map { p ->
                        ActivitySampleEntity(
                            activityId = localId,
                            accountId = accountId,
                            tsMs = p.tsMs,
                            hr = p.hr,
                            cadence = p.cadence,
                            powerW = p.powerW,
                            elevM = p.elevM,
                            tempC = p.tempC,
                        )
                    },
                )

                db.activityRouteDao().deleteByActivity(localId)
                if (parsed.routes.isNotEmpty()) {
                    db.activityRouteDao().upsertAll(
                        parsed.routes.map { p ->
                            ActivityRouteEntity(
                                activityId = localId,
                                seq = p.seq,
                                lat = p.lat,
                                lng = p.lng,
                                elevM = p.elevM,
                                tsMs = p.tsMs,
                                speedMs = p.speedMs,
                                hr = p.hr,
                                cadence = p.cadence,
                            )
                        },
                    )
                }

                // 用 FIT 里的精确值校准摘要（接口摘要有时缺字段）
                db.activityDao().upsert(
                    entity.copy(
                        distanceM = parsed.totalDistanceM.takeIf { it > 0 } ?: entity.distanceM,
                        calories = parsed.totalCalories.takeIf { it > 0 } ?: entity.calories,
                        avgHr = parsed.avgHr.takeIf { it > 0 } ?: entity.avgHr,
                        maxHr = parsed.maxHr.takeIf { it > 0 } ?: entity.maxHr,
                        hasGps = parsed.routes.isNotEmpty(),
                    ),
                )
                sampleTotal += parsed.samples.size
                fitTotal++
            }

            db.accountDao().updateStatus(accountId, "normal", System.currentTimeMillis())
            // 首页「今日」跟主账号走；第一次同步时如果还没有主账号，直接用这个
            if (primaryAccountStore.primaryAccountId.value == null) {
                primaryAccountStore.setPrimary(accountId)
            }
            _state.update {
                it.copy(
                    running = false,
                    phase = null,
                    done = activities.size,
                    hasData = true,
                    lastSyncAtMs = System.currentTimeMillis(),
                    message = buildString {
                        append("同步完成：${summaries.size} 天汇总，${activities.size} 条活动")
                        if (fitTotal > 0) append("，新解析 $fitTotal 个 FIT（$sampleTotal 个采样点）")
                    },
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(running = false, phase = null, message = "同步失败：${e.message}")
            }
        }
    }

    // ============================ 内部 ============================

    private suspend fun ensureToken(): String {
        val cached = store.token()
        val exp = store.tokenExpiresAt()
        val now = System.currentTimeMillis() / 1000
        if (cached != null && exp - now > 3600) return cached

        val key = store.apiKey() ?: error("还没有连接 Personal API，请先粘贴 API Key")
        val token = client.exchangeToken(key)
        val newExp = client.tokenExpirySeconds(token).takeIf { it > 0 } ?: (now + 86_400)
        store.saveToken(token, newExp)
        return token
    }

    private suspend fun ensureAccount(): Long {
        val saved = store.accountId()
        if (saved > 0) return saved
        val existing = db.accountDao().byUniqueKey(SOURCE, REGION, EXTERNAL_ID)
        val id = existing?.id ?: db.accountDao().upsert(
            AccountEntity(
                sourceType = SOURCE,
                region = REGION,
                externalUserId = EXTERNAL_ID,
                displayName = "Personal API",
                fullName = "Garmin Connect（官方 Personal API）",
                status = "normal",
                addedAtMs = System.currentTimeMillis(),
            ),
        )
        store.saveAccountId(id)
        return id
    }

    private fun formatDate(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    private fun daysAgo(days: Int): Date =
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }.time

    companion object {
        const val SOURCE = "garmin_personal"
        const val REGION = "com"
        const val EXTERNAL_ID = "personal-api"
    }
}
