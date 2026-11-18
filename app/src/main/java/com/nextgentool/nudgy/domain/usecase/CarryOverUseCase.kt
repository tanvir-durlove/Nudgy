package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.dateStringOf
import com.nextgentool.nudgy.domain.startOfDayMillis
import javax.inject.Inject

/**
 * Sprint 1-2 — carry-over (Feature 16, spec §6), pure rules.
 *
 * Q2 (locked): carry-over is NEVER automatic. Yesterday's unfinished
 * tasks wait in the Today banner and in the Plan draft; the user moves
 * them — all at once or one by one. Moving sets [carriedOverFromDate]
 * to the old due date and moves the due to today, keeping the same
 * time-of-day.
 */
class CarryOverUseCase @Inject constructor() {

    /**
     * Unfinished tasks from before today: top-level, has a due date,
     * due before today's local midnight, not DONE. Group rows are
     * included (they show in the banner with a badge — spec device-test).
     * Subtasks are excluded: they carry no due date of their own
     * (due = 0, which is also < today — the NO_DUE_DATE guard matters).
     * Oldest first.
     */
    fun findCarryOver(allTasks: List<TaskEntity>, startOfToday: Long): List<TaskEntity> =
        allTasks.filter { task ->
            task.parentTaskId == null &&
                task.dueDateEpochMillis != TaskRepository.NO_DUE_DATE &&
                task.dueDateEpochMillis < startOfToday &&
                task.status != TaskStatus.DONE
        }.sortedBy { it.dueDateEpochMillis }

    /** New due when a carried-over task moves to today: same time-of-day, today's date. */
    fun moveDueToToday(dueEpochMillis: Long, startOfToday: Long): Long {
        val timeOfDay = dueEpochMillis - startOfDayMillis(dueEpochMillis)
        return startOfToday + timeOfDay
    }

    /** The date the task is carried over FROM (yyyy-MM-dd of its old due). */
    fun carriedOverFromDate(dueEpochMillis: Long): String = dateStringOf(dueEpochMillis)
}
