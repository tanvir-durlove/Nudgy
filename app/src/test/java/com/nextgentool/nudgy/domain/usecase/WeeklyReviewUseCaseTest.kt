package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1-5 — Weekly review bucketing (Feature 17, spec §6). Pure.
 *
 * All instants are built with Calendar in the default timezone and all
 * expectations are built the same way, so the tests hold in any
 * timezone. "Now" is pinned to Wednesday 2026-11-18 15:00, whose
 * Mon–Sun week is Nov 16 – Nov 22.
 */
class WeeklyReviewUseCaseTest {

    private val useCase = WeeklyReviewUseCase()

    private fun at(
        year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0
    ): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Wednesday 2026-11-18 15:00 — mid-week, so past/future due splits. */
    private val now = at(2026, 11, 18, 15)

    private fun task(
        id: String,
        due: Long = TaskRepository.NO_DUE_DATE,
        isCompleted: Boolean = false,
        completedAt: Long? = null,
        carriedOverFromDate: String? = null,
        parentTaskId: String? = null
    ) = TaskEntity(
        id = id,
        userId = "u1",
        title = id,
        dueDateEpochMillis = due,
        isCompleted = isCompleted,
        completedAtEpochMillis = completedAt,
        carriedOverFromDate = carriedOverFromDate,
        parentTaskId = parentTaskId
    )

    @Test
    fun `week starts on Monday and ends the next Monday`() {
        val review = useCase.buildReview(emptyList(), now)
        assertEquals(at(2026, 11, 16), review.weekStartMillis)
        assertEquals(at(2026, 11, 23), review.weekEndMillis)
        assertEquals("Nov 16 – Nov 22", review.weekLabel)
    }

    @Test
    fun `Sunday still belongs to the week that started Monday`() {
        val review = useCase.buildReview(emptyList(), at(2026, 11, 22, 20))
        assertEquals(at(2026, 11, 16), review.weekStartMillis)
    }

    @Test
    fun `Monday at midnight starts a new week`() {
        val review = useCase.buildReview(emptyList(), at(2026, 11, 23, 0))
        assertEquals(at(2026, 11, 23), review.weekStartMillis)
        assertEquals(at(2026, 11, 30), review.weekEndMillis)
    }

    @Test
    fun `finished holds tasks completed this week, not last week`() {
        val review = useCase.buildReview(
            listOf(
                // Completed Tuesday this week.
                task("done-this-week", isCompleted = true, completedAt = at(2026, 11, 17, 10)),
                // Completed Sunday last week (Nov 15).
                task("done-last-week", isCompleted = true, completedAt = at(2026, 11, 15, 10)),
                // Completed flag set but no completion time: not counted.
                task("done-no-time", isCompleted = true, completedAt = null),
                task("not-done", isCompleted = false)
            ),
            now
        )
        assertEquals(listOf("done-this-week"), review.finished.map { it.id })
    }

    @Test
    fun `missed holds past-due unfinished tasks of this week only`() {
        val review = useCase.buildReview(
            listOf(
                // Due Monday 09:00, deadline passed, unfinished.
                task("missed", due = at(2026, 11, 16, 9)),
                // Due Friday — deadline has not passed yet, not missed.
                task("due-friday", due = at(2026, 11, 20, 9)),
                // Due Monday but completed: finished, not missed.
                task(
                    "done-monday",
                    due = at(2026, 11, 16, 9),
                    isCompleted = true,
                    completedAt = at(2026, 11, 16, 10)
                ),
                // Due last Saturday, still unfinished: missed last week.
                task("overdue-last-week", due = at(2026, 11, 14, 9)),
                task("no-date")
            ),
            now
        )
        assertEquals(listOf("missed"), review.missed.map { it.id })
    }

    @Test
    fun `slipped holds tasks carried over this week`() {
        val review = useCase.buildReview(
            listOf(
                task("slipped", carriedOverFromDate = "2026-11-17"),
                task("slipped-last-week", carriedOverFromDate = "2026-11-09"),
                task("never-slipped")
            ),
            now
        )
        assertEquals(listOf("slipped"), review.slipped.map { it.id })
    }

    @Test
    fun `a task can be both slipped and missed`() {
        // Carried over Monday, new due Tuesday 09:00, still unfinished
        // past its deadline: the slip happened this week AND it missed.
        val review = useCase.buildReview(
            listOf(
                task(
                    "both",
                    due = at(2026, 11, 17, 9),
                    carriedOverFromDate = "2026-11-16"
                )
            ),
            now
        )
        assertEquals(listOf("both"), review.slipped.map { it.id })
        assertEquals(listOf("both"), review.missed.map { it.id })
    }

    @Test
    fun `subtasks never appear in any bucket`() {
        val review = useCase.buildReview(
            listOf(
                task(
                    "sub-done",
                    isCompleted = true,
                    completedAt = at(2026, 11, 17, 10),
                    parentTaskId = "parent"
                ),
                task(
                    "sub-missed",
                    due = at(2026, 11, 16, 9),
                    parentTaskId = "parent"
                )
            ),
            now
        )
        assertTrue(review.finished.isEmpty())
        assertTrue(review.missed.isEmpty())
        assertTrue(review.slipped.isEmpty())
    }

    @Test
    fun `an empty week has empty buckets`() {
        val review = useCase.buildReview(emptyList(), now)
        assertTrue(review.finished.isEmpty())
        assertTrue(review.missed.isEmpty())
        assertTrue(review.slipped.isEmpty())
    }
}
