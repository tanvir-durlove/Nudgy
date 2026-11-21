package com.nextgentool.nudgy.reminders

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.focus.PomodoroAlarm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sprint 4-1 — the Pomodoro end signal in the receiver (spec §4,
 * Q2=(a), Q4=(a)): the alarm fires, the session is completed in
 * Room, and the end notification is posted — even though the app
 * itself never ran. The receiver does its work on Dispatchers.IO,
 * so each test polls the database (up to 2s) instead of assuming
 * the coroutine finished with onReceive.
 *
 * The receiver builds its repository from the production singleton
 * (NudgyDatabase.getInstance), not an in-memory test DB, so this
 * class seeds through the singleton too. The singleton is NEVER
 * closed here (its static INSTANCE would stay closed for later
 * tests); isolation comes from a distinct user id per test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PomodoroReceiverTest {

    private lateinit var context: Context
    private lateinit var repository: FocusHabitRepository
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val db = NudgyDatabase.getInstance(context)
        repository = FocusHabitRepository(context, db)
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun useUser(userId: String) {
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", userId).apply()
    }

    private fun endIntent(sessionId: String, kind: FocusSessionKind, taskTitle: String?): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            action = PomodoroAlarm.ACTION_POMODORO_END
            putExtra(PomodoroAlarm.EXTRA_SESSION_ID, sessionId)
            putExtra(PomodoroAlarm.EXTRA_SESSION_KIND, kind.name)
            putExtra(PomodoroAlarm.EXTRA_TASK_TITLE, taskTitle)
        }

    /** Polls until the session is completed, or false after 2s. */
    private fun awaitCompleted(sessionId: String): Boolean {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            val done = runBlocking { repository.getSessionById(sessionId) }
            if (done?.completed == true) return true
            Thread.sleep(50)
        }
        return false
    }

    /** Polls until a notification with [title] appears, or false after 2s. */
    private fun awaitNotification(title: String): Boolean {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            if (postedTitles().contains(title)) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun postedTitles(): List<String> =
        shadowOf(notificationManager).allNotifications.mapNotNull {
            it.notification.extras.getString(Notification.EXTRA_TITLE)
        }

    @Test
    fun `a focus end completes the session and notifies take a break`() {
        val userId = "pomodoro-recv-focus"
        useUser(userId)
        val session = runBlocking {
            repository.startSession(userId, null, 25, FocusSessionKind.FOCUS)
        }
        ReminderReceiver().onReceive(context, endIntent(session.id, FocusSessionKind.FOCUS, "Call mom"))

        assertTrue(awaitCompleted(session.id))
        assertTrue(awaitNotification("Focus session complete"))
        assertEquals(25, runBlocking {
            repository.getFocusMinutesForDay(userId, System.currentTimeMillis())
        })
    }

    @Test
    fun `a break end notifies ready to focus and its minutes never count`() {
        val userId = "pomodoro-recv-break"
        useUser(userId)
        val session = runBlocking {
            repository.startSession(userId, null, 5, FocusSessionKind.BREAK)
        }
        ReminderReceiver().onReceive(context, endIntent(session.id, FocusSessionKind.BREAK, null))

        assertTrue(awaitCompleted(session.id))
        assertTrue(awaitNotification("Break over"))
        assertEquals(0, runBlocking {
            repository.getFocusMinutesForDay(userId, System.currentTimeMillis())
        })
    }

    @Test
    fun `an already-completed session still notifies but is not completed twice`() {
        val userId = "pomodoro-recv-done"
        useUser(userId)
        val session = runBlocking {
            repository.startSession(userId, null, 25, FocusSessionKind.FOCUS)
        }
        val completedAt = System.currentTimeMillis()
        runBlocking { repository.completeSession(session.id, completedAt) }
        ReminderReceiver().onReceive(context, endIntent(session.id, FocusSessionKind.FOCUS, null))

        assertTrue(awaitNotification("Focus session complete"))
        val after = runBlocking { repository.getSessionById(session.id) }
        assertNotNull(after)
        assertEquals(completedAt, after!!.endedAtEpochMillis)
    }

    @Test
    fun `a cancelled session never completes and never notifies`() {
        val userId = "pomodoro-recv-cancelled"
        useUser(userId)
        val session = runBlocking {
            repository.startSession(userId, null, 25, FocusSessionKind.FOCUS)
        }
        runBlocking { repository.cancelSession(session.id, System.currentTimeMillis()) }
        ReminderReceiver().onReceive(context, endIntent(session.id, FocusSessionKind.FOCUS, null))

        // Give the receiver's coroutine time to run, then assert nothing happened.
        Thread.sleep(500)
        val after = runBlocking { repository.getSessionById(session.id) }
        assertNotNull(after)
        assertFalse(after!!.completed)
        assertTrue(postedTitles().isEmpty())
    }

    @Test
    fun `a session from another account never completes and never notifies`() {
        val userId = "pomodoro-recv-other"
        useUser(userId)
        val session = runBlocking {
            repository.startSession("someone-else", null, 25, FocusSessionKind.FOCUS)
        }
        ReminderReceiver().onReceive(context, endIntent(session.id, FocusSessionKind.FOCUS, null))

        Thread.sleep(500)
        val after = runBlocking { repository.getSessionById(session.id) }
        assertNotNull(after)
        assertFalse(after!!.completed)
        assertTrue(postedTitles().isEmpty())
    }

    @Test
    fun `an unknown session id is ignored`() {
        useUser("pomodoro-recv-unknown")
        ReminderReceiver().onReceive(
            context, endIntent("no-such-session", FocusSessionKind.FOCUS, null)
        )
        Thread.sleep(300)
        assertTrue(postedTitles().isEmpty())
    }
}
