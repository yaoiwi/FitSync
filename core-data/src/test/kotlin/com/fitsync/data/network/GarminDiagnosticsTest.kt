package com.fitsync.data.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 诊断记录的语义测试：409「已存在」不能被算成失败。
 *
 * 搬运健康数据时，目标账号本来就有那一天的文件，上传接口返回 409 是正常结果。
 * 以前这些 409 会把 12 条失败名额占满，用户看到「最近 12 次失败」以为搬运坏了。
 */
class GarminDiagnosticsTest {

    @Before
    fun setUp() = GarminDiagnostics.clear()

    @After
    fun tearDown() = GarminDiagnostics.clear()

    private fun entry(
        code: Int,
        body: String = "",
        atMs: Long = 1_700_000_000_000L,
    ) = GarminDiagnostics.Entry(
        atMs = atMs,
        endpoint = "/upload-service/upload/.fit",
        method = "POST",
        code = code,
        tokenLength = 1969,
        host = "connectapi.garmin.cn",
        cfRay = null,
        location = null,
        bodySnippet = body,
    )

    private val duplicateBody = """
        {"detailedImportResult":{"uploadId":"","uploadUuid":null,"owner":11814876,"fileSize":312}}
    """.trimIndent()

    @Test
    fun `409 不进失败列表`() {
        GarminDiagnostics.record(entry(409, duplicateBody))

        assertNull("409 是「已存在」，不该出现在最近一次失败里", GarminDiagnostics.latest())
        assertTrue(GarminDiagnostics.recent().isEmpty())
        assertEquals(
            "无失败；最近 1 次上传都是「已存在」（409），属正常",
            GarminDiagnostics.expectedNote(),
        )
    }

    @Test
    fun `真失败和 409 分开记_互不挤占名额`() {
        repeat(20) { GarminDiagnostics.record(entry(409, duplicateBody)) }
        GarminDiagnostics.record(entry(403, """{"message":"HTTP 403 Forbidden"}"""))

        assertEquals(403, GarminDiagnostics.latest()!!.code)
        assertEquals("失败只留最近 12 条", 1, GarminDiagnostics.recent().size)
    }

    @Test
    fun `dump 把 409 收成一行汇总而不是十二条失败`() {
        repeat(12) { GarminDiagnostics.record(entry(409, duplicateBody)) }

        val text = GarminDiagnostics.dump()
        assertTrue("标题不该写失败次数：$text", text.startsWith("FitSync 同步诊断（最近没有失败）"))
        assertTrue(text.contains("另有 12 次上传返回 HTTP 409"))
        assertTrue("要说明「已存在」不是失败", text.contains("不计入失败"))
    }

    @Test
    fun `几百字节的文件要点明是空数据包`() {
        GarminDiagnostics.record(entry(409, duplicateBody))

        val text = GarminDiagnostics.dump()
        assertTrue("应解释 312 字节是什么：$text", text.contains("文件 312 字节"))
        assertTrue(text.contains("空数据包"))
    }

    @Test
    fun `几十 KB 的正常文件不乱猜成空包`() {
        GarminDiagnostics.record(entry(409, """{"fileSize":41794}"""))

        val text = GarminDiagnostics.dump()
        assertTrue(text.contains("文件 41794 字节"))
        assertTrue("大文件不该被说成空包：$text", !text.contains("空数据包"))
    }

    @Test
    fun `有真失败时标题按失败条数写`() {
        GarminDiagnostics.record(entry(419, """{"message":"Wellness device is not active"}"""))
        GarminDiagnostics.record(entry(409, duplicateBody))

        val text = GarminDiagnostics.dump()
        assertTrue(text.startsWith("FitSync 同步诊断（最近 1 次失败）"))
        assertTrue(text.contains("HTTP 419"))
        assertTrue(text.contains("另有 1 次上传返回 HTTP 409"))
    }
}
