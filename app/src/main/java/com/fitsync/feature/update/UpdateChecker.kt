package com.fitsync.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 应用内检查更新。
 *
 * 数据源是仓库的 GitHub Releases：读了最新一条 release 的 tag，和本机版本号比较。
 * 仓库还没发 Release 时返回 [Result.NoRelease]，不做任何打扰。
 */
class UpdateChecker @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        /** 有新版本 */
        data class Available(
            val version: String,
            val url: String,
            val notes: String,
        ) : Result

        /** 已经是最新 */
        data object UpToDate : Result

        /** 仓库还没有发布 Release */
        data object NoRelease : Result

        /** 网络或解析失败 */
        data class Failed(val message: String) : Result
    }

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FitSync-Android")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> Result.NoRelease
                    !response.isSuccessful -> Result.Failed("HTTP ${response.code}")
                    else -> {
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val tag = json.optString("tag_name").ifBlank {
                            json.optString("name")
                        }
                        if (tag.isBlank()) return@use Result.NoRelease
                        val version = tag.trimStart('v', 'V')
                        if (isNewer(version, currentVersion)) {
                            Result.Available(
                                version = version,
                                url = json.optString("html_url").ifBlank { RELEASES_PAGE },
                                notes = json.optString("body").trim(),
                            )
                        } else {
                            Result.UpToDate
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 逐段比较数字，`0.10.10` 要大于 `0.10.9`（字符串比较会判错） */
    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val b = current.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    companion object {
        const val PROJECT_URL = "https://github.com/yaoiwi/FitSync"
        const val ISSUES_URL = "https://github.com/yaoiwi/FitSync/issues"
        const val RELEASES_PAGE = "https://github.com/yaoiwi/FitSync/releases"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/yaoiwi/FitSync/releases/latest"
    }
}
