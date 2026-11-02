package com.fitsync.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * schema 版本与日志保留。
 *
 * 两个都是「改的时候没人会想起」的地方：
 * 1. 备份恢复曾经把版本号写死在另一个文件里，v2→v3 时只改了 @Database，
 *    结果本版本导出的备份一律被自己的校验拒掉；
 * 2. `sync_logs` 从建库起只增不减，清理方法一直没人调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseSchemaTest {

    private lateinit var context: Context
    private lateinit var db: FitSyncDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FitSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `实际库版本与备份校验常量一致`() {
        // 备份恢复拿 FITSYNC_DB_VERSION 做上限校验，两者一旦分叉，
        // 用户导出的备份就再也恢复不了——而且只有换机时才会发现
        assertEquals(FITSYNC_DB_VERSION, db.openHelper.readableDatabase.version)
    }

    @Test
    fun `写满一批日志后清掉保留期以外的记录`() = runBlocking {
        val repository = FitDataRepositoryImpl(db)
        val ancient = System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000
        repository.insertSyncLog(log(tsMs = ancient))

        // 清理是每 20 条触发一次，这里正好写满一批
        repeat(19) { repository.insertSyncLog(log(tsMs = System.currentTimeMillis())) }

        val remaining = db.syncLogDao().observeRecentAll(limit = 100).first()
        assertTrue("40 天前的日志应已被清掉", remaining.none { it.tsMs == ancient })
        assertEquals(19, remaining.size)
    }

    private fun log(tsMs: Long) = SyncLogEntity(
        accountId = 1L,
        trigger = "manual",
        status = "success",
        tsMs = tsMs,
    )
}
