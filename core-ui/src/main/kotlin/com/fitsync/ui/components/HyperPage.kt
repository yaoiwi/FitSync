package com.fitsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
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
        if (onRefresh != null) {
            // 官方 Miuix 下拉刷新：与顶部折叠动画共用 scrollBehavior
            PullToRefresh(
                isRefreshing = isRefreshing,
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
    }
}
