package com.fitsync.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记录「哪一天的健康数据刚搬到哪个账号」。
 *
 * **为什么需要单独记**：搬运成功后，目标账号的本地日汇总要等一次同步才会出现。
 * 在这之前再点一次搬运，会重复下载 + 重复上传（服务端返回 409 判重）。
 * 记一下，[SKIP_WINDOW_MS] 内点第二次就不再重复打接口。
 *
 * **为什么带时间戳而不是只记日期**：只记日期的话，用户若怀疑没搬成功，
 * 就永远被跳过、也没法重试。超过窗口自动失效，重试是允许的。
 */
@Singleton
class HealthCopyLedger @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 刚刚搬过的日期（[SKIP_WINDOW_MS] 内） */
    fun copiedDates(fromAccountId: Long, toAccountId: Long): Set<String> =
        entries(fromAccountId, toAccountId).keys

    fun markCopied(fromAccountId: Long, toAccountId: Long, date: String) {
        val now = System.currentTimeMillis()
        val merged = entries(fromAccountId, toAccountId)
            .filterValues { now - it < SKIP_WINDOW_MS }
            .toMutableMap()
            .apply { put(date, now) }
        val text = merged.entries.joinToString(",") { "${it.key}=${it.value}" }
        prefs.edit().putString(key(fromAccountId, toAccountId), text).apply()
    }

    fun clear(fromAccountId: Long, toAccountId: Long) {
        prefs.edit().remove(key(fromAccountId, toAccountId)).apply()
    }

    /** 读 `日期=时间戳` 形式的记录，自动丢掉过期项 */
    private fun entries(from: Long, to: Long): Map<String, Long> {
        val now = System.currentTimeMillis()
        return prefs.getString(key(from, to), null)
            ?.split(',')
            ?.mapNotNull { item ->
                val parts = item.split('=')
                if (parts.size != 2) return@mapNotNull null
                val date = parts[0].takeIf { it.length == 10 } ?: return@mapNotNull null
                val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
                if (now - ts >= SKIP_WINDOW_MS) null else date to ts
            }
            ?.toMap()
            ?: emptyMap()
    }

    private fun key(from: Long, to: Long) = "copy_${from}_$to"

    private companion object {
        const val PREFS = "fitsync_health_copy_ledger"

        /**
         * 搬完的日期在这段时间内不再重复搬。
         *
         * 原来只记 24 小时，是为了「用户怀疑没搬成功时能立刻重试」。但一次 365 天的
         * 搬运要分好几轮点（中途撞限流、切后台被杀都会中断），24 小时的窗口会让
         * 第二天的下一轮把前一天搬过的几百天全部重来一遍——白花几百个请求，
         * 正是最容易被限流的动作。改成 90 天：只记成功的日期，失败的日期不记，
         * 所以「重试」仍然有效，重试的只是没成功的那部分。
         */
        const val SKIP_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
    }
}
