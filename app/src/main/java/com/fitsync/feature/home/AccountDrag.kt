package com.fitsync.feature.home

import kotlin.math.roundToInt

/**
 * 账号拖动排序的计算部分。
 *
 * 单独抽出来是因为「手指拖到第几格」这件事很容易算错（夹边界、一次跨多格、
 * 拖动中行已经被重排导致索引漂移），而它又完全不需要 Compose 就能验证。
 */
internal object AccountDrag {

    /**
     * 把累计位移夹在列表范围内。
     *
     * 夹的不是「能拖多远」，而是「行顶不越出卡片」：第一行往上、最后一行往下都拖不动，
     * 这样浮起来的行不会被卡片的圆角裁掉半截。
     */
    fun clampOffset(fromIndex: Int, dragDy: Float, slotPx: Float, lastIndex: Int): Float {
        val lo = -fromIndex * slotPx
        val hi = (lastIndex - fromIndex) * slotPx
        return dragDy.coerceIn(lo.coerceAtMost(hi), lo.coerceAtLeast(hi))
    }

    /**
     * 这一行的视觉顶端现在落在第几格。
     *
     * 用「顶端落在哪格」而不是「移动了多少格」：一次拖动跨两格时前者照样对，
     * 后者只在慢慢挪的时候准。
     */
    fun landingIndex(fromIndex: Int, dragDy: Float, slotPx: Float, lastIndex: Int): Int {
        if (slotPx <= 0f) return fromIndex.coerceIn(0, lastIndex.coerceAtLeast(0))
        return ((fromIndex * slotPx + dragDy) / slotPx)
            .roundToInt()
            .coerceIn(0, lastIndex.coerceAtLeast(0))
    }

    /** 把 [from] 位置的元素挪到 [to]，其余保持相对顺序 */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from == to || from !in list.indices || to !in list.indices) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }
}
