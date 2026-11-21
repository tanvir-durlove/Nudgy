package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.graphics.Color
import com.nextgentool.nudgy.ui.theme.nudgyPaletteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Device feedback 2026-11-21 (ci-677) — dark mode: card backgrounds
 * went dark (theme-aware surface #262833) while text stayed on the
 * static dark R.color resources — ink 1.21:1, muted 1.93:1 on the
 * dark surface, i.e. dark-on-dark, unreadable. The fix swaps text on
 * dark surfaces to the theme-aware palette tokens (NudgyCharcoal /
 * NudgyMuted / NudgyAccent).
 *
 * This is deliberately a PLAIN JUnit class — no Robolectric runner,
 * no compose rule, no Room DB, no ViewModels. These two tests are
 * pure color math on the palette values; they must never share a
 * setUp with real ViewModels. (2026-11-21 leak: DarkThemeTest built
 * real TasksViewModel/TodayViewModel in setUp and tearDown only did
 * db.close() — the ViewModel scopes outlived the DB, and a Room call
 * against the closed DB threw uncaught, which the coroutines-test
 * harness reported as UncaughtExceptionsBeforeTest on the next pure
 * test, 3 runs in a row. The render test stays in DarkThemeTest,
 * whose tearDown now clears the ViewModel scopes before closing
 * the DB. Do NOT move these tests back into that class.)
 *
 * The contrast math is implemented locally on purpose: the usual
 * androidx.core ColorUtils.calculateContrast calls android.graphics
 * .Color, which is a stub that throws RuntimeException on the plain
 * JVM (proven by CI run 44947056598). The formula below is the same
 * WCAG relative-luminance math, verified to reproduce the
 * independently-checked ratios exactly (charcoal/card 13.01,
 * muted/card 6.96, accent/card 6.55, charcoal/canvas 14.77,
 * muted/canvas 7.89, accent/canvas 7.44).
 */
class DarkPaletteTest {

    @Test
    fun `dark palette text passes contrast on dark card and canvas`() {
        val dark = nudgyPaletteFor(true)
        // The exact pairs the swapped call sites sit on: primary and
        // secondary text on cards (surface), and section headers on
        // the screen background (canvas). Small accent text (links,
        // "Group" badges) must pass on the card too.
        assertTrue(contrastRatio(dark.charcoal, dark.card) >= 4.5)
        assertTrue(contrastRatio(dark.muted, dark.card) >= 4.5)
        assertTrue(contrastRatio(dark.accent, dark.card) >= 4.5)
        assertTrue(contrastRatio(dark.charcoal, dark.canvas) >= 4.5)
        assertTrue(contrastRatio(dark.muted, dark.canvas) >= 4.5)
        assertTrue(contrastRatio(dark.accent, dark.canvas) >= 4.5)
    }

    @Test
    fun `light palette token values are the approved ones`() {
        val light = nudgyPaletteFor(false)
        assertEquals(Color(0xFF1C1B1F), light.charcoal)
        assertEquals(Color(0xFF6F6A72), light.muted)
        assertEquals(Color(0xFFFFFFFF), light.card)
        assertEquals(Color(0xFFFBF9F4), light.canvas)
        assertEquals(Color(0xFFCC3F27), light.accent)
    }

    /** WCAG 2.x contrast ratio between two opaque sRGB colors. */
    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = luminance(foreground) + 0.05
        val l2 = luminance(background) + 0.05
        return max(l1, l2) / min(l1, l2)
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val v = channel.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) +
            0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)
    }
}
