package com.fitsync.data.sync

import com.fitsync.data.db.AccountEntity
import com.fitsync.data.db.AccountRegion
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.SourceType
import com.fitsync.data.network.CredentialStore
import com.fitsync.data.network.GarminConnectClient
import com.fitsync.data.network.GarminSsoClient
import com.fitsync.data.network.isTokenFresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 活动跨账号复制（中国区 → 国际区，或反过来）。
 *
 * **为什么只做活动**：Garmin 只对活动开放了写入接口（`POST /upload-service/upload`），
 * 日汇总 / 睡眠 / 心率 / 喝水都没有写入 API——这是 Garmin 侧的限制，不是本工程的取舍。
 * 健康数据只能在本 App 内合并展示，或导出成文件。
 *
 * 流程：源账号拉活动列表（本地库）→ 下载活动原始文件（FIT/TCX）→ 上传到目标账号。
 * 目标账号已有的活动（按开始时间比对）会跳过，避免重复上传。
 */
@Singleton
class ActivityCopyService @Inject constructor(
    private val repository: FitDataRepository,
    private val connectClient: GarminConnectClient,
    private val credentialStore: CredentialStore,
    private val ssoClient: GarminSsoClient,
) {

    data class CopyResult(
        val copied: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val messages: List<String> = emptyList(),
        val error: String? = null,
    ) {
        val summary: String
            get() = when {
                error != null -> "复制失败：$error"
                copied == 0 && skipped == 0 && failed == 0 -> "源账号没有可复制的活动"
                else -> buildString {
                    append("已复制 $copied 条")
                    if (skipped > 0) append("，跳过 $skipped 条（对方已有）")
                    if (failed > 0) append("，失败 $failed 条")
                }
            }
    }

    suspend fun copyRecent(
        fromAccountId: Long,
        toAccountId: Long,
        limit: Int = 10,
    ): CopyResult = withContext(Dispatchers.IO) {
        if (fromAccountId == toAccountId) {
            return@withContext CopyResult(error = "源账号和目标账号相同")
        }
        val from = repository.getAccount(fromAccountId)
            ?: return@withContext CopyResult(error = "源账号不存在")
        val to = repository.getAccount(toAccountId)
            ?: return@withContext CopyResult(error = "目标账号不存在")
        if (from.sourceType != SourceType.GARMIN.code || to.sourceType != SourceType.GARMIN.code) {
            return@withContext CopyResult(error = "只能在同一数据源类型之间复制（Garmin → Garmin）")
        }

        val fromRoman = AccountRegion.from(from.region)
        val toRegion = AccountRegion.from(to.region)

        val tokens = runCatching {
            tokenOf(from) to tokenOf(to)
        }.getOrElse { e ->
            return@withContext CopyResult(error = e.message ?: "获取访问令牌失败")
        }

        val sourceActivities = repository.observeActivities(fromAccountId).first().take(limit)
        if (sourceActivities.isEmpty()) {
            return@withContext CopyResult()
        }
        // 目标账号已有的活动开始时间，用于去重（同时也能挡住重复点击）
        val existingStarts = repository.observeActivities(toAccountId).first()
            .map { it.startTimeMs }
            .toSet()

        var copied = 0
        var skipped = 0
        var failed = 0
        val messages = mutableListOf<String>()
        val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        sourceActivities.forEach { activity ->
            if (activity.startTimeMs in existingStarts) {
                skipped++
                return@forEach
            }

            val file = runCatching {
                connectClient.downloadActivityFile(fromRoman, tokens.first, activity.externalId)
            }.getOrNull()

            if (file == null) {
                failed++
                messages += "${activity.sport} ${dateFormat.format(Date(activity.startTimeMs))}：下载失败"
                return@forEach
            }

            val uploaded = runCatching {
                connectClient.uploadActivityFile(
                    region = toRegion,
                    accessToken = tokens.second,
                    fileName = file.fileName,
                    bytes = file.bytes,
                )
            }.getOrNull()

            if (uploaded != null) {
                copied++
            } else {
                failed++
                messages += "${activity.sport} ${dateFormat.format(Date(activity.startTimeMs))}：上传失败"
            }
        }

        CopyResult(
            copied = copied,
            skipped = skipped,
            failed = failed,
            messages = messages.take(5),
        )
    }

    private suspend fun tokenOf(account: AccountEntity): String {
        val credential = credentialStore.load(
            SourceType.GARMIN.code,
            account.region,
            account.externalUserId,
        ) ?: throw IllegalStateException("账号 ${account.displayName} 的凭据缺失，请重新登录")
        // 只判 isNotBlank 不够：本地令牌一小时后必过期，复用就会 401
        if (credential.isTokenFresh()) return credential.accessToken
        val refreshed = ssoClient.refreshSession(credential)
        credentialStore.save(refreshed)
        return refreshed.accessToken
    }
}
