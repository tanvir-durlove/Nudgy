package com.nextgentool.nudgy.domain

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import kotlin.math.roundToInt

/**
 * Sprint 2-2 — pure slot math for drag-to-slot (Feature 7 (ঘ), spec §6).
 *
 * Everything here is pure (no Compose, no Room) so the drop math is
 * pinned by unit tests, not by a gesture: the gesture only ever feeds
 * a Y position into [slotStartForY] and hands the resulting slot to
 * the ViewModel.
 *
 * The day grid is 24 hours starting at local midnight; a slot snaps to
 * the nearest 15 minutes. A task with an estimate blocks for that long;
 * without one it blocks for [DEFAULT_SLOT_MINUTES] — the estimate
 * itself is NEVER changed by a drop (spec §6 (ঘ)).
 */

/** Slots snap to this many minutes (15). */
const val SLOT_SNAP_MINUTES = 15

/** Block length (minutes) for a task with no estimate — display only. */
const val DEFAULT_SLOT_MINUTES = 30

/** The block length (minutes) a task occupies in the grid. */
fun slotBlockMinutes(task: TaskEntity): Int = task.estimatedMinutes ?: DEFAULT_SLOT_MINUTES

/**
 * The slot start for a pointer [yPx] inside the day grid, where the
 * grid starts at [dayStartMillis] and one hour is [hourHeightPx] tall.
 * Snaps to [SLOT_SNAP_MINUTES] and clamps into the day, so a release
 * above the grid lands at 00:00 and below it at 23:45 — the caller
 * (the drag) decides whether a release outside the grid drops at all;
 * this function never returns an out-of-day time.
 */
fun slotStartForY(yPx: Float, hourHeightPx: Float, dayStartMillis: Long): Long {
    if (hourHeightPx <= 0f) return dayStartMillis
    val minutesFromMidnight = (yPx / hourHeightPx) * 60f
    val snapped = (minutesFromMidnight / SLOT_SNAP_MINUTES).roundToInt() * SLOT_SNAP_MINUTES
    val clamped = snapped.coerceIn(0, 24 * 60 - SLOT_SNAP_MINUTES)
    return dayStartMillis + clamped * 60_000L
}

/**
 * The drag strip (spec Q4(b)): tasks due on the selected day that have
 * no slot yet, plus Anytime (no-date) tasks. Completed tasks and
 * subtasks never appear — a done task needs no slot, and subtasks have
 * no Board presence (S1-4g). Group tasks ARE included: the drop writes
 * plannedStart/plannedDate through TaskRepository.updateTask, which is
 * a defensive local-only update for group rows (it never dirty-marks
 * them — same local-only arrangement as the Board reorder, S1-4g f2).
 * Due-time order, no-date last.
 */
fun stripTasksForDay(
    tasks: List<TaskEntity>,
    dayStartMillis: Long,
    dayEndMillis: Long
): List<TaskEntity> =
    tasks.filter { task ->
        !task.isCompleted &&
            task.parentTaskId == null &&
            task.plannedStartEpochMillis == null &&
            (task.dueDateEpochMillis == TaskRepository.NO_DUE_DATE ||
                task.dueDateEpochMillis in dayStartMillis..dayEndMillis)
    }.sortedWith(
        compareBy<TaskEntity> { it.dueDateEpochMillis == TaskRepository.NO_DUE_DATE }
            .thenBy { it.dueDateEpochMillis }
            .thenBy { it.createdAtEpochMillis }
    )

/** Tasks already placed on the selected day (a plannedStart inside it). */
fun placedTasksForDay(
    tasks: List<TaskEntity>,
    dayStartMillis: Long,
    dayEndMillis: Long
): List<TaskEntity> =
    tasks.filter { task ->
        val start = task.plannedStartEpochMillis
        start != null && start in dayStartMillis..dayEndMillis
    }.sortedBy { it.plannedStartEpochMillis }

/**
 * The next free slot at or after [candidateStart] for a block of
 * [durationMinutes], given the day's [busy] intervals (events +
 * placed-task blocks, as start/end epoch pairs). Scans forward from
 * interval end to interval end — so a suggestion that itself collides
 * advances past the next busy item instead of landing on it. Returns
 * null when no free slot fits before [dayLimitMillis] (next midnight).
 *
 * In the event-only case this returns exactly checkConflict's
 * suggestedAlternativeSlot (the first conflicting event's end); the
 * scan exists so the suggestion is guaranteed free of placed tasks
 * too, and so an all-day event (00:00–24:00) does not poison it —
 * all-day events are excluded from [busy] by the caller, because one
 * would otherwise flag every slot on the day as busy.
 */
fun nextFreeSlot(
    candidateStart: Long,
    durationMinutes: Int,
    busy: List<Pair<Long, Long>>,
    dayLimitMillis: Long
): Long? {
    val durationMs = durationMinutes * 60_000L
    var start = candidateStart
    val sorted = busy.sortedBy { it.first }
    while (start + durationMs <= dayLimitMillis) {
        val end = start + durationMs
        val overlap = sorted.firstOrNull { it.first < end && it.second > start }
        if (overlap == null) return start
        start = overlap.second
    }
    return null
}

/**
 * A drop that landed on a busy slot (spec Q3(a)): the task, the slot
 * the user aimed at, the suggested free slot (null = none left today),
 * and the title of what is already there (event or placed task).
 */
data class PendingDropConflict(
    val task: TaskEntity,
    val requestedStartMillis: Long,
    val suggestedStartMillis: Long?,
    val conflictingTitle: String?
)
