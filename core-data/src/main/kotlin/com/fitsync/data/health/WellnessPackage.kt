package com.fitsync.data.health

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 从 wellness 包里取出的一个 FIT 文件 */
class WellnessFit(val name: String, val bytes: ByteArray)

/**
 * 处理 Garmin 的「当天 wellness 原始包」。
 *
 * `GET /download-service/files/wellness/{date}` 返回一个 zip，里面是设备当天上传的
 * 原始 FIT（monitoring / sleep / stress 都在里面）。把这些文件**原样**转给另一个区，
 * 比在本机重新拼一个 monitoring FIT 可靠得多：
 *
 * - 时间戳、时区、设备身份都是设备自己写的，不存在"参数语义用错"；
 * - 睡眠分期、压力、身体电量这些结构复杂的数据不用自己重建；
 * - 一天只有一个包，请求量比逐日接口小。
 *
 * 来源说明：这条思路（下载 wellness 包 → 原样转发）参考了 DailySync
 * （https://github.com/gooin/dailysync-rev，GPL-3.0）。**此处没有使用其任何代码**，
 * 只是沿用了公开的接口路径与行为约定；解压策略与限额都是本项目自行设计。
 * 详见 THIRD_PARTY_NOTICES.md。
 */
object WellnessPackage {

    /** 单个 FIT 上限：wellness 文件通常几十 KB，8 MB 足够挡住异常包 */
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024

    /** 一个包里最多取这么多 FIT，避免畸形压缩包把内存吃光 */
    private const val MAX_ENTRIES = 50

    /** FIT 文件头最短 12 字节 */
    private const val MIN_FIT_BYTES = 12

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /** 取出 zip 里所有 `.fit`；不是 zip、或没有 FIT 时返回空列表 */
    fun extract(bytes: ByteArray): List<WellnessFit> {
        if (!isZip(bytes)) return emptyList()
        val fits = mutableListOf<WellnessFit>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && fits.size < MAX_ENTRIES) {
                    val name = entry.name.substringAfterLast('/')
                    val isFit = !entry.isDirectory && name.lowercase(Locale.US).endsWith(".fit")
                    if (isFit) {
                        val data = zip.readBytes()
                        if (data.size in MIN_FIT_BYTES..MAX_ENTRY_BYTES) {
                            fits += WellnessFit(name, data)
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return fits
    }

    /** 测试与调试用：把若干文件打成一个 zip */
    internal fun pack(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
}
