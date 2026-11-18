package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import javax.inject.Inject

/** One row of the day's visual timeline (Feature 15). */
sealed interface TimelineEntry {
    val startMillis: Long

    /** A task slotted by Plan with an estimate: a block on the timeline. */
    data class TaskBlock(
        val task: TaskEntity,
        override val startMillis: Long,
        val durationMinutes: Int
    ) : TimelineEntry

    /** A task without an estimate: a point at its due time. */
    data class TaskPoint(
        val task: TaskEntity,
        override val startMillis: Long
    ) : TimelineEntry

    /** A calendar event (read-only): a block from start to end. */
    data class EventBlock(val event: CalendarEventEntity) : TimelineEntry {
        override val startMillis: Long get() = event.startEpochMillis
    }

    /** The now-line: teal, never coral. */
    data class NowLine(override val startMillis: Long) : TimelineEntry
}

data class TimelineResult(
    val entries: List<TimelineEntry>,
    /** Today-due tasks with no time to place (due 00:00): the "Anytime" group. */
    val untimedTasks: List<TaskEntity>
)

/**
 * Sprint 1-2 — visual timeline builder (Feature 15, spec §6). Pure.
 *
 * A task with plannedStart + estimate is a BLOCK at plannedStart;
 * anything else is a POINT at its due time (Q8 — no estimate, no
 * block). A task due at exactly 00:00 (the quick-add default = "no
 * time picked") has no time to place and goes to the Anytime group
 * instead of the timed list. Events are blocks. Overlapping entries
 * list consecutively in time order, never stacked on top of each
 * other. The teal now-line is inserted at the current time whenever
 * the timeline has any timed entry.
 */
class GetTodayTimelineUseCase @Inject constructor() {

    fun buildTimeline(
        todayTasks: List<TaskEntity>,
        todayEvents: List<CalendarEventEntity>,
        startOfToday: Long,
        now: Long
    ): TimelineResult {
        val timed = mutableListOf<TimelineEntry>()
        val untimed = mutableListOf<TaskEntity>()
        for (task in todayTasks) {
            val plannedStart = task.plannedStartEpochMillis
            val estimate = task.estimatedMinutes
            if (plannedStart != null && estimate != null) {
                timed.add(TimelineEntry.TaskBlock(task, plannedStart, estimate))
            } else if (task.dueDateEpochMillis != TaskRepository.NO_DUE_DATE &&
                task.dueDateEpochMillis != startOfToday
            ) {
                timed.add(TimelineEntry.TaskPoint(task, task.dueDateEpochMillis))
            } else {
                untimed.add(task)
            }
        }
        for (event in todayEvents) {
            timed.add(TimelineEntry.EventBlock(event))
        }
        val sorted = timed.sortedBy { it.startMillis }.toMutableList()
        if (sorted.isNotEmpty()) {
            val insertAt = sorted.indexOfFirst { it.startMillis > now }
                .takeIf { it >= 0 } ?: sorted.size
            sorted.add(insertAt, TimelineEntry.NowLine(now))
        }
        return TimelineResult(
            entries = sorted,
            untimedTasks = untimed.sortedBy { it.createdAtEpochMillis }
        )
    }
}
