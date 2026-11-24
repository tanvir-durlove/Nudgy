package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.usecase.AddSubtaskUseCase
import com.nextgentool.nudgy.domain.usecase.CreateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.DeleteTaskUseCase
import com.nextgentool.nudgy.domain.usecase.SaveViewUseCase
import com.nextgentool.nudgy.domain.usecase.ToggleTaskCompletionUseCase
import com.nextgentool.nudgy.domain.usecase.UpdateTaskUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Sprint 6-3 — the sample delete, proven WITHOUT compose (feature 40,
 * Q3=(a), spec §6).
 *
 * Round 5 split: the compose delete test in SampleTaskRowTest hung
 * the test step once it got past the chip wait (the bisect proved it
 * — with only that test @Ignore'd the suite went green, PR run 880
 * SUCCESS, push run 879 in 3m36s). The compose test now proves the
 * render only (chip + delete button); THIS test proves the delete
 * LOGIC: TasksViewModel.deleteTask(sample) removes exactly the
 * sample and leaves the ordinary task untouched. No compose rule,
 * no waitUntil, no semantics, no performClick.
 *
 * Plain JUnit ON PURPOSE (round 6): the runTest + setMain shape
 * threw UncompletedCoroutinesError 8/8 (runs 881–888) across four
 * shapes — repo-flow await, uiState await, store.clear() in the
 * body, advanceUntilIdle() + clear — while the SAME ViewModel +
 * SAME delete path passes in the compose test (plain JUnit +
 * runBlocking, no runTest) and the SAME ViewModel passes under
 * the identical runTest + setMain shape in
 * EisenhowerMatrixViewModelTest. The CI log carries only the
 * error type, never the message (verified from run 888's full
 * log), so the uncompleted coroutine could not be named from CI.
 * The parent's verdict: a runTest-cleanup harness interaction,
 * not a product defect — the product paths are all one-shot
 * plain IO (deleteTask = one viewModelScope.launch,
 * repository.deleteTask / getAllTasksOnce = withContext(IO),
 * seedBuiltInTemplates = a plain IO loop). So this test
 * deliberately uses plain JUnit with the repo's own pumpUntil
 * pattern (PlanViewModelTest) instead of runTest. Do NOT move
 * it back to runTest without first naming the uncompleted
 * coroutine from a local run — the CI log cannot show it.
 *
 * The tap wiring itself (IconButton onClick → viewModel.deleteTask)
 * is NOT proven by this test — DEVICE-GATE item for the
 * sprint-end build.
 *
 * Follows the GroupsViewModelTest pattern: the ViewModel is built
 * inside each test (never in setUp — the init coroutines need the
 * test Main), the ViewModel lives in a ViewModelStore cleared
 * BEFORE the database closes, and in-flight Room IO is drained
 * first (S4-1 round 7). The uid matches Eisenhower's
 * ("test-user-1" via nudgy_auth_prefs) instead of the GUEST
 * fallback — the last remaining difference from the passing
 * Eisenhower test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SampleTaskDeleteTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private val store = ViewModelStore()
    private val userId = "test-user-1"
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", userId).apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(context, db)
    }

    @After
    fun tearDown() {
        // PR #86 rule: cancel the ViewModel scopes before the database
        // closes, then drain in-flight Room IO (S4-1 round 7).
        store.clear()
        scope?.cancel()
        Thread.sleep(500)
        db.close()
    }

    private fun buildViewModel(): TasksViewModel {
        val viewModel = TasksViewModel(
            repository = repository,
            createTaskUseCase = CreateTaskUseCase(repository),
            toggleCompletion = ToggleTaskCompletionUseCase(repository),
            updateTask = UpdateTaskUseCase(repository),
            deleteTask = DeleteTaskUseCase(repository),
            addSubtask = AddSubtaskUseCase(repository),
            saveView = SaveViewUseCase(repository)
        )
        store.put("tasks", viewModel)
        return viewModel
    }

    /** Drive the paused Main looper until [condition] holds (PlanViewModelTest pattern). */
    private fun pumpUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(50)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(condition())
    }

    @Test
    fun `deleting a sample removes only it`() {
        val tasksViewModel = buildViewModel()
        // Keep the uiState sharing coroutine alive (WhileSubscribed):
        // without a subscriber the List's state never refreshes.
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope!!.launch { tasksViewModel.uiState.collect() }

        val sample = runBlocking {
            repository.createTask(
                userId = userId,
                title = "Morning walk",
                dueEpochMillis = startOfTodayMillis() + 1 * 60 * 60 * 1000L,
                isSample = true
            )
        }
        runBlocking {
            repository.createTask(
                userId = userId,
                title = "Real task",
                dueEpochMillis = startOfTodayMillis()
            )
        }
        // Both rows are visible in the List before the delete.
        pumpUntil { tasksViewModel.uiState.value.items.size == 2 }

        tasksViewModel.deleteTask(sample)

        // The sample is gone and the ordinary task is the only row left.
        pumpUntil {
            val items = tasksViewModel.uiState.value.items
            items.size == 1 && items.single().task.title == "Real task"
        }
        val remaining = tasksViewModel.uiState.value.items.map { it.task }
        assertEquals("Real task", remaining.single().title)
        assertTrue(remaining.none { it.id == sample.id })
        // The delete reached the DB, not just the List's state.
        assertEquals(1, runBlocking { repository.getAllTasksOnce(userId).size })
    }
}
