package com.fitsync.data.health

import com.garmin.fit.DateTime
import com.garmin.fit.Decode
import com.garmin.fit.Mesg
import com.garmin.fit.MesgListener
import com.garmin.fit.MesgNum
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * 生成出来的 monitoring FIT 必须能被官方解码器读回。
 *
 * 这里的断言都是真机踩出来的：`local_timestamp` 曾经被写成 Unix 秒（相当于 2046 年），
 * 上传后服务端直接 500，而本地不报错——只有解码回来看才发现。
 */
class MonitoringFitBuilderTest {

    private lateinit var file: File
    private val serial = 3484463308L
    private val dayStartMs = LocalDate.of(2026, 10, 29)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Before
    fun setUp() {
        file = File.createTempFile("monitoring-test", ".fit")
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun build(
        steps: Int = 480,
        sleepMinutes: Int = 420,
        stress: Int = 40,
        productId: Int = 3992,
    ) {
        MonitoringFitBuilder.build(
            target = file,
            serialNumber = serial,
            productId = productId,
            dayStartMs = dayStartMs,
            steps = steps,
            restingHr = 52,
            sleepMinutes = sleepMinutes,
            stressAvg = stress,
        )
    }

    private fun decodeAll(): List<Mesg> {
        val out = mutableListOf<Mesg>()
        Decode().read(file.inputStream(), MesgListener { mesg -> out += mesg })
        assertTrue("解码后应当有消息", out.isNotEmpty())
        return out
    }

    private fun List<Mesg>.of(num: Int) = filter { it.num == num }

    private fun Mesg.long(name: String): Long? =
        (getFieldValue(name) as? Number)?.toLong()

    @Test
    fun `生成的文件通过完整性校验`() {
        build()
        val intact = file.inputStream().use { Decode().checkFileIntegrity(it) }
        assertTrue("CRC / 文件头应通过校验", intact)
    }

    @Test
    fun `file_id 是 monitoring_b 且带上设备身份`() {
        build()
        val id = decodeAll().of(MesgNum.FILE_ID).first()
        assertEquals("file 类型应为 monitoring_b(32)", 32L, id.long("type"))
        assertEquals(serial, id.long("serial_number"))
        assertEquals(3992L, id.long("product"))
    }

    @Test
    fun `时间戳写成 FIT 纪元秒而不是 Unix 秒`() {
        build()
        // 解码器回给监听器的是通用 Mesg，字段名不一定读得出来，所以直接看字节：
        // 这几项都是 uint32 小端，写错纪元会整整差 631065600。
        val bytes = file.readBytes()
        val offsetSeconds = TimeZone.getDefault().getOffset(dayStartMs) / 1000
        val fitSeconds = dayStartMs / 1000 - 631_065_600L
        val localSeconds = fitSeconds + offsetSeconds

        assertTrue("应写入 FIT 纪元的 local_timestamp（$localSeconds）", bytes.containsUInt32(localSeconds))
        assertTrue("第一条步数样本的时间戳应是当天 08:00", bytes.containsUInt32(fitSeconds + 8 * 3600))
        assertTrue(
            "文件里不应出现 Unix 秒（${dayStartMs / 1000}），出现就说明又写成了 Unix 时间",
            !bytes.containsUInt32(dayStartMs / 1000),
        )
        assertTrue("monitoring_info 必须存在", decodeAll().of(MesgNum.MONITORING_INFO).isNotEmpty())
    }

    /** FIT SDK 默认按大端写，别处也可能给大端文件，所以两种字节序都认 */
    private fun ByteArray.containsUInt32(value: Long): Boolean {
        val needles = listOf(
            java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array(),
            java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array(),
        )
        for (needle in needles) {
            outer@ for (i in 0..size - needle.size) {
                for (j in needle.indices) {
                    if (this[i + j] != needle[j]) continue@outer
                }
                return true
            }
        }
        return false
    }

    @Test
    fun `步数按 15 分钟粒度铺开且总量守恒`() {
        build(steps = 480)
        val samples = decodeAll().of(MesgNum.MONITORING)
        assertEquals(48, samples.size)
        assertEquals(480L, samples.sumOf { it.long("steps") ?: 0L })
    }

    @Test
    fun `睡眠与压力消息数量与输入对得上`() {
        build(sleepMinutes = 420, stress = 40)
        val mesgs = decodeAll()
        assertEquals("420 分钟 / 5 分钟一段", 84, mesgs.of(MesgNum.SLEEP_LEVEL).size)
        assertEquals("每小时一条", 24, mesgs.of(MesgNum.STRESS_LEVEL).size)
    }
}
