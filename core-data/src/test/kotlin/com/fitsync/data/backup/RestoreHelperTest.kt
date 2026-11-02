package com.fitsync.data.backup

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.fitsync.data.db.FITSYNC_DB_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 备份文件头的 schema 读取。
 *
 * 这一层曾经把 3 读成 50331648（多一次字节序反转），导致用户手上所有备份
 * 都被自己的「来自更新版本」校验挡住——而只有换机恢复时才会发现。
 * 所以这里不手搓字节，直接用真 SQLite 生成文件再读回来，保证和线上文件同构。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreHelperTest {

    private fun sqliteFile(userVersion: Int): File {
        val file = File.createTempFile("fitsync-backup", ".db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA user_version = $userVersion")
        }
        return file
    }

    @Test
    fun `读出的版本号就是 Room 写进去的那个`() {
        val file = sqliteFile(FITSYNC_DB_VERSION)
        assertEquals(FITSYNC_DB_VERSION, RestoreHelper.schemaVersion(file))
        file.delete()
    }

    @Test
    fun `本机导出的备份能通过版本校验`() {
        val file = sqliteFile(FITSYNC_DB_VERSION)
        assertTrue(RestoreHelper.schemaVersion(file) in 1..FITSYNC_DB_VERSION)
        file.delete()
    }

    @Test
    fun `更高版本的备份会被识别成超额版本`() {
        val file = sqliteFile(FITSYNC_DB_VERSION + 1)
        assertTrue(RestoreHelper.schemaVersion(file) > FITSYNC_DB_VERSION)
        file.delete()
    }

    @Test
    fun `不是 SQLite 文件时返回无效标记`() {
        val file = File.createTempFile("not-a-db", ".db").apply {
            writeBytes(ByteArray(4096) { 0x41 })
        }
        assertEquals(RestoreHelper.INVALID_SCHEMA, RestoreHelper.schemaVersion(file))
        file.delete()
    }

    @Test
    fun `文件太小时返回无效标记`() {
        val file = File.createTempFile("tiny", ".db").apply { writeBytes(ByteArray(8)) }
        assertEquals(RestoreHelper.INVALID_SCHEMA, RestoreHelper.schemaVersion(file))
        file.delete()
    }

    // ==================== 恢复后的账号状态 ====================

    @Test
    fun `恢复还没应用时不能动账号状态`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 模拟「点了恢复但没重启」：pending 文件还在
        val pending = File(context.filesDir, RestoreHelper.PENDING_NAME).apply {
            writeBytes(ByteArray(100))
        }
        RestoreHelper.markCredentialsNeedReset(context)

        assertFalse(RestoreHelper.consumeCredentialsReset(context))

        pending.delete()
        // 重启替换成功之后才轮到重置账号
        assertTrue(RestoreHelper.consumeCredentialsReset(context))
    }

    @Test
    fun `重置标记只消费一次`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        RestoreHelper.markCredentialsNeedReset(context)

        assertTrue(RestoreHelper.consumeCredentialsReset(context))
        // 第二次不该再触发一遍，否则每次回前台都提示「已从备份恢复」
        assertFalse(RestoreHelper.consumeCredentialsReset(context))
    }
}
