package com.fitsync.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记录「哪一天的哪些文件已经搬到哪个账号」。
 *
 * **为什么需要单独记**：搬运成功后，目标账号的本地日汇总要等一次同步才会出现。
 * 在这之前再点一次搬运，会重复下载 + 重复上传（服务端返回 409 判重）。
 * 记一下，[SKIP_WINDOW_MS] 内点第二次就不再重复打接口。
 *
 * **为什么记到文件级而不是只记日期**：一天的健康数据是「一个 zip 里装几个 FIT」
 * （monitoring / sleep / stress），上传接口是按**文件**判重、也是按**文件**失败的。
 * 只记日期会有两个后果：
 *
 * 1. 今天搬的时候手表还没把今早的睡眠传上云端，包里只有 monitoring；这一天照样被
 *    记成「搬完了」，第二天再搬直接跳过 —— 那天的睡眠永远补不上；
 * 2. 一天里 monitoring 传成功、睡眠被拒，这天不算搬完，下次重试时 monitoring 会再传
 *    一遍（409 一闪而过），白打一个请求。
 *
 * 记下文件名之后，这两件事都变成「比对集合、只传缺的」。
 *
 * **为什么带时间戳**：一是超出 [SKIP_WINDOW_MS] 的记录自动失效（用户怀疑没搬成功
 * 时仍能重试）；二是最近处理过的日期要**重新核一遍**——有睡眠的看
 * [RECHECK_WINDOW_MS]，还没有睡眠的看 [SLEEP_WAIT_WINDOW_MS]，见 [needsCopy]。
 *
 * **为什么在内存里攒着、隔几天才落盘**：一年期搬运要记 365 次。原来每次都是
 * 「读全量字符串 → 逐项解析 → 拼回去 → 写 XML」，365 次就是 O(n²) 的字符串解析
 * 加 365 次磁盘写，在搬运这个本来就慢的流程里白占时间。现在只在进程内改 map，
 * 每 [FLUSH_EVERY] 条落一次盘；最坏情况丢这十几条记录，重试时服务端 409 直接跳过，
 * 代价只是多几个请求。
 */
@Singleton
class HealthCopyLedger @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** `from_to` → (日期 → 记账)。进程内唯一真相，[prefs] 只是它的持久化副本 */
    private val cache = mutableMapOf<String, MutableMap<String, DayRecord>>()

    /** 哪些键有未落盘的改动 */
    private val dirty = mutableSetOf<String>()
    private var pendingWrites = 0

    /**
     * 某一天的记账。
     *
     * [files] 是「已确认躺在目标账号上的文件指纹」（见 `WellnessPackage.fingerprints`）。
     * 空集合表示这一天处理过、但不知道具体是哪些文件 —— 老版本写的记录就是这样，
     * 下次复查时会自然补全。
     */
    data class DayRecord(
        val atMs: Long,
        val files: Set<String>,
    ) {
        /**
         * 这天已经确认「有睡眠文件」。
         *
         * 有睡眠说明数据齐了，复查 3 天就够；没有就得一直盯着——
         * 可能只是手表还没把睡眠传上去，不代表那天没有。
         */
        fun hasSleep(): Boolean = files.any { it.startsWith(SLEEP_PREFIX) }
    }

    /** 刚搬过的日期：在 [SKIP_WINDOW_MS] 内、且没被判定为「这天不用搬」的才在里面 */
    @Synchronized
    fun copiedDates(fromAccountId: Long, toAccountId: Long): Set<String> =
        alive(key(fromAccountId, toAccountId)).keys.toSet()

    /** 一次取整个账号对的记账，避免按天逐个加锁（365 天就是 365 次） */
    @Synchronized
    fun records(fromAccountId: Long, toAccountId: Long): Map<String, DayRecord> =
        alive(key(fromAccountId, toAccountId)).toMap()

    @Synchronized
    fun dayRecord(fromAccountId: Long, toAccountId: Long, date: String): DayRecord? =
        alive(key(fromAccountId, toAccountId))[date]

    // 搬运两天并行，读-改-写必须串行，否则两个工人的写入会互相覆盖，
    // 表现是「明明搬过，下一轮又重搬」——白白多打几百个请求。
    @Synchronized
    fun markFiles(
        fromAccountId: Long,
        toAccountId: Long,
        date: String,
        files: Collection<String>,
    ) {
        val k = key(fromAccountId, toAccountId)
        val map = alive(k)
        // 增量合并而不是替换：下次只传「包里有、台账里没有」的那些文件。
        // 替换会把上一轮成功过的文件从台账里抹掉，下一轮又要重传一遍。
        val prev = map[date]?.files.orEmpty()
        map[date] = DayRecord(System.currentTimeMillis(), prev + files)
        dirty += k
        if (++pendingWrites >= FLUSH_EVERY) flushLocked()
    }

    /** 不知道文件明细时用这个（导入写回每天只有一个 monitoring 文件） */
    @Synchronized
    fun markCopied(fromAccountId: Long, toAccountId: Long, date: String) =
        markFiles(fromAccountId, toAccountId, date, emptyList())

    /** 把攒下的记账写回磁盘。搬运结束时一定要调一次，否则最后十几条只在内存里 */
    @Synchronized
    fun flush() = flushLocked()

    /**
     * 作废最近 [days] 天的记账，逼下一次搬运重新核对这几天。
     *
     * 「补齐最近几天」按钮用它：正常流程里，没睡眠的日期要等到窗口过期（14 天）
     * 才会停止复查，有睡眠的 3 天后就跳过；用户想立刻再核一遍时，只能先把这几天
     * 的记录删掉，否则 `needsCopy` 会直接把它们判成「已经搬过」。
     *
     * 只删日期落在 `今天 ~ 今天-(days-1)` 的记录，更早的断点不受影响。
     */
    @Synchronized
    fun forgetRecent(
        fromAccountId: Long,
        toAccountId: Long,
        days: Int,
        today: LocalDate = LocalDate.now(),
    ) {
        if (days <= 0) return
        val k = key(fromAccountId, toAccountId)
        val map = alive(k)
        // 日期是 yyyy-MM-dd，字典序即时间序
        val cutoff = today.minusDays((days - 1).toLong()).toString()
        val removed = map.keys.filter { it >= cutoff }
        if (removed.isEmpty()) return
        removed.forEach { map.remove(it) }
        dirty += k
        flushLocked()
    }

    @Synchronized
    fun clear(fromAccountId: Long, toAccountId: Long) {
        val k = key(fromAccountId, toAccountId)
        cache.remove(k)
        dirty.remove(k)
        prefs.edit().remove(k).apply()
    }

    /**
     * 上一次搬运留下的痕迹，用来在搬运页告诉用户「会从哪里接着往前搬」。
     *
     * 搬运按「今天 → 更早」的顺序推进，所以成功记录里**最老的那一天**就是断点。
     * 没有记录（从没搬过，或记录已过 [SKIP_WINDOW_MS]）时返回 null。
     */
    @Synchronized
    fun progress(fromAccountId: Long, toAccountId: Long): Progress? {
        val map = alive(key(fromAccountId, toAccountId))
        if (map.isEmpty()) return null
        return Progress(
            // 日期是 yyyy-MM-dd，字典序即时间序
            oldestDate = map.keys.min(),
            copiedDays = map.size,
            lastAtMs = map.values.maxOf { it.atMs },
        )
    }

    data class Progress(
        /** 记录里最老的一天：下次搬运会从它继续往前 */
        val oldestDate: String,
        /** 记录里共有多少天（都在 [SKIP_WINDOW_MS] 窗口内） */
        val copiedDays: Int,
        /** 最近一次记账时间 */
        val lastAtMs: Long,
    )

    // ==================== 中断标记 ====================

    /**
     * 一次没跑完的搬运。
     *
     * 搬运跑在协程里，进程被国产 ROM 清掉时 `finally` 不会执行，
     * 所以「标记还在」本身就等于「上次没搬完」——不需要再存一个状态字段。
     * 正常结束、被用户主动停止、以及把剩余天数搬完，都会调 [clearRunning]。
     */
    data class RunMark(
        val fromAccountId: Long,
        val toAccountId: Long,
        val days: Int,
        val startedAtMs: Long,
    )

    /** 开始搬运时登记。同一对账号只留一条，旧的先清掉 */
    @Synchronized
    fun markRunning(fromAccountId: Long, toAccountId: Long, days: Int) {
        dismissAllRunMarks()
        prefs.edit()
            .putString(runningKey(fromAccountId, toAccountId), "$days|${System.currentTimeMillis()}")
            .apply()
    }

    @Synchronized
    fun clearRunning(fromAccountId: Long, toAccountId: Long) {
        prefs.edit().remove(runningKey(fromAccountId, toAccountId)).apply()
    }

    /** 启动 / 进搬运页时问一次：上次是不是没跑完 */
    @Synchronized
    fun interruptedRun(): RunMark? {
        val entry = prefs.all.entries.firstOrNull { it.key.startsWith(RUNNING_PREFIX) } ?: return null
        val value = entry.value as? String ?: return null
        val ids = entry.key.removePrefix(RUNNING_PREFIX).split('_')
        if (ids.size != 2) return null
        val parts = value.split('|')
        return RunMark(
            fromAccountId = ids[0].toLongOrNull() ?: return null,
            toAccountId = ids[1].toLongOrNull() ?: return null,
            days = parts.getOrNull(0)?.toIntOrNull() ?: 7,
            startedAtMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
        )
    }

    @Synchronized
    fun dismissAllRunMarks() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(RUNNING_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** 读某个键的活记录，顺手丢掉过期项 */
    private fun alive(k: String): MutableMap<String, DayRecord> {
        val map = cache.getOrPut(k) { load(k) }
        val now = System.currentTimeMillis()
        map.entries.removeAll { now - it.value.atMs >= SKIP_WINDOW_MS }
        return map
    }

    @Synchronized
    private fun flushLocked() {
        if (dirty.isEmpty()) {
            pendingWrites = 0
            return
        }
        val editor = prefs.edit()
        for (k in dirty) {
            val map = cache[k] ?: continue
            val text = map.entries.joinToString(",") { "${it.key}=${encode(it.value)}" }
            editor.putString(k, text)
        }
        editor.apply()
        dirty.clear()
        pendingWrites = 0
    }

    /** `时间戳` 或 `时间戳|文件1;文件2`。没有文件明细时写成前者，与老格式一致 */
    private fun encode(record: DayRecord): String {
        if (record.files.isEmpty()) return record.atMs.toString()
        val files = record.files.joinToString(FILE_SEP) { it.replace(UNSAFE, "_") }
        return "${record.atMs}|$files"
    }

    private fun decode(raw: String): DayRecord? {
        val bar = raw.indexOf('|')
        val ts = (if (bar >= 0) raw.substring(0, bar) else raw).toLongOrNull() ?: return null
        // 老格式只有时间戳：知道这天处理过，不知道具体文件
        if (bar < 0) return DayRecord(ts, emptySet())
        val files = raw.substring(bar + 1)
            .split(FILE_SEP)
            .filter { it.isNotBlank() }
            // 1.5.9 写的是 `sleep:173`（还没有组内序号），语义上就是「第一个
            // 同类型同大小的文件」。补成 `sleep:173#1` 之后，新版算出来的指纹能直接命中，
            // 升级不用把最近几天再传一遍；而当时漏掉的那个同大小文件正好补传。
            .map { if ('#' in it) it else "$it#1" }
            .toSet()
        return DayRecord(ts, files)
    }

    /** 读 `日期=值` 形式的记录，自动丢掉过期项 */
    private fun load(k: String): MutableMap<String, DayRecord> {
        val now = System.currentTimeMillis()
        return prefs.getString(k, null)
            ?.split(',')
            ?.mapNotNull { item ->
                val parts = item.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val date = parts[0].takeIf { it.length == 10 } ?: return@mapNotNull null
                val record = decode(parts[1]) ?: return@mapNotNull null
                if (now - record.atMs >= SKIP_WINDOW_MS) null else date to record
            }
            ?.toMap()
            ?.toMutableMap()
            ?: mutableMapOf()
    }

    private fun key(from: Long, to: Long) = "copy_${from}_$to"

    private fun runningKey(from: Long, to: Long) = "$RUNNING_PREFIX${from}_$to"

    companion object {
        private const val PREFS = "fitsync_health_copy_ledger"

        private const val RUNNING_PREFIX = "running_"

        private const val FILE_SEP = ";"

        /** 文件指纹里可能出现的分隔符，落盘前一律替换掉，避免把一条记录拆成两半 */
        private val UNSAFE = Regex("[,;=|]")

        /**
         * 搬完的日期在这段时间内不再重复搬。
         *
         * 原来只记 24 小时，是为了「用户怀疑没搬成功时能立刻重试」。但一次 365 天的
         * 搬运要分好几轮点（中途撞限流、切后台被杀都会中断），24 小时的窗口会让
         * 第二天的下一轮把前一天搬过的几百天全部重来一遍——白花几百个请求，
         * 正是最容易被限流的动作。改成 90 天：只记成功的日期，失败的日期不记，
         * 所以「重试」仍然有效，重试的只是没成功的那部分。
         *
         * 支持 730 天跨度后又发现 90 天不够：选 2 年、分三次点（每次隔一个多月），
         * 第二轮就会把第一轮的一整年重跑一遍。窗口改成 800 天 —— 必须比最大跨度
         * （730 天）还长一点，否则「最老那条记录」会在第 730 天刚好过期，
         * 而它对应的正是两年期最后一个待搬日。代价只是 SharedPreferences 里多存
         * 几百条日期（约 15 KB），而重跑几百天要花的是一小时的请求。
         */
        const val SKIP_WINDOW_MS = 800L * 24 * 60 * 60 * 1000

        /**
         * 最近这么多天内处理过的日期，要重新核一遍文件集合。
         *
         * 睡眠数据在 Garmin 云端是**事后生成**的：今早醒来时手表可能还没把睡眠传上去，
         * 当天搬到的包里就只有 monitoring。如果按「这天搬完了」一刀切，第二天再搬
         * 会直接跳过，那天的睡眠就永远补不上。
         *
         * 3 天是给「已经确认有睡眠」的日期用的：数据齐了，多盯就是白下载。
         * 还没出现睡眠的日期用 [SLEEP_WAIT_WINDOW_MS]，见 [needsCopy]。
         */
        const val RECHECK_WINDOW_MS = 3L * 24 * 60 * 60 * 1000

        /**
         * 还没出现睡眠的日期盯多久。
         *
         * 「今天搬了、包里只有步数」有两种可能：手表还没上传睡眠（过几天会有），
         * 或者那天真没戴表睡（永远没有）。从包本身分不出来，所以只能多看几天。
         *
         * 3 天不够：手表放抽屉里一周再连手机是很常见的事，那时云端才补齐睡眠，
         * 而按 3 天算，这天早就被跳过、再也不复查了。14 天覆盖「出差一周、
         * 周末才同步」这种节奏；再长就没意义了，Garmin 自己也不会延迟这么久。
         *
         * 代价：每次搬运多下载几个包（一天一个请求，上传仍然是 0），可以忽略。
         */
        const val SLEEP_WAIT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

        /** 指纹前缀，与 `WellnessPackage.kindOf` 保持一致 */
        private const val SLEEP_PREFIX = "sleep:"

        /** 攒够这么多条落一次盘 */
        const val FLUSH_EVERY = 20

        /**
         * 这天还要不要去核一遍。
         *
         * 没记录过（[record] 为 null）当然要；有记录的，按「这天有没有睡眠」分两档：
         *
         * - 已经有睡眠：数据齐了，[RECHECK_WINDOW_MS] 内复查（可能又补了压力/身体电量），
         *   更老的直接跳过；
         * - 还没有睡眠：可能是还没生成，用 [SLEEP_WAIT_WINDOW_MS] 一直盯着，
         *   直到睡眠出现或超过 14 天。老版本只有日期、没有文件明细的记录也走这一档，
         *   复查一次顺带把指纹补全。
         *
         * 纯函数，方便单测。
         */
        fun needsCopy(record: DayRecord?, now: Long): Boolean {
            if (record == null) return true
            val window = if (record.hasSleep()) RECHECK_WINDOW_MS else SLEEP_WAIT_WINDOW_MS
            return now - record.atMs < window
        }

        /**
         * 按「记录里最老的一天」估算选定的跨度里还有多少天没搬。
         *
         * 搬运从今天往回推进，已经搬到 [oldestDate] 就说明它后面的都处理过了；
         * 跨度 [span] 天覆盖 `今天 ~ (今天 - span + 1)`，比 [oldestDate] 更老的才算剩余。
         * 纯函数，方便单测。
         */
        fun remainingDays(oldestDate: String, span: Int, today: LocalDate): Int {
            if (span <= 0) return 0
            val oldest = runCatching { LocalDate.parse(oldestDate) }.getOrNull() ?: return span
            val windowStart = today.minusDays((span - 1).toLong())
            // oldest 比窗口起点更老 → 窗口内都在记录覆盖范围内，剩余 0
            if (!oldest.isAfter(windowStart)) return 0
            return ChronoUnit.DAYS.between(windowStart, oldest).toInt().coerceAtLeast(0)
        }
    }
}
