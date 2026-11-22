package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.endOfDayMillis
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem

/**
 * Sprint 4-3 — Eisenhower Matrix (spec §6, Q7=(a), Q8=(a)).
 *
 * The quadrants are DERIVED from the task's existing fields — no new
 * TaskEntity field (Q7=(a)). Importance is the priority (HIGH =
 * important); urgency is the due date (due today, overdue, or within
 * 24 hours from now). A task with no due date is never urgent.
 * Completed tasks have no quadrant (null) — they live in Done.
 *
 * A quadrant move (Q8=(a)) is a priority + due change, not a position
 * change: [moveTargetFor] gives the new values, and the Board's
 * boardOrder rules do not apply here. "Due today" is the end of the
 * local day (23:59:59.999, device-local — the same day math as the
 * rest of the app, see DayDates).
 *
 * Everything here is pure — unit-tested without Compose or Room.
 */
enum class EisenhowerQuadrant(val label: String, val caption: String) {
    Q1("Do first", "Urgent · Important"),
    Q2("Schedule", "Not urgent · Important"),
    Q3("Delegate", "Urgent · Not important"),
    Q4("Eliminate", "Not urgent · Not important")
}

/** The priority + due date a quadrant move writes (spec §6). */
data class EisenhowerMoveTarget(
    val priority: Priority,
    val dueDateEpochMillis: Long
)

/** A due date at most this far ahead still counts as urgent. */
private const val URGENCY_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

/**
 * The quadrant a task belongs in, or null when it must not show in
 * the matrix (completed). A due date in the past (overdue) is urgent;
 * a due date exactly at the 24-hour edge is urgent.
 */
fun quadrantOf(task: TaskEntity, now: Long = System.currentTimeMillis()): EisenhowerQuadrant? {
    if (task.isCompleted) return null
    val urgent = task.dueDateEpochMillis != TaskRepository.NO_DUE_DATE &&
        task.dueDateEpochMillis <= now + URGENCY_WINDOW_MILLIS
    val important = task.priority == Priority.HIGH
    return when {
        urgent && important -> EisenhowerQuadrant.Q1
        !urgent && important -> EisenhowerQuadrant.Q2
        urgent && !important -> EisenhowerQuadrant.Q3
        else -> EisenhowerQuadrant.Q4
    }
}

/**
 * What a move into [quadrant] writes (spec §6 mapping):
 * Q1 → HIGH + due today; Q2 → HIGH + due cleared; Q3 → MEDIUM + due
 * today; Q4 → LOW + due cleared. The mapping depends only on the
 * TARGET quadrant, never on where the task came from — so a move
 * round-trips in both directions (Q1 → Q4 and Q4 → Q1 both land the
 * task in the quadrant it was dropped in).
 */
fun moveTargetFor(
    quadrant: EisenhowerQuadrant,
    now: Long = System.currentTimeMillis()
): EisenhowerMoveTarget = when (quadrant) {
    EisenhowerQuadrant.Q1 ->
        EisenhowerMoveTarget(Priority.HIGH, endOfDayMillis(now))
    EisenhowerQuadrant.Q2 ->
        EisenhowerMoveTarget(Priority.HIGH, TaskRepository.NO_DUE_DATE)
    EisenhowerQuadrant.Q3 ->
        EisenhowerMoveTarget(Priority.MEDIUM, endOfDayMillis(now))
    EisenhowerQuadrant.Q4 ->
        EisenhowerMoveTarget(Priority.LOW, TaskRepository.NO_DUE_DATE)
}

/**
 * Groups list items by quadrant. Pure — unit-tested without Compose.
 * Every item lands in exactly one quadrant; completed items are
 * excluded (they live in Done, not in the matrix).
 */
fun groupMatrixItems(
    items: List<TaskListItem>,
    now: Long = System.currentTimeMillis()
): Map<EisenhowerQuadrant, List<TaskListItem>> {
    val grouped = EisenhowerQuadrant.entries.associateWith { mutableListOf<TaskListItem>() }
    for (item in items) {
        val quadrant = quadrantOf(item.task, now) ?: continue
        grouped.getValue(quadrant).add(item)
    }
    return grouped
}
