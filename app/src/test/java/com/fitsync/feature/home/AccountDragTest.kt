package com.fitsync.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拖动排序的纯计算测试。
 *
 * 这里只覆盖「算落点」和「换位置」两件事——手势本身（长按触发、消费事件）
 * 只能在真机上验，但算错了会导致拖到一半跳位、拖不出第一格之类的问题，
 * 这些用数字就能盯住。
 */
class AccountDragTest {

    private val slot = 100f
    private val last = 2

    @Test
    fun `第一行不能往上拖出卡片`() {
        assertEquals(0f, AccountDrag.clampOffset(fromIndex = 0, dragDy = -260f, slotPx = slot, lastIndex = last))
    }

    @Test
    fun `最后一行不能往下拖出卡片`() {
        assertEquals(0f, AccountDrag.clampOffset(fromIndex = 2, dragDy = 260f, slotPx = slot, lastIndex = last))
    }

    @Test
    fun `中间行拖动范围是一格上下`() {
        assertEquals(-slot, AccountDrag.clampOffset(1, -999f, slot, last))
        assertEquals(slot, AccountDrag.clampOffset(1, 999f, slot, last))
        // 没越界就原样保留，别把手指的细微位移也吃掉
        assertEquals(37.5f, AccountDrag.clampOffset(1, 37.5f, slot, last))
    }

    @Test
    fun `过半格才换位`() {
        assertEquals(0, AccountDrag.landingIndex(0, 49f, slot, last))
        assertEquals(1, AccountDrag.landingIndex(0, 51f, slot, last))
        // 往回拖同理
        assertEquals(2, AccountDrag.landingIndex(2, -49f, slot, last))
        assertEquals(1, AccountDrag.landingIndex(2, -51f, slot, last))
    }

    @Test
    fun `一次跨多格也落得准`() {
        assertEquals(2, AccountDrag.landingIndex(0, 250f, slot, last))
        assertEquals(0, AccountDrag.landingIndex(2, -250f, slot, last))
    }

    @Test
    fun `落点永远在列表内`() {
        assertEquals(0, AccountDrag.landingIndex(0, -500f, slot, last))
        assertEquals(2, AccountDrag.landingIndex(0, 500f, slot, last))
    }

    @Test
    fun `行高还没测出来时不乱跳`() {
        assertEquals(1, AccountDrag.landingIndex(1, 80f, slotPx = 0f, lastIndex = last))
    }

    @Test
    fun `移动保持其余元素相对顺序`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), AccountDrag.move(list, 0, 2))
        assertEquals(listOf("a", "d", "b", "c"), AccountDrag.move(list, 3, 1))
        assertEquals(list, AccountDrag.move(list, 2, 2))
        assertEquals(list, AccountDrag.move(list, -1, 2))
        assertEquals(list, AccountDrag.move(list, 0, 9))
    }

    @Test
    fun `从中间拖到末尾再拖回来顺序还原`() {
        var order = listOf(10L, 20L, 30L)
        val startIndex = order.indexOf(20L)

        // 往下拖到最后一格
        var dy = AccountDrag.clampOffset(startIndex, 180f, slot, order.lastIndex)
        var landing = AccountDrag.landingIndex(startIndex, dy, slot, order.lastIndex)
        order = AccountDrag.move(order, order.indexOf(20L), landing)
        assertEquals(listOf(10L, 30L, 20L), order)

        // 再拖回原位：松回起拖那一格，落点回到 1，顺序还原
        dy = AccountDrag.clampOffset(startIndex, 0f, slot, order.lastIndex)
        landing = AccountDrag.landingIndex(startIndex, dy, slot, order.lastIndex)
        order = AccountDrag.move(order, order.indexOf(20L), landing)
        assertEquals(listOf(10L, 20L, 30L), order)
    }
}
