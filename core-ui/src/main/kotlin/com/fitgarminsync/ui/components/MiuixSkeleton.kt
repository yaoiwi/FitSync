package com.fitgarminsync.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitgarminsync.ui.theme.MiuiText1
import com.fitgarminsync.ui.theme.MiuixAction
import com.fitgarminsync.ui.theme.MiuixCardSurface
import com.fitgarminsync.ui.theme.MiuixDivider
import com.fitgarminsync.ui.theme.MiuixGroupBg
import com.fitgarminsync.ui.theme.MiuixPrimary
import com.fitgarminsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch

/**
 * Miuix / HyperOS 风格的页面骨架。
 *
 * 参照 HyperIsland 3.2.1 的版式：
 * ```
 * 大标题(30sp) + 副标题
 *   [分组标题]
 *   ┌ 卡片（16dp 圆角、18dp 内边距）──────────┐
 *   │ 行：标题 / 摘要 / 尾部控件（高 64dp）    │
 *   └──────────────────────────────────────┘
 *   [胶囊导航栏，浮在底部]
 * ```
 * 这些组件刻意不依赖 Material3 的 Card / Switch —— M3 的卡片默认带阴影和 12dp 圆角，
 * 与目标视觉差得远，改起来不如直接画。
 */

// ============================ 卡片 ============================

val MiuixCardShape = RoundedCornerShape(16.dp)

@Composable
fun MiuixCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 直接用官方 Card（HyperIsland 同款）：圆角、底色、按压反馈都由 Miuix 控制；
    // insideMargin 归零，因为我们的行内容自己带 18dp 内边距。
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        content = content,
    )
}

/** 卡片内分隔线：左右各缩进 18dp，与文字对齐 */
@Composable
fun MiuixDivider(startIndent: Int = 18) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent.dp)
            .height(0.5.dp)
            .background(MiuixDivider),
    )
}

// ============================ 列表行 ============================

/**
 * 一行设置项。
 *
 * [leading] 放 40dp 图标；[trailing] 放开关、数值或箭头。
 * 点击涟漪用 [indication] 默认值，卡片本身不裁切，所以不需要额外处理。
 */
@Composable
fun MiuixRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showArrow: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    BasicComponent(
        title = title,
        modifier = modifier,
        summary = summary,
        startAction = leading,
        endActions = {
            if (trailing != null) trailing()
            if (showArrow) {
                Spacer(Modifier.width(8.dp))
                Text(text = "›", fontSize = 20.sp, color = MiuixAction)
            }
        },
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
    )
}

/** 40dp 圆角方块图标，用于列表行左侧 */
@Composable
fun MiuixLeadingIcon(
    icon: ImageVector,
    tint: Color,
    container: Color,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
    }
}

// ============================ 分段控件 ============================

/**
 * 通用分段控件（HyperOS 的 SegmentedControl）：两档或三档选择。
 * 选中块是浮起的小圆角块，未选中透明，整体一个圆角底槽。
 */
@Composable
fun MiuixSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixGroupBg)
            .padding(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) MiuixCardSurface else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) MiuiText1 else MiuixSummary,
                )
            }
        }
    }
}

// ============================ 开关 ============================

/** 官方 Miuix 开关：带弹簧动画、拖拽手势和 HyperOS 配色 */
@Composable
fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
    )
}

// ============================ 标题 ============================

/** 分组标题，对应 Miuix 的 SmallTitle */
@Composable
fun MiuixSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixSummary,
        modifier = modifier.padding(start = 18.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * 页面大标题。与 HyperIsland 一致：左对齐 30sp、字重 SemiBold，右侧可放操作。
 * [scrollOffset] 用于滚动时轻微缩放，0 为完全展开。
 */
@Composable
fun MiuixLargeHeader(
    title: String,
    subtitle: String? = null,
    scrollOffset: Float = 0f,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scale = (1f - scrollOffset.coerceIn(0f, 1f) * 0.12f)
    Column(modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 18.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuiText1,
                letterSpacing = (-0.4).sp,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    },
            )
            actions()
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 13.sp, color = MiuixSummary, lineHeight = 18.sp)
        }
    }
}

// ============================ 按钮 ============================

/** 主按钮：48dp 高、16dp 圆角、充满宽度 */
@Composable
fun MiuixPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val bg = if (enabled) MiuixPrimary else MiuixPrimary.copy(alpha = 0.35f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled && !loading) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
}

/** 次按钮：透明底 + 主色文字 */
@Composable
fun MiuixTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color? = null,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            color = color ?: if (enabled) MiuixPrimary else MiuixSummary,
        )
    }
}

// ============================ 底部导航 ============================

data class MiuixNavItem(
    val label: String,
    val icon: ImageVector,
)

/** 胶囊导航栏，浮在内容之上；经 GlassHost 提供 backdrop 时自动变成液态玻璃 */
@Composable
fun MiuixBottomNav(
    items: List<MiuixNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(31.dp)
    val glassTint = if (isSystemInDarkTheme()) {
        Color(0xFF1C1C1E).copy(alpha = 0.55f)
    } else {
        Color.White.copy(alpha = 0.62f)
    }
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(62.dp)
            .liquidGlass(shape = shape, tint = glassTint),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(46.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) MiuixGroupBg else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (selected) MiuixPrimary else MiuixSummary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.label,
                    fontSize = 10.5.sp,
                    color = if (selected) MiuixPrimary else MiuixSummary,
                )
            }
        }
    }
}
