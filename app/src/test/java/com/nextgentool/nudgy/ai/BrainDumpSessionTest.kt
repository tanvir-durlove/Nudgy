package com.nextgentool.nudgy.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Sprint 3-4 — [BrainDumpSession] is pure (no Android): the dump's
 * transcript accumulation and silence allowance are pinned here. The
 * behaviours that matter: utterances join with ". " (so the splitter
 * sees a sentence boundary even when the speaker did not pause with
 * punctuation); a heard utterance resets the silence count; after
 * [BrainDumpSession.MAX_SILENT_RESTARTS] silent restarts the session
 * gives up on its own instead of listening forever; late callbacks
 * after the session ended are dropped.
 */
@RunWith(JUnit4::class)
class BrainDumpSessionTest {

    @Test
    fun `start opens an empty session`() {
        val session = BrainDumpSession()
        assertFalse(session.isActive)
        session.start()
        assertTrue(session.isActive)
        assertEquals("", session.transcript)
        assertEquals(0, session.utteranceCount)
    }

    @Test
    fun `utterances join with a sentence boundary`() {
        val session = BrainDumpSession()
        session.start()
        session.onUtterance("buy milk")
        session.onUtterance("call mom at 3pm")
        assertEquals("buy milk. call mom at 3pm", session.transcript)
        assertEquals(2, session.utteranceCount)
    }

    @Test
    fun `an utterance ending with punctuation joins cleanly`() {
        val session = BrainDumpSession()
        session.start()
        session.onUtterance("buy milk.")
        session.onUtterance("call mom at 3pm!")
        assertEquals("buy milk. call mom at 3pm", session.transcript)
    }

    @Test
    fun `blank utterances are ignored`() {
        val session = BrainDumpSession()
        session.start()
        session.onUtterance("   ")
        session.onUtterance("buy milk")
        assertEquals("buy milk", session.transcript)
        assertEquals(1, session.utteranceCount)
    }

    @Test
    fun `utterances after stop are dropped`() {
        val session = BrainDumpSession()
        session.start()
        session.onUtterance("buy milk")
        assertEquals("buy milk", session.stop())
        assertFalse(session.isActive)
        session.onUtterance("call mom at 3pm")
        assertEquals("buy milk", session.transcript)
        assertEquals(1, session.utteranceCount)
    }

    @Test
    fun `stop on an empty session returns blank`() {
        val session = BrainDumpSession()
        session.start()
        assertEquals("", session.stop())
        assertFalse(session.isActive)
    }

    @Test
    fun `start resets the previous session`() {
        val session = BrainDumpSession()
        session.start()
        session.onUtterance("buy milk")
        session.stop()
        session.start()
        assertEquals("", session.transcript)
        assertEquals(0, session.utteranceCount)
        assertTrue(session.isActive)
    }

    @Test
    fun `silent restarts are allowed up to the cap then give up`() {
        val session = BrainDumpSession()
        session.start()
        repeat(BrainDumpSession.MAX_SILENT_RESTARTS) {
            assertTrue(session.noteSilentRestart())
        }
        assertFalse(session.noteSilentRestart())
    }

    @Test
    fun `a heard utterance resets the silence count`() {
        val session = BrainDumpSession()
        session.start()
        repeat(BrainDumpSession.MAX_SILENT_RESTARTS) {
            assertTrue(session.noteSilentRestart())
        }
        session.onUtterance("buy milk")
        repeat(BrainDumpSession.MAX_SILENT_RESTARTS) {
            assertTrue(session.noteSilentRestart())
        }
        assertFalse(session.noteSilentRestart())
    }

    @Test
    fun `silent restart on an inactive session returns false`() {
        val session = BrainDumpSession()
        assertFalse(session.noteSilentRestart())
    }
}
