package com.fitsync

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.fitsync.ui.theme.MonetPref
import com.fitsync.ui.theme.monetPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 莫奈取色的色板映射。
 *
 * 两件事必须钉住：界面骨架真的换成壁纸色（否则开关等于没开），
 * 数据语义色不被壁纸带跑（否则「步数绿」「心率红」会变成紫色）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonetPaletteTest {

    /** 假装是一套「棕黄色壁纸」生成出来的动态色板 */
    private val wallpaper = lightColorScheme(
        primary = Color(0xFF7A5900),
        primaryContainer = Color(0xFFFFDF9E),
        background = Color(0xFFFFFBFF),
        onSurface = Color(0xFF1D1B16),
        onSurfaceVariant = Color(0xFF4C4639),
        surfaceContainerLow = Color(0xFFF6F0E4),
        surfaceContainerHighest = Color(0xFFE6E0D4),
        outline = Color(0xFF7C776A),
        outlineVariant = Color(0xFFCDC6B8),
        error = Color(0xFFBA1A1A),
    )

    @Test
    fun `骨架色全部来自动态色板`() {
        val p = monetPalette(wallpaper, isDark = false)

        assertEquals(wallpaper.primary, p.primary)
        assertEquals(wallpaper.primary, p.accent)
        assertEquals(wallpaper.primaryContainer, p.primaryContainer)
        assertEquals(wallpaper.onSurface, p.text1)
        assertEquals(wallpaper.onSurfaceVariant, p.text2)
        assertEquals(wallpaper.outline, p.text3)
        assertEquals(wallpaper.background, p.bg)
        assertEquals(wallpaper.background, p.canvas)
        assertEquals(wallpaper.surfaceContainerLow, p.card)
        assertEquals(wallpaper.surfaceContainerLow, p.cardSurface)
        assertEquals(wallpaper.surfaceContainerHighest, p.groupBg)
        assertEquals(wallpaper.outlineVariant, p.divider)
        assertEquals(wallpaper.outline, p.outline)
        assertEquals(wallpaper.error, p.error)
    }

    @Test
    fun `数据语义色保持固定不随壁纸漂移`() {
        val light = monetPalette(wallpaper, isDark = false)
        assertEquals(Color(0xFF146C2E), light.green)
        assertEquals(Color(0xFFBA1A1A), light.red)

        // 深色下换成提亮过的语义色，骨架仍来自 scheme
        val dark = monetPalette(wallpaper, isDark = true)
        assertEquals(Color(0xFF8CD5A0), dark.green)
        assertEquals(wallpaper.primary, dark.primary)
        assertEquals(wallpaper.surfaceContainerLow, dark.cardSurface)
    }

    @Test
    fun `开关默认关闭且可持久化`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MonetPref.set(context, false)

        assertFalse(MonetPref.isEnabled(context))
        MonetPref.set(context, true)
        assertTrue(MonetPref.isEnabled(context))
        MonetPref.set(context, false)
        assertFalse(MonetPref.isEnabled(context))
    }
}
