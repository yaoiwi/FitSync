package com.fitsync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.components.MiuixDivider
import com.fitsync.ui.components.MiuixPrimaryButton
import com.fitsync.ui.components.MiuixRow
import com.fitsync.ui.components.MiuixSectionTitle
import com.fitsync.ui.components.MiuixSegmentedControl
import com.fitsync.ui.components.MiuixSwitch
import com.fitsync.ui.theme.LocalUiStyle
import com.fitsync.ui.theme.UiStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 两套界面风格都要能组合出来。
 *
 * 组件是按 [LocalUiStyle] 分支的：MD3 分支里换了完全不同的实现（M3 Card / Switch /
 * SegmentedButton / Button），任何一处参数写错都只会在用户切风格那一刻崩，
 * 编译期发现不了。这里把核心组件各渲染一遍，至少保证「切过去不白屏」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiStyleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @androidx.compose.runtime.Composable
    private fun content() {
        Column {
            MiuixSectionTitle("同步")
            MiuixCard {
                MiuixRow(
                    title = "同步健康数据",
                    summary = "日汇总、睡眠，按天增量",
                    showArrow = true,
                    onClick = {},
                )
                MiuixDivider()
                MiuixRow(
                    title = "补齐历史记录",
                    trailing = { MiuixSwitch(checked = true, onCheckedChange = {}) },
                )
            }
            MiuixSegmentedControl(
                options = listOf("20 条", "50 条", "100 条"),
                selectedIndex = 1,
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixPrimaryButton(text = "全部同步", onClick = {})
        }
    }

    @Test
    fun md3_renders_core_components() {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUiStyle provides UiStyle.Md3) {
                    content()
                }
            }
        }
        composeRule.onNodeWithText("同步健康数据").assertIsDisplayed()
        composeRule.onNodeWithText("日汇总、睡眠，按天增量").assertIsDisplayed()
        composeRule.onNodeWithText("补齐历史记录").assertIsDisplayed()
        composeRule.onNodeWithText("50 条").assertIsDisplayed()
        composeRule.onNodeWithText("全部同步").assertIsDisplayed()
    }

    @Test
    fun miuix_renders_core_components() {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUiStyle provides UiStyle.Miuix) {
                    content()
                }
            }
        }
        composeRule.onNodeWithText("同步健康数据").assertIsDisplayed()
        composeRule.onNodeWithText("补齐历史记录").assertIsDisplayed()
        composeRule.onNodeWithText("全部同步").assertIsDisplayed()
    }
}
