package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.nextgentool.nudgy.ui.theme.nudgyPaletteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
 */
class DarkPaletteTest {

    @Test
    fun `dark palette text passes contrast on dark card and canvas`() {
        val dark = nudgyPaletteFor(true)
        // The exact pairs the swapped call sites sit on: primary and
        // secondary text on cards (surface), and section headers on
        // the screen background (canvas). Small accent text (links,
        // "Group" badges) must pass on the card too.
        assertTrue(ColorUtils.calculateContrast(dark.charcoal.toArgb(), dark.card.toArgb()) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(dark.muted.toArgb(), dark.card.toArgb()) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(dark.accent.toArgb(), dark.card.toArgb()) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(dark.charcoal.toArgb(), dark.canvas.toArgb()) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(dark.muted.toArgb(), dark.canvas.toArgb()) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(dark.accent.toArgb(), dark.canvas.toArgb()) >= 4.5)
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
}
