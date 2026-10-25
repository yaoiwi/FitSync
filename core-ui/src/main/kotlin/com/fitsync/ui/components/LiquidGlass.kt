package com.fitsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitsync.ui.theme.MiuixCanvas
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource

/**
 * 液态玻璃（官方 Miuix miuix-blur 实现）。
 *
 * 之前自绘的「玻璃」只是半透明色块 + 一道竖向渐变描边，没有真正采样背景，
 * 所以浮在列表上的导航栏看起来就是一块灰塑料。这里换成 HyperIsland 3.2.1
 * 同款官方方案：
 *
 * 1. [GlassHost] 包住页面内容，用 `rememberLayerBackdrop` 把内容录进 GraphicsLayer，
 *    并通过 [LocalGlassBackdrop] 把 backdrop 传给子树；
 * 2. 玻璃元素（导航栏、悬浮条）用 `Modifier.textureBlur` 采样 backdrop，
 *    RuntimeShader 做真实模糊 + 高光描边，效果与 HyperOS 一致。
 *
 * 低版本（RuntimeShader 需要 API 33）自动降级为「半透明底 + 高光描边」。
 */

/** 当前页面的玻璃采样源；null 表示不支持模糊，调用方应走降级样式 */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 标记「这一层内容会被玻璃采样」。
 *
 * 只挂在滚动内容上，不要挂在整页上——否则顶栏/导航栏也会被录进 backdrop，
 * 玻璃采样到自己上一帧就会出现拖影（HyperIsland 的 BarBackdropContent 就是这个思路）。
 */
@Composable
fun Modifier.glassSource(): Modifier {
    val backdrop = LocalGlassBackdrop.current
    return if (backdrop != null) this.layerBackdrop(backdrop) else this
}

/**
 * 页面级玻璃宿主。
 *
 * 分两层，与 HyperIsland 的 BarBlurHost / BarBackdropContent 结构一致：
 * - [content] 会被录进 backdrop，是玻璃「看得见」的内容层；
 * - [overlay] 是玻璃元素本身（导航栏、悬浮条），**不参与采样**——
 *   如果把玻璃层也录进去，它就会采样到自己上一帧的玻璃，出现套娃式拖影。
 *
 * ```
 * GlassHost(
 *     overlay = { LiquidGlassNavigationBar(Modifier.align(BottomCenter)) },
 * ) {
 *     OverviewTab(...)     // 内容从玻璃下面滚过
 * }
 * ```
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    background: Color = MiuixCanvas,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = if (isRuntimeShaderSupported()) {
        rememberLayerBackdrop {
            drawRect(background)
            drawContent()
        }
    } else {
        null
    }
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(modifier = modifier.fillMaxSize().background(background)) {
            content()
            overlay()
        }
    }
}

/**
 * 液态玻璃修饰符。
 *
 * 有 backdrop 时：真实背景模糊 + 玻璃底色 + BloomStroke 高光描边；
 * 没有时（API < 33）：退化为半透明底 + 竖向渐变描边，至少形状和层次还在。
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color,
    blurRadius: Dp = 24.dp,
    highlightAlpha: Float = 0.55f,
): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val stroke = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = highlightAlpha),
            Color.White.copy(alpha = highlightAlpha * 0.12f),
        ),
    )
    if (backdrop == null) {
        return this.clip(shape).background(tint).border(0.8.dp, stroke, shape)
    }
    val density = LocalDensity.current
    val highlight = remember(highlightAlpha) {
        Highlight(
            width = 0.8.dp,
            alpha = highlightAlpha,
            style = BloomStroke(
                color = Color.White.copy(alpha = 0.30f),
                innerBlurRadius = 2.dp,
                primaryLight = LightSource(
                    position = LightPosition(0.5f, -0.3f, -0.05f),
                    color = Color.White,
                    intensity = 1f,
                ),
                secondaryLight = LightSource(
                    position = LightPosition(0.5f, 0.8f, -0.5f),
                    color = Color.White,
                    intensity = 0.4f,
                ),
                dualPeak = true,
            ),
        )
    }
    return this.textureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadius = with(density) { blurRadius.toPx() },
        noiseCoefficient = 0.02f,
        colors = BlurDefaults.blurColors(
            blendColors = listOf(BlendColorEntry(color = tint)),
        ),
        highlight = highlight,
    )
}
