package com.nextgentool.nudgy.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 3-1 — Q7(a): the bottom nav hides while the keyboard is
 * open. The rule is a pure function ([shouldShowBottomNav]) so it is
 * unit-testable here; a live IME cannot be driven in Robolectric, so
 * the flip itself is a device-test item (spec §10.15).
 */
class NudgyNavHostTest {

    @Test
    fun `bottom nav shows when keyboard is closed`() {
        assertTrue(shouldShowBottomNav(isImeVisible = false))
    }

    @Test
    fun `bottom nav hides when keyboard is open`() {
        assertFalse(shouldShowBottomNav(isImeVisible = true))
    }
}
