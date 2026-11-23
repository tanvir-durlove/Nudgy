package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4-2 — the Habit tracker over real in-memory Room (spec §5).
 *
 * Manually-constructed ViewModel (no Hilt), following the
 * FocusViewModelTest pattern: Main is set to the test dispatcher
 * inside each test, and the ViewModel is built inside the test (never
 * in setUp — the init coroutines need the test Main). TearDown follows
 * the PR #86 rule: the ViewModel lives in a ViewModelStore and the
 * store is cleared BEFORE the database closes, and in-flight Room IO
 * is drained first (S4-1 round 7: an init query on a real IO thread
 * can still be running when tearDown runs).
 *
 * Time: check-ins are driven with pinned local days (noon, to stay
 * clear of the midnight boundary). The ViewModel's init refresh uses
 * the real today; every pinned scenario below is chosen so the
 * real-today streak equals the pinned streak, so a late init refresh
 * can never overwrite the asserted state with a different number.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HabitViewModelTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var focusRepository: FocusHabitRepository
    private lateinit var taskRepository: TaskRepository
    private val store = ViewModelStore()
    private val userId = "test-user-1"

    /** Noon on the local day [daysAgo] days before today. */
    private fun dayAt(daysAgo: Int): Long =
        startOfDayMillis(-daysAgo, System.currentTimeMillis()) + 12 * 60 * 60 * 1000L

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

    private fun buildViewModel(fakeAuth: GroupAuth? = null): HabitViewModel {
        val viewModel = HabitViewModel(focusRepository, taskRepository, fakeAuth)
        store.put("habits", viewModel)
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

    private suspend fun HabitViewModel.awaitLoaded(): HabitUiState =
        uiState.first { !it.isLoading }

    private suspend fun HabitViewModel.awaitHabit(title: String): HabitUiState =
        uiState.first { state -> state.habits.any { it.habit.title == title } }

    // ---- Create / rename ----

    @Test
    fun `creating a habit adds it to the list`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()

        viewModel.setNewTitle("Drink water")
        viewModel.createHabit()
        val state = viewModel.awaitHabit("Drink water")

        assertEquals(1, state.habits.size)
        assertEquals("Drink water", state.habits.single().habit.title)
        assertEquals(0, state.habits.single().currentStreak)
        assertFalse(state.habits.single().checkedInToday)
        assertEquals("", state.newTitle)
    }

    @Test
    fun `a blank habit title is a no-op`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()

        viewModel.setNewTitle("   ")
        viewModel.createHabit()

        assertTrue(viewModel.uiState.value.habits.isEmpty())
    }

    @Test
    fun `renaming a habit changes its title`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        viewModel.setNewTitle("Read")
        viewModel.createHabit()
        val created = viewModel.awaitHabit("Read")

        viewModel.startRename(created.habits.single())
        viewModel.setEditTitle("Read a chapter")
        viewModel.confirmRename()
        val renamed = viewModel.awaitHabit("Read a chapter")

        assertEquals("Read a chapter", renamed.habits.single().habit.title)
        assertNull(renamed.editingHabitId)
    }

    @Test
    fun `a blank rename is a no-op and the edit stays open`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        viewModel.setNewTitle("Read")
        viewModel.createHabit()
        val created = viewModel.awaitHabit("Read")

        viewModel.startRename(created.habits.single())
        viewModel.setEditTitle("   ")
        viewModel.confirmRename()

        val state = viewModel.uiState.value
        assertEquals("Read", state.habits.single().habit.title)
        assertEquals(created.habits.single().habit.id, state.editingHabitId)
    }

    @Test
    fun `habits stay in creation order`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        listOf("First", "Second", "Third").forEach { title ->
            viewModel.setNewTitle(title)
            viewModel.createHabit()
            viewModel.awaitHabit(title)
        }

        assertEquals(
            listOf("First", "Second", "Third"),
            viewModel.uiState.value.habits.map { it.habit.title }
        )
    }

    // ---- Check-in / undo (once per day) ----

    @Test
    fun `a second check-in on the same day is a no-op`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        viewModel.setNewTitle("Meditate")
        viewModel.createHabit()
        val created = viewModel.awaitHabit("Meditate")
        val habitId = created.habits.single().habit.id
        val todayNoon = dayAt(0)

        viewModel.checkIn(habitId, todayNoon)
        viewModel.uiState.first { it.habits.singleOrNull()?.checkedInToday == true }
        // A second check-in for the same day: the repository returns
        // false (primary key) — never a second row.
        viewModel.checkIn(habitId, todayNoon)
        viewModel.refresh(todayNoon)

        val state = viewModel.uiState.first {
            it.habits.singleOrNull()?.checkedInToday == true &&
                it.habits.singleOrNull()?.currentStreak == 1
        }
        assertEquals(1, state.habits.single().currentStreak)
        assertEquals(1, focusRepository.getCheckInDates(habitId).size)
    }

    @Test
    fun `undo within the day clears today's check-in`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        viewModel.setNewTitle("Meditate")
        viewModel.createHabit()
        val created = viewModel.awaitHabit("Meditate")
        val habitId = created.habits.single().habit.id
        val todayNoon = dayAt(0)

        viewModel.checkIn(habitId, todayNoon)
        viewModel.uiState.first { it.habits.singleOrNull()?.checkedInToday == true }
        viewModel.undoCheckIn(habitId, todayNoon)
        val state = viewModel.uiState.first {
            it.habits.singleOrNull()?.checkedInToday == false
        }

        assertEquals(0, state.habits.single().currentStreak)
        assertFalse(focusRepository.hasCheckedInToday(habitId, todayNoon))
    }

    // ---- Streak display states (pinned local days) ----

    @Test
    fun `three consecutive check-ins show a streak of three`() = vmTest {
        val habit = focusRepository.createHabit(userId, "Walk")
        focusRepository.checkIn(habit.id, dayAt(3))
        focusRepository.checkIn(habit.id, dayAt(2))
        focusRepository.checkIn(habit.id, dayAt(1))
        val viewModel = buildViewModel()
        viewModel.refresh(dayAt(1))

        val state = viewModel.uiState.first {
            it.habits.singleOrNull()?.currentStreak == 3
        }
        assertEquals(3, state.habits.single().currentStreak)
        assertEquals(3, state.habits.single().longestStreak)
        assertTrue(state.habits.single().checkedInToday)
    }

    @Test
    fun `today not checked in yet leaves the streak at risk, not broken`() = vmTest {
        val habit = focusRepository.createHabit(userId, "Walk")
        focusRepository.checkIn(habit.id, dayAt(2))
        focusRepository.checkIn(habit.id, dayAt(1))
        val viewModel = buildViewModel()
        viewModel.refresh(dayAt(0))

        val state = viewModel.uiState.first {
            it.habits.singleOrNull()?.currentStreak == 2
        }
        assertEquals(2, state.habits.single().currentStreak)
        assertFalse(state.habits.single().checkedInToday)
    }

    @Test
    fun `a missed day breaks the streak but the longest stays`() = vmTest {
        val habit = focusRepository.createHabit(userId, "Walk")
        focusRepository.checkIn(habit.id, dayAt(3))
        focusRepository.checkIn(habit.id, dayAt(2))
        // Day 1 (yesterday) missed: today the streak is 0.
        val viewModel = buildViewModel()
        viewModel.refresh(dayAt(0))

        val state = viewModel.uiState.first {
            it.habits.singleOrNull()?.currentStreak == 0
        }
        assertEquals(0, state.habits.single().currentStreak)
        assertEquals(2, state.habits.single().longestStreak)
        assertFalse(state.habits.single().checkedInToday)
    }

    // ---- Delete ----

    @Test
    fun `deleting a habit removes it and its check-ins`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        viewModel.setNewTitle("Walk")
        viewModel.createHabit()
        val created = viewModel.awaitHabit("Walk")
        val habitId = created.habits.single().habit.id
        viewModel.checkIn(habitId, dayAt(0))
        viewModel.uiState.first { it.habits.singleOrNull()?.checkedInToday == true }

        viewModel.requestDelete(habitId)
        assertEquals(habitId, viewModel.uiState.value.confirmingDeleteHabitId)
        viewModel.confirmDelete()
        val state = viewModel.uiState.first { it.habits.isEmpty() }

        assertTrue(state.habits.isEmpty())
        assertNull(focusRepository.getHabitById(habitId))
        assertEquals(0, focusRepository.getCheckInDates(habitId).size)
    }

    @Test
    fun `cancelling a delete leaves the habit in place`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.awaitLoaded()
        viewModel.setNewTitle("Walk")
        viewModel.createHabit()
        val created = viewModel.awaitHabit("Walk")
        val habitId = created.habits.single().habit.id

        viewModel.requestDelete(habitId)
        viewModel.cancelDelete()

        assertNull(viewModel.uiState.value.confirmingDeleteHabitId)
        assertEquals(1, viewModel.uiState.value.habits.size)
    }

    // ---- Sprint 5 fix round 4: live user ----

    @Test
    fun `habits follow the guest to signed-in flip`() = vmTest {
        // setUp wrote test-user-1; this test starts as guest, like a
        // fresh install before sign-in.
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", AuthManager.GUEST_USER_ID).apply()
        val guestUid = AuthManager.GUEST_USER_ID
        focusRepository.createHabit(guestUid, "Guest habit")
        focusRepository.createHabit(userId, "Signed-in habit")

        val fakeAuth = FlipGroupAuth()
        val viewModel = buildViewModel(fakeAuth)

        // Guest phase: the guest's habit is visible (this proves the
        // guest subscription is live, not just the initial empty
        // value); the signed-in user's habit is not.
        val guestState = viewModel.awaitHabit("Guest habit")
        assertFalse(guestState.habits.any { it.habit.title == "Signed-in habit" })

        fakeAuth.userStateFlow.value =
            NudgyUser(uid = userId, displayName = "Ayesha", email = "ayesha@example.com")

        // After the flip the signed-in user's habit takes over and the
        // guest's disappears.
        val signedInState = viewModel.awaitHabit("Signed-in habit")
        assertFalse(signedInState.habits.any { it.habit.title == "Guest habit" })
    }
}
