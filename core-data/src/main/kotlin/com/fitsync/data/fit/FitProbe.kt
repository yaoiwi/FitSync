package com.fitsync.data.fit

import com.garmin.fit.Decode
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Mesg
import com.garmin.fit.MesgListener
import com.garmin.fit.MesgNum
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 只看 FIT 的「身份证」，不解析内容。
 *
 * Garmin 官方导出包里的 FIT 是混装的：活动、monitoring（日常监测）、sleep、weight
 * 全在同一个目录甚至同一个嵌套 zip 里，文件名也看不出类型（`garminuser_.fit`、
 * `{email}_{serial}.fit`）。要分开处理只能读 `file_id` 消息里的 `type` 字段。
 *
 * 这里刻意只读 file_id 就停（[Decode.read] 读到回调里主动抛异常会污染解析状态，
 * 所以用标记位跳过后续处理），因为：
 * 1. 活动文件的样本点由 [FitActivityParser] 负责，重复解析浪费；
 * 2. wellness 文件动辄几万个样本，在导入阶段全解一遍会让 1GB 的包跑到天荒地老。
 */
object FitProbe {

    /** FIT 文件类型里我们关心的几种，数值来自 Garmin FIT SDK 的 `File` 枚举 */
    const val TYPE_ACTIVITY = 4
    const val TYPE_MONITORING_A = 14
    const val TYPE_ACTIVITY_SUMMARY = 15
    const val TYPE_MONITORING_DAILY = 20
    const val TYPE_MONITORING_B = 28
    const val TYPE_MONITORING = 32

    data class Info(
        /** file_id.type；读不到时为 -1 */
        val fileType: Int,
        /** file_id.time_created（毫秒）；读不到时为 0 */
        val timeCreatedMs: Long,
        /** 是否含 session 消息（活动文件的强特征） */
        val hasSession: Boolean,
    ) {
        val isActivity: Boolean
            get() = fileType == TYPE_ACTIVITY ||
                (fileType < 0 && hasSession)

        val isWellness: Boolean
            get() = fileType == TYPE_MONITORING_A ||
                fileType == TYPE_MONITORING_DAILY ||
                fileType == TYPE_MONITORING_B ||
                fileType == TYPE_MONITORING ||
                fileType == TYPE_ACTIVITY_SUMMARY

        /** UTC 日期 `yyyy-MM-dd`，用于判断这个文件覆盖哪一天 */
        val utcDate: String?
            get() = if (timeCreatedMs > 0L) {
                SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date(timeCreatedMs))
            } else {
                null
            }
    }

    fun read(bytes: ByteArray): Info? {
        if (bytes.size < 14) return null
        var type = -1
        var createdMs = 0L
        var hasSession = false
        var sawFileId = false

        val listener = MesgListener { mesg: Mesg ->
            when (mesg.num) {
                MesgNum.FILE_ID -> {
                    val id = FileIdMesg(mesg)
                    type = id.type?.value?.toInt() ?: -1
                    createdMs = (id.timeCreated?.timestamp ?: 0L).toLong() * 1000L
                    sawFileId = true
                }
                MesgNum.SESSION -> hasSession = true
                else -> Unit
            }
        }

        val ok = runCatching {
            Decode().read(ByteArrayInputStream(bytes), listener)
        }.getOrDefault(false)

        if (!ok && !sawFileId) return null
        return Info(fileType = type, timeCreatedMs = createdMs, hasSession = hasSession)
    }
}
