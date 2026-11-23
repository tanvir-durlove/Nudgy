package com.nextgentool.nudgy.ui.viewmodel

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.model.BuiltInTemplates
import com.nextgentool.nudgy.domain.model.TemplateDraft
import com.nextgentool.nudgy.domain.todayDateString
import com.nextgentool.nudgy.domain.usecase.AddSubtaskUseCase
import com.nextgentool.nudgy.domain.usecase.CarryOverUseCase
import com.nextgentool.nudgy.domain.usecase.CreateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.DeleteTaskUseCase
import com.nextgentool.nudgy.domain.usecase.GetTodayTimelineUseCase
import com.nextgentool.nudgy.domain.usecase.PlanDayUseCase
import com.nextgentool.nudgy.domain.usecase.SaveViewUseCase
import com.nextgentool.nudgy.domain.usecase.ToggleTaskCompletionUseCase
import com.nextgentool.nudgy.domain.usecase.UpdateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.WeeklyReviewUseCase
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 5 fix round 4 — the guest→signed-in flip, for the tab
 * ViewModels that froze the uid captured at construction.
 *
 * The device bug (ci-803): after reinstall + sign-in, Today stayed
 * on the guest uid — Plan (built later, after sign-in) wrote the
 * plan onto the signed-in uid's rows, but Today read the guest uid's
 * (empty) rows, so "Start my day" never showed on Today. Each test
 * below seeds data on BOTH uids, builds the ViewModel as guest,
 * proves the guest rows are live, flips the auth state, and proves
 * the signed-in rows take over with no restart. Follows the
 * GroupsViewModelTest flip-test pattern: the user is set through the
 * auth prefs, the ViewModel is built inside each test (never in
 * setUp — the init coroutines need the test Main), the ViewModel
 * lives in a ViewModelStore cleared BEFORE the database closes, and
 * in-flight Room IO is drained first (S4-1 round 7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveUserViewModelFlipTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var calendarRepository: CalendarRepository
    private val store = ViewModelStore()
    private val signedInUid = "user-1"
    private val guestUid = AuthManager.GUEST_USER_ID

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        setActiveUser(guestUid)
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = TaskRepository(context, db)
        calendarRepository = CalendarRepository(context, db)
    }

    @After
    fun tearDown() {
        // PR #86 rule: cancel the ViewModel scopes before the database
        // closes, then drain in-flight Room IO (S4-1 round 7), then
        // clear the auth prefs so no test leaks a uid.
        store.clear()
        Thread.sleep(500)
        db.close()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun setActiveUser(uid: String?) {
        val prefs = context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
        if (uid == null) {
            prefs.edit().clear().apply()
        } else {
            prefs.edit().putString("active_user_id", uid).apply()
        }
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

    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** The sign-in event: the auth flow emits the signed-in user. */
    private fun flip(auth: FlipGroupAuth) {
        auth.userStateFlow.value =
            NudgyUser(uid = signedInUid, displayName = "Ayesha", email = "ayesha@example.com")
    }

    // ---- Today (the reported flow) ----

    @Test
    fun `today shows the signed-in user's planned task after the flip, not the guest's`() = vmTest {
        // Seed BEFORE the ViewModel exists, as in production: the plan
        // lands on the signed-in uid's rows while Today is still guest.
        val guestTask = taskRepository.createTask(
            userId = guestUid, title = "Guest task", dueEpochMillis = at(0, 14)
        )
        taskRepository.updateTask(guestTask.copy(plannedDate = todayDateString()))
        val signedInTask = taskRepository.createTask(
            userId = signedInUid, title = "Signed-in task", dueEpochMillis = at(0, 15)
        )
        taskRepository.updateTask(signedInTask.copy(plannedDate = todayDateString()))

        val fakeAuth = FlipGroupAuth()
        val viewModel = TodayViewModel(
            repository = taskRepository,
            calendarRepository = calendarRepository,
            toggleCompletion = ToggleTaskCompletionUseCase(taskRepository),
            carryOverUseCase = CarryOverUseCase(),
            timelineUseCase = GetTodayTimelineUseCase(),
            weeklyReviewUseCase = WeeklyReviewUseCase(),
            groupAuth = fakeAuth
        ).also { store.put("today", it) }

        // Guest phase: the guest's planned task is visible (this
        // proves the guest subscription is live, not just the initial
        // empty value); the signed-in user's task is not.
        val guestState = viewModel.uiState.first { state ->
            state.myDayItems.any { it.task.title == "Guest task" }
        }
        assertFalse(guestState.myDayItems.any { it.task.title == "Signed-in task" })

        flip(fakeAuth)

        // After the flip the signed-in user's planned task appears and
        // the guest's disappears — the ci-803 bug was that Today never
        // saw the signed-in rows at all.
        val signedInState = viewModel.uiState.first { state ->
            state.myDayItems.any { it.task.title == "Signed-in task" }
        }
        assertFalse(signedInState.myDayItems.any { it.task.title == "Guest task" })
    }

    // ---- Tasks ----

    @Test
    fun `tasks list follows the guest to signed-in flip`() = vmTest {
        taskRepository.createTask(
            userId = guestUid, title = "Guest task", dueEpochMillis = at(0, 14)
        )
        taskRepository.createTask(
            userId = signedInUid, title = "Signed-in task", dueEpochMillis = at(0, 15)
        )

        val fakeAuth = FlipGroupAuth()
        val viewModel = TasksViewModel(
            repository = taskRepository,
            createTaskUseCase = CreateTaskUseCase(taskRepository),
            toggleCompletion = ToggleTaskCompletionUseCase(taskRepository),
            updateTask = UpdateTaskUseCase(taskRepository),
            deleteTask = DeleteTaskUseCase(taskRepository),
            addSubtask = AddSubtaskUseCase(taskRepository),
            saveView = SaveViewUseCase(taskRepository),
            groupAuth = fakeAuth
        ).also { store.put("tasks", it) }

        val guestState = viewModel.uiState.first { state ->
            state.items.any { it.task.title == "Guest task" }
        }
        assertFalse(guestState.items.any { it.task.title == "Signed-in task" })

        flip(fakeAuth)

        val signedInState = viewModel.uiState.first { state ->
            state.items.any { it.task.title == "Signed-in task" }
        }
        assertFalse(signedInState.items.any { it.task.title == "Guest task" })
    }

    // ---- Plan ----

    @Test
    fun `plan draft re-seeds for the signed-in user after the flip`() = vmTest {
        taskRepository.createTask(
            userId = guestUid, title = "Guest task",
            dueEpochMillis = at(0, 14), estimatedMinutes = 60
        )
        taskRepository.createTask(
            userId = signedInUid, title = "Signed-in task",
            dueEpochMillis = at(0, 15), estimatedMinutes = 60
        )

        val fakeAuth = FlipGroupAuth()
        val viewModel = PlanViewModel(
            repository = taskRepository,
            planDayUseCase = PlanDayUseCase(),
            carryOverUseCase = CarryOverUseCase(),
            groupAuth = fakeAuth
        ).also { store.put("plan", it) }

        // Guest phase: the draft seeds from the guest's today-due task.
        // A today-due 60-min task is auto-picked by buildDraft (it fits
        // the 480-min budget), so the assertion reads both sections —
        // the section is the draft's choice, the flip is what this
        // test proves.
        val guestState = viewModel.uiState.first { state ->
            (state.pickedRows + state.unpickedRows).any { it.task.title == "Guest task" }
        }
        assertFalse(
            (guestState.pickedRows + guestState.unpickedRows).any { it.task.title == "Signed-in task" }
        )

        flip(fakeAuth)

        // After the flip the draft re-seeds from the signed-in user's
        // tasks (the flip resets the pick state and re-seeds).
        val signedInState = viewModel.uiState.first { state ->
            (state.pickedRows + state.unpickedRows).any { it.task.title == "Signed-in task" }
        }
        assertFalse(
            (signedInState.pickedRows + signedInState.unpickedRows).any { it.task.title == "Guest task" }
        )
    }

    // ---- Templates ----

    @Test
    fun `templates follow the guest to signed-in flip`() = vmTest {
        // A custom template on the signed-in uid only. The three
        // built-ins are seeded for BOTH uids, so they cannot prove
        // the flip — the custom one can.
        taskRepository.createTemplate(
            signedInUid,
            "Signed-in template",
            listOf(
                TemplateDraft(
                    title = "Flip check",
                    estimatedMinutes = 15,
                    priority = Priority.MEDIUM
                )
            )
        )

        val fakeAuth = FlipGroupAuth()
        val viewModel = TemplatesViewModel(
            repository = taskRepository,
            groupAuth = fakeAuth
        ).also { store.put("templates", it) }

        // Guest phase: the guest's own built-ins are visible (the
        // seed + flow prove the guest subscription is live); the
        // signed-in user's custom template is not.
        val guestTemplates = viewModel.templates.first { templates ->
            templates.any { it.id == BuiltInTemplates.DAILY_ROUTINE_ID }
        }
        assertFalse(guestTemplates.any { it.name == "Signed-in template" })

        flip(fakeAuth)

        val signedInTemplates = viewModel.templates.first { templates ->
            templates.any { it.name == "Signed-in template" }
        }
        assertTrue(signedInTemplates.any { it.id == BuiltInTemplates.DAILY_ROUTINE_ID })
    }
}

/**
 * Sprint 5 fix round 4 — a [GroupAuth] the flip tests can drive: the
 * real AuthManager's user state only moves on a real Firebase sign-in,
 * so the guest→signed-in flip is proven here instead of on a device.
 * Shared by the flip tests in this file and in CalendarViewModelTest,
 * FocusViewModelTest, HabitViewModelTest and ChatViewModelTest (same
 * package). (GroupsViewModelTest has its own private copy.)
 */
internal class FlipGroupAuth : GroupAuth {
    val userStateFlow = MutableStateFlow<NudgyUser?>(null)
    override val userState: StateFlow<NudgyUser?> = userStateFlow

    var signInCalls = 0
    var silentSignInCalls = 0

    override fun signIn(
        activity: Activity,
        scope: CoroutineScope,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        signInCalls++
        onSuccess()
    }

    override fun attemptSilentSignIn(scope: CoroutineScope) {
        silentSignInCalls++
    }
}
