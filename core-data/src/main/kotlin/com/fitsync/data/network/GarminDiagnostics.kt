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
    }

    private const val MAX = 12
    private val entries = ArrayDeque<Entry>()

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
        entries.addLast(entry)
        while (entries.size > MAX) entries.removeFirst()
    }

    @Synchronized
    fun recent(): List<Entry> = entries.toList().asReversed()

    @Synchronized
    fun latest(): Entry? = entries.lastOrNull()

    @Synchronized
    fun clear() {
        entries.clear()
        tokenScope = null
        tokenSource = ""
    }

    /** 给「复制诊断信息」用：整段纯文本，可直接贴进聊天窗口 */
    @Synchronized
    fun dump(): String {
        if (entries.isEmpty()) return "暂无失败记录"
        return buildString {
            appendLine("FitSync 同步诊断（最近 ${entries.size} 次失败）")
            appendLine("身份：iOS UA（GCM-iOS-5.22.1.4），无 X-Garmin-* 设备头")
            tokenScope?.let { scope ->
                val has = scope.split(' ').contains("CONNECT_READ")
                appendLine(
                    "令牌权限：" + (if (has) "含 CONNECT_READ" else "**缺 CONNECT_READ**") +
                        "（来源 $tokenSource）"
                )
            }
            appendLine("—".repeat(24))
            entries.toList().asReversed().forEachIndexed { index, entry ->
                if (index > 0) appendLine()
                appendLine(entry.toLine())
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
}
