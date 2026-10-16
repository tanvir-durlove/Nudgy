package com.example.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [NudgyUser.extractInitials].
 *
 * Pure string logic shown in the top bar avatar — no Android framework needed.
 */
class NudgyUserTest {

    @Test
    fun `two-word name yields first letters of first two words`() {
        assertEquals("AR", NudgyUser.extractInitials("Arif Rahman", "arif@example.com"))
    }

    @Test
    fun `single-word name yields its first two letters`() {
        assertEquals("TA", NudgyUser.extractInitials("Tanvir", "t@example.com"))
    }

    @Test
    fun `blank name falls back to email prefix`() {
        assertEquals("TA", NudgyUser.extractInitials("", "tanvir@example.com"))
        assertEquals("TA", NudgyUser.extractInitials("   ", "tanvir@example.com"))
    }

    @Test
    fun `blank name and blank email yield default initials`() {
        assertEquals("AR", NudgyUser.extractInitials("", ""))
    }

    @Test
    fun `leading and trailing spaces are ignored`() {
        assertEquals("AR", NudgyUser.extractInitials("  Arif Rahman  ", "x@y.zz"))
    }
}
