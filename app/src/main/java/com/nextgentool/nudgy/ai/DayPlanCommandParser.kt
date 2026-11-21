package com.nextgentool.nudgy.ai

import java.util.Locale

/**
 * Sprint 3-6 (features 21 + 22) — the two "plan the day" commands,
 * detected LOCALLY (no LLM, no cap) so the ViewModel can compute the
 * day plan itself before anything reaches the prefilter. The
 * prefilter has no day-plan branch (its old "Plan my day" summary,
 * branch 8, is deleted in S3-6 — the phrase now really plans the day).
 *
 * Priority is checked FIRST: "দিনটা সাজিয়ে দাও" (order it) must
 * never fall into the auto-schedule phrases ("দিনটা সাজাও").
 *
 * Deliberately NOT a day-plan command:
 * - "plan" / "schedule" alone — too vague (could be a task title).
 * - "give me a breakdown of my day" — a summary request, not a plan.
 * - "show today's tasks" / "find free time" — other branches own them.
 */
enum class DayPlanCommand { AUTO_SCHEDULE, PRIORITY }

object DayPlanCommandParser {

    private val PRIORITY_EN = listOf(
        "what should i do first",
        "what do i do first",
        "what should i tackle first",
        "which task first",
        "what's first",
        "whats first",
        "prioritize my day",
        "prioritise my day",
        "prioritize today",
        "prioritise today",
        "sort by priority",
        "order by priority",
        "most important first"
    )

    private val PRIORITY_BN = listOf(
        "কোনটা আগে করবো",
        "কোনটা আগে করব",
        "কোনটি আগে করবো",
        "কোনটি আগে করব",
        "সাজিয়ে দাও",
        "অগ্রাধিকার",
        "প্রায়োরিটি",
        "প্রায়োরিটাইজ"
    )

    private val SCHEDULE_EN = listOf(
        "plan my day",
        "schedule my day",
        "schedule today",
        "plan today",
        "organize my day",
        "organise my day",
        "arrange my day",
        "plan out my day",
        "schedule my tasks for today",
        "plan my tasks"
    )

    private val SCHEDULE_BN = listOf(
        "আমার দিনটা সাজাও",
        "দিনটা সাজাও",
        "দিন সাজাও",
        "আমার দিনটা শিডিউল করো",
        "দিনটা শিডিউল করো",
        "দিন শিডিউল করো",
        "দিনের প্ল্যান",
        "দিনের প্লান"
    )

    fun parse(prompt: String): DayPlanCommand? {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        // Priority first: "সাজিয়ে দাও" (order it) sits close to
        // "সাজাও" (schedule it) and must never fall through.
        if (PRIORITY_EN.any { lower.contains(it) } || PRIORITY_BN.any { trimmed.contains(it) }) {
            return DayPlanCommand.PRIORITY
        }
        if (SCHEDULE_EN.any { lower.contains(it) } || SCHEDULE_BN.any { trimmed.contains(it) }) {
            return DayPlanCommand.AUTO_SCHEDULE
        }
        return null
    }
}
