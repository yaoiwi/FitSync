package com.fitsync.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** wellness zip 的解析：只认 `.fit`，其它文件和非 zip 一律忽略。 */
class WellnessPackageTest {

    private fun fit(size: Int = 32) = ByteArray(size) { 0x0E }

    @Test
    fun `取出 zip 里的 fit 并保留文件名`() {
        val zip = WellnessPackage.pack(
            "wellness.fit" to fit(40),
            "sub/sleep.fit" to fit(24),
            "readme.txt" to "hello".toByteArray(),
        )
        val fits = WellnessPackage.extract(zip)
        assertEquals(2, fits.size)
        assertEquals(setOf("wellness.fit", "sleep.fit"), fits.map { it.name }.toSet())
        assertEquals(40, fits.first { it.name == "wellness.fit" }.bytes.size)
    }

    @Test
    fun `不是 zip 时返回空列表`() {
        assertTrue(WellnessPackage.extract(ByteArray(64)).isEmpty())
        assertFalse(WellnessPackage.isZip(ByteArray(64)))
    }

    @Test
    fun `过小的 fit 被丢掉`() {
        val zip = WellnessPackage.pack("tiny.fit" to ByteArray(4))
        assertTrue(WellnessPackage.extract(zip).isEmpty())
    }

    @Test
    fun `识别 zip 魔数`() {
        val zip = WellnessPackage.pack("a.fit" to fit())
        assertTrue(WellnessPackage.isZip(zip))
    }

    // ==================== 文件指纹 ====================
    //
    // 指纹用来判断「这个文件是不是已经搬到目标账号了」。台账以前只记日期，
    // 于是「当天搬的时候睡眠还没生成」会被记成搬完，第二天直接跳过，睡眠永远补不上。

    private fun fp(vararg fits: WellnessFit) = WellnessPackage.fingerprints(fits.toList())

    @Test
    fun `指纹带类型，大小相同的不同文件不会被混为一谈`() {
        // 一天里 monitoring 和 stress 都可能只有几百字节：只按大小会漏传一个
        val ids = fp(
            WellnessFit("2026-11-02_monitoring.fit", fit(400)),
            WellnessFit("2026-11-02_stress.fit", fit(400)),
        )
        assertEquals(listOf("monitor:400#1", "stress:400#1"), ids)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `同一天两个同类型同大小的文件各有各的指纹`() {
        // 实测日志里同一天就是会出现两个 418 字节的 monitoring 分片。
        // 只按「类型 + 大小」会把第二个判成「已经传过」直接跳过，数据就悄悄少了
        val ids = fp(
            WellnessFit("a_monitoring.fit", fit(418)),
            WellnessFit("b_monitoring.fit", fit(418)),
        )
        assertEquals(listOf("monitor:418#1", "monitor:418#2"), ids)
    }

    @Test
    fun `文件名带时间戳也认得出来，不会每次都当新文件`() {
        // 同一份数据重新下载时文件名可能带生成时刻，按名字记账就会永远判成新文件
        val a = fp(WellnessFit("2026-11-02-19-04-46_sleep.fit", fit(173)))[0]
        val b = fp(WellnessFit("2026-11-02-08-12-03_sleep.fit", fit(173)))[0]
        assertEquals(a, b)
        assertEquals("sleep:173#1", a)
    }

    @Test
    fun `文件顺序变了指纹集合不变`() {
        val a = WellnessFit("a_monitoring.fit", fit(400))
        val b = WellnessFit("b_sleep.fit", fit(173))
        assertEquals(fp(a, b).toSet(), fp(b, a).toSet())
    }

    @Test
    fun `数据量变了指纹就变，该重传`() {
        // 睡眠后来补全了：文件变大 → 指纹变 → 重传一次（服务端 409 会挡住真重复）
        val before = fp(WellnessFit("sleep.fit", fit(173)))[0]
        val after = fp(WellnessFit("sleep.fit", fit(41794)))[0]
        assertNotEquals(before, after)
    }

    @Test
    fun `认不出类型时退回 fit`() {
        assertEquals("fit:312#1", fp(WellnessFit("2026-11-02-19-04-46.fit", fit(312)))[0])
    }

    @Test
    fun `一组文件的指纹按原顺序一一对应`() {
        assertEquals(
            listOf("monitor:400#1", "sleep:173#1", "stress:312#1"),
            fp(
                WellnessFit("a_monitoring.fit", fit(400)),
                WellnessFit("a_sleep.fit", fit(173)),
                WellnessFit("a_stress.fit", fit(312)),
            ),
        )
    }
}
