package com.fitsync.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搬运结果的文案。
 *
 * 界面只显示 `summary` 这一行，所以「为什么只搬了 1 天」必须写在里面：
 * 用户看到 `已搬运 1 天` 的第一反应是功能坏了。
 */
class HealthSyncSummaryTest {

    private fun result(
        uploaded: Int = 0,
        duplicateDays: Int = 0,
        failed: Int = 0,
        noDataDays: Int = 0,
        requestedDays: Int = 7,
        skippedExisting: Int = 0,
        skippedCopied: Int = 0,
        uploadedDates: List<String> = emptyList(),
        messages: List<String> = emptyList(),
        error: String? = null,
    ) = HealthSyncService.Result(
        uploaded = uploaded,
        duplicateDays = duplicateDays,
        failed = failed,
        noDataDays = noDataDays,
        requestedDays = requestedDays,
        skippedExisting = skippedExisting,
        skippedCopied = skippedCopied,
        uploadedDates = uploadedDates,
        messages = messages,
        error = error,
    )

    @Test
    fun `全部搬完时不提跳过`() {
        val summary = result(
            uploaded = 3,
            uploadedDates = listOf("2026-10-29", "2026-10-28", "2026-10-27"),
        ).summary
        assertTrue(summary, summary.startsWith("已搬运 3 天"))
        assertTrue(summary, summary.contains("10-29"))
        assertTrue(summary, !summary.contains("跳过"))
    }

    @Test
    fun `目标已有同样文件时说明是重复而不是失败`() {
        val summary = result(
            uploaded = 1,
            duplicateDays = 2,
            uploadedDates = listOf("2026-10-29"),
        ).summary
        assertTrue(summary, summary.contains("已搬运 1 天"))
        assertTrue(summary, summary.contains("2 天目标账号已有同样文件"))
    }

    @Test
    fun `目标本地已有数据时全部跳过`() {
        val summary = result(skippedExisting = 7).summary
        assertTrue(summary, summary.startsWith("没有新日期可搬"))
        assertTrue(summary, summary.contains("目标账号这 7 天本地已有数据"))
    }

    @Test
    fun `源账号没有数据时要说清不是程序的问题`() {
        val summary = result(noDataDays = 7).summary
        assertTrue(summary, summary.contains("7 天源账号没有数据"))
    }

    @Test
    fun `全部失败时带出第一条原因`() {
        val summary = result(
            failed = 2,
            messages = listOf("2026-10-29：HTTP 419 上传被拒"),
        ).summary
        assertEquals("全部失败：2026-10-29：HTTP 419 上传被拒", summary)
    }

    @Test
    fun `有错误时优先显示错误`() {
        val summary = result(uploaded = 1, error = "刚被 Garmin 限流，约 8 分钟后再试").summary
        assertEquals("搬运失败：刚被 Garmin 限流，约 8 分钟后再试", summary)
    }

    @Test
    fun `部分成功也会提失败天数`() {
        val summary = result(
            uploaded = 4,
            failed = 2,
            noDataDays = 1,
            uploadedDates = listOf("2026-10-29", "2026-10-28", "2026-10-27", "2026-10-26"),
        ).summary
        assertTrue(summary, summary.contains("失败 2 天"))
        assertTrue(summary, summary.contains("1 天源账号没有数据"))
    }
}
