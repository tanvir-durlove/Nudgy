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
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-3 — the sample delete, proven WITHOUT compose (feature 40,
 * Q3=(a), spec §6).
 *
 * DIAGNOSTIC BISECT round 9 — EMPTY body; do not merge in this shape.
 * Rounds 5–8 eliminated, in order: runTest cleanup (8/8
 * UncompletedCoroutinesError, but no hang), plain-JUnit + idle()
 * pump (hang), bounded idleFor pump (hang — idle() refuted), live
 * uiState.collect dropped + single idleFor(500ms) + DB poll (hang —
 * live-collect refuted). The parent also verified in code that no
 * Dispatchers.Main exists anywhere in the TaskRepository
 * create/delete paths or DeleteTaskUseCase (Main appears only in
 * AuthManager sign-in/delete flows, untouched by this test), so a
 * Main-deadlock inside runBlocking is refuted for those paths.
 * Round 4's bisect only proved the hang depends on this test
 * RUNNING — not that it is inside its body; it could be
 * shared-state fallout in a LATER test class. This round stops
 * guessing: if the suite goes green, the hang needs a body
 * operation (create/delete/pump/poll); if it still hangs, the
 * hang is in setUp/construction/tearDown or in shared-state
 * fallout to a later class — the uploaded test-results XML
 * (workflow now has timeout-minutes: 10 + an Upload test
 * results step) names the hanging class.
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
        // DIAGNOSTIC BISECT round 9 — empty body. No runBlocking, no
        // repository calls, no looper calls, no delete, no poll.
        // Only construction + tearDown remain.
        val tasksViewModel = buildViewModel()
        assertNotNull(tasksViewModel)
    }
}
