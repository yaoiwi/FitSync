package com.fitsync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.fitsync.data.health.CopyHealthReport
import com.fitsync.feature.detail.CopyHealthReportContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 搬运体检页的渲染测试。
 *
 * 重点不是像素，而是「每种状态都有对应文案、不会被静默省略」：
 * 这页存在的意义就是把「哪天缺睡眠、为什么缺」说清楚，文案丢了等于功能没了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CopyHealthReportScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    private fun render(
        report: CopyHealthReport.Summary?,
        loading: Boolean = false,
        error: String? = null,
    ) {
        val owner = TestLifecycleOwner()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                CopyHealthReportContent(
                    targetName = "国际区账号",
                    spans = listOf(7, 30, 90),
                    spanIndex = 0,
                    onSelectSpan = {},
                    report = report,
                    loading = loading,
                    error = error,
                    onBack = {},
                )
            }
        }
    }

    private fun sample(): CopyHealthReport.Summary = CopyHealthReport.Summary(
        span = 7,
        days = listOf(
            CopyHealthReport.Day("2026-11-04", CopyHealthReport.Kind.COMPLETE, 2, 17718, 0),
            CopyHealthReport.Day("2026-11-03", CopyHealthReport.Kind.WAITING_SLEEP, 1, 418, 5),
            CopyHealthReport.Day("2026-11-02", CopyHealthReport.Kind.EMPTY_PACKAGE, 1, 312, 12),
            CopyHealthReport.Day("2026-11-01", CopyHealthReport.Kind.STOPPED, 1, 12000, 0),
            CopyHealthReport.Day("2026-10-31", CopyHealthReport.Kind.NOT_COPIED, 0, 0, 0),
            CopyHealthReport.Day("2026-10-30", CopyHealthReport.Kind.LOCAL_ONLY, 0, 0, 0),
            CopyHealthReport.Day("2026-10-29", CopyHealthReport.Kind.COMPLETE, 2, 17000, 0),
        ),
    )

    @Test
    fun `体检页显示结论和四种主要状态`() {
        render(sample())

        // 标题可能同时出现在大标题和返回栈的无障碍节点里，这里只要求至少渲染出一处
        assertTrue(composeRule.onAllNodesWithText("搬运体检").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText(
            "最近 7 天里 2 天有睡眠，1 天等手表补传，1 天是空包，1 天已停止复查，1 天没搬过",
        ).assertIsDisplayed()
        // 「等补传」「空包」在统计里和逐日明细里都会出现，只要至少有一处即算渲染成功
        assertTrue(composeRule.onAllNodesWithText("等补传").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("空包").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("有睡眠").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `没有两个账号时显示错误而不是空白`() {
        render(report = null, error = "需要两个 Garmin 账号才能核对")

        composeRule.onNodeWithText("读不出体检结果").assertIsDisplayed()
        composeRule.onNodeWithText("需要两个 Garmin 账号才能核对").assertIsDisplayed()
    }

    @Test
    fun `加载中显示进行中的提示`() {
        render(report = null, loading = true)

        composeRule.onNodeWithText("正在核对…").assertIsDisplayed()
    }
}
