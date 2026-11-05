package com.fitsync.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步范围的两个开关。
 *
 * 看着像废话，但这两个布尔值决定「哪类接口会被调用」：写反了就是
 * 「只同步活动」却把 7 天日汇总全拉一遍，用户看不出，配额照扣。
 */
class SyncScopeTest {

    @Test
    fun `只同步健康时不碰活动`() {
        assertTrue(SyncScope.HEALTH.health)
        assertFalse(SyncScope.HEALTH.activity)
    }

    @Test
    fun `只同步活动时不碰健康`() {
        assertFalse(SyncScope.ACTIVITY.health)
        assertTrue(SyncScope.ACTIVITY.activity)
    }

    @Test
    fun `全部同步两样都要`() {
        assertTrue(SyncScope.ALL.health)
        assertTrue(SyncScope.ALL.activity)
    }
}
