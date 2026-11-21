package com.nextgentool.nudgy.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sprint 3-6 (features 21 + 22) — the day-plan command parser is
 * pure local matching: no LLM, no cap. Priority is checked first,
 * so "দিনটা সাজিয়ে দাও" (order it) never falls into the
 * auto-schedule phrases ("দিনটা সাজাও").
 */
class DayPlanCommandParserTest {

    @Test
    fun `plan my day and schedule my day are auto-schedule`() {
        assertEquals(DayPlanCommand.AUTO_SCHEDULE, DayPlanCommandParser.parse("Plan my day"))
        assertEquals(DayPlanCommand.AUTO_SCHEDULE, DayPlanCommandParser.parse("schedule my day"))
        assertEquals(DayPlanCommand.AUTO_SCHEDULE, DayPlanCommandParser.parse("please schedule today"))
        assertEquals(DayPlanCommand.AUTO_SCHEDULE, DayPlanCommandParser.parse("plan today"))
        assertEquals(DayPlanCommand.AUTO_SCHEDULE, DayPlanCommandParser.parse("organize my day"))
    }

    @Test
    fun `bengali schedule phrases are auto-schedule`() {
        assertEquals(DayPlanCommand.AUTO_SCHEDULE, DayPlanCommandParser.parse("আমার দিনটা সাজাও"))
        assertEquals(DayPlanCommand.AUTO_SCHEDULE, DayPlanCommandParser.parse("দিনটা সাজাও"))
        assertEquals(DayPlanCommand.AUTO_SCHEDULE, DayPlanCommandParser.parse("দিনটা শিডিউল করো"))
        assertEquals(DayPlanCommand.AUTO_SCHEDULE, DayPlanCommandParser.parse("দিনের প্ল্যান"))
    }

    @Test
    fun `priority phrases are priority in both languages`() {
        assertEquals(DayPlanCommand.PRIORITY, DayPlanCommandParser.parse("What should I do first?"))
        assertEquals(DayPlanCommand.PRIORITY, DayPlanCommandParser.parse("prioritize my day"))
        assertEquals(DayPlanCommand.PRIORITY, DayPlanCommandParser.parse("sort by priority"))
        assertEquals(DayPlanCommand.PRIORITY, DayPlanCommandParser.parse("most important first"))
        assertEquals(DayPlanCommand.PRIORITY, DayPlanCommandParser.parse("কোনটা আগে করবো?"))
        assertEquals(DayPlanCommand.PRIORITY, DayPlanCommandParser.parse("দিনটা সাজিয়ে দাও"))
        assertEquals(DayPlanCommand.PRIORITY, DayPlanCommandParser.parse("অগ্রাধিকার ঠিক করে দাও"))
    }

    @Test
    fun `sajiye never falls into schedule`() {
        // "সাজিয়ে দাও" (order it) sits one suffix away from "সাজাও"
        // (schedule it) — the parser must tell them apart.
        assertEquals(DayPlanCommand.PRIORITY, DayPlanCommandParser.parse("আমার দিনটা সাজিয়ে দাও"))
        assertEquals(DayPlanCommand.AUTO_SCHEDULE, DayPlanCommandParser.parse("আমার দিনটা সাজাও"))
    }

    @Test
    fun `vague or unrelated phrases match nothing`() {
        assertNull(DayPlanCommandParser.parse("plan"))
        assertNull(DayPlanCommandParser.parse("schedule"))
        assertNull(DayPlanCommandParser.parse("show today's tasks"))
        assertNull(DayPlanCommandParser.parse("find free time"))
        assertNull(DayPlanCommandParser.parse("give me a breakdown of my day"))
        assertNull(DayPlanCommandParser.parse("buy milk tomorrow"))
        assertNull(DayPlanCommandParser.parse(""))
        assertNull(DayPlanCommandParser.parse("   "))
    }
}
