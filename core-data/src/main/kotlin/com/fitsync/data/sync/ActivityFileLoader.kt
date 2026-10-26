package com.fitsync.data.sync

import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.AccountRegion
import com.fitsync.data.db.ActivityLapEntity
import com.fitsync.data.db.ActivitySampleEntity
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.FitSyncDatabase
import com.fitsync.data.db.SourceType
import com.fitsync.data.network.CredentialStore
import com.fitsync.data.network.GarminConnectClient
import com.fitsync.data.network.GarminSsoClient
import com.fitsync.data.network.isTokenFresh
import com.fitsync.data.fit.FitActivityParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按需加载某条活动的原始文件（FIT），补齐逐点采样。
 *
 * 为什么不在定时同步里做：一条活动几百 KB 到几 MB，20 条就十几 MB，
 * 而用户可能一周只看一两次详情。改成"点开才拉"，流量和同步耗时都可控。
 *
 * 只写 `activity_samples`（心率 / 配速 / 海拔 / 步频 / 功率），
 * 不写 `activity_routes`——轨迹展示这条路先不做。
 */
@Singleton
class ActivityFileLoader @Inject constructor(
    private val db: FitSyncDatabase,
    private val repository: FitDataRepository,
    private val connectClient: GarminConnectClient,
    private val credentialStore: CredentialStore,
    private val ssoClient: GarminSsoClient,
) {

    /** [message] 为空表示成功（或本来就有数据）；非空是给用户看的一句原因 */
    data class LoadResult(
        val samples: Int = 0,
        val message: String? = null,
    )

    suspend fun loadSamples(activityId: Long): LoadResult = withContext(Dispatchers.IO) {
        val activity = db.activityDao().getById(activityId)
            ?: return@withContext LoadResult(message = "活动不存在")

        // 已经有采样点（之前拉过，或来自 FIT 导入）就直接用
        val existing = db.activitySampleDao().byActivity(activityId)
        if (existing.isNotEmpty()) return@withContext LoadResult(existing.size)

        val account = repository.getAccount(activity.accountId)
            ?: return@withContext LoadResult(message = "账号已删除，拿不到原始文件")
        if (account.sourceType != SourceType.GARMIN.code) {
            return@withContext LoadResult(message = "本地导入的记录没有原始文件")
        }

        val token = try {
            tokenOf(account)
        } catch (e: Exception) {
            return@withContext LoadResult(message = e.message ?: "获取访问令牌失败")
        }

        val file = runCatching {
            connectClient.downloadActivityFile(
                region = AccountRegion.from(account.region),
                accessToken = token,
                activityId = activity.externalId,
            )
        }.getOrNull() ?: return@withContext LoadResult(message = "下载活动文件失败，稍后再试")

        val lower = file.fileName.lowercase(Locale.US)
        if (!lower.endsWith(".fit")) {
            // Garmin 偶尔只给 TCX/GPX，两者结构完全不同，硬套 FIT 解析只会得到空结果
            val ext = file.fileName.substringAfterLast('.', "").uppercase(Locale.US)
            return@withContext LoadResult(message = "这条记录只有 ${ext.ifBlank { "未知" }} 文件，暂时只能解析 FIT")
        }

        val parsed = FitActivityParser.parse(file.bytes)
        if (parsed.samples.isEmpty()) {
            return@withContext LoadResult(message = "文件里没有可用采样点")
        }

        db.activitySampleDao().deleteByActivity(activityId)
        db.activitySampleDao().upsertAll(
            parsed.samples.map { p ->
                ActivitySampleEntity(
                    activityId = activityId,
                    accountId = activity.accountId,
                    tsMs = p.tsMs,
                    hr = p.hr,
                    cadence = p.cadence,
                    speedMs = p.speedMs,
                    powerW = p.powerW,
                    elevM = p.elevM,
                    tempC = p.tempC,
                )
            },
        )

        // 分段：自动计圈或手动按圈，之前一直是空表
        db.activityLapDao().deleteByActivity(activityId)
        if (parsed.laps.isNotEmpty()) {
            db.activityLapDao().upsertAll(
                parsed.laps.map { lap ->
                    ActivityLapEntity(
                        activityId = activityId,
                        lap = lap.index,
                        startTimeMs = lap.startMs,
                        endTimeMs = lap.endMs,
                        durationSec = lap.durationSec,
                        distanceM = lap.distanceM,
                        avgHr = lap.avgHr,
                        maxHr = lap.maxHr,
                    )
                }
            )
        }

        // 用 FIT 里的精确值校准摘要：列表里那些 0 值多半是列表接口缺字段
        db.activityDao().upsert(
            activity.copy(
                distanceM = parsed.totalDistanceM.takeIf { it > 0 } ?: activity.distanceM,
                calories = parsed.totalCalories.takeIf { it > 0 } ?: activity.calories,
                avgHr = parsed.avgHr.takeIf { it > 0 } ?: activity.avgHr,
                maxHr = parsed.maxHr.takeIf { it > 0 } ?: activity.maxHr,
            )
        )

        LoadResult(parsed.samples.size)
    }

    /** 与活动复制同一套取令牌逻辑：先看有效期，过期才换，避免每次点详情都走一次 OAuth1 */
    private suspend fun tokenOf(account: AccountEntity): String {
        val credential = credentialStore.load(
            SourceType.GARMIN.code,
            account.region,
            account.externalUserId,
        ) ?: throw IllegalStateException("账号凭据缺失，请重新登录该账号")
        if (credential.isTokenFresh()) return credential.accessToken
        val refreshed = ssoClient.refreshSession(credential)
        credentialStore.save(refreshed)
        return refreshed.accessToken
    }
}
