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

    /**
     * 一组 FIT 的指纹，用来判断「这个文件是不是已经搬到目标账号了」。
     *
     * 形如 `sleep:41794#1`。
     *
     * **为什么用大小而不是文件名**：wellness 包是 Garmin 后台按天打包的，
     * 同一份数据重新下载时文件名可能带生成时刻，用名字会每次都判成新文件，
     * 台账就白记了。字节数由数据量决定：那天的数据没变，大小就不变；
     * 数据后来补全了（比如睡眠延迟生成）大小必然变 —— 那正好该重传一次。
     *
     * **为什么要带类型**：一天里 monitoring 和 stress 的 FIT 都可能只有几百字节，
     * 只按大小会把不同文件当成同一个，导致漏传。类型只能从文件名猜，
     * 认不出来退回 `fit`，此时大小是主要判据。
     *
     * **为什么末尾还有 `#N`**：同一天真的会出现两个都是 418 字节的 monitoring 分片
     * （实测日志里就有）。只按「类型 + 大小」会把它们算成同一个文件，第二个被判成
     * 「已经传过」直接跳过 —— 数据就悄悄少了。`#N` 是「同类型同大小的第几个」：
     * 组内序号，与文件名无关，顺序变了也不影响；文件集合不变，指纹集合就不变。
     */
    fun fingerprints(fits: List<WellnessFit>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return fits.map { fit ->
            val base = "${kindOf(fit.name)}:${fit.bytes.size}"
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            "$base#$n"
        }
    }

    /**
     * 从文件名猜 FIT 的类型。
     *
     * Garmin 的 wellness 包命名没有公开规范，实测常见形态会把类型写进名字
     * （`..._sleep.fit`、`..._monitoring.fit`）。认不出来就退回 `fit`，
     * 此时指纹退化成「只看大小」，仍然能挡住绝大部分重复。
     */
    private fun kindOf(name: String): String {
        val lower = name.lowercase(Locale.US)
        return when {
            "sleep" in lower -> "sleep"
            "monitor" in lower -> "monitor"
            "stress" in lower -> "stress"
            "hrv" in lower -> "hrv"
            "respiration" in lower -> "resp"
            "spo2" in lower -> "spo2"
            else -> "fit"
        }
    }

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
