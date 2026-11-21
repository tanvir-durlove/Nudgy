package com.nextgentool.nudgy.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.ui.screens.ChatActionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Sprint 0 test harness — parser / time-logic cases FIRST (test before
 * the parser is rewritten in Sprint 3).
 *
 * Covers the exact bugs that shipped before (2026-11-11 diagnosis):
 *  - a bare hour defaulted to AM ("3 PM doctor" became a 3 AM task)
 *  - Bengali date words ("কালকে") leaked into titles
 *  - a schedule with no time invented one instead of asking
 *  - the reply showed the reminder time (2h before) instead of the
 *    event time
 *
 * Setup: Robolectric + in-memory Room, real repositories, real intent
 * bank asset (context.assets). No Firestore user is signed in, so the
 * cloud-sync paths must no-op; if a creation test ever fails on the
 * sync path, the fix is a guard/fake in the repository — never delete
 * the test.
 *
 * DRAFT: not yet compiled — CI must go green before merge (workflow
 * trigger widening is a separate morning decision).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClientIntentPreFilterTest {

    private lateinit var db: NudgyDatabase
    private lateinit var preFilter: ClientIntentPreFilter
    private lateinit var tasks: TaskRepository
    private lateinit var calendar: CalendarRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tasks = TaskRepository(context, db)
        calendar = CalendarRepository(context, db)
        val memories = MemoryRepository(context, db)
        preFilter = ClientIntentPreFilter(tasks, calendar, memories, context.assets)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    @Test
    fun `bare hour asks AM or PM in English and creates nothing`() = runTest {
        val result = preFilter.tryHandle("test-user", "call tasnim at 5", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.Handled)
        val handled = result as PreFilterResult.Handled
        assertNull(handled.createdTask)
        assertNull(handled.createdEvent)
        assertTrue(handled.responseText.contains("AM or PM"))
        assertEquals(listOf("5 AM", "5 PM"), handled.quickSuggestions)
    }

    @Test
    fun `bare hour asks AM or PM in Bengali and creates nothing`() = runTest {
        val result = preFilter.tryHandle("test-user", "কাল ৩টায় ডাক্তার", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.Handled)
        val handled = result as PreFilterResult.Handled
        assertNull(handled.createdTask)
        assertNull(handled.createdEvent)
        assertTrue(handled.responseText.contains("সকাল নাকি বিকেল"))
    }

    // Sprint 3-2 (Q2 = (a)) — draft-then-confirm: these two tests used
    // to assert immediate creation. The parse now returns a
    // PendingDraft with NOTHING in the DB; the task exists only after
    // confirmDraft. The reason is the point of the sprint: a wrong
    // parse must never silently create a wrong task.

    @Test
    fun `explicit PM drafts a task at 17_00 and creates it only on confirm`() = runTest {
        val result = preFilter.tryHandle("test-user", "call tasnim at 5pm", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.PendingDraft)
        val draft = (result as PreFilterResult.PendingDraft).draft
        assertEquals(DraftKind.TASK, draft.kind)
        assertFalse(result.previewText.contains("AM or PM"))
        assertEquals(17, hourOf(draft.startEpochMillis))
        assertTrue(draft.title.contains("tasnim", ignoreCase = true))
        // Nothing is created until Confirm.
        assertTrue(tasks.getAllTasksOnce("test-user").isEmpty())
        val confirmed = preFilter.confirmDraft("test-user", draft)
        assertTrue(confirmed is PreFilterResult.Handled)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdTask)
        assertEquals(17, hourOf(handled.createdTask!!.dueDateEpochMillis))
        assertTrue(handled.createdTask.title.contains("tasnim", ignoreCase = true))
    }

    @Test
    fun `explicit AM drafts a task in the future and creates it only on confirm`() = runTest {
        val result = preFilter.tryHandle("test-user", "call tasnim at 5am", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.PendingDraft)
        val draft = (result as PreFilterResult.PendingDraft).draft
        assertEquals(5, hourOf(draft.startEpochMillis))
        assertTrue(tasks.getAllTasksOnce("test-user").isEmpty())
        val confirmed = preFilter.confirmDraft("test-user", draft)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdTask)
        assertEquals(5, hourOf(handled.createdTask!!.dueDateEpochMillis))
        assertTrue(handled.createdTask.dueDateEpochMillis > System.currentTimeMillis())
    }

    @Test
    fun `Bengali date word is stripped from the title`() = runTest {
        val result =
            preFilter.tryHandle("test-user", "কালকে ৫টায় তাসনিমকে কল করো", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.Handled)
        val handled = result as PreFilterResult.Handled
        assertNull(handled.createdTask)
        assertTrue(handled.responseText.contains("তাসনিম"))
        assertFalse(handled.responseText.contains("কালকে"))
    }

    @Test
    fun `schedule with no time asks for the time instead of inventing one`() = runTest {
        val result = preFilter.tryHandle("test-user", "schedule appointment", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.Handled)
        val handled = result as PreFilterResult.Handled
        assertNull(handled.createdTask)
        assertNull(handled.createdEvent)
        assertTrue(handled.responseText.contains("What time"))
    }

    // Sprint 3-0 — branch coverage for the paths the ChatViewModel
    // send pipeline now exercises (query / focus / plan / memory /
    // clear / complete). The 6 original tests only covered the parser
    // bugs; these pin the branches the chat actually answers with.

    @Test
    fun `plan my day returns a plan with a focus proposal and creates nothing`() = runTest {
        val result = preFilter.tryHandle("test-user", "plan my day", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.Handled)
        val handled = result as PreFilterResult.Handled
        assertNull(handled.createdTask)
        assertNull(handled.createdEvent)
        assertEquals(ChatActionType.FOCUS_TIME_PROPOSAL, handled.actionType)
        assertNotNull(handled.focusStartMillis)
        assertNotNull(handled.focusEndMillis)
        assertTrue(handled.responseText.contains("plan", ignoreCase = true))
    }

    @Test
    fun `find free time proposes a focus slot without creating an event`() = runTest {
        val result = preFilter.tryHandle("test-user", "find free time", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.Handled)
        val handled = result as PreFilterResult.Handled
        assertNull(handled.createdEvent)
        assertEquals(ChatActionType.FOCUS_TIME_PROPOSAL, handled.actionType)
        assertNotNull(handled.focusStartMillis)
        assertNotNull(handled.focusEndMillis)
        assertTrue(handled.responseText.contains("free focus time"))
    }

    @Test
    fun `block focus time drafts the focus event and creates it only on confirm`() = runTest {
        val result = preFilter.tryHandle("test-user", "block 45m focus", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.PendingDraft)
        val draft = (result as PreFilterResult.PendingDraft).draft
        assertEquals(DraftKind.FOCUS_EVENT, draft.kind)
        assertTrue(draft.title.contains("Focus"))
        val today = startOfDayMillis(System.currentTimeMillis())
        assertTrue(calendar.observeEventsForDay("test-user", today).first().isEmpty())
        val confirmed = preFilter.confirmDraft("test-user", draft)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdEvent)
        assertTrue(handled.createdEvent!!.title.contains("Focus"))
    }

    @Test
    fun `remember that stores a memory and recall reads it back`() = runTest {
        val saved = preFilter.tryHandle(
            "test-user", "remember that I like morning workouts", emptyList(), emptyList()
        )
        assertTrue(saved is PreFilterResult.Handled)
        assertTrue((saved as PreFilterResult.Handled).responseText.contains("saved"))
        val recalled = preFilter.tryHandle(
            "test-user", "what do you remember about me", emptyList(), emptyList()
        )
        assertTrue(recalled is PreFilterResult.Handled)
        assertTrue((recalled as PreFilterResult.Handled).responseText.contains("morning workouts"))
    }

    @Test
    fun `show todays tasks lists the created task`() = runTest {
        val drafted = preFilter.tryHandle("test-user", "call tasnim at 5pm", emptyList(), emptyList())
        assertTrue(drafted is PreFilterResult.PendingDraft)
        preFilter.confirmDraft("test-user", (drafted as PreFilterResult.PendingDraft).draft)
        val todayTasks = tasks.getAllTasksOnce("test-user")
        assertEquals(1, todayTasks.size)
        val result = preFilter.tryHandle("test-user", "show today's tasks", todayTasks, emptyList())
        assertTrue(result is PreFilterResult.Handled)
        assertTrue((result as PreFilterResult.Handled).responseText.contains("tasnim", ignoreCase = true))
    }

    @Test
    fun `complete then clear completed removes the task`() = runTest {
        val drafted = preFilter.tryHandle("test-user", "call tasnim at 5pm", emptyList(), emptyList())
        preFilter.confirmDraft("test-user", (drafted as PreFilterResult.PendingDraft).draft)
        val todayTasks = tasks.getAllTasksOnce("test-user")
        assertEquals(1, todayTasks.size)
        val completed = preFilter.tryHandle("test-user", "complete task tasnim", todayTasks, emptyList())
        assertTrue(completed is PreFilterResult.Handled)
        assertTrue((completed as PreFilterResult.Handled).responseText.contains("completed"))
        assertTrue(tasks.getAllTasksOnce("test-user").single().isCompleted)
        val cleared = preFilter.tryHandle("test-user", "clear completed tasks", emptyList(), emptyList())
        assertTrue(cleared is PreFilterResult.Handled)
        assertTrue((cleared as PreFilterResult.Handled).responseText.contains("cleared"))
        assertTrue(tasks.getAllTasksOnce("test-user").isEmpty())
    }

    // Sprint 3-2 — draft-then-confirm coverage for every refactored
    // creation branch: the parse returns a PendingDraft, the DB stays
    // empty until confirmDraft, and confirm saves exactly the draft.

    @Test
    fun `schedule draft creates nothing until confirmed`() = runTest {
        val result = preFilter.tryHandle(
            "test-user", "schedule meeting with team tomorrow at 2pm", emptyList(), emptyList()
        )
        assertTrue(result is PreFilterResult.PendingDraft)
        val draft = (result as PreFilterResult.PendingDraft).draft
        assertEquals(DraftKind.SCHEDULE, draft.kind)
        assertTrue(draft.title.contains("team", ignoreCase = true))
        assertEquals(14, hourOf(draft.startEpochMillis))
        val tomorrow = startOfDayMillis(System.currentTimeMillis() + 86_400_000L)
        assertTrue(calendar.observeEventsForDay("test-user", tomorrow).first().isEmpty())
        val confirmed = preFilter.confirmDraft("test-user", draft)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdEvent)
        assertEquals(14, hourOf(handled.createdEvent!!.startEpochMillis))
        assertTrue(handled.createdEvent.title.contains("team", ignoreCase = true))
    }

    @Test
    fun `reminder draft creates nothing until confirmed`() = runTest {
        val result = preFilter.tryHandle(
            // NOTE: no "call" verb here — "remind me to call mom"
            // routes to TASK by design (the call-task shortcut runs
            // before the reminder shortcut; a call to a person is a
            // task). "buy milk" reaches the REMINDER branch.
            "test-user", "remind me to buy milk at 6pm", emptyList(), emptyList()
        )
        assertTrue(result is PreFilterResult.PendingDraft)
        val draft = (result as PreFilterResult.PendingDraft).draft
        assertEquals(DraftKind.REMINDER, draft.kind)
        assertTrue(draft.title.contains("milk", ignoreCase = true))
        assertEquals(18, hourOf(draft.startEpochMillis))
        assertTrue(tasks.getAllTasksOnce("test-user").isEmpty())
        val confirmed = preFilter.confirmDraft("test-user", draft)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdTask)
        assertEquals(18, hourOf(handled.createdTask!!.dueDateEpochMillis))
    }

    @Test
    fun `event fallback drafts the appointment and creates it only on confirm`() = runTest {
        // NOTE: reaching step 4 needs a genuine matcher MISS (score
        // < 0.30). Short English phrases with a time token all score
        // 0.42+ — "Gym session tomorrow at 6am" (0.46) and "Workout
        // tomorrow at 6am" (0.45) are both caught by the bank (step 3).
        // This Bengali phrase scores 0.27 on a faithful replica of
        // IntentBankMatcher (TF-IDF cosine, same tokenize + nukta fold),
        // so the matcher abstains and the step-4 fallback fires. The
        // "gym" keeps hasActionContext true. "বিকেলে" (with ে) matters:
        // bare "বিকেল" is NOT a bengaliDaypartHour key, so
        // isBareHourToken treats "৪টায়" as a bare hour and step 4 asks
        // instead of drafting — "বিকেলে" resolves it to 16:00. Title
        // keeps "আগামীকাল": step 4's \b-anchored Bengali strip does not
        // match on the JVM (Robolectric) — pre-existing quirk.
        val result = preFilter.tryHandle(
            "test-user", "আগামীকাল বিকেলে ৪টায় সাঁতার কাটতে যাব gym-এ বন্ধুর সাথে", emptyList(), emptyList()
        )
        assertTrue(result is PreFilterResult.PendingDraft)
        val draft = (result as PreFilterResult.PendingDraft).draft
        assertEquals(DraftKind.SCHEDULE, draft.kind)
        // Step 4 (not step 3): an EVENT on the Personal calendar.
        assertEquals("EVENT", draft.scheduleType)
        assertEquals("Personal", draft.scheduleLocation)
        assertTrue(draft.title.contains("সাঁতার"))
        assertEquals(16, hourOf(draft.startEpochMillis))
        val tomorrow = startOfDayMillis(System.currentTimeMillis() + 86_400_000L)
        assertTrue(calendar.observeEventsForDay("test-user", tomorrow).first().isEmpty())
        val confirmed = preFilter.confirmDraft("test-user", draft)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdEvent)
        assertEquals(16, hourOf(handled.createdEvent!!.startEpochMillis))
        assertTrue(handled.createdEvent.title.contains("সাঁতার"))
    }

    @Test
    fun `catch-all drafts the task and creates it only on confirm`() = runTest {
        val result = preFilter.tryHandle("test-user", "Go for petrol", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.PendingDraft)
        val draft = (result as PreFilterResult.PendingDraft).draft
        assertEquals(DraftKind.TASK, draft.kind)
        assertTrue(draft.title.contains("petrol", ignoreCase = true))
        assertTrue(tasks.getAllTasksOnce("test-user").isEmpty())
        val confirmed = preFilter.confirmDraft("test-user", draft)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdTask)
        assertTrue(handled.createdTask!!.title.contains("petrol", ignoreCase = true))
    }

    @Test
    fun `thirty minute reminder drafts before creating`() = runTest {
        val before = System.currentTimeMillis()
        val result = preFilter.tryHandle(
            "test-user", "Remind me in 30 minutes", emptyList(), emptyList()
        )
        assertTrue(result is PreFilterResult.PendingDraft)
        val draft = (result as PreFilterResult.PendingDraft).draft
        assertEquals(DraftKind.TASK, draft.kind)
        assertEquals("Follow-up reminder", draft.title)
        assertTrue(draft.reminderAtDue)
        assertTrue(tasks.getAllTasksOnce("test-user").isEmpty())
        val confirmed = preFilter.confirmDraft("test-user", draft)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdTask)
        val dueInMinutes = (handled.createdTask!!.dueDateEpochMillis - before) / 60_000L
        assertTrue(dueInMinutes in 29..31)
    }

    @Test
    fun `clarification answer completes through the draft path`() = runTest {
        val asked = preFilter.tryHandle("test-user", "call tasnim at 5", emptyList(), emptyList())
        assertTrue(asked is PreFilterResult.Handled)
        assertTrue((asked as PreFilterResult.Handled).responseText.contains("AM or PM"))
        assertTrue(tasks.getAllTasksOnce("test-user").isEmpty())
        // The hour answer produces a DRAFT — not a saved task.
        val drafted = preFilter.tryHandle("test-user", "5 PM", emptyList(), emptyList())
        assertTrue(drafted is PreFilterResult.PendingDraft)
        val draft = (drafted as PreFilterResult.PendingDraft).draft
        assertEquals(DraftKind.TASK, draft.kind)
        assertEquals(17, hourOf(draft.startEpochMillis))
        assertTrue(tasks.getAllTasksOnce("test-user").isEmpty())
        val confirmed = preFilter.confirmDraft("test-user", draft)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdTask)
        assertEquals(17, hourOf(handled.createdTask!!.dueDateEpochMillis))
    }

    @Test
    fun `guessed kind clarification completes through the draft path`() = runTest {
        val asked = preFilter.tryHandle("test-user", "buy milk 5", emptyList(), emptyList())
        assertTrue(asked is PreFilterResult.Handled)
        assertTrue((asked as PreFilterResult.Handled).responseText.contains("AM or PM"))
        val kindAsked = preFilter.tryHandle("test-user", "5 PM", emptyList(), emptyList())
        assertTrue(kindAsked is PreFilterResult.Handled)
        assertTrue((kindAsked as PreFilterResult.Handled).responseText.contains("Task or schedule"))
        assertTrue(tasks.getAllTasksOnce("test-user").isEmpty())
        val drafted = preFilter.tryHandle("test-user", "Task", emptyList(), emptyList())
        assertTrue(drafted is PreFilterResult.PendingDraft)
        val draft = (drafted as PreFilterResult.PendingDraft).draft
        assertEquals(DraftKind.TASK, draft.kind)
        assertEquals(17, hourOf(draft.startEpochMillis))
        assertTrue(tasks.getAllTasksOnce("test-user").isEmpty())
        val confirmed = preFilter.confirmDraft("test-user", draft)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdTask)
        assertEquals(17, hourOf(handled.createdTask!!.dueDateEpochMillis))
    }

    @Test
    fun `edited draft confirms with the edited values`() = runTest {
        val result = preFilter.tryHandle("test-user", "call tasnim at 5pm", emptyList(), emptyList())
        val draft = (result as PreFilterResult.PendingDraft).draft
        val edited = draft
            .withTitle("Call Tasnim about the launch")
            .withStartTime(draft.startEpochMillis + 60 * 60 * 1000L)
        val confirmed = preFilter.confirmDraft("test-user", edited)
        val handled = confirmed as PreFilterResult.Handled
        assertNotNull(handled.createdTask)
        assertEquals("Call Tasnim about the launch", handled.createdTask!!.title)
        assertEquals(18, hourOf(handled.createdTask.dueDateEpochMillis))
    }
}
