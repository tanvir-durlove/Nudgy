package com.nextgentool.nudgy.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.screens.ChatActionType
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tasks = TaskRepository(context, db)
        val calendar = CalendarRepository(context, db)
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

    @Test
    fun `explicit PM creates a task at 17_00`() = runTest {
        val result = preFilter.tryHandle("test-user", "call tasnim at 5pm", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.Handled)
        val handled = result as PreFilterResult.Handled
        assertNotNull(handled.createdTask)
        assertFalse(handled.responseText.contains("AM or PM"))
        assertEquals(17, hourOf(handled.createdTask!!.dueDateEpochMillis))
        assertTrue(handled.createdTask.title.contains("tasnim", ignoreCase = true))
    }

    @Test
    fun `explicit AM creates a task in the future`() = runTest {
        val result = preFilter.tryHandle("test-user", "call tasnim at 5am", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.Handled)
        val handled = result as PreFilterResult.Handled
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
    fun `block focus time creates the focus event`() = runTest {
        val result = preFilter.tryHandle("test-user", "block 45m focus", emptyList(), emptyList())
        assertTrue(result is PreFilterResult.Handled)
        val handled = result as PreFilterResult.Handled
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
        val created = preFilter.tryHandle("test-user", "call tasnim at 5pm", emptyList(), emptyList())
        assertTrue(created is PreFilterResult.Handled)
        val todayTasks = tasks.getAllTasksOnce("test-user")
        assertEquals(1, todayTasks.size)
        val result = preFilter.tryHandle("test-user", "show today's tasks", todayTasks, emptyList())
        assertTrue(result is PreFilterResult.Handled)
        assertTrue((result as PreFilterResult.Handled).responseText.contains("tasnim", ignoreCase = true))
    }

    @Test
    fun `complete then clear completed removes the task`() = runTest {
        preFilter.tryHandle("test-user", "call tasnim at 5pm", emptyList(), emptyList())
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
}
