package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis

/**
 * Sprint 3-6 (features 21 + 22) — the shared "plan the day"
 * computation. Pure rules over tasks, no Android, no repository:
 * the slot search itself lives on CalendarRepository
 * ([findSuggestedFocusSlot]) and the ViewModel drives it.
 *
 * Shared input (both features): today's work = top-level,
 * unfinished tasks that are overdue, due today, or Anytime
 * (no due date), and not yet planned (no plannedStart). The
 * spec's feature-21 input line says "আজ due বা Anytime" — overdue
 * is read in, because the two features share this computation and
 * the priority scoring's first key ("overdue first") is dead
 * without overdue tasks in the set; an overdue task is today's
 * work by any honest reading.
 *
 * "Has a real time" means a due date that is not the midnight
 * default: the quick-add stamps 00:00 when the user picks a day
 * but no time (same rule as [PlanDayUseCase]).
 */
object DayPlanner {

    /** Today's unplaced work, in no particular order (callers sort). */
    fun unplacedTasks(
        allTasks: List<TaskEntity>,
        todayStart: Long,
        todayEnd: Long
    ): List<TaskEntity> = allTasks.filter { task ->
        task.parentTaskId == null &&
            !task.isCompleted &&
            task.plannedStartEpochMillis == null &&
            (task.dueDateEpochMillis == TaskRepository.NO_DUE_DATE ||
                task.dueDateEpochMillis < todayEnd)
    }

    /** True when the task has a due time to plan around (not "no date", not local midnight). */
    fun hasRealTime(task: TaskEntity): Boolean =
        task.dueDateEpochMillis != TaskRepository.NO_DUE_DATE &&
            task.dueDateEpochMillis != startOfDayMillis(task.dueDateEpochMillis)

    /** True when the task's due time has already passed. */
    fun isOverdue(task: TaskEntity, now: Long): Boolean =
        hasRealTime(task) && task.dueDateEpochMillis < now

    private fun priorityRank(priority: Priority): Int = when (priority) {
        Priority.HIGH -> 0
        Priority.MEDIUM -> 1
        Priority.LOW -> 2
    }

    /**
     * Auto-schedule order (spec §6, feature 21): due time first,
     * then priority (HIGH first); timeless tasks last.
     */
    fun scheduleOrder(tasks: List<TaskEntity>): List<TaskEntity> =
        tasks.sortedWith(
            compareBy<TaskEntity> { if (hasRealTime(it)) 0 else 1 }
                .thenBy { if (hasRealTime(it)) it.dueDateEpochMillis else Long.MAX_VALUE }
                .thenBy { priorityRank(it.priority) }
                .thenBy { it.createdAtEpochMillis }
        )

    /**
     * Priority order (spec §6, feature 22): overdue first, then due
     * time, then priority (HIGH first), then calendar conflict last
     * (a task whose due window collides with a meeting ranks below
     * a free one). [hasConflict] is supplied by the caller — the
     * ViewModel's [checkConflict] call per task.
     */
    fun priorityOrder(
        tasks: List<TaskEntity>,
        now: Long,
        hasConflict: (TaskEntity) -> Boolean
    ): List<TaskEntity> =
        tasks.sortedWith(
            compareBy<TaskEntity> { if (isOverdue(it, now)) 0 else 1 }
                .thenBy { if (hasRealTime(it)) it.dueDateEpochMillis else Long.MAX_VALUE }
                .thenBy { priorityRank(it.priority) }
                .thenBy { if (hasConflict(it)) 1 else 0 }
                .thenBy { it.createdAtEpochMillis }
        )
}
