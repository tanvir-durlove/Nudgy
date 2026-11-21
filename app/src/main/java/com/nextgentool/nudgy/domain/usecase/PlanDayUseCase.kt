package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import java.util.Calendar
import javax.inject.Inject

/** The auto draft: what Plan picked by itself, and what it left out. */
data class PlanDraft(
    val picked: List<TaskEntity>,
    val unpicked: List<TaskEntity>,
    val totalEstimatedMinutes: Int
)

/**
 * Sprint 1-2 — Plan My Day draft + start times (Feature 14, spec §6).
 * Pure rules, no Android dependencies beyond java.util — unit-testable.
 *
 * Q1 (locked): draft order is due-time order with carry-over FIRST, and
 * the day takes at most 8h of estimates. Q8 (locked): an estimate is
 * never mandatory — a task without one never consumes budget and can
 * always be picked; on the timeline it shows as a point, not a block.
 */
class PlanDayUseCase @Inject constructor() {

    companion object {
        /** Q1: at most 8h of estimates in one day's auto draft. */
        const val MAX_ESTIMATE_MINUTES_PER_DAY = 480

        /** Sequential slots for tasks without a due time start at 09:00. */
        const val SLOT_START_HOUR = 9

        /** Default slot length when a task has no estimate (Q8). */
        const val DEFAULT_SLOT_MINUTES = 30
    }

    /**
     * Auto draft: carry-over first (due order), then today-due with a
     * real time (due-time order), then tasks with no time set last;
     * previously-picked tasks that are neither carry-over nor today-due
     * join in due order. A task whose estimate still fits the 8h budget
     * is picked; a task without an estimate is always picked (it
     * consumes no budget); a task past the budget starts unpicked — the
     * user can still pick it by hand. Previous picks stay picked even
     * if the budget has moved since. Callers must filter out completed
     * tasks before calling.
     */
    fun buildDraft(
        candidates: List<TaskEntity>,
        carryOverIds: Set<String>,
        previouslyPickedIds: Set<String>,
        maxEstimateMinutes: Int = MAX_ESTIMATE_MINUTES_PER_DAY
    ): PlanDraft {
        // A timeless task has no time to order by — listing it first
        // would put it ahead of the 9 AM task. Timed first, timeless
        // last: the Timeline treats it the same way (its "Anytime"
        // group comes after the timed entries).
        val ordered = candidates.sortedWith(
            compareBy<TaskEntity> { if (it.id in carryOverIds) 0 else 1 }
                .thenBy { if (hasNoTime(it)) 1 else 0 }
                .thenBy { it.dueDateEpochMillis }
        )
        var budget = 0
        val picked = mutableListOf<TaskEntity>()
        val unpicked = mutableListOf<TaskEntity>()
        for (task in ordered) {
            val estimate = task.estimatedMinutes
            if (task.id in previouslyPickedIds) {
                picked.add(task)
                if (estimate != null) budget += estimate
                continue
            }
            if (estimate == null || budget + estimate <= maxEstimateMinutes) {
                picked.add(task)
                if (estimate != null) budget += estimate
            } else {
                unpicked.add(task)
            }
        }
        return PlanDraft(
            picked = picked,
            unpicked = unpicked,
            totalEstimatedMinutes = picked.sumOf { it.estimatedMinutes ?: 0 }
        )
    }

    /**
     * Start times for the picked tasks, in plan order. "Start my day"
     * stamps these as plannedStartEpochMillis:
     * - a task due today with a real time keeps its due time;
     * - anything else gets the next free sequential slot, starting at
     *   09:00 today (or now, whichever is later, rounded up to the next
     *   15 minutes), advancing by its estimate (30 min when it has none).
     * This is ordering, not Sprint 3 auto-scheduling: no free-slot search,
     * no conflict moves. Pure + deterministic given `now`.
     */
    fun assignStartTimes(
        pickedOrdered: List<TaskEntity>,
        startOfToday: Long,
        now: Long
    ): Map<String, Long> {
        val endOfToday = startOfToday + 24 * 60 * 60 * 1000L
        var cursor = maxOf(
            startOfToday + SLOT_START_HOUR * 60 * 60 * 1000L,
            roundUpToQuarterHour(now)
        )
        val result = LinkedHashMap<String, Long>()
        for (task in pickedOrdered) {
            val due = task.dueDateEpochMillis
            val start = if (due != TaskRepository.NO_DUE_DATE &&
                due in startOfToday until endOfToday &&
                due != startOfToday
            ) {
                due
            } else {
                val slot = cursor
                cursor += (task.estimatedMinutes ?: DEFAULT_SLOT_MINUTES) * 60 * 1000L
                slot
            }
            result[task.id] = start
        }
        return result
    }

    /**
     * Sprint 3-6 — start times for a PRIORITY-ordered list (feature
     * 22, Q5 = (a)). Unlike [assignStartTimes], a task's due time is
     * NEVER kept: keeping it would re-sort the day by due time and
     * destroy the priority order (My Day sorts by plannedStart).
     * Every task gets the next sequential slot in priority order,
     * from 09:00 today (or now, whichever is later, rounded up to
     * the next 15 minutes), advancing by its estimate (30 min when
     * it has none). The ORDER is the contract, not the clock time:
     * applying late in the day may run slots past midnight — the
     * My-Day order still follows the priority list. Pure +
     * deterministic given `now`.
     */
    fun assignPriorityStartTimes(
        priorityOrdered: List<TaskEntity>,
        startOfToday: Long,
        now: Long
    ): Map<String, Long> {
        var cursor = maxOf(
            startOfToday + SLOT_START_HOUR * 60 * 60 * 1000L,
            roundUpToQuarterHour(now)
        )
        val result = LinkedHashMap<String, Long>()
        for (task in priorityOrdered) {
            result[task.id] = cursor
            cursor += (task.estimatedMinutes ?: DEFAULT_SLOT_MINUTES) * 60 * 1000L
        }
        return result
    }

    /**
     * True when the task has no time to plan around: no due date, or
     * due at local midnight (the "no time picked" default — the
     * quick-add stamps 00:00 when the user picks a day but no time).
     */
    private fun hasNoTime(task: TaskEntity): Boolean =
        task.dueDateEpochMillis == TaskRepository.NO_DUE_DATE ||
            task.dueDateEpochMillis == startOfDayMillis(task.dueDateEpochMillis)

    private fun roundUpToQuarterHour(now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minutes = cal.get(Calendar.MINUTE)
        val rounded = ((minutes + 14) / 15) * 15
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, rounded)
        return cal.timeInMillis
    }
}
