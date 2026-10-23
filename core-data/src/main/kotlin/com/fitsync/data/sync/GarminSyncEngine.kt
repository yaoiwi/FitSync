package com.fitsync.data.sync

import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.AccountStatus
import com.fitsync.data.db.ActivityEntity
import com.fitsync.data.db.DailySummaryEntity
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.SleepSessionEntity
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    )

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    suspend fun syncAccount(accountId: Long, backfillDays: Int = 7): SyncOutcome =
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
                SyncOutcome(false, 0, message)
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

        val today = LocalDate.now()
        var day = resolveFromDate(accountId, today, backfillDays)

        while (!day.isAfter(today)) {
            val date = day.format(dateFormatter)

            val summary = connectClient.fetchDailySummary(
                account.regionEnum(), token, account.displayName, date,
            )
            if (summary != null) {
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
                records++
            }

            val sleep = connectClient.fetchSleep(
                account.regionEnum(), token, account.displayName, date,
            )
            if (sleep != null) {
                repository.upsertSleepSession(
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
                records++
            }

            day = day.plusDays(1)
        }

        connectClient.fetchActivities(account.regionEnum(), token, limit = 20).forEach { a ->
            repository.upsertActivity(
                ActivityEntity(
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
                    hasGps = a.distanceM > 0,
                )
            )
            records++
        }

        val todayText = today.format(dateFormatter)
        val doneAt = System.currentTimeMillis()
        advanceCursor(accountId, "daily", todayText, doneAt)
        advanceCursor(accountId, "sleep", todayText, doneAt)
        advanceCursor(accountId, "activity", todayText, doneAt)
        return records
    }

    /** 同步所有启用账号；返回总体结果，供 UI 与 Worker 使用 */
    suspend fun syncAll(
        backfillDays: Int = 7,
        primaryFirstId: Long? = null,
    ): List<Pair<Long, SyncOutcome>> {
        val accounts = repository.observeEnabledAccounts().first()
            // 本地导入（SourceType.FILE）没有远端数据，不参与同步
            .filter { it.sourceType == SourceType.GARMIN.code }
            // 主账号先同步：首页以它为准，先拿到它的最新数据
            .sortedByDescending { it.id == primaryFirstId }
        return accounts.map { it.id to syncAccount(it.id, backfillDays) }
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
        val resume = last.minusDays(1)
        return if (resume.isAfter(fallback)) resume else fallback
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
}
