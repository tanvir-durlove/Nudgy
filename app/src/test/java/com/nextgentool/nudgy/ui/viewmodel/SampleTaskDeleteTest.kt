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
 * DIAGNOSTIC BISECT round 10 — CREATES ONLY; do not merge in this
 * shape. Round 9 proved the empty body goes green (PR 899 + push
 * 900, 931/931, 0 failures), so setUp + VM construction + tearDown
 * are innocent and shared-state fallout to a later class is
 * refuted — the hang needs a body operation (create/delete/pump/
 * poll). This round restores ONLY the two creates: if green, the
 * hang is in delete/idleFor/poll (next: add the delete); if it
 * hangs, a create is guilty. The uploaded test-results XML
 * (workflow: timeout-minutes: 10 + Upload test results step)
 * names the hanging class.
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
        // DIAGNOSTIC BISECT round 10 — creates only. No delete, no
        // idleFor/pump, no DB poll loop, no looper calls anywhere.
        val tasksViewModel = buildViewModel()
        assertNotNull(tasksViewModel)

        runBlocking {
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

        val tasks = runBlocking { repository.getAllTasksOnce(userId) }
        assertEquals(2, tasks.size)
        assertTrue(tasks.any { it.title == "Morning walk" })
        assertTrue(tasks.any { it.title == "Real task" })
    }
}
