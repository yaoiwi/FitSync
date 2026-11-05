package com.fitsync.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button as Md3Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card as Md3Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider as Md3HorizontalDivider
import androidx.compose.material3.Icon as Md3Icon
import androidx.compose.material3.MaterialTheme as Md3Theme
import androidx.compose.material3.NavigationBar as Md3NavigationBar
import androidx.compose.material3.NavigationBarItem as Md3NavigationBarItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch as Md3Switch
import androidx.compose.material3.Text as Md3Text
import androidx.compose.material3.TextButton as Md3TextButton
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
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuiText2
import com.fitsync.ui.theme.MiuixAction
import com.fitsync.ui.theme.MiuixCardSurface
import com.fitsync.ui.theme.MiuixDivider
import com.fitsync.ui.theme.MiuixGroupBg
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuixPrimaryContainer
import com.fitsync.ui.theme.MiuixSummary
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import com.fitsync.ui.theme.isMd3

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
    // MD3：填充式卡片（平、无阴影），圆角 16dp，颜色用 surfaceContainerLow 一档，
    // 在 surface 背景上刚好能分出层次，又不至于像描边卡那样显重。
    if (isMd3) {
        Md3Card(
            modifier = modifier
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MiuixCardSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            content = content,
        )
        return
    }
    // 直接用官方 Card（HyperIsland 同款）：圆角、底色、按压反馈都由 Miuix 控制；
    // insideMargin 归零，因为我们的行内容自己带 18dp 内边距。
    //
    // 左右 12dp：卡片不能贴到屏幕边。16dp 圆角在边缘只露出半个弧，看着像被切掉了；
    // 而且底部导航胶囊本身有 12dp 边距，卡片通栏时两者左右对不齐。
    //
    // 上下 5dp：相邻两张卡之间留出 10dp。以前今日卡和本周卡只隔 2px，
    // 上下两个圆角在左右两端各咬出一个黑三角，就是"缝隙两边都有缺口"的来源。
    Card(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        content = content,
    )
}

/** 卡片内分隔线：左右各缩进 18dp，与文字对齐 */
@Composable
fun MiuixDivider(startIndent: Int = 18) {
    if (isMd3) {
        // MD3 的分隔线是 1dp 的 outlineVariant，比 HyperOS 的 0.5dp 要实一点
        Md3HorizontalDivider(
            modifier = Modifier.padding(start = startIndent.dp),
            thickness = 1.dp,
            color = MiuixDivider,
        )
        return
    }
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
    val tick = rememberTick()
    // MD3：列表项用 72dp 基准高、标题 bodyLarge、摘要 bodyMedium；
    // 点击涟漪交给 clickable，和 Miuix 的 BasicComponent 分开实现。
    if (isMd3) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clickable(enabled = onClick != null) {
                    tick()
                    onClick?.invoke()
                }
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Md3Text(
                    text = title,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    color = MiuiText1,
                )
                if (!summary.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Md3Text(
                        text = summary,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        color = MiuixSummary,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
            if (showArrow) {
                Spacer(Modifier.width(6.dp))
                Md3Text(text = "›", fontSize = 20.sp, color = MiuixAction)
            }
        }
        return
    }
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
        // 可点行都带一次轻震，和 HyperOS 的列表手感一致
        onClick = onClick?.let { action ->
            {
                tick()
                action()
            }
        },
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
    val tick = rememberTick()
    // MD3：分段按钮带描边，选中项填充 primaryContainer，未选中透明
    if (isMd3) {
        SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = {
                        if (index != selectedIndex) tick()
                        onSelect(index)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MiuixPrimaryContainer,
                        activeContentColor = Md3Theme.colorScheme.onPrimaryContainer,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = MiuiText2,
                    ),
                    label = { Md3Text(text = label, fontSize = 14.sp) },
                )
            }
        }
        return
    }
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
                    .clickable {
                        if (index != selectedIndex) tick()
                        onSelect(index)
                    }
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
    val tick = rememberTick()
    if (isMd3) {
        Md3Switch(
            checked = checked,
            onCheckedChange = {
                tick()
                onCheckedChange(it)
            },
            modifier = modifier,
            enabled = enabled,
        )
        return
    }
    Switch(
        checked = checked,
        onCheckedChange = {
            tick()
            onCheckedChange(it)
        },
        modifier = modifier,
        enabled = enabled,
    )
}

// ============================ 标题 ============================

/** 分组标题，对应 Miuix 的 SmallTitle */
@Composable
fun MiuixSectionTitle(text: String, modifier: Modifier = Modifier) {
    // MD3：分组标题用 primary 色的 labelLarge，是设置页里少数带颜色的文字
    if (isMd3) {
        Md3Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
            color = MiuixPrimary,
            modifier = modifier.padding(start = 30.dp, top = 22.dp, bottom = 8.dp),
        )
        return
    }
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixSummary,
        // 30 = 卡片左右外边距 12 + 卡片内边距 18：标题和卡片正文左边缘对齐，
        // 不然卡片一缩进，标题就比正文多凸出 12dp
        modifier = modifier.padding(start = 30.dp, top = 20.dp, bottom = 8.dp),
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
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scale = (1f - scrollOffset.coerceIn(0f, 1f) * 0.12f)
    // MD3：headlineMedium 28sp / Regular，左 16dp，右侧操作与标题基线对齐
    if (isMd3) {
        Column(
            modifier = Modifier.padding(
                start = if (navigationIcon != null) 4.dp else 16.dp,
                end = 12.dp,
                top = 14.dp,
                bottom = 4.dp,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (navigationIcon != null) {
                    navigationIcon()
                    Spacer(Modifier.width(2.dp))
                }
                Md3Text(
                    text = title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Normal,
                    color = MiuiText1,
                    letterSpacing = 0.sp,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin =
                                androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        },
                )
                actions()
            }
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Md3Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MiuixSummary,
                )
            }
        }
        return
    }
    Column(
        modifier = Modifier.padding(
            // 有返回箭头时左边距收窄，箭头本身带内边距，避免标题被挤到屏幕中间
            start = if (navigationIcon != null) 6.dp else 18.dp,
            end = 12.dp,
            top = 10.dp,
            bottom = 4.dp,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (navigationIcon != null) {
                navigationIcon()
                Spacer(Modifier.width(2.dp))
            }
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
    val tick = rememberTick()
    // MD3：filled button，全圆角、48dp 高，文字 onPrimary
    if (isMd3) {
        Md3Button(
            onClick = {
                tick()
                onClick()
            },
            modifier = modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = enabled && !loading,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MiuixPrimary,
                contentColor = Md3Theme.colorScheme.onPrimary,
            ),
        ) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Md3Theme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Md3Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        return
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled && !loading) {
                tick()
                onClick()
            },
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
    val tick = rememberTick()
    if (isMd3) {
        Md3TextButton(
            onClick = {
                tick()
                onClick()
            },
            modifier = modifier,
            enabled = enabled,
        ) {
            Md3Text(
                text = text,
                fontSize = 15.sp,
                color = color ?: if (enabled) {
                    Md3Theme.colorScheme.primary
                } else {
                    MiuixSummary
                },
            )
        }
        return
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) {
                tick()
                onClick()
            }
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
    // MD3：标准贴底导航栏，选中项由 M3 自己画指示器胶囊，不做玻璃效果
    if (isMd3) {
        Md3NavigationBar(
            modifier = modifier.fillMaxWidth(),
            containerColor = MiuixCardSurface,
            tonalElevation = 0.dp,
        ) {
            items.forEachIndexed { index, item ->
                Md3NavigationBarItem(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    icon = {
                        Md3Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    label = { Md3Text(text = item.label, fontSize = 11.sp) },
                )
            }
        }
        return
    }
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
