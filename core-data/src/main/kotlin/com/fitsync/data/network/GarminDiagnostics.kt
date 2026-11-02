package com.fitsync.data.network

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近若干次 Garmin 请求的失败快照。
 *
 * **为什么需要**：403 的文案只有一句 `Forbidden`，看不出是「没带令牌」「令牌被作废」
 * 还是「出口 IP 被拦」。这三种原因的处理方式完全不同（续期 / 重登 / 换网络），
 * 靠猜只会让用户反复试。这里把请求的关键事实留下来，界面上可以一键复制：
 * 端点、状态码、是否带 Authorization、令牌长度、User-Agent 形态、`cf-ray`、响应体摘要。
 *
 * **409 不算失败**：搬运健康数据时，目标账号本来就有那一天的文件，上传接口会返回
 * 409 `Duplicate Wellness File`。这是「已存在」，是正常结果——把它和 403/419 混在
 * 一个列表里，用户会看到「最近 12 次失败」，以为搬运坏了。所以 409 单独计数、
 * 不占失败名额，也不参与「最近一次失败」。
 *
 * **不记录**：令牌原文、账号、密码。令牌只留长度，够判断「有没有带」。
 */
object GarminDiagnostics {

    /** 一次请求的结果快照 */
    data class Entry(
        val atMs: Long,
        val endpoint: String,
        val method: String,
        val code: Int,
        /** 请求头里是否真的带了 Authorization；长度用来发现「带了但是空的」 */
        val tokenLength: Int,
        /** 请求域名：用来判断是不是打到了错误的区域（garmin.cn / garmin.com） */
        val host: String = "",
        val cfRay: String?,
        val location: String?,
        val bodySnippet: String,
        /** 预期内的结果（409 已存在）：留在诊断里当证据，但不算失败 */
        val expected: Boolean = false,
    ) {
        fun toLine(): String {
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(atMs))
            val sb = StringBuilder()
            val where = if (host.isBlank()) endpoint else "$host$endpoint"
            sb.append("$time  $method $where → HTTP $code\n")
            sb.append("  令牌：")
            sb.append(if (tokenLength > 0) "已带（长度 $tokenLength）" else "**未带**")
            if (!cfRay.isNullOrBlank()) sb.append("　cf-ray：").append(cfRay)
            if (!location.isNullOrBlank()) sb.append("\n  重定向到：").append(location)
            if (bodySnippet.isNotBlank()) sb.append("\n  响应：").append(bodySnippet)
            return sb.toString()
        }

        /** 响应体里的 `fileSize`，409 汇总时用来判断「是不是同一个空文件」 */
        internal fun fileSize(): Int =
            FILE_SIZE.find(bodySnippet)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private const val MAX = 12

    /** 真失败：403 / 419 / 429 / 5xx / 网络错误 */
    private val entries = ArrayDeque<Entry>()

    /** 409「已存在」：只留最近几条做证据，不占 [entries] 的名额 */
    private val expected = ArrayDeque<Entry>()

    /** 最近一次换到的令牌权限；决定 403 是「没权限」还是「端点被拒」 */
    @Volatile
    private var tokenScope: String? = null

    @Volatile
    private var tokenSource: String = ""

    /**
     * 记下这次换到的令牌带不带 `CONNECT_READ`。
     *
     * 只存「有没有这条权限」和来源（登录 / 续期），不存令牌原文。
     * 用户贴诊断时，这一行就能把「令牌签错了」和「接口改了」区分开。
     */
    @Synchronized
    fun recordToken(scope: String, source: String) {
        tokenScope = scope
        tokenSource = source
    }

    @Synchronized
    fun record(entry: Entry) {
        // 409 一律按「预期结果」处理，不管调用方有没有显式标：
        // 上传判重是 Garmin 的正常语义，不该出现在失败列表里
        val e = if (entry.code == 409) entry.copy(expected = true) else entry
        if (e.expected) {
            expected.addLast(e)
            while (expected.size > MAX) expected.removeFirst()
            return
        }
        entries.addLast(e)
        while (entries.size > MAX) entries.removeFirst()
    }

    /** 只返回真失败（不含 409）；界面「最近一次失败」用这个 */
    @Synchronized
    fun recent(): List<Entry> = entries.toList().asReversed()

    @Synchronized
    fun latest(): Entry? = entries.lastOrNull()

    /** 最近有没有 409：没有真失败时，设置页用它顶上摘要 */
    @Synchronized
    fun expectedNote(): String? {
        if (expected.isEmpty()) return null
        return "无失败；最近 ${expected.size} 次上传都是「已存在」（409），属正常"
    }

    @Synchronized
    fun clear() {
        entries.clear()
        expected.clear()
        tokenScope = null
        tokenSource = ""
    }

    /** 给「复制诊断信息」用：整段纯文本，可直接贴进聊天窗口 */
    @Synchronized
    fun dump(): String {
        if (entries.isEmpty() && expected.isEmpty()) return "暂无失败记录"
        return buildString {
            appendLine(
                if (entries.isEmpty()) "FitSync 同步诊断（最近没有失败）"
                else "FitSync 同步诊断（最近 ${entries.size} 次失败）"
            )
            appendLine("身份：iOS UA（GCM-iOS-5.22.1.4），无 X-Garmin-* 设备头")
            tokenScope?.let { scope ->
                val has = scope.split(' ').contains("CONNECT_READ")
                appendLine(
                    "令牌权限：" + (if (has) "含 CONNECT_READ" else "**缺 CONNECT_READ**") +
                        "（来源 $tokenSource）"
                )
            }
            appendLine("—".repeat(24))
            if (entries.isEmpty()) {
                appendLine("没有真正的失败请求。")
            } else {
                entries.toList().asReversed().forEachIndexed { index, entry ->
                    if (index > 0) appendLine()
                    appendLine(entry.toLine())
                }
            }
            if (expected.isNotEmpty()) {
                appendLine()
                append("另有 ${expected.size} 次上传返回 HTTP 409：目标账号本来就有这些文件，")
                appendLine("属正常结果——没有重复写入，也不计入失败。")
                val last = expected.last()
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(last.atMs))
                val size = last.fileSize()
                append("  最近一次：$time")
                if (size > 0) append("，文件 $size 字节")
                appendLine("，${if (last.host.isBlank()) last.endpoint else last.host + last.endpoint}")
                // 几百字节的 FIT 通常只有 file_id / device_info，是「那天没戴表」的空包。
                // 点出来，省得用户以为是文件被截断了
                if (size in 1..1023) {
                    appendLine("  这类几百字节的文件是当天的空数据包（没戴表 / 只连了一下手机），")
                    appendLine("  本来就没什么可搬；目标账号已有同一天的记录，所以返回 409。")
                }
            }
        }
    }

    /**
     * 从响应体里摘出可读的报错。
     *
     * Garmin 的 403 形如 `{"message":"HTTP 403 Forbidden","error":"ForbiddenException"}`，
     * 原样截断比只留状态码有用得多；HTML 登录页则只留前一小段。
     */
    fun snippet(body: String?, limit: Int = 180): String {
        val text = body.orEmpty().trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return ""
        return if (text.length <= limit) text else text.take(limit) + "…"
    }

    private val FILE_SIZE = Regex("\"fileSize\"\\s*:\\s*(\\d+)")
}
