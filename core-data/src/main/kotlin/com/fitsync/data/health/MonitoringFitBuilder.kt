package com.fitsync.data.health

import com.garmin.fit.ActivityType
import com.garmin.fit.DateTime
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.File as FitFile
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.Manufacturer
import com.garmin.fit.MonitoringInfoMesg
import com.garmin.fit.MonitoringMesg
import com.garmin.fit.SleepLevel
import com.garmin.fit.SleepLevelMesg
import com.garmin.fit.SourceType
import com.garmin.fit.StressLevelMesg
import java.io.File
import java.util.TimeZone

/**
 * 用 Garmin 官方 FIT SDK 生成 **monitoring（日常监测）** 文件。
 *
 * **当前搬运流程已不再使用它**（1.14 起改为直接转发源账号的 wellness 原始包，
 * 见 [HealthSyncService]）。这里保留是因为它仍是一个可用的兜底能力：
 * 源账号拿不到原始包（如老账号没有 wellness 文件）时，可以按本地日汇总重新生成。
 *
 * 为什么是这条路径：Garmin 没有"写入健康数据"的 REST 接口，但设备上传的
 * monitoring FIT 会被 Connect 计入日汇总（步数 / 心率 / 睡眠 / 压力）。
 * `POST /upload-service/upload` 接受 FIT 文件——所以只要生成合规的 monitoring FIT，
 * 并以目标账号**已绑定设备**的身份写入（serial number / product 一致），
 * 数据就能落到目标账号。
 *
 * 这正是 DailySync 那类服务的前提：两个区都要有「主要可穿戴设备」标签，
 * 且必须是同一块手表（设备身份要对得上）。
 *
 * 局限：日汇总只有聚合值，没有原始秒级样本，所以这里按合理分布铺点
 * （步数铺在白天、睡眠铺在夜间、压力每小时一条）。Garmin 会自行聚合。
 */
object MonitoringFitBuilder {

    /** FIT 时间戳的纪元：1989-12-31 00:00 UTC，与 Unix 纪元相差 631065600 秒 */
    private const val FIT_EPOCH_OFFSET_SECONDS = 631_065_600L

    /**
     * @param dayStartMs 当天 00:00 的 epoch 毫秒（本地时区）
     * @param serialNumber 目标账号绑定设备的序列号（必须匹配，否则 Garmin 可能拒收）
     * @param productId 目标账号绑定设备的产品 ID
     */
    fun build(
        target: File,
        serialNumber: Long,
        productId: Int,
        dayStartMs: Long,
        steps: Int,
        restingHr: Int,
        sleepMinutes: Int,
        stressAvg: Int,
    ) {
        val encoder = FileEncoder(target, Fit.ProtocolVersion.V2_0)
        // 注意构造函数语义：DateTime(long) 收的是「FIT 秒」，DateTime(Date) 才收毫秒。
        // 之前传的是毫秒，被当成秒后是 2046 年，超出 uint32 直接编码成 invalid，
        // 整个文件的时间戳全是 0xffffffff，服务端只能回 500。
        fun fitTime(ms: Long) = DateTime(java.util.Date(ms))

        val created = fitTime(dayStartMs)
        // 文件生成时间用真实当前时间：设备上传历史 monitoring 数据时也是"现在写文件、
        // 时间戳是过去"，把 time_created 设成当天 00:00 反而不像真实设备。
        val fileCreated = fitTime(System.currentTimeMillis())

        // 坑：FIT 的时间戳不是 Unix 秒，而是「1989-12-31 00:00 UTC 起算」的秒数。
        // 之前这里直接拿 (epochMillis + 时区偏移) / 1000 得到 Unix 秒，等于把
        // local_timestamp 写到了 2046 年（超出 uint32 还会被截断），服务端拿到就 500。
        // 正确做法：先减掉 FIT 纪元偏移得到 FIT 秒，再加上时区偏移。
        val fitSeconds = dayStartMs / 1000L - FIT_EPOCH_OFFSET_SECONDS
        val offsetSeconds = TimeZone.getDefault().getOffset(dayStartMs) / 1000L
        val localSeconds = fitSeconds + offsetSeconds

        encoder.write(
            FileIdMesg().apply {
                type = FitFile.MONITORING_B
                manufacturer = Manufacturer.GARMIN
                product = productId
                this.serialNumber = serialNumber
                timeCreated = fileCreated
            }
        )

        encoder.write(
            DeviceInfoMesg().apply {
                timestamp = fileCreated
                deviceIndex = 0
                manufacturer = Manufacturer.GARMIN
                product = productId
                this.serialNumber = serialNumber
                sourceType = SourceType.LOCAL
            }
        )

        // Garmin 的 monitoring 文件以 monitoring_info 声明"这一天有哪些活动类型"。
        // 缺了它，服务端可能把文件当成无法归类的 monitoring 数据直接丢掉。
        encoder.write(
            MonitoringInfoMesg().apply {
                timestamp = created
                localTimestamp = localSeconds
                setActivityType(0, ActivityType.WALKING)
            }
        )

        // 步数：08:00–20:00 平均铺开，每 15 分钟一条
        if (steps > 0) {
            val slots = 48
            val per = steps / slots
            var remain = steps - per * slots
            for (i in 0 until slots) {
                val extra = if (remain > 0) 1.also { remain-- } else 0
                val value = per + extra
                if (value <= 0) continue
                val ts = dayStartMs + (8 * 60 + i * 15) * 60_000L
                encoder.write(
                    MonitoringMesg().apply {
                        timestamp = fitTime(ts)
                        this.steps = value.toLong()
                        activityType = ActivityType.WALKING
                    }
                )
            }
        }

        // 睡眠：从前一天 23:00 起，每 5 分钟一个阶段
        if (sleepMinutes > 0) {
            var t = dayStartMs - 60 * 60_000L
            val end = t + sleepMinutes * 60_000L
            var i = 0
            while (t < end) {
                val level = when (i % 6) {
                    0, 1 -> SleepLevel.LIGHT
                    2 -> SleepLevel.DEEP
                    3 -> SleepLevel.REM
                    4 -> SleepLevel.LIGHT
                    else -> SleepLevel.AWAKE
                }
                encoder.write(
                    SleepLevelMesg().apply {
                        timestamp = fitTime(t)
                        sleepLevel = level
                    }
                )
                t += 5 * 60_000L
                i++
            }
        }

        // 压力：每小时一条，围绕日均值轻微波动
        if (stressAvg > 0) {
            for (h in 0 until 24) {
                val ts = dayStartMs + h * 3_600_000L
                val value = (stressAvg + ((h % 5) - 2) * 3).coerceIn(0, 100)
                encoder.write(
                    StressLevelMesg().apply {
                        stressLevelTime = fitTime(ts)
                        stressLevelValue = value.toShort()
                    }
                )
            }
        }

        // 静息心率：Garmin 用 monitoring 里的 heart_rate 字段，这里先不写样本，
        // 由日汇总的 resting_hr 在账号侧体现（避免伪造逐秒心率）。
        if (restingHr > 0) {
            // 保留参数：后续若要写 HeartRateMesg 可在这里展开
        }

        encoder.close()
    }
}
