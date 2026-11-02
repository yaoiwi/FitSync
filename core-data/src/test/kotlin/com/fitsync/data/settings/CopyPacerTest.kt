package com.fitsync.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 并发档位。
 *
 * 这里的断言都是「别太激进」：起步必须还是 2 天（已验证安全），
 * 撞到 429 必须立刻退回 1 天并且重新攒，不能因为进程重启又跳回高档位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CopyPacerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fitsync_copy_pacer", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `默认两天并行`() {
        assertEquals(2, CopyPacer(context).parallel)
    }

    @Test
    fun `连续二十天干净才升一档`() {
        val pacer = CopyPacer(context)
        repeat(19) { pacer.noteCleanDay() }
        assertEquals(2, pacer.parallel)

        pacer.noteCleanDay()
        assertEquals(3, pacer.parallel)
        // 升档后计数清零，不会一路冲到 4
        repeat(100) { pacer.noteCleanDay() }
        assertEquals(3, pacer.parallel)
    }

    @Test
    fun `撞到限流立刻退回一档`() {
        val pacer = CopyPacer(context)
        repeat(20) { pacer.noteCleanDay() }
        assertEquals(3, pacer.parallel)

        pacer.noteRateLimited()
        assertEquals(1, pacer.parallel)
        assertEquals(0, pacer.cleanStreak())
    }

    @Test
    fun `档位跨进程保留`() {
        CopyPacer(context).noteRateLimited()
        // 429 锁 48 小时，重启后又从 2 档起步就会立刻再撞一次
        assertEquals(1, CopyPacer(context).parallel)
    }

    @Test
    fun `被打下来之后要从一档重新爬`() {
        val pacer = CopyPacer(context)
        pacer.noteRateLimited()
        repeat(19) { pacer.noteCleanDay() }
        assertEquals(1, pacer.parallel)
        pacer.noteCleanDay()
        assertEquals(2, pacer.parallel)
    }
}
