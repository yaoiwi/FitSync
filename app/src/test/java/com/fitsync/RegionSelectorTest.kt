package com.fitsync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fitsync.ui.components.RegionSegmentedControl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归测试：区域选择器必须真的能点。
 *
 * 之前 `RegionSegmentedControl` 收了 onRegionSelected 却从没调用，
 * 导致「国际区」永远选不中（编译期只有一条 "parameter is never used" 警告）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionSelectorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 点击国际区会回调com() {
        var picked: String? = null
        composeRule.setContent {
            var region by remember { mutableStateOf("cn") }
            RegionSegmentedControl(
                selectedRegion = region,
                onRegionSelected = {
                    picked = it
                    region = it
                },
            )
        }

        composeRule.onNodeWithText("🌐 国际区").performClick()

        assertEquals("com", picked)
    }
}
