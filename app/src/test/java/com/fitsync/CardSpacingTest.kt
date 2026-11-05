package com.fitsync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.fitsync.ui.components.MiuixCard
import com.fitsync.ui.theme.FitSyncTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 卡片间距回归测试。
 *
 * 1.6.3 之前卡片上下只隔 2px，两个 16dp 圆角在接缝两端各咬出一个黑三角，
 * 用户截图反馈「板块和板块缝隙左右两边都有」。间距改成上下各 5dp 后，
 * 这里把「视觉间距 ≥ 10dp」固定住，防止以后有人为了「更紧凑」再调回去。
 *
 * 用 Robolectric 在 JVM 上真实排版，不依赖模拟器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardSpacingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `相邻两张卡片之间至少留出 10dp`() {
        composeRule.setContent {
            FitSyncTheme(darkTheme = false) {
                Column {
                    MiuixCard {
                        Box(
                            modifier = Modifier
                                .testTag("first")
                                .fillMaxWidth()
                                .height(40.dp),
                        )
                    }
                    MiuixCard {
                        Box(
                            modifier = Modifier
                                .testTag("second")
                                .fillMaxWidth()
                                .height(40.dp),
                        )
                    }
                }
            }
        }

        val first = composeRule.onNodeWithTag("first").getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithTag("second").getUnclippedBoundsInRoot()
        val gap = second.top - first.bottom

        assertTrue(
            "两张卡片之间的实际间距是 $gap，小于 10dp 时圆角会在接缝处咬出缺口",
            gap.value >= 10f,
        )
        // 上界防止有人把间距改成一整屏：卡片必须还能看出是一个分组
        assertTrue("卡片间距 $gap 过大，版面会散", gap.value <= 16f)
    }

    @Test
    fun `卡片左右不再贴屏幕边缘`() {
        composeRule.setContent {
            FitSyncTheme(darkTheme = false) {
                MiuixCard {
                    Box(
                        modifier = Modifier
                            .testTag("edge")
                            .fillMaxWidth()
                            .height(40.dp),
                    )
                }
            }
        }

        val bounds = composeRule.onNodeWithTag("edge").getUnclippedBoundsInRoot()
        val left = bounds.left
        assertTrue(
            "卡片内容离屏幕左边只有 $left，16dp 圆角会被切掉半个弧",
            left.value >= 12f,
        )
    }
}
