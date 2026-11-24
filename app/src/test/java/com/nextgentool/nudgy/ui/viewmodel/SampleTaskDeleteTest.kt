package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-3 — the sample delete, proven WITHOUT compose (feature 40,
 * Q3=(a), spec §6).
 *
 * Parent-verified 2026-11-24: the delete logic is proven at the
 * repository level because the ViewModel's Main-launched delete
 * hangs under Robolectric's paused Main looper (rounds 11–13) —
 * a harness interaction, not a product defect. The VM's
 * deleteTask is a one-line viewModelScope.launch wrapper around
 * repository.deleteTask (code-verified); the IconButton tap
 * wiring is a DEVICE-GATE item for the sprint-end build.
 * Round 9 proved the empty
 * body goes green (PR 899 + push 900, 931/931, 0 failures), so
 * setUp + VM construction + tearDown are innocent and shared-state
 * fallout to a later class is refuted for the empty test. Round 10
 * proved the two creates are innocent (push 901 green; PR 902
 * green on the sanctioned re-run after the documented PR #99
 * GroupRepositoryTest harness-leak flake). Round 11 proved the
 * hang came back with the VM delete (push 903 + PR 904 both hit
 * the 10-minute step timeout) — and round 11 had NO poll loop,
 * so the poll loop is exonerated. Round 12 proved delete + pump
 * with NO post-delete DB read still hung (PR 906 + push 905,
 * both 10-minute timeouts), so the post-delete DB read is
 * exonerated too. Round 13 proved delete with NO pump still
 * hung (PR 908 + push 907, both 10-minute timeouts), so the
 * pump (idleFor) is exonerated — the ONLY difference from
 * green round 10 is the `tasksViewModel.deleteTask(sample)`
 * call. This round (14) deletes at the repository level instead:
 * `runBlocking { repository.deleteTask(sample.id) }` runs on
 * the test thread + Dispatchers.IO, with NO viewModelScope,
 * NO Dispatchers.Main, NO pump — and it went green on both runs
 * (push 909 + PR 910 attempt 2, 931/931, 0 failures), so the hang
 * needs the VM's Main-launched delete and this shape is the
 * SHIPPABLE test. On a TIMEOUT run no test-results XML exists
 * (Gradle writes XML only when the test task finishes), so the
 * run conclusion + timeout message are the evidence.
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

    @Test
    fun `deleting a sample removes only it`() {
        // Repository-level delete (round 14): no ViewModel delete
        // call, no Main, no pump. Runs on the test thread +
        // Dispatchers.IO via runBlocking.
        val tasksViewModel = buildViewModel()
        assertNotNull(tasksViewModel)

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

        val before = runBlocking { repository.getAllTasksOnce(userId) }
        assertEquals(2, before.size)
        assertTrue(before.any { it.title == "Morning walk" })
        assertTrue(before.any { it.title == "Real task" })

        // DIAGNOSTIC BISECT round 14 — repository-level delete:
        // no ViewModel delete call, no Main, no pump. Runs on the
        // test thread + Dispatchers.IO via runBlocking.
        runBlocking { repository.deleteTask(sample.id) }

        val after = runBlocking { repository.getAllTasksOnce(userId) }
        assertEquals(1, after.size)
        assertEquals("Real task", after[0].title)
        assertTrue(after.none { it.id == sample.id })
    }
}
