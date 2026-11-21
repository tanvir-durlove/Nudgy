package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.domain.startOfDayMillis
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1-2 — Plan draft + start times (Feature 14, Q1 + Q8).
 */
class PlanDayUseCaseTest {

    private val useCase = PlanDayUseCase()

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
        estimatedMinutes: Int? = null,
        plannedDate: String? = null
    ) = TaskEntity(
        id = id,
        userId = "u1",
        title = id,
        dueDateEpochMillis = due,
        estimatedMinutes = estimatedMinutes,
        plannedDate = plannedDate
    )

    @Test
    fun `draft puts carry-over first, then today-due in due order`() {
        val candidates = listOf(
            task("today-15", at(0, 15)),
            task("carry-18", at(-1, 18)),
            task("today-9", at(0, 9)),
            task("carry-10", at(-2, 10))
        )
        val draft = useCase.buildDraft(
            candidates = candidates,
            carryOverIds = setOf("carry-18", "carry-10"),
            previouslyPickedIds = emptySet()
        )
        assertEquals(
            listOf("carry-10", "carry-18", "today-9", "today-15"),
            draft.picked.map { it.id }
        )
        assertTrue(draft.unpicked.isEmpty())
    }

    @Test
    fun `draft caps estimates at 8h and no-estimate tasks never consume budget`() {
        val candidates = listOf(
            task("a-240", at(0, 9), estimatedMinutes = 240),
            task("b-240", at(0, 10), estimatedMinutes = 240),
            task("c-60", at(0, 11), estimatedMinutes = 60),
            task("d-no-est", at(0, 12), estimatedMinutes = null)
        )
        val draft = useCase.buildDraft(
            candidates = candidates,
            carryOverIds = emptySet(),
            previouslyPickedIds = emptySet()
        )
        // 240 + 240 = 480 (the cap); the 60-min task no longer fits.
        assertEquals(listOf("a-240", "b-240", "d-no-est"), draft.picked.map { it.id })
        assertEquals(listOf("c-60"), draft.unpicked.map { it.id })
        assertEquals(480, draft.totalEstimatedMinutes)
    }

    @Test
    fun `previous picks stay picked even past the budget`() {
        val candidates = listOf(
            task("a-400", at(0, 9), estimatedMinutes = 400),
            task("b-400", at(0, 10), estimatedMinutes = 400, plannedDate = "2099-01-01")
        )
        val draft = useCase.buildDraft(
            candidates = candidates,
            carryOverIds = emptySet(),
            previouslyPickedIds = setOf("b-400")
        )
        assertEquals(listOf("a-400", "b-400"), draft.picked.map { it.id })
    }

    @Test
    fun `start times keep due times and slot the rest from 09_00`() {
        val todayStart = startOfDayMillis(System.currentTimeMillis())
        // "now" is 08:00 today, so the first free slot is 09:00.
        val now = todayStart + 8 * 60 * 60 * 1000L
        val picked = listOf(
            task("due-15", at(0, 15)),
            task("no-due-60", 0L, estimatedMinutes = 60),
            task("no-due-no-est", 0L, estimatedMinutes = null)
        )
        val starts = useCase.assignStartTimes(picked, todayStart, now)
        assertEquals(at(0, 15), starts["due-15"])
        assertEquals(todayStart + 9 * 60 * 60 * 1000L, starts["no-due-60"])
        // 60-min slot taken, next slot is 10:00; no estimate = 30-min slot.
        assertEquals(todayStart + 10 * 60 * 60 * 1000L, starts["no-due-no-est"])
    }

    @Test
    fun `a task due at exactly midnight gets a slot, not its due time`() {
        val todayStart = startOfDayMillis(System.currentTimeMillis())
        val now = todayStart + 8 * 60 * 60 * 1000L
        val picked = listOf(task("midnight", todayStart))
        val starts = useCase.assignStartTimes(picked, todayStart, now)
        assertEquals(todayStart + 9 * 60 * 60 * 1000L, starts["midnight"])
    }

    // ---- Sprint 3-6 — priority start times (feature 22, Q5 = (a)) ----

    @Test
    fun `priority start times are sequential in priority order, never the due time`() {
        val todayStart = startOfDayMillis(System.currentTimeMillis())
        // "now" is 08:00 today, so the first slot is 09:00.
        val now = todayStart + 8 * 60 * 60 * 1000L
        // The first task is due 15:00 but ranked first — its due time
        // must NOT be kept, or My Day would re-sort by due time and
        // destroy the priority order.
        val ordered = listOf(
            task("first-due-15", at(0, 15)),
            task("second-due-9", at(0, 9), estimatedMinutes = 60),
            task("third-no-est", 0L, estimatedMinutes = null)
        )
        val starts = useCase.assignPriorityStartTimes(ordered, todayStart, now)
        assertEquals(todayStart + 9 * 60 * 60 * 1000L, starts["first-due-15"])
        // The 30-min default slot for the first task ends at 09:30;
        // the 60-min second task runs 09:30–10:30.
        assertEquals(todayStart + 9 * 60 * 60 * 1000L + 30 * 60 * 1000L, starts["second-due-9"])
        assertEquals(todayStart + 10 * 60 * 60 * 1000L + 30 * 60 * 1000L, starts["third-no-est"])
    }

    @Test
    fun `priority start times start from now when now is past 09_00`() {
        val todayStart = startOfDayMillis(System.currentTimeMillis())
        // "now" is 14:07 today — the first slot rounds up to 14:15.
        val now = todayStart + 14 * 60 * 60 * 1000L + 7 * 60 * 1000L
        val ordered = listOf(task("a", at(0, 10)), task("b", at(0, 11)))
        val starts = useCase.assignPriorityStartTimes(ordered, todayStart, now)
        assertEquals(todayStart + 14 * 60 * 60 * 1000L + 15 * 60 * 1000L, starts["a"])
        assertEquals(todayStart + 14 * 60 * 60 * 1000L + 45 * 60 * 1000L, starts["b"])
    }
}
