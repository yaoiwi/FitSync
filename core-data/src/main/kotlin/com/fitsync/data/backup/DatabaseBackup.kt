package com.fitsync.data.backup

import android.content.Context
import android.net.Uri
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
        if (version !in 1..CURRENT_SCHEMA) {
            pending.delete()
            error("这不是有效的 FitSync 备份（schema=$version）")
        }
    }

    private companion object {
        /** 与 FitSyncDatabase 的 @Database(version = 2) 保持一致 */
        const val CURRENT_SCHEMA = 2
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

    /** 读 SQLite 文件头：偏移 60 处的 4 字节大端整数就是 schema 版本 */
    fun schemaVersion(file: File): Int = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(60)
            Integer.reverseBytes(raf.readInt())
        }
    }.getOrDefault(-1)
}
