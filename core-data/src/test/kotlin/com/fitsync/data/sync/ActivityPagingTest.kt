package com.fitsync.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活动条数的换算规则。
 *
 * 这些数直接决定「按条选择」要发几个请求：界面上多选一档，配额就多几倍。
 * 埋在网络调用里测不到，所以单独抽出来用纯函数验证。
 */
class ActivityPagingTest {

    @Test
    fun `页大小与默认值保持不变`() {
        assertEquals("单页 20 条是接口稳定值，改大会被截断", 20, ActivityPaging.PAGE_SIZE)
        assertEquals("默认一页就够，最省配额", 20, ActivityPaging.DEFAULT_LIMIT)
        assertEquals("默认值必须正好一页", 1, ActivityPaging.pages(ActivityPaging.DEFAULT_LIMIT))
    }

    @Test
    fun `界面档位都能整除或明确进位`() {
        // 20 → 1 页，50 → 3 页（20+20+10），100 → 5 页
        assertEquals(1, ActivityPaging.pages(20))
        assertEquals(3, ActivityPaging.pages(50))
        assertEquals(5, ActivityPaging.pages(100))
    }

    @Test
    fun `页数是向上取整`() {
        assertEquals(2, ActivityPaging.pages(21))
        assertEquals(2, ActivityPaging.pages(40))
        assertEquals(3, ActivityPaging.pages(41))
        assertEquals(10, ActivityPaging.pages(200))
    }

    @Test
    fun `非法条数收敛到安全区间`() {
        assertEquals("0 当成没设过，退回默认", ActivityPaging.DEFAULT_LIMIT, ActivityPaging.normalize(0))
        assertEquals(ActivityPaging.DEFAULT_LIMIT, ActivityPaging.normalize(-5))
        assertEquals("小于一页按一页算", ActivityPaging.PAGE_SIZE, ActivityPaging.normalize(5))
        assertEquals("上限 200，再多就该走回填", ActivityPaging.MAX_LIMIT, ActivityPaging.normalize(5000))
        assertEquals(50, ActivityPaging.normalize(50))
    }

    @Test
    fun `界面档位本身必须合法`() {
        for (option in ActivityPaging.OPTIONS) {
            assertEquals(
                "档位 $option 不该被 normalize 改动",
                option,
                ActivityPaging.normalize(option),
            )
            assertTrue("档位 $option 应有明确页数", ActivityPaging.pages(option) >= 1)
        }
    }
}
