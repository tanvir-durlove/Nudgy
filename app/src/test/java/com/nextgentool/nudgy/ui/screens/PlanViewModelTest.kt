package com.nextgentool.nudgy.ui.screens

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.todayDateString
import com.nextgentool.nudgy.domain.usecase.CarryOverUseCase
import com.nextgentool.nudgy.domain.usecase.PlanDayUseCase
import com.nextgentool.nudgy.ui.viewmodel.PlanViewModel
import com.nextgentool.nudgy.ui.viewmodel.startOfTodayMillis
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Sprint 1-2 — PlanViewModel without Compose: pins the draft order and
 * the "Start my day" database contract directly, so a failure here
 * names the ViewModel / use-case logic instead of the Compose harness.
 *
 * The Main looper is pumped explicitly (Robolectric pauses it):
 * viewModelScope work resumes on Dispatchers.Main after its IO hops.
 * uiState is stateIn(WhileSubscribed), so the test collects it — an
 * uncollected StateFlow never leaves its initial (loading) value and
 * startMyDay would early-return on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanViewModelTest {

    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var viewModel: PlanViewModel
    private lateinit var scope: CoroutineScope

    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(context, db)
        // Seed BEFORE the ViewModel exists, as in production (Plan opens
        // onto tasks that already exist).
        runBlocking {
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Old report",
                dueEpochMillis = at(-1, 14),
                estimatedMinutes = 120
            )
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Dentist",
                dueEpochMillis = at(0, 15),
                estimatedMinutes = 60
            )
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Big project",
                dueEpochMillis = at(0, 10),
                estimatedMinutes = 500
            )
            repository.createTask(
                userId = repository.currentUserId(),
                title = "No-est task",
                dueEpochMillis = startOfTodayMillis(),
                estimatedMinutes = null
            )
        }
        viewModel = PlanViewModel(
            repository = repository,
            planDayUseCase = PlanDayUseCase(),
            carryOverUseCase = CarryOverUseCase()
        )
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { viewModel.uiState.collect {} }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** Drive the paused Main looper until [condition] holds. */
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
    fun `draft is carry-over first, then timed, then timeless, within budget`() {
        pumpUntil { !viewModel.uiState.value.isLoading }
        val state = viewModel.uiState.value
        assertEquals(
            listOf("Old report", "Dentist", "No-est task"),
            state.pickedRows.map { it.task.title }
        )
        assertEquals(listOf("Big project"), state.unpickedRows.map { it.task.title })
        assertEquals(180, state.totalEstimatedMinutes)
    }

    @Test
    fun `startMyDay stamps the plan into Room`() {
        pumpUntil { !viewModel.uiState.value.isLoading }
        viewModel.startMyDay()
        val today = todayDateString()
        // Wait for planApplied, not for a single task: the stamp loop
        // writes in picked order (Old, Dentist, No-est), so waiting for
        // the Dentist alone can return while No-est is still unstamped
        // and the read below would see a half-finished plan.
        pumpUntil { viewModel.uiState.value.planApplied }
        val tasks = runBlocking {
            repository.getAllTasksOnce(repository.currentUserId())
        }
        val oldReport = tasks.first { it.title == "Old report" }
        val dentist = tasks.first { it.title == "Dentist" }
        val bigProject = tasks.first { it.title == "Big project" }
        val noEst = tasks.first { it.title == "No-est task" }
        assertEquals(today, oldReport.plannedDate)
        assertEquals(today, dentist.plannedDate)
        assertEquals(today, noEst.plannedDate)
        // The carried-over task moved: due is today, origin recorded.
        assertTrue(
            oldReport.dueDateEpochMillis in startOfTodayMillis() until
                startOfTodayMillis() + 24 * 60 * 60 * 1000L
        )
        assertEquals(
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(at(-1, 14)),
            oldReport.carriedOverFromDate
        )
        // The dentist keeps its due time as its planned start.
        assertEquals(at(0, 15), dentist.plannedStartEpochMillis)
        // The unpicked task is not planned.
        assertNull(bigProject.plannedDate)
        assertNull(bigProject.plannedStartEpochMillis)
    }
}
