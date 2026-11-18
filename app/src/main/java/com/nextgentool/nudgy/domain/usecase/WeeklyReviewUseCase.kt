package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.domain.dateStringOf
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.startOfWeekMillis
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/** One week's accounting, as three plain lists — no numbers, no report. */
data class WeeklyReview(
    /** Completed this week (completedAt in the week). */
    val finished: List<TaskEntity>,
    /** Due this week, deadline passed, still unfinished. */
    val missed: List<TaskEntity>,
    /** Carried over this week (carriedOverFromDate in the week). */
    val slipped: List<TaskEntity>,
    /** Monday 00:00 local of this week. */
    val weekStartMillis: Long,
    /** Monday 00:00 local of next week (exclusive end). */
    val weekEndMillis: Long,
    /** "Nov 16 – Nov 22" style label for the week. */
    val weekLabel: String
)

/**
 * Sprint 1-5 — Weekly review bucketing (Feature 17, spec §6). Pure.
 *
 * Week = Monday–Sunday (Q5, locked), device-local. NO new table — the
 * buckets are computed from `tasks` only. The three sections are
 * computed independently, so one task can appear in two of them when
 * that is the truth (e.g. carried over this week AND still unfinished
 * past its new due time: slipped and missed).
 *
 * - Finished: completedAtEpochMillis inside this week. A task completed
 *   this week counts even if it was due last week — it finished now.
 * - Missed: due EARLIER this week (before today) and still unfinished.
 *   A task due today is NOT missed yet — the day is not over, the user
 *   still has time. A task due before this week was missed last week,
 *   not this one. No-date tasks are never missed.
 * - Slipped: carriedOverFromDate inside this week, whatever its
 *   completion state — the slip itself happened this week.
 * - Subtasks are never review items: they are part of their parent's
 *   progress, not tasks of their own.
 *
 * Boundary with Sprint 4 (feature 27): this is ONLY the three lists.
 * No counts, no percentages, no graphs, no report — Sprint 4 owns
 * those. The Finished rows show the hand-written actual time when one
 * was logged (Feature 18) — that is list content, not a report.
 */
class WeeklyReviewUseCase @Inject constructor() {

    fun buildReview(tasks: List<TaskEntity>, now: Long): WeeklyReview {
        val weekStart = startOfWeekMillis(now)
        val weekEnd = weekStart + 7 * 24 * 60 * 60 * 1000L
        // "Missed" needs the day to be over: a task due today is still
        // actionable, so only due dates before today count.
        val todayStart = startOfDayMillis(now)
        val weekStartDate = dateStringOf(weekStart)
        val weekEndDate = dateStringOf(weekEnd)
        val topLevel = tasks.filter { it.parentTaskId == null }
        val finished = topLevel.filter {
            it.isCompleted &&
                it.completedAtEpochMillis != null &&
                it.completedAtEpochMillis in weekStart until weekEnd
        }.sortedBy { it.completedAtEpochMillis }
        val missed = topLevel.filter {
            !it.isCompleted &&
                it.dueDateEpochMillis in weekStart until todayStart
        }.sortedBy { it.dueDateEpochMillis }
        val slipped = topLevel.filter {
            val from = it.carriedOverFromDate
            from != null && from >= weekStartDate && from < weekEndDate
        }.sortedBy { it.carriedOverFromDate }
        return WeeklyReview(
            finished = finished,
            missed = missed,
            slipped = slipped,
            weekStartMillis = weekStart,
            weekEndMillis = weekEnd,
            weekLabel = weekLabel(weekStart, weekEnd)
        )
    }

    /** "Nov 16 – Nov 22": Monday to Sunday of the week starting [weekStart]. */
    fun weekLabel(weekStart: Long, weekEnd: Long): String {
        val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
        val sunday = weekEnd - 24 * 60 * 60 * 1000L
        return "${fmt.format(weekStart)} – ${fmt.format(sunday)}"
    }
}
