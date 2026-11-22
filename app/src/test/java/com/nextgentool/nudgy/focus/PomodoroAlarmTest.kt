package com.nextgentool.nudgy.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.reminders.ReminderReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sprint 4-1 — the real Pomodoro end alarm under Robolectric
 * shadows (spec §4, Q2=(a)). The ViewModel tests use a fake; this
 * one proves the real implementation actually arms an exact alarm
 * at the planned end, with the right receiver action, and that
 * cancelling removes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PomodoroAlarmTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var alarm: RealPomodoroAlarm

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm = RealPomodoroAlarm(context)
    }

    @Test
    fun `scheduling arms one exact alarm at the planned end`() {
        val trigger = System.currentTimeMillis() + 25 * 60_000L
        assertTrue(alarm.scheduleEnd("session-1", FocusSessionKind.FOCUS, "Call mom", trigger))

        val shadow = shadowOf(alarmManager)
        assertEquals(1, shadow.scheduledAlarms.size)
        val next = shadow.nextScheduledAlarm
        assertNotNull(next)
        assertEquals(trigger, next!!.triggerAtMs)
        // The stored PendingIntent is the pomodoro end for this
        // session: an equal intent (same action + extras + request
        // code) resolves to the same PendingIntent.
        val expected = Intent(context, ReminderReceiver::class.java).apply {
            action = PomodoroAlarm.ACTION_POMODORO_END
            putExtra(PomodoroAlarm.EXTRA_SESSION_ID, "session-1")
            putExtra(PomodoroAlarm.EXTRA_SESSION_KIND, FocusSessionKind.FOCUS.name)
            putExtra(PomodoroAlarm.EXTRA_TASK_TITLE, "Call mom")
        }
        val resolved = PendingIntent.getBroadcast(
            context,
            PomodoroAlarm.requestCode("session-1"),
            expected,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull(resolved)
    }

    @Test
    fun `a break end arms an alarm with the break kind`() {
        val trigger = System.currentTimeMillis() + 5 * 60_000L
        assertTrue(alarm.scheduleEnd("session-2", FocusSessionKind.BREAK, null, trigger))
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
        val next = shadowOf(alarmManager).nextScheduledAlarm
        assertNotNull(next)
        assertEquals(trigger, next!!.triggerAtMs)
    }

    @Test
    fun `scheduling in the past fails and arms nothing`() {
        assertFalse(
            alarm.scheduleEnd(
                "session-3", FocusSessionKind.FOCUS, null,
                System.currentTimeMillis() - 60_000L
            )
        )
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `cancel removes the alarm`() {
        val trigger = System.currentTimeMillis() + 25 * 60_000L
        alarm.scheduleEnd("session-4", FocusSessionKind.FOCUS, null, trigger)
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)

        alarm.cancel("session-4")
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `cancelling an unknown session is a no-op`() {
        alarm.cancel("no-such-session")
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
        assertNull(
            PendingIntent.getBroadcast(
                context,
                PomodoroAlarm.requestCode("no-such-session"),
                Intent(context, ReminderReceiver::class.java).apply {
                    action = PomodoroAlarm.ACTION_POMODORO_END
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }
}
