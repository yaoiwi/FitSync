package com.fitsync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.fitsync.feature.home.SettingsTab
import com.fitsync.ui.theme.LocalUiStyle
import com.fitsync.ui.theme.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「悬浮导航栏」「触感反馈」只属于 HyperOS 风格。
 *
 * MD3 用的是贴底标准导航栏，行点击也不走 Haptics；开关留在那里只会「拨了没反应」。
 * 这里钉住的是：MD3 下两项必须消失，HyperOS 下必须还在。
 *
 * 高度开到 2000dp：设置页是 LazyColumn，屏幕外的项根本不会组合，
 * 用默认 640dp 会让「触感反馈」压根查不到，测试就变成假的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class SettingsStyleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(style: UiStyle) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStyle provides style) {
                SettingsTab(uiStyle = style)
            }
        }
    }

    @Test
    fun `HyperOS 下保留悬浮导航栏与触感反馈`() {
        render(UiStyle.Miuix)

        composeRule.onNodeWithText("悬浮导航栏").assertIsDisplayed()
        composeRule.onNodeWithText("触感反馈").assertIsDisplayed()
    }

    @Test
    fun `Material 3 下隐藏这两个开关`() {
        render(UiStyle.Md3)

        // 外观卡其余内容仍在，说明不是整页没渲染
        composeRule.onNodeWithText("界面风格").assertIsDisplayed()
        composeRule.onNodeWithText("深色模式").assertIsDisplayed()

        assertEquals(
            0,
            composeRule.onAllNodesWithText("悬浮导航栏").fetchSemanticsNodes().size,
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("触感反馈").fetchSemanticsNodes().size,
        )
    }
}
