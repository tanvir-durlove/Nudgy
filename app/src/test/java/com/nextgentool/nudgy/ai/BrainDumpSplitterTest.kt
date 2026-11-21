package com.nextgentool.nudgy.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Sprint 3-4 — [BrainDumpSplitter] is pure (no Android): the dump's
 * sentence/line + time-token split rules are pinned here. The rule
 * that matters most is the time-token guard on conjunctions: "buy
 * milk and bread" stays ONE piece (an "and"-list is not two tasks),
 * while "buy milk and call mom at 3pm" splits (the right side has a
 * time). A wrong split only costs a preview row the user can remove;
 * a missed split silently merges two tasks into one — so the guard
 * is deliberately time-driven, not conjunction-driven.
 */
@RunWith(JUnit4::class)
class BrainDumpSplitterTest {

    @Test
    fun `empty and blank transcripts split to nothing`() {
        assertTrue(BrainDumpSplitter.split("").isEmpty())
        assertTrue(BrainDumpSplitter.split("   \n  ").isEmpty())
        assertTrue(BrainDumpSplitter.split("...").isEmpty())
    }

    @Test
    fun `sentences split on terminators`() {
        assertEquals(
            listOf("Buy milk", "Call mom at 3pm"),
            BrainDumpSplitter.split("Buy milk. Call mom at 3pm.")
        )
    }

    @Test
    fun `newlines split`() {
        assertEquals(
            listOf("Buy milk", "Call mom at 3pm"),
            BrainDumpSplitter.split("Buy milk\nCall mom at 3pm")
        )
    }

    @Test
    fun `and splits when the right side has a time`() {
        assertEquals(
            listOf("buy milk", "call mom at 3pm"),
            BrainDumpSplitter.split("buy milk and call mom at 3pm")
        )
    }

    @Test
    fun `and splits when the left side has a time`() {
        assertEquals(
            listOf("call mom at 3pm", "buy milk"),
            BrainDumpSplitter.split("call mom at 3pm and buy milk")
        )
    }

    @Test
    fun `and without any time stays one piece`() {
        assertEquals(
            listOf("buy milk and bread"),
            BrainDumpSplitter.split("buy milk and bread")
        )
    }

    @Test
    fun `comma before a timed second task splits`() {
        assertEquals(
            listOf("buy milk", "call mom at 3pm"),
            BrainDumpSplitter.split("buy milk, call mom at 3pm")
        )
    }

    @Test
    fun `comma without a time stays one piece`() {
        assertEquals(
            listOf("buy milk, bread"),
            BrainDumpSplitter.split("buy milk, bread")
        )
    }

    @Test
    fun `then splits when a time is present`() {
        assertEquals(
            listOf("buy milk", "call mom at 5pm"),
            BrainDumpSplitter.split("buy milk then call mom at 5pm")
        )
    }

    @Test
    fun `tomorrow counts as a time token`() {
        assertEquals(
            listOf("call mom tomorrow", "buy milk"),
            BrainDumpSplitter.split("call mom tomorrow and buy milk")
        )
    }

    @Test
    fun `three sentences become three pieces`() {
        assertEquals(
            listOf("buy milk", "call mom at 3pm", "gym tomorrow"),
            BrainDumpSplitter.split("buy milk. call mom at 3pm. gym tomorrow")
        )
    }

    @Test
    fun `bengali dari splits sentences`() {
        assertEquals(
            listOf("বাজার করো", "তাসনিমকে কল করো"),
            BrainDumpSplitter.split("বাজার করো। তাসনিমকে কল করো")
        )
    }

    @Test
    fun `bengali ar splits when the left side has a time`() {
        assertEquals(
            listOf("কাল সকালে বাজার করো", "তাসনিমকে কল করো"),
            BrainDumpSplitter.split("কাল সকালে বাজার করো আর তাসনিমকে কল করো")
        )
    }

    @Test
    fun `bengali ar without a time stays one piece`() {
        assertEquals(
            listOf("বাজার করো আর দুধ কিনো"),
            BrainDumpSplitter.split("বাজার করো আর দুধ কিনো")
        )
    }

    @Test
    fun `bengali clock time drives a split`() {
        assertEquals(
            listOf("বাজার করো", "তাসনিমকে ৩টায় কল করো"),
            BrainDumpSplitter.split("বাজার করো আর তাসনিমকে ৩টায় কল করো")
        )
    }
}
