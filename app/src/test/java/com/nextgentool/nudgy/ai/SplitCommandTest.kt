package com.nextgentool.nudgy.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sprint 3-5 (feature 20) — the split-command parser. Pure parsing:
 * no Android, no DB, no LLM. The parser only ever DETECTS the
 * command and extracts the target title; the ViewModel resolves the
 * task and the LLM does the split.
 */
class SplitCommandTest {

    @Test
    fun `break this down has no title`() {
        assertEquals(SplitCommand(null), SplitCommandParser.parse("break this down"))
        assertEquals(SplitCommand(null), SplitCommandParser.parse("Break this down."))
        assertEquals(SplitCommand(null), SplitCommandParser.parse("please break this down"))
        assertEquals(SplitCommand(null), SplitCommandParser.parse("break this task down"))
        assertEquals(SplitCommand(null), SplitCommandParser.parse("break it down"))
        assertEquals(SplitCommand(null), SplitCommandParser.parse("break down"))
    }

    @Test
    fun `break down with a title extracts it`() {
        assertEquals(
            SplitCommand("Plan Diwali party"),
            SplitCommandParser.parse("break down Plan Diwali party")
        )
        assertEquals(
            SplitCommand("Plan Diwali party"),
            SplitCommandParser.parse("break \"Plan Diwali party\" down")
        )
        assertEquals(
            SplitCommand("Plan Diwali party"),
            SplitCommandParser.parse("break Plan Diwali party down")
        )
        assertEquals(
            SplitCommand("Plan Diwali party"),
            SplitCommandParser.parse("break down Plan Diwali party into subtasks")
        )
    }

    @Test
    fun `split this into subtasks has no title, split with a title extracts it`() {
        assertEquals(SplitCommand(null), SplitCommandParser.parse("split this into subtasks"))
        assertEquals(SplitCommand(null), SplitCommandParser.parse("split it"))
        assertEquals(
            SplitCommand("Plan Diwali party"),
            SplitCommandParser.parse("split Plan Diwali party into subtasks")
        )
        assertEquals(
            SplitCommand("Plan Diwali party"),
            SplitCommandParser.parse("split Plan Diwali party into steps")
        )
    }

    @Test
    fun `bengali split commands parse with and without a title`() {
        assertEquals(SplitCommand(null), SplitCommandParser.parse("এই টাস্কটা ভেঙে দাও"))
        assertEquals(SplitCommand(null), SplitCommandParser.parse("এই কাজটা ভাগ করে দাও"))
        assertEquals(
            SplitCommand("পার্টির আয়োজনটা"),
            SplitCommandParser.parse("পার্টির আয়োজনটা ভেঙে দাও")
        )
        assertEquals(
            SplitCommand("পার্টির আয়োজনটা"),
            SplitCommandParser.parse("ভেঙে দাও পার্টির আয়োজনটা")
        )
        assertEquals(
            SplitCommand("বাড়ির কাজটা"),
            SplitCommandParser.parse("বাড়ির কাজটা ভাগ করো")
        )
    }

    @Test
    fun `bengali split verb with anusvara-plus-ga spelling parses too`() {
        // Device feedback 2026-11-21 (ci-677) — Tanvir typed "ভেংগে
        // দাও" (ংগ); the parser only knew "ভেঙে দাও" (ং), so the
        // command fell through to a reminder draft. All ংগ variants
        // of the verb are the same command.
        assertEquals(
            SplitCommand("তাসনিমলে কল"),
            SplitCommandParser.parse("এই টাস্ক টাকে ভেংগে দাও তাসনিমলে কল")
        )
        assertEquals(SplitCommand(null), SplitCommandParser.parse("এই টাস্কটা ভেংগে দাও"))
        assertEquals(
            SplitCommand("পার্টির আয়োজনটা"),
            SplitCommandParser.parse("পার্টির আয়োজনটা ভেংগে দাও")
        )
        assertEquals(
            SplitCommand("পার্টির আয়োজনটা"),
            SplitCommandParser.parse("ভেঙ্গে দাও পার্টির আয়োজনটা")
        )
        assertEquals(
            SplitCommand("বাড়ির কাজটা"),
            SplitCommandParser.parse("বাড়ির কাজটা ভাংগে দাও")
        )
        assertEquals(
            SplitCommand("বাড়ির কাজটা"),
            SplitCommandParser.parse("বাড়ির কাজটা ভাঙ্গে দাও")
        )
    }

    @Test
    fun `non-split sentences return null`() {
        assertNull(SplitCommandParser.parse("buy milk"))
        assertNull(SplitCommandParser.parse("give me a breakdown of my day"))
        assertNull(SplitCommandParser.parse("what is the breakdown for today?"))
        // A bare "split X" with no "into subtasks" suffix is too
        // ambiguous (split the bill, split the cost) — not a command.
        assertNull(SplitCommandParser.parse("split the bill"))
        assertNull(SplitCommandParser.parse("split the cost with Sara"))
        assertNull(SplitCommandParser.parse(""))
        assertNull(SplitCommandParser.parse("   "))
    }
}
