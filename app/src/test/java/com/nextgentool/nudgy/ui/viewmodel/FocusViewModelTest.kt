package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.focus.PomodoroAlarm
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4-1 — the Pomodoro timer over real in-memory Room (spec §4).
 *
 * Manually-constructed ViewModel (no Hilt) with a fake alarm and a
 * fake sound, following the CalendarViewModelTest pattern: Main is
 * set to the test dispatcher inside each test, and the ViewModel is
 * built inside the test (never in setUp — the init coroutines need
 * the test Main). TearDown follows the PR #86 rule: the ViewModel
 * lives in a ViewModelStore and the store is cleared BEFORE the
 * database closes, so the ticker coroutine can never touch a closed
 * DB.
 *
 * Time: every session is started from the REAL current instant, so
 * the ticker's wall-clock math holds and today's stats cover the
 * session. NEVER call advanceUntilIdle / advanceTimeBy after a
 * session starts — the ticker re-schedules itself every second, so
 * advancing virtual time spins it forever; targeted `first {}`
 * waits are the only waits used here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FocusViewModelTest {

    private class FakePomodoroAlarm : PomodoroAlarm {
        data class Scheduled(
            val sessionId: String,
            val kind: FocusSessionKind,
            val taskTitle: String?,
            val triggerEpochMillis: Long
        )

        val scheduled = mutableListOf<Scheduled>()
        val cancelled = mutableListOf<String>()

        override fun scheduleEnd(
            sessionId: String,
            kind: FocusSessionKind,
            taskTitle: String?,
            triggerEpochMillis: Long
        ): Boolean {
            scheduled.add(Scheduled(sessionId, kind, taskTitle, triggerEpochMillis))
            return true
        }

        override fun cancel(sessionId: String) {
            cancelled.add(sessionId)
        }
    }

    private class FakeReplySoundPlayer : ReplySoundPlayer {
        var playCount = 0
        override fun playReplySound() {
            playCount++
        }
    }

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var focusRepository: FocusHabitRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var fakeAlarm: FakePomodoroAlarm
    private lateinit var fakeSound: FakeReplySoundPlayer
    private val store = ViewModelStore()
    private val userId = "test-user-1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", userId).apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        focusRepository = FocusHabitRepository(context, db)
        taskRepository = TaskRepository(context, db)
        fakeAlarm = FakePomodoroAlarm()
        fakeSound = FakeReplySoundPlayer()
    }

    @After
    fun tearDown() {
        // PR #86 rule: cancel the ViewModel scopes (the ticker) before
        // the database closes.
        store.clear()
        db.close()
    }

    private fun buildViewModel(): FocusViewModel {
        val viewModel = FocusViewModel(focusRepository, taskRepository, fakeAlarm, fakeSound)
        store.put("focus", viewModel)
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

    private suspend fun FocusViewModel.awaitRunning(): FocusUiState =
        uiState.first { it.phase == PomodoroPhase.RUNNING && it.sessionId != null }

    private suspend fun FocusViewModel.awaitIdle(): FocusUiState =
        uiState.first { it.phase == PomodoroPhase.IDLE && it.sessionId == null }

    private suspend fun FocusViewModel.awaitPaused(): FocusUiState =
        uiState.first { it.phase == PomodoroPhase.PAUSED }

    // ---- Start ----

    @Test
    fun `starting a focus session records it and arms the end alarm`() = vmTest {
        val viewModel = buildViewModel()
        val before = System.currentTimeMillis()
        viewModel.startFocus()
        val state = viewModel.awaitRunning()

        assertEquals(FocusSessionKind.FOCUS, state.kind)
        assertEquals(25 * 60_000L, state.totalMillis)
        assertTrue(state.remainingMillis in 1L..25 * 60_000L)
        assertNull(state.selectedTaskId)

        val session = focusRepository.getSessionById(state.sessionId!!)
        assertNotNull(session)
        assertEquals(FocusSessionKind.FOCUS, session!!.kind)
        assertTrue(kotlin.math.abs(before + 25 * 60_000L - session.plannedEndEpochMillis) <= 5_000L)
        assertTrue(fakeAlarm.scheduled.any {
            it.sessionId == state.sessionId && it.kind == FocusSessionKind.FOCUS &&
                it.triggerEpochMillis == session.plannedEndEpochMillis
        })
        // Nothing completed yet: today's minutes are still 0.
        assertEquals(0, state.todaysFocusMinutes)
    }

    @Test
    fun `starting a focus session on a task links its minutes to that task`() = vmTest {
        val task = taskRepository.createTask(userId, "Call mom")
        val viewModel = buildViewModel()
        viewModel.selectTask(task.id)
        viewModel.startFocus()
        val state = viewModel.awaitRunning()

        assertEquals(task.id, state.selectedTaskId)
        assertEquals("Call mom", state.selectedTaskTitle)
        assertTrue(fakeAlarm.scheduled.any {
            it.sessionId == state.sessionId && it.taskTitle == "Call mom"
        })

        viewModel.finishCurrentSession()
        viewModel.awaitIdle()
        assertEquals(
            25,
            focusRepository.getFocusMinutesForTask(
                userId, task.id, 0L, System.currentTimeMillis() + 60_000L
            )
        )
    }

    @Test
    fun `starting a second session ends the first without completing it`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.startFocus()
        val first = viewModel.awaitRunning()
        viewModel.startFocus()
        // Wait for the NEW session, not the still-current first one.
        val second = viewModel.uiState.first {
            it.phase == PomodoroPhase.RUNNING && it.sessionId != null && it.sessionId != first.sessionId
        }

        assertNotEquals(first.sessionId, second.sessionId)
        val ended = focusRepository.getSessionById(first.sessionId!!)
        assertNotNull(ended)
        assertEquals(false, ended!!.completed)
        assertNotNull(ended.endedAtEpochMillis)
        // Its alarm was cancelled, so no end notification can fire for it.
        assertTrue(fakeAlarm.cancelled.contains(first.sessionId))
        assertEquals(0, focusRepository.getFocusMinutesForDay(userId, System.currentTimeMillis()))
    }

    // ---- Pause / resume / cancel ----

    @Test
    fun `pause freezes the countdown and cancels the alarm, resume re-arms it`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.startFocus()
        val running = viewModel.awaitRunning()
        val frozenRemaining = running.remainingMillis

        viewModel.pause()
        val paused = viewModel.awaitPaused()
        assertEquals(frozenRemaining, paused.remainingMillis)
        assertTrue(fakeAlarm.cancelled.contains(running.sessionId))

        val resumeAt = System.currentTimeMillis()
        viewModel.resume()
        val resumed = viewModel.awaitRunning()
        assertEquals(frozenRemaining, resumed.remainingMillis)
        // The persisted end moved to now + the frozen remaining time,
        // so the pause itself never eats into the session.
        val session = focusRepository.getSessionById(resumed.sessionId!!)
        assertNotNull(session)
        assertTrue(
            kotlin.math.abs(resumeAt + frozenRemaining - session!!.plannedEndEpochMillis) <= 5_000L
        )
        assertTrue(fakeAlarm.scheduled.any {
            it.sessionId == resumed.sessionId && it.triggerEpochMillis == session.plannedEndEpochMillis
        })
    }

    @Test
    fun `cancel ends the session not completed and its minutes never count`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.startFocus()
        val running = viewModel.awaitRunning()

        viewModel.cancelCurrent()
        viewModel.awaitIdle()

        val session = focusRepository.getSessionById(running.sessionId!!)
        assertNotNull(session)
        assertEquals(false, session!!.completed)
        assertNotNull(session.endedAtEpochMillis)
        assertTrue(fakeAlarm.cancelled.contains(running.sessionId))
        assertEquals(0, focusRepository.getFocusMinutesForDay(userId, System.currentTimeMillis()))
        assertEquals(0, fakeSound.playCount)
    }

    @Test
    fun `a paused session can still be cancelled`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.startFocus()
        viewModel.awaitRunning()
        viewModel.pause()
        viewModel.awaitPaused()
        viewModel.cancelCurrent()
        val idle = viewModel.awaitIdle()
        assertEquals(PomodoroPhase.IDLE, idle.phase)
        assertEquals(0, focusRepository.getFocusMinutesForDay(userId, System.currentTimeMillis()))
    }

    // ---- Finish ----

    @Test
    fun `finishing completes the session, plays the sound, and shows today's minutes`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.startFocus()
        viewModel.awaitRunning()

        viewModel.finishCurrentSession()
        val idle = viewModel.uiState.first {
            it.phase == PomodoroPhase.IDLE && it.sessionId == null &&
                it.justFinishedKind == FocusSessionKind.FOCUS
        }

        assertEquals(25, idle.todaysFocusMinutes)
        assertEquals(1, idle.completedFocusToday)
        assertEquals(1, fakeSound.playCount)
        assertEquals(25, focusRepository.getFocusMinutesForDay(userId, System.currentTimeMillis()))
    }

    @Test
    fun `finishing an already-completed session plays no second sound`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.startFocus()
        val running = viewModel.awaitRunning()
        // The end alarm's receiver got there first (app open race).
        focusRepository.completeSession(running.sessionId!!, System.currentTimeMillis())

        viewModel.finishCurrentSession()
        viewModel.uiState.first {
            it.phase == PomodoroPhase.IDLE && it.justFinishedKind == FocusSessionKind.FOCUS
        }
        assertEquals(0, fakeSound.playCount)
        assertEquals(25, focusRepository.getFocusMinutesForDay(userId, System.currentTimeMillis()))
    }

    @Test
    fun `finishing a break never counts toward focus minutes`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.startFocus()
        viewModel.awaitRunning()
        viewModel.finishCurrentSession()
        viewModel.uiState.first { it.justFinishedKind == FocusSessionKind.FOCUS }

        viewModel.startBreak()
        val breakRunning = viewModel.awaitRunning()
        assertEquals(FocusSessionKind.BREAK, breakRunning.kind)
        assertEquals(5 * 60_000L, breakRunning.totalMillis)

        viewModel.finishCurrentSession()
        val idle = viewModel.uiState.first {
            it.phase == PomodoroPhase.IDLE && it.justFinishedKind == FocusSessionKind.BREAK
        }
        assertEquals(25, idle.todaysFocusMinutes)
        assertEquals(1, idle.completedFocusToday)
    }

    // ---- Long break ----

    @Test
    fun `three completed sessions mean a short next break, four mean a long one`() = vmTest {
        val viewModel = buildViewModel()
        repeat(3) {
            viewModel.startFocus()
            viewModel.awaitRunning()
            viewModel.finishCurrentSession()
            viewModel.uiState.first { it.justFinishedKind == FocusSessionKind.FOCUS }
        }
        val afterThree = viewModel.uiState.first { it.completedFocusToday == 3 }
        assertEquals(5, afterThree.nextBreakMinutes)

        viewModel.startFocus()
        viewModel.awaitRunning()
        viewModel.finishCurrentSession()
        val afterFour = viewModel.uiState.first {
            it.completedFocusToday == 4 && it.justFinishedKind == FocusSessionKind.FOCUS
        }
        assertEquals(15, afterFour.nextBreakMinutes)
        assertEquals(100, afterFour.todaysFocusMinutes)

        // The long break itself is 15 minutes long.
        viewModel.startBreak()
        val breakRunning = viewModel.awaitRunning()
        assertEquals(15 * 60_000L, breakRunning.totalMillis)
    }

    // ---- Process-death resume ----

    @Test
    fun `a running session whose planned end passed is completed on reopen`() = vmTest {
        // A session that started 30 minutes ago (25-minute length):
        // its end passed while the app was dead.
        focusRepository.startSession(
            userId, null, 25, FocusSessionKind.FOCUS,
            now = System.currentTimeMillis() - 30 * 60_000L
        )
        val viewModel = buildViewModel()
        val state = viewModel.uiState.first {
            it.justFinishedKind == FocusSessionKind.FOCUS
        }
        assertEquals(PomodoroPhase.IDLE, state.phase)
        assertEquals(25, state.todaysFocusMinutes)
        assertEquals(1, state.completedFocusToday)
        // Silent: no sound for a session that ended while the app was dead.
        assertEquals(0, fakeSound.playCount)
    }

    @Test
    fun `a running session still inside its window resumes counting down`() = vmTest {
        val session = focusRepository.startSession(
            userId, null, 25, FocusSessionKind.FOCUS,
            now = System.currentTimeMillis()
        )
        val viewModel = buildViewModel()
        val state = viewModel.awaitRunning()

        assertEquals(session.id, state.sessionId)
        assertEquals(FocusSessionKind.FOCUS, state.kind)
        assertTrue(state.remainingMillis in 1L..25 * 60_000L)
        // The alarm is re-armed in case the system dropped it.
        assertTrue(fakeAlarm.scheduled.any { it.sessionId == session.id })
    }

    @Test
    fun `restore with no running session stays idle`() = vmTest {
        val viewModel = buildViewModel()
        val state = viewModel.uiState.first { it.phase == PomodoroPhase.IDLE }
        assertNull(state.sessionId)
        assertNull(state.justFinishedKind)
    }

    // ---- Task picker ----

    @Test
    fun `the picker lists open tasks and selecting one sticks for the next start`() = vmTest {
        val open = taskRepository.createTask(userId, "Read a chapter")
        val done = taskRepository.createTask(userId, "Already done")
        taskRepository.toggleTaskCompletion(done)
        val viewModel = buildViewModel()
        val state = viewModel.uiState.first { it.availableTasks.size == 1 }

        assertEquals(open.id, state.availableTasks.single().id)
        viewModel.selectTask(open.id)
        val selected = viewModel.uiState.first { it.selectedTaskId == open.id }
        assertEquals("Read a chapter", selected.selectedTaskTitle)
        viewModel.selectTask(null)
        viewModel.uiState.first { it.selectedTaskId == null }
    }
}
