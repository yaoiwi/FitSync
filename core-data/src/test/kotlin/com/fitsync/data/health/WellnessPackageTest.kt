package com.fitsync.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
