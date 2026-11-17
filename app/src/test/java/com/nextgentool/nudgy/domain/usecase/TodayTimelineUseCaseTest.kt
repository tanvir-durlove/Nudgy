package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.domain.startOfDayMillis
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1-2 — visual timeline (Feature 15): blocks, points, events,
 * now-line, and the Anytime group.
 */
class TodayTimelineUseCaseTest {

    private val useCase = GetTodayTimelineUseCase()

    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun task(
        id: String,
        due: Long,
        plannedStart: Long? = null,
        estimatedMinutes: Int? = null
    ) = TaskEntity(
        id = id,
        userId = "u1",
        title = id,
        dueDateEpochMillis = due,
        plannedStartEpochMillis = plannedStart,
        estimatedMinutes = estimatedMinutes
    )

    private fun event(id: String, start: Long, end: Long) = CalendarEventEntity(
        id = id,
        userId = "u1",
        title = id,
        startEpochMillis = start,
        endEpochMillis = end
    )

    @Test
    fun `planned start plus estimate is a block, otherwise a point at due time`() {
        val todayStart = startOfDayMillis(System.currentTimeMillis())
        val now = at(0, 8)
        val result = useCase.buildTimeline(
            todayTasks = listOf(
                task("block", at(0, 10), plannedStart = at(0, 9), estimatedMinutes = 60),
                task("point", at(0, 15)),
                task("planned-no-est", at(0, 12), plannedStart = at(0, 11))
            ),
            todayEvents = emptyList(),
            startOfToday = todayStart,
            now = now
        )
        val blocks = result.entries.filterIsInstance<TimelineEntry.TaskBlock>()
        assertEquals(listOf("block"), blocks.map { it.task.id })
        assertEquals(at(0, 9), blocks.single().startMillis)
        assertEquals(60, blocks.single().durationMinutes)
        val points = result.entries.filterIsInstance<TimelineEntry.TaskPoint>()
        // "planned-no-est" points at its DUE time (12:00), not plannedStart.
        assertEquals(listOf("planned-no-est", "point"), points.map { it.task.id })
        assertEquals(at(0, 12), points[0].startMillis)
        assertEquals(at(0, 15), points[1].startMillis)
    }

    @Test
    fun `a task due at midnight has no time to place and lands in Anytime`() {
        val todayStart = startOfDayMillis(System.currentTimeMillis())
        val result = useCase.buildTimeline(
            todayTasks = listOf(task("midnight", todayStart)),
            todayEvents = emptyList(),
            startOfToday = todayStart,
            now = at(0, 8)
        )
        assertTrue(result.entries.isEmpty())
        assertEquals(listOf("midnight"), result.untimedTasks.map { it.id })
    }

    @Test
    fun `events are blocks and entries sort by start time`() {
        val todayStart = startOfDayMillis(System.currentTimeMillis())
        val result = useCase.buildTimeline(
            todayTasks = listOf(task("task-15", at(0, 15))),
            todayEvents = listOf(event("meet-10", at(0, 10), at(0, 11))),
            startOfToday = todayStart,
            now = at(0, 8)
        )
        val timed = result.entries.filter { it !is TimelineEntry.NowLine }
        assertEquals(2, timed.size)
        assertTrue(timed[0] is TimelineEntry.EventBlock)
        assertTrue(timed[1] is TimelineEntry.TaskPoint)
    }

    @Test
    fun `the now-line sits between past and future entries`() {
        val todayStart = startOfDayMillis(System.currentTimeMillis())
        val result = useCase.buildTimeline(
            todayTasks = listOf(task("past", at(0, 9)), task("future", at(0, 15))),
            todayEvents = emptyList(),
            startOfToday = todayStart,
            now = at(0, 12)
        )
        val kinds = result.entries.map {
            when (it) {
                is TimelineEntry.TaskPoint -> "point:${it.task.id}"
                is TimelineEntry.NowLine -> "now"
                else -> "other"
            }
        }
        assertEquals(listOf("point:past", "now", "point:future"), kinds)
    }

    @Test
    fun `an empty day has no now-line`() {
        val todayStart = startOfDayMillis(System.currentTimeMillis())
        val result = useCase.buildTimeline(
            todayTasks = emptyList(),
            todayEvents = emptyList(),
            startOfToday = todayStart,
            now = at(0, 12)
        )
        assertTrue(result.entries.isEmpty())
        assertTrue(result.untimedTasks.isEmpty())
    }
}
