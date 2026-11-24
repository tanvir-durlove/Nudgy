package com.nextgentool.nudgy.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.model.FocusSessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-2 — the reminder sound gate (spec §2 Q8=(a)).
 *
 * The gate is the channel: Sound ON posts on the HIGH-importance
 * reminder channel, Sound OFF on the LOW-importance silent twin —
 * the notification itself is never suppressed (both builds carry
 * the title, the text and (for reminders) the Done/Snooze actions).
 * The channel is the deterministic assertion: on API 26+ a
 * notification's sound comes from its channel, so the channel id
 * IS the sound behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `channelFor picks the sounding channel when on and the silent one when off`() {
        assertEquals(
            ReminderManager.CHANNEL_ID,
            ReminderNotification.channelFor(true)
        )
        assertEquals(
            ReminderManager.SILENT_CHANNEL_ID,
            ReminderNotification.channelFor(false)
        )
    }

    @Test
    fun `reminder posts on the sounding channel with both actions when sound is on`() {
        val notification = ReminderNotification.reminderNotification(
            context, "task-1", "Dentist", true
        )
        assertEquals(ReminderManager.CHANNEL_ID, notification.channelId)
        assertEquals("Dentist", notification.extras.getCharSequence("android.title"))
        assertEquals(2, notification.actions.size)
        assertEquals("Done", notification.actions[0].title)
        assertEquals("Snooze 10m", notification.actions[1].title)
    }

    @Test
    fun `reminder posts on the silent channel with both actions when sound is off`() {
        val notification = ReminderNotification.reminderNotification(
            context, "task-1", "Dentist", false
        )
        assertEquals(ReminderManager.SILENT_CHANNEL_ID, notification.channelId)
        assertEquals("Dentist", notification.extras.getCharSequence("android.title"))
        // The notification is still there — only the sound is gone.
        assertEquals(2, notification.actions.size)
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun `pomodoro end posts on the sounding channel when on, silent when off`() {
        val sounding = ReminderNotification.pomodoroNotification(
            context, "session-1", FocusSessionKind.FOCUS, "Dentist", true
        )
        assertEquals(ReminderManager.CHANNEL_ID, sounding.channelId)
        assertEquals(
            "Focus session complete",
            sounding.extras.getCharSequence("android.title")
        )

        val silent = ReminderNotification.pomodoroNotification(
            context, "session-1", FocusSessionKind.BREAK, null, false
        )
        assertEquals(ReminderManager.SILENT_CHANNEL_ID, silent.channelId)
        assertEquals("Break over", silent.extras.getCharSequence("android.title"))
        assertTrue(silent.actions == null || silent.actions.isEmpty())
    }
}
