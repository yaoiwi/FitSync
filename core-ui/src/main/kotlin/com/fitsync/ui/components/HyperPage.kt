package com.fitsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.fitsync.ui.theme.MiuixPrimary
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * HyperIsland 同款页面骨架。
 *
 * 两个官方动画都在这里：
 * - **上滑折叠**：[MiuixScrollBehavior] 接到 LazyColumn 的 nestedScroll 上，
 *   向下滚时大标题（largeTitle）逐渐收起、顶部栏压成小标题——就是 HyperOS 的
 *   ExitUntilCollapsed 效果；向上滚回来又会展开。
 * - **弹性回弹**：[overScrollVertical] 给列表两端加 iOS 风格的过滚动阻尼。
 *
 * 内容区底部固定留 96dp，避免最后一行被悬浮的液态玻璃导航栏压住。
 */
@Composable
fun HyperPage(
    title: String,
    subtitle: String = "",
    isRefreshing: Boolean = false,
    /**
     * 忙碌时让下拉刷新头停住转圈。
     *
     * 只给长任务用（搬运 / 回填 / 导入写回，动辄十几分钟）：那时用户更需要
     * 「它还在跑」的确认，也基本不会去翻列表。普通同步保持 false，
     * 否则刷新头会抢走手势，同步期间列表滑不动。
     */
    stopHeadWhileBusy: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    listModifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            // 顶部渐进模糊条（HyperIsland 的 BlurredBar）：列表从标题底下滚过时，
            // 越靠近状态栏越模糊，标题区保持可读。没有 RuntimeShader 时退化成竖向渐变。
            val backdrop = LocalGlassBackdrop.current
            val surface = MiuixTheme.colorScheme.surface
            Box(
                modifier = if (backdrop != null) {
                    Modifier.progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 16f,
                        gradient = ProgressiveBlur.Top.copy(
                            startFraction = 0.12f,
                            endFraction = 1f,
                            curve = 1.25f,
                        ),
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = surface.copy(alpha = 0.66f)),
                            ),
                        ),
                    )
                } else {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(surface.copy(alpha = 0.96f), surface.copy(alpha = 0.72f)),
                        ),
                    )
                },
            ) {
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    subtitle = subtitle,
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = navigationIcon ?: {},
                    actions = actions,
                )
            }
        },
    ) { padding ->
        val list: @Composable () -> Unit = {
            // 只有列表这层会被玻璃采样，顶栏与导航栏都不在 backdrop 里
            Box(modifier = Modifier.fillMaxSize().glassSource()) {
                LazyColumn(
                    modifier = listModifier
                        .fillMaxSize()
                        .overScrollVertical()
                        // 把滚动量交给 TopAppBar 的折叠动画
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = 96.dp,
                    ),
                    content = content,
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            // 长任务期间才让刷新头停住；其余情况一律不进入 refreshing 态
            val headStopped = stopHeadWhileBusy && isRefreshing
            if (onRefresh != null) {
                // 官方 Miuix 下拉刷新：与顶部折叠动画共用 scrollBehavior。
                //
                // isRefreshing **刻意传 false**：控件一旦进入 refreshing 态，就会把刷新头
                // 固定在顶部、并在 nestedScroll 里参与位移分配，同步期间列表滚不动；
                // 头部那个无限旋转动画还会让整页玻璃每帧重采样，掉帧很明显。
                // 刷新中的状态改用下面那条静态细线表示——不动画、不抢手势。
                PullToRefresh(
                    isRefreshing = headStopped,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    topAppBarScrollBehavior = scrollBehavior,
                    contentPadding = PaddingValues(top = padding.calculateTopPadding()),
                    refreshTexts = listOf("", "", "", ""),
                    content = list,
                )
            } else {
                list()
            }
            if (isRefreshing && !headStopped) {
                // 同步中：顶栏下沿一条静态细线。不做位移动画，列表照常可以滑动
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = padding.calculateTopPadding())
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MiuixPrimary.copy(alpha = 0.55f)),
                )
            }
        }
    }
}
