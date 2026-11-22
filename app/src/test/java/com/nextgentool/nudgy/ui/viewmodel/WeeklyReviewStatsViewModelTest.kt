package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.startOfWeekMillis
import com.nextgentool.nudgy.domain.usecase.CarryOverUseCase
import com.nextgentool.nudgy.domain.usecase.GetTodayTimelineUseCase
import com.nextgentool.nudgy.domain.usecase.ToggleTaskCompletionUseCase
import com.nextgentool.nudgy.domain.usecase.WeeklyReviewUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4-4 — the weekly review's numbers come from real data
 * (spec §7, §10 item 10).
 *
 * Manually-constructed TodayViewModel over real in-memory Room (no
 * Hilt), following the EisenhowerMatrixViewModelTest pattern: Main is
 * set to the test dispatcher inside each test, and the ViewModel is
 * built inside the test (never in setUp — the init coroutines need
 * the test Main). TearDown follows the PR #86 rule: the ViewModel
 * lives in a ViewModelStore and the store is cleared BEFORE the
 * database closes, and in-flight Room IO is drained first (S4-1
 * round 7).
 *
 * What is pinned here is the READ-BACK: the review's stats
 * (focus minutes, best current/longest streak) equal the seeded
 * sessions and check-ins — a session outside the week, a break, and
 * a cancelled session never count; a streak is the use-case math,
 * not a cached counter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeeklyReviewStatsViewModelTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var focusRepository: FocusHabitRepository
    private val store = ViewModelStore()
    private val userId = "test-user-1"

    /** Monday 00:00 local of this week. */
    private fun monday(): Long = startOfWeekMillis(System.currentTimeMillis())

    /** Noon on the local day [daysAgo] days before today (0 = today). */
    private fun noon(daysAgo: Int = 0): Long =
        startOfDayMillis(-daysAgo, System.currentTimeMillis()) + 12 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", userId).apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = TaskRepository(context, db)
        focusRepository = FocusHabitRepository(context, db)
    }

    @After
    fun tearDown() {
        // PR #86 rule: cancel the ViewModel scopes before the
        // database closes (same leak as DarkThemeTest).
        store.clear()
        // S4-1 round 7 — drain in-flight Room IO before closing the DB.
        Thread.sleep(500)
        db.close()
    }

    private fun buildViewModel(): TodayViewModel {
        val viewModel = TodayViewModel(
            repository = taskRepository,
            calendarRepository = CalendarRepository(context, db),
            toggleCompletion = ToggleTaskCompletionUseCase(taskRepository),
            carryOverUseCase = CarryOverUseCase(),
            timelineUseCase = GetTodayTimelineUseCase(),
            weeklyReviewUseCase = WeeklyReviewUseCase(),
            focusHabitRepository = focusRepository
        )
        store.put("today", viewModel)
        return viewModel
    }

    /** runTest with Main on the test dispatcher (CalendarViewModelTest pattern). */
    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun TodayViewModel.awaitStats(): TodayUiState =
        uiState.first { !it.isLoading && it.weeklyReviewStats != null }

    /** A completed 25-minute FOCUS session starting at [start]. */
    private suspend fun completeFocusSession(start: Long) {
        val session = focusRepository.startSession(
            userId, null, 25, FocusSessionKind.FOCUS, start
        )
        focusRepository.completeSession(session.id, start + 25 * 60_000L)
    }

    @Test
    fun `focus minutes count only completed FOCUS sessions started this week`() = vmTest {
        // In week: Monday + Tuesday, 25 minutes each.
        completeFocusSession(monday() + 10 * 60 * 60 * 1000L)
        completeFocusSession(monday() + 34 * 60 * 60 * 1000L)
        // Last week: started before Monday — must not count.
        completeFocusSession(monday() - 24 * 60 * 60 * 1000L)
        // A break this week: breaks never count as focus minutes.
        val breakSession = focusRepository.startSession(
            userId, null, 5, FocusSessionKind.BREAK, monday() + 12 * 60 * 60 * 1000L
        )
        focusRepository.completeSession(breakSession.id, monday() + 12 * 60 * 60 * 1000L + 5 * 60_000L)
        // A cancelled focus session this week: never completed, never counts.
        val cancelled = focusRepository.startSession(
            userId, null, 25, FocusSessionKind.FOCUS, monday() + 14 * 60 * 60 * 1000L
        )
        focusRepository.cancelSession(cancelled.id, monday() + 14 * 60 * 60 * 1000L + 5 * 60_000L)

        val state = buildViewModel().awaitStats()

        assertEquals(50, state.weeklyReviewStats!!.focusMinutesThisWeek)
    }

    @Test
    fun `streaks in the review match the check-in data`() = vmTest {
        // Habit A: checked in today + yesterday + the day before → 3.
        val walk = focusRepository.createHabit(userId, "Walk")
        focusRepository.checkIn(walk.id, noon(0))
        focusRepository.checkIn(walk.id, noon(1))
        focusRepository.checkIn(walk.id, noon(2))
        // Habit B: a 5-day run ten days ago, nothing since → longest 5, current 0.
        val read = focusRepository.createHabit(userId, "Read")
        for (daysAgo in 14 downTo 10) {
            focusRepository.checkIn(read.id, noon(daysAgo))
        }

        val state = buildViewModel().awaitStats()
        val stats = state.weeklyReviewStats!!

        assertEquals(3, stats.bestCurrentStreak)
        assertEquals(5, stats.bestLongestStreak)
        assertEquals(2, stats.habits.size)
    }

    @Test
    fun `review numbers equal the raw data from one seeded week`() = vmTest {
        // Two tasks finished this week (completedAt inside the week).
        val first = taskRepository.createTask(
            userId, "Finished one", dueEpochMillis = TaskRepository.NO_DUE_DATE
        )
        taskRepository.updateTask(
            first.copy(
                status = TaskStatus.DONE,
                isCompleted = true,
                completedAtEpochMillis = monday() + 12 * 60 * 60 * 1000L
            )
        )
        val second = taskRepository.createTask(
            userId, "Finished two", dueEpochMillis = TaskRepository.NO_DUE_DATE
        )
        taskRepository.updateTask(
            second.copy(
                status = TaskStatus.DONE,
                isCompleted = true,
                completedAtEpochMillis = monday() + 36 * 60 * 60 * 1000L
            )
        )
        // Two completed focus sessions this week: 50 minutes.
        completeFocusSession(monday() + 10 * 60 * 60 * 1000L)
        completeFocusSession(monday() + 34 * 60 * 60 * 1000L)
        // One habit with a 2-day streak (today + yesterday).
        val walk = focusRepository.createHabit(userId, "Walk")
        focusRepository.checkIn(walk.id, noon(0))
        focusRepository.checkIn(walk.id, noon(1))

        val state = buildViewModel().awaitStats()

        // All three numbers from the same seeded week, side by side:
        // the finished count from completedAt, the minutes from the
        // sessions, the streak from the check-ins.
        assertEquals(2, state.weeklyReview!!.finished.size)
        assertEquals(50, state.weeklyReviewStats!!.focusMinutesThisWeek)
        assertEquals(2, state.weeklyReviewStats!!.bestCurrentStreak)
        assertEquals(2, state.weeklyReviewStats!!.bestLongestStreak)
    }

    @Test
    fun `an empty week shows zero focus minutes and zero streaks`() = vmTest {
        val state = buildViewModel().awaitStats()
        val stats = state.weeklyReviewStats!!

        assertEquals(0, stats.focusMinutesThisWeek)
        assertEquals(0, stats.bestCurrentStreak)
        assertEquals(0, stats.bestLongestStreak)
        assertEquals(emptyList<com.nextgentool.nudgy.data.repository.HabitStreak>(), stats.habits)
    }
}
