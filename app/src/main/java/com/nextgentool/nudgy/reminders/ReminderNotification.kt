package com.nextgentool.nudgy.reminders

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nextgentool.nudgy.MainActivity
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.focus.PomodoroAlarm

/**
 * Sprint 6-2 — the reminder notification builders (spec §2 Q8=(a)).
 *
 * Extracted from ReminderReceiver so the sound gate is testable:
 * the builders pick the channel from the persisted Sound &
 * Haptics toggle — [ReminderManager.CHANNEL_ID] when sound is on,
 * the IMPORTANCE_LOW [ReminderManager.SILENT_CHANNEL_ID] when it
 * is off. The notification itself is never suppressed: with sound
 * off it still appears in the shade, just silently (no sound, no
 * vibration, no heads-up).
 */
object ReminderNotification {

    /** The channel a notification posts on — the whole sound gate, in one pure line. */
    fun channelFor(soundEnabled: Boolean): String =
        if (soundEnabled) ReminderManager.CHANNEL_ID else ReminderManager.SILENT_CHANNEL_ID

    /**
     * The actionable task reminder (Done / Snooze 10m) — the builder
     * code moved verbatim from ReminderReceiver.showActionableNotification,
     * with only the channel now chosen by [soundEnabled].
     */
    fun reminderNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        soundEnabled: Boolean
    ): Notification {
        val notifId = ReminderManager.getRequestCode(taskId)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderManager.ACTION_MARK_DONE
            putExtra(ReminderManager.EXTRA_TASK_ID, taskId)
        }
        val donePendingIntent = PendingIntent.getBroadcast(
            context,
            ReminderManager.getRequestCode(taskId + "_done"),
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderManager.ACTION_SNOOZE_30M
            putExtra(ReminderManager.EXTRA_TASK_ID, taskId)
            putExtra(ReminderManager.EXTRA_TASK_TITLE, taskTitle)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            ReminderManager.getRequestCode(taskId + "_snooze"),
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, channelFor(soundEnabled))
            .setSmallIcon(R.drawable.ic_nudgy_notification)
            .setContentTitle(taskTitle)
            .setContentText("Time to get this done! 💪")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Done", donePendingIntent)
            .addAction(0, "Snooze 10m", snoozePendingIntent)
            .build()
    }

    /**
     * The Pomodoro end notification — the builder code moved verbatim
     * from ReminderReceiver.showPomodoroEndNotification, with only
     * the channel now chosen by [soundEnabled] (a focus session
     * ending is a reminder too, so the same toggle silences it).
     */
    fun pomodoroNotification(
        context: Context,
        sessionId: String,
        kind: FocusSessionKind,
        taskTitle: String?,
        soundEnabled: Boolean
    ): Notification {
        val notifId = PomodoroAlarm.requestCode(sessionId)
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (title, text) = if (kind == FocusSessionKind.FOCUS) {
            "Focus session complete" to
                (taskTitle?.let { "Nice work on \"$it\" — time for a break." }
                    ?: "Nice work — time for a break.")
        } else {
            "Break over" to "Ready to focus?"
        }
        return NotificationCompat.Builder(context, channelFor(soundEnabled))
            .setSmallIcon(R.drawable.ic_nudgy_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }
}
