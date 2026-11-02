package com.fitsync.data.sync

import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.AccountRegion
import com.fitsync.data.db.AccountStatus
import com.fitsync.data.db.BackfillShardEntity
import com.fitsync.data.db.BackfillShardStatus
import com.fitsync.data.db.ActivityEntity
import com.fitsync.data.db.DailyHeartRateEntity
import com.fitsync.data.db.DailySummaryEntity
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.SleepSessionEntity
import com.fitsync.data.db.SleepStageEntity
import com.fitsync.data.db.SourceType
import com.fitsync.data.db.SyncCursorEntity
import com.fitsync.data.db.SyncLogEntity
import com.fitsync.data.network.CredentialStore
import com.fitsync.data.network.GarminConnectClient
import com.fitsync.data.network.GarminNetworkException
import com.fitsync.data.network.GarminSsoClient
import com.fitsync.data.network.StorableCredential
import com.fitsync.data.network.isTokenFresh
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 同步引擎（v1）。
 *
 * 策略：
 * - 每个账号按「日」增量同步：日汇总 + 睡眠；活动取最近 20 条按 externalId 去重；
 * - 首次（无游标）回填 7 天，之后从上次同步日的前一天续上，避免跨天数据缺口；
 * - 令牌两层兜底：本地过期时间提前 5 分钟预刷新；万一服务端提前作废，
 *   捕获 401 后再换一次重试，用户无感；
 * - 续期彻底失败才标记 `EXPIRED`，提示用户重新登录；
 * - 每次同步写一条 `sync_logs`，并更新 `accounts.last_sync_at_ms` / `status`。
 */
@Singleton
class GarminSyncEngine @Inject constructor(
    private val repository: FitDataRepository,
    private val connectClient: GarminConnectClient,
    private val credentialStore: CredentialStore,
    private val ssoClient: GarminSsoClient,
) {

    data class SyncOutcome(
        val ok: Boolean,
        val records: Int,
        val message: String,
        /** 是否因为 429 被拒：上层据此进入全局冷却，避免继续硬撞 */
        val rateLimited: Boolean = false,
        /** 服务端 `Retry-After` 给出的等待时长（毫秒），没有就是 null */
        val retryAfterMs: Long? = null,
        /** 是否被 403 拒绝：不是限流，重试也不会好，后台任务不该再 retry */
        val forbidden: Boolean = false,
        /** 另一个同步正在跑，本次一个请求都没发出去 */
        val busy: Boolean = false,
    )

    /**
     * 一轮 [syncAll] 的结果。
     *
     * 用独立类型而不是裸 List，是为了把「没同步」和「同步了 0 个账号」分开：
     * 前者要提示用户稍后再试，后者才是「没有可同步的账号」。
     */
    data class SyncBatch(
        val busy: Boolean,
        val results: List<Pair<Long, SyncOutcome>>,
    )

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    /**
     * 同步互斥。
     *
     * 后台 Worker 和前台手动同步是两个互不相干的入口：前者由 WorkManager 按周期唤起，
     * 后者是用户点「同步」，各自 `scope.launch`。以前它们完全不知道对方存在，
     * 自动同步正跑到一半时点「同步」，两路会同时打 Garmin——而 Garmin 判定的是
     * 「同一账号短时间内第二次 429」，撞上就是 48 小时锁定，不是慢一点的事。
     *
     * 用进程内 Mutex 而不是 `sync_locks` 表：App 只有一个进程，数据库锁还要处理
     * TTL、崩溃残留和跨进程可见性，代价远大于收益。抢不到锁就立刻返回，
     * 不排队——让用户等一个几十秒的队列，不如直接告诉他「已经在同步了」。
     */
    private val syncMutex = Mutex()

    suspend fun syncAccount(accountId: Long, backfillDays: Int = 7): SyncOutcome {
        if (!syncMutex.tryLock()) {
            return SyncOutcome(false, 0, "已经在同步了，等这一轮结束再试", busy = true)
        }
        return try {
            syncAccountLocked(accountId, backfillDays)
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun syncAccountLocked(accountId: Long, backfillDays: Int): SyncOutcome =
        withContext(Dispatchers.IO) {
            val account = repository.getAccount(accountId)
                ?: return@withContext SyncOutcome(false, 0, "账号不存在")
            if (account.sourceType != SourceType.GARMIN.code) {
                return@withContext SyncOutcome(false, 0, "本地导入账号无需同步")
            }
            val credential = credentialStore.load(
                SourceType.GARMIN.code,
                account.region,
                account.externalUserId,
            ) ?: return@withContext SyncOutcome(false, 0, "本地凭据缺失，请重新登录")

            val startedAt = System.currentTimeMillis()

            try {
                val records = try {
                    pullAll(account, ensureToken(credential), backfillDays)
                } catch (e: GarminNetworkException.Unauthorized) {
                    // 本地记录的过期时间不可信时（服务端提前作废），换一张再来一次
                    pullAll(account, refreshOrThrow(credential).accessToken, backfillDays)
                } catch (e: GarminNetworkException.Forbidden) {
                    // Garmin 在令牌失效时并不总是老实回 401，有时直接 403
                    // （请求被重定向到登录页、或令牌被提前吊销）。这时续期能救回来，
                    // 所以先无条件换一张新令牌再试一次；仍 403 才是真的权限/网络问题。
                    val fresh = try {
                        refreshOrThrow(credential)
                    } catch (refreshError: Exception) {
                        // 续期本身也失败（OAuth1 被吊销），保留原始的 403 更有信息量
                        throw e
                    }
                    pullAll(account, fresh.accessToken, backfillDays)
                }

                val todayText = LocalDate.now().format(dateFormatter)
                advanceCursor(accountId, "daily", todayText, startedAt)
                advanceCursor(accountId, "sleep", todayText, startedAt)
                advanceCursor(accountId, "activity", todayText, startedAt)
                repository.updateAccountStatus(accountId, AccountStatus.NORMAL.code, startedAt)

                log(accountId, ok = true, records = records, durationMs = System.currentTimeMillis() - startedAt)
                SyncOutcome(true, records, "已同步 $records 条记录")
            } catch (e: GarminNetworkException.SessionExpired) {
                val message = e.message ?: "登录已过期，请重新登录"
                repository.updateAccountStatus(accountId, AccountStatus.EXPIRED.code, startedAt)
                // 失败时不动 last_sync_at_ms，避免把失败伪装成"刚同步过"
                log(
                    accountId,
                    ok = false,
                    records = 0,
                    durationMs = System.currentTimeMillis() - startedAt,
                    error = message,
                )
                SyncOutcome(false, 0, message)
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                log(
                    accountId,
                    ok = false,
                    records = 0,
                    durationMs = System.currentTimeMillis() - startedAt,
                    error = message,
                )
                SyncOutcome(
                    ok = false,
                    records = 0,
                    message = message,
                    rateLimited = e is GarminNetworkException.RiskBlocked,
                    retryAfterMs = (e as? GarminNetworkException.RiskBlocked)?.retryAfterMs,
                    forbidden = e is GarminNetworkException.Forbidden,
                )
            }
        }

    /**
     * 拉一个账号的全部数据并落库，返回写入条数。
     *
     * 游标推进放在这里而不是外层：401 重试会整体重跑一遍，upsert 幂等，
     * 游标跟着最后一次成功的批次走即可。
     */
    private suspend fun pullAll(
        account: AccountEntity,
        token: String,
        backfillDays: Int,
    ): Int {
        val accountId = account.id
        var records = 0

        // 昵称可能是空的或只剩 GUID，Garmin 的日汇总接口不认 GUID，会一直 404。
        // 这里先换成真正能当路径用的标识（displayName 或数字 profileId）。
        val pathUser = connectClient.resolvePathUser(account.regionEnum(), token, account.displayName)

        val today = LocalDate.now()
        var day = resolveFromDate(accountId, today, backfillDays)
        val firstDay = day

        while (!day.isAfter(today)) {
            // Garmin 的限流看的是「单位时间内的请求数」，一天两次请求连着发，
            // 7 天就是 14 个请求在几秒内打出去，很容易直接 429。
            // 这里按天留出间隔，代价只是同步慢几秒，换来不触发风控。
            if (day != firstDay) delay(DAY_REQUEST_GAP_MS)
            val date = day.format(dateFormatter)
            if (saveSummary(accountId, account.regionEnum(), token, pathUser, date)) records++
            if (saveSleep(accountId, account.regionEnum(), token, pathUser, date)) records++
            day = day.plusDays(1)
        }

        connectClient.fetchActivities(account.regionEnum(), token, limit = 20).forEach { a ->
            saveActivity(accountId, a)
            records++
        }

        val todayText = today.format(dateFormatter)
        val doneAt = System.currentTimeMillis()
        advanceCursor(accountId, "daily", todayText, doneAt)
        advanceCursor(accountId, "sleep", todayText, doneAt)
        advanceCursor(accountId, "activity", todayText, doneAt)
        return records
    }

    // ============================ 落库（同步与回填共用） ============================

    /** 返回 false 表示这一天没有数据（Garmin 对空日期会返回全 0，客户端已过滤成 null） */
    private suspend fun saveSummary(
        accountId: Long,
        region: AccountRegion,
        token: String,
        displayName: String,
        date: String,
    ): Boolean {
        val summary = connectClient.fetchDailySummary(region, token, displayName, date) ?: return false
        repository.upsertDailySummaries(
            listOf(
                DailySummaryEntity(
                    accountId = accountId,
                    date = summary.date,
                    steps = summary.steps,
                    distanceM = summary.distanceM,
                    floors = summary.floors,
                    caloriesActive = summary.caloriesActive,
                    caloriesResting = summary.caloriesResting,
                    restingHr = summary.restingHr,
                    stressAvg = summary.stressAvg,
                    stressMax = summary.stressMax,
                    bodyBatteryMin = summary.bodyBatteryMin,
                    bodyBatteryMax = summary.bodyBatteryMax,
                    spo2Avg = summary.spo2Avg,
                )
            )
        )
        // 日心率：接口给的是全天最低/最高/静息，够画一条长期曲线
        if (summary.minHr > 0 || summary.maxHr > 0 || summary.restingHr > 0) {
            repository.upsertDailyHeartRate(
                listOf(
                    DailyHeartRateEntity(
                        accountId = accountId,
                        date = summary.date,
                        minHr = summary.minHr,
                        maxHr = summary.maxHr,
                        avgHr = 0,
                        restingHr = summary.restingHr,
                    )
                )
            )
        }
        return true
    }

    private suspend fun saveSleep(
        accountId: Long,
        region: AccountRegion,
        token: String,
        displayName: String,
        date: String,
    ): Boolean {
        val sleep = connectClient.fetchSleep(region, token, displayName, date) ?: return false
        // REPLACE 会换主键，先把旧分期删掉，避免留下孤儿行
        repository.getSleepSessionByExternalId(accountId, sleep.externalId)?.let {
            repository.deleteSleepStages(it.id)
        }
        val sessionId = repository.upsertSleepSession(
            SleepSessionEntity(
                accountId = accountId,
                externalId = sleep.externalId,
                startTimeMs = sleep.startTimeMs,
                endTimeMs = sleep.endTimeMs,
                totalSleepMin = sleep.totalSleepMin,
                inBedMin = sleep.inBedMin,
                sleepScore = sleep.sleepScore,
                avgHr = sleep.avgHr,
                avgSpo2 = sleep.avgSpo2,
                avgBreathing = sleep.avgBreathing,
            )
        )
        if (sleep.levels.isNotEmpty()) {
            repository.upsertSleepStages(
                sleep.levels.map { level ->
                    SleepStageEntity(
                        sleepSessionId = sessionId,
                        stage = level.stage,
                        startTimeMs = level.startMs,
                        endTimeMs = level.endMs,
                        durationMin = ((level.endMs - level.startMs) / 60_000L).toInt(),
                    )
                }
            )
        }
        return true
    }

    private suspend fun saveActivity(accountId: Long, a: GarminConnectClient.Activity) {
        // 保留本地主键：详情页拉的采样点是按 activity.id 关联的，换 id 会全部失联
        val existing = repository.getActivityByExternalId(accountId, a.externalId)
        repository.upsertActivity(
            ActivityEntity(
                id = existing?.id ?: 0L,
                accountId = accountId,
                externalId = a.externalId,
                sport = a.sport,
                activityType = a.name,
                startTimeMs = a.startTimeMs,
                durationSec = a.durationSec,
                distanceM = a.distanceM,
                avgHr = a.avgHr,
                maxHr = a.maxHr,
                calories = a.calories,
                elevationGainM = a.elevationGainM,
                hasGps = existing?.hasGps ?: (a.distanceM > 0),
            )
        )
    }

    /**
     * 历史回填：过去 [days] 天的日汇总/睡眠，加上全量活动（分页翻到底）。
     *
     * 一年约 730 天、每天 2 个请求。刻意限速 1 秒/次，并且一次最多跑 3 个月的
     * 分片（剩下的下次继续）；只由用户在前台手动触发，
     * 进度通过 [onProgress] 回传，避免界面看起来像卡死。
     */
    suspend fun backfillHistory(
        accountId: Long,
        days: Int = 365,
        onProgress: suspend (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val account = repository.getAccount(accountId)
            ?: return@withContext SyncOutcome(false, 0, "账号不存在")
        if (account.sourceType != SourceType.GARMIN.code) {
            return@withContext SyncOutcome(false, 0, "本地导入账号无需回填")
        }
        val credential = credentialStore.load(
            SourceType.GARMIN.code,
            account.region,
            account.externalUserId,
        ) ?: return@withContext SyncOutcome(false, 0, "本地凭据缺失，请重新登录")

        val startedAt = System.currentTimeMillis()
        val region = account.regionEnum()
        val today = LocalDate.now()
        val from = today.minusDays(days.toLong().coerceAtLeast(1))
        val total = ChronoUnit.DAYS.between(from, today).toInt() + 1

        try {
            val token = ensureToken(credential)
            val pathUser = connectClient.resolvePathUser(region, token, account.displayName)
            var records = 0
            var done = 0

            // 按月分片：已完成的月份直接跳过；当月因为天数在增长，会重新补一次。
            // 中途被杀掉时，pending 分片还在，下次只补没跑完的月份。
            val existing = repository.backfillShards(accountId, "health").associateBy { it.month }
            val months = generateSequence(from.withDayOfMonth(1)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(today.withDayOfMonth(1)) }
                .toList()

            val todo = months.mapNotNull { month ->
                val monthStart = maxOf(month, from)
                val monthEnd = minOf(month.plusMonths(1).minusDays(1), today)
                val key = month.format(monthFormatter)
                val old = existing[key]
                if (old != null &&
                    old.status == BackfillShardStatus.DONE.code &&
                    old.endDate >= monthEnd.toString()
                ) {
                    null
                } else {
                    BackfillShardEntity(
                        id = old?.id ?: 0L,
                        accountId = accountId,
                        dataDomain = "health",
                        month = key,
                        startDate = monthStart.toString(),
                        endDate = monthEnd.toString(),
                        status = BackfillShardStatus.PENDING.code,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
            }
            repository.upsertBackfillShards(todo)

            val pending = repository.pendingBackfillShards(accountId)
                .filter { it.dataDomain == "health" }
            // 每次最多补 3 个月：一年份 = 730 天 × 2 个接口 = 1460 个请求，
            // 一口气发完必然 429，然后就是 48 小时锁账号。分片本身会记录进度，
            // 剩下的月份下次点「回填」接着跑，不需要从头来。
            val batch = pending.take(MAX_SHARDS_PER_RUN)
            for (shard in batch) {
                var day = LocalDate.parse(shard.startDate)
                val end = LocalDate.parse(shard.endDate)
                while (!day.isAfter(end)) {
                    val date = day.format(dateFormatter)
                    if (saveSummary(accountId, region, token, pathUser, date)) records++
                    if (saveSleep(accountId, region, token, pathUser, date)) records++
                    done++
                    if (done % 5 == 0) onProgress(done, total, "已补到 $date")
                    // 200ms 太密：Garmin 的限流看的是每分钟请求数，1 秒一个才稳
                    delay(BACKFILL_REQUEST_GAP_MS)
                    day = day.plusDays(1)
                }
                repository.markBackfillShard(
                    shard.id,
                    BackfillShardStatus.DONE.code,
                    System.currentTimeMillis(),
                )
                onProgress(done.coerceAtMost(total), total, "已完成 ${shard.month}")
            }
            val remainingShards = (pending.size - batch.size).coerceAtLeast(0)

            var start = 0
            val pageSize = 100
            while (start < 3000) {
                val page = connectClient.fetchActivities(region, token, limit = pageSize, start = start)
                if (page.isEmpty()) break
                page.forEach { saveActivity(accountId, it) }
                records += page.size
                onProgress(total, total, "已拉取活动 ${start + page.size} 条")
                if (page.size < pageSize) break
                start += pageSize
                delay(BACKFILL_REQUEST_GAP_MS)
            }

            val todayText = today.format(dateFormatter)
            val finishedAt = System.currentTimeMillis()
            advanceCursor(accountId, "daily", todayText, finishedAt)
            advanceCursor(accountId, "sleep", todayText, finishedAt)
            advanceCursor(accountId, "activity", todayText, finishedAt)
            repository.updateAccountStatus(accountId, AccountStatus.NORMAL.code, finishedAt)
            log(accountId, ok = true, records = records, durationMs = finishedAt - startedAt)
            SyncOutcome(
                true,
                records,
                buildString {
                    append("回填完成，共 $records 条记录")
                    if (remainingShards > 0) {
                        append("；还有 $remainingShards 个月未补，")
                        append("等几分钟再点一次「回填历史」会从断点继续")
                    }
                },
            )
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            log(
                accountId,
                ok = false,
                records = 0,
                durationMs = System.currentTimeMillis() - startedAt,
                error = message,
            )
            // 回填中途 429 也必须上报：以前这里只返回 ok=false，上层不会进冷却，
            // 用户看到失败就再点一次，几千个请求接着打，直接把账号锁死。
            SyncOutcome(
                ok = false,
                records = 0,
                message = message,
                rateLimited = e is GarminNetworkException.RiskBlocked,
                retryAfterMs = (e as? GarminNetworkException.RiskBlocked)?.retryAfterMs,
                forbidden = e is GarminNetworkException.Forbidden,
            )
        }
    }

    /** 同步所有启用账号；返回总体结果，供 UI 与 Worker 使用 */
    suspend fun syncAll(
        backfillDays: Int = 7,
        primaryFirstId: Long? = null,
    ): SyncBatch {
        if (!syncMutex.tryLock()) return SyncBatch(busy = true, results = emptyList())
        return try {
            SyncBatch(busy = false, results = syncAllLocked(backfillDays, primaryFirstId))
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun syncAllLocked(
        backfillDays: Int,
        primaryFirstId: Long?,
    ): List<Pair<Long, SyncOutcome>> {
        val accounts = repository.observeEnabledAccounts().first()
            // 本地导入（SourceType.FILE）没有远端数据，不参与同步
            .filter { it.sourceType == SourceType.GARMIN.code }
            // 主账号先同步：首页以它为准，先拿到它的最新数据
            .sortedByDescending { it.id == primaryFirstId }
        val results = mutableListOf<Pair<Long, SyncOutcome>>()
        accounts.forEachIndexed { index, account ->
            if (index > 0) delay(ACCOUNT_GAP_MS)
            // 已经在 syncAll 的锁里，直接走不加锁的版本，否则自己会把自己拦住
            val outcome = syncAccountLocked(account.id, backfillDays)
            results += account.id to outcome
            // 一个账号被限流，另一个账号此刻发请求也只会再被拒一次，
            // 而且会让冷却窗口继续延长——直接收手
            if (outcome.rateLimited) return results
        }
        return results
    }

    /**
     * 取一个可用令牌。
     *
     * 之前这里只判断 `accessToken.isNotBlank()`，于是本地那张一小时后必然过期的
     * 令牌会被反复拿去用，直接换来 401。现在按 `tokenExpiresAtMs` 提前 5 分钟预刷新；
     * 过期时间未知（老数据为 0）时先信任，交给 401 重试兜底。
     */
    private suspend fun ensureToken(credential: StorableCredential): String {
        if (credential.isTokenFresh()) return credential.accessToken
        return refreshOrThrow(credential).accessToken
    }

    /**
     * 用 OAuth1 令牌换新的 access_token。
     * 换不到说明授权链已经断了（OAuth1 被吊销 / 旧版本凭据没存），只能重新登录。
     */
    private suspend fun refreshOrThrow(credential: StorableCredential): StorableCredential {
        val refreshed = try {
            ssoClient.refreshSession(credential)
        } catch (e: GarminNetworkException.RiskBlocked) {
            // 续期被限流 ≠ 登录过期。以前这里一律包成 SessionExpired，用户被引导去
            // 重新登录，结果又打一串登录请求，把限流拖成 48 小时锁定。
            throw e
        } catch (e: GarminNetworkException.Forbidden) {
            throw e
        } catch (e: Exception) {
            throw GarminNetworkException.SessionExpired(
                "登录已过期，自动续期失败，请重新登录该账号（${e.message}）"
            )
        }
        if (refreshed.accessToken.isBlank()) {
            throw GarminNetworkException.SessionExpired("续期未拿到访问令牌，请重新登录该账号")
        }
        // 刷新结果写回加密仓，避免每次同步都重新换 token
        credentialStore.save(refreshed)
        return refreshed
    }

    /**
     * 游标推进。
     *
     * `SyncCursorDao.advance()` 是 UPDATE，行不存在时影响 0 行——旧账号或游标被清过的情况下
     * 会永远停在"全量回填"。这里缺行就插入。
     */
    private suspend fun advanceCursor(accountId: Long, domain: String, date: String, ts: Long) {
        if (repository.getSyncCursor(accountId, domain) == null) {
            repository.upsertSyncCursor(
                SyncCursorEntity(
                    accountId = accountId,
                    dataDomain = domain,
                    lastSyncDate = date,
                    lastSyncTsMs = ts,
                )
            )
        } else {
            repository.advanceSyncCursor(accountId, domain, date, ts, "")
        }
    }

    private suspend fun resolveFromDate(accountId: Long, today: LocalDate, backfillDays: Int): LocalDate {
        val cursor = repository.getSyncCursor(accountId, "daily")
        val fallback = today.minusDays((backfillDays - 1).toLong())
        val last = cursor?.lastSyncDate?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it, dateFormatter) }.getOrNull() }
            ?: return fallback
        // 今天已经成功同步过：再点一次只需要今天，不必把昨天整天重拉一遍。
        // 常规同步一天 2 个接口 × 2 天 = 4 个请求，省掉昨天就只剩 2 个，
        // 对「一天点好几次」的用户，这是最直接的降请求手段。
        if (!last.isBefore(today)) return today
        // 从上次同步那天续上：那天的睡眠/汇总可能在同步之后才补齐，需要再拉一次
        return if (last.isAfter(fallback)) last else fallback
    }

    private suspend fun log(
        accountId: Long,
        ok: Boolean,
        records: Int,
        durationMs: Long,
        error: String = "",
    ) {
        repository.insertSyncLog(
            SyncLogEntity(
                accountId = accountId,
                trigger = "manual",
                status = if (ok) "success" else "failed",
                errorMsg = error,
                records = records,
                durationMs = durationMs,
                tsMs = System.currentTimeMillis(),
            )
        )
    }

    private fun AccountEntity.regionEnum() =
        com.fitsync.data.db.AccountRegion.from(region)

    private companion object {
        /** 逐日请求之间的间隔：一天 2 个请求，400ms 间隔下 7 天约 5 秒完成 */
        const val DAY_REQUEST_GAP_MS = 400L

        /** 回填请求间隔：1 秒一个，宁可慢，也不要把账号送进 48 小时锁定 */
        const val BACKFILL_REQUEST_GAP_MS = 1_000L

        /** 一次回填最多跑 3 个月分片，剩下的下次继续（分片进度会保留） */
        const val MAX_SHARDS_PER_RUN = 3

        /** 两个账号之间的间隔：换账号时多留一点，避免被当成连续突发 */
        const val ACCOUNT_GAP_MS = 1_200L
    }
}
