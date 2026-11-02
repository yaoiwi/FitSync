package com.fitsync.data.backup

import android.content.Context
import android.net.Uri
import com.fitsync.data.db.FITSYNC_DB_VERSION
import com.fitsync.data.db.FitSyncDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据库备份与恢复。
 *
 * 备份用「WAL checkpoint + 复制主库文件」，不用 `VACUUM INTO`——
 * 后者要 SQLite 3.27（Android 10）才支持，而本应用最低到 Android 8。
 *
 * 恢复不直接覆盖正在使用的库：先把备份写进 `filesDir/pending-restore.db`，
 * 下次启动时由 [RestoreHelper] 在打开数据库之前替换，避免运行中掉表。
 */
@Singleton
class DatabaseBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FitSyncDatabase,
) {

    /** 返回写入的字节数 */
    suspend fun backupTo(uri: Uri): Long = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath(FitSyncDatabase.DB_NAME)
        if (!dbFile.exists()) error("本机还没有数据库文件")

        // 把 WAL 里未落盘的内容合并回主库，复制出来的才是完整快照
        db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }

        val tmp = File(context.cacheDir, "fitsync-backup.db")
        tmp.delete()
        dbFile.copyTo(tmp, overwrite = true)

        val written = context.contentResolver.openOutputStream(uri)?.use { out ->
            tmp.inputStream().use { it.copyTo(out) }
        } ?: run {
            tmp.delete()
            error("无法写入所选位置")
        }
        tmp.delete()
        written
    }

    /** 把备份放到待恢复位置，校验通过后下次启动生效 */
    suspend fun stageRestore(uri: Uri) = withContext(Dispatchers.IO) {
        val pending = File(context.filesDir, RestoreHelper.PENDING_NAME)
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            pending.outputStream().use { out -> input.copyTo(out) }
        } ?: error("无法读取所选文件")

        if (copied <= 0L) {
            pending.delete()
            error("文件是空的")
        }
        val version = RestoreHelper.schemaVersion(pending)
        // 旧备份要放行：Room 打开时会依次跑 MIGRATION_1_2、MIGRATION_2_3。
        // 但 schema 比当前新必须挡住——那是更晚的版本导出的，本机没有降级迁移，
        // 直接打开只会得到一堆「no such column」。
        if (version !in 1..FITSYNC_DB_VERSION) {
            pending.delete()
            error(
                if (version > FITSYNC_DB_VERSION) {
                    "备份来自更新的 FitSync（schema $version，本机只到 $FITSYNC_DB_VERSION），请先升级"
                } else {
                    "这不是有效的 FitSync 备份"
                },
            )
        }
        // 恢复要等下次启动才真正替换文件，这里先记一笔：
        // 备份里只有数据库，token 的 Keystore 密钥不随文件走，恢复后必须重新登录
        RestoreHelper.markCredentialsNeedReset(context)
    }
}

/**
 * 启动时替换数据库。
 *
 * 必须在 `Room.databaseBuilder` **之前**调用：Room 一旦打开旧库，
 * 再换文件就晚了。
 */
object RestoreHelper {

    const val PENDING_NAME = "pending-restore.db"

    private const val PREFS = "fitsync_restore"
    private const val KEY_CREDENTIALS_RESET = "pending_credentials_reset"

    /** 恢复的备份里带着账号，但凭据解不开，启动后要把账号标成「需重新登录」 */
    fun markCredentialsNeedReset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CREDENTIALS_RESET, true).apply()
    }

    /**
     * 恢复是否已经生效、且还没处理过账号状态。
     *
     * 只有 `pending-restore.db` 已经不在了才成立——那说明 [applyIfNeeded] 替换成功。
     * 还留着 pending 就说明用户点了恢复但没重启，此时不能动账号状态。
     */
    fun consumeCredentialsReset(context: Context): Boolean {
        if (File(context.filesDir, PENDING_NAME).exists()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CREDENTIALS_RESET, false)) return false
        prefs.edit().putBoolean(KEY_CREDENTIALS_RESET, false).apply()
        return true
    }

    fun applyIfNeeded(context: Context) {
        val pending = File(context.filesDir, PENDING_NAME)
        if (!pending.exists()) return

        val dbFile = context.getDatabasePath(FitSyncDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()
        // 旧的 -wal / -shm 必须删掉，否则会把恢复出来的库覆盖回去
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        runCatching { pending.copyTo(dbFile, overwrite = true) }
            .onSuccess { pending.delete() }
            .onFailure { android.util.Log.w("RestoreHelper", "恢复数据库失败", it) }
    }

    /**
     * 读 SQLite 文件头里的 `user_version`（偏移 60，4 字节**大端**），Room 就把它当 schema 版本写。
     *
     * 这里踩过一次坑：早先多绕了一圈 [Integer.reverseBytes]，把 3 读成 50331648，
     * 直到校验常量换成 `FITSYNC_DB_VERSION` 才暴露——用户手上的备份一律被自己的校验拒掉。
     * SQLite 文件头所有整数都是大端，直接 `readInt()` 就是正确值，不要再反转。
     *
     * 顺手校验魔数：随便挑个文件进来读出的四字节很可能落在 1..3 之间，
     * 放过去会在下次启动时把库覆盖成一堆垃圾。
     */
    fun schemaVersion(file: File): Int = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < SQLITE_HEADER_SIZE) return@use INVALID_SCHEMA
            val magic = ByteArray(SQLITE_MAGIC.size)
            raf.readFully(magic)
            if (!magic.contentEquals(SQLITE_MAGIC)) return@use INVALID_SCHEMA
            raf.seek(60)
            raf.readInt()
        }
    }.getOrDefault(INVALID_SCHEMA)

    /** 不是 SQLite 文件、或读不出来 */
    const val INVALID_SCHEMA = -1

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** 100 字节文件头，少于此长度不可能是库 */
    private const val SQLITE_HEADER_SIZE = 100L
}
