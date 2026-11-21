package com.nextgentool.nudgy.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.reminders.ReminderManager
import com.nextgentool.nudgy.reminders.ReminderReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Sprint 4-1 — the Pomodoro end alarm, as a seam (spec §4, Q2=(a)).
 *
 * The timer UI only counts down while the app is open (no foreground
 * service), but the session's planned end lives in Room and this alarm
 * fires at that instant even when the app is backgrounded or dead:
 * [ReminderReceiver] then completes the session and posts the
 * "time for a break" notification. Same exact-alarm pattern as
 * [ReminderManager] (setExactAndAllowWhileIdle + the inexact
 * fallback when exact alarms are off), same notification channel.
 *
 * The ViewModel depends on the interface, not the real AlarmManager,
 * so tests substitute a fake that records schedule/cancel calls
 * (PomodoroAlarmTest covers the real implementation under Robolectric
 * shadows instead). One alarm per session: the request code derives
 * from the session id, so scheduling a new end replaces any stale
 * one for that session.
 */
interface PomodoroAlarm {

    /**
     * Schedules the end signal for [sessionId] at [triggerEpochMillis].
     * Returns false only when the trigger is already in the past.
     */
    fun scheduleEnd(
        sessionId: String,
        kind: FocusSessionKind,
        taskTitle: String?,
        triggerEpochMillis: Long
    ): Boolean

    /** Cancels a scheduled end (pause / cancel / a superseded session). */
    fun cancel(sessionId: String)

    companion object {
        const val ACTION_POMODORO_END = "com.nextgentool.nudgy.ACTION_POMODORO_END"
        const val EXTRA_SESSION_ID = "extra_pomodoro_session_id"
        const val EXTRA_SESSION_KIND = "extra_pomodoro_session_kind"
        const val EXTRA_TASK_TITLE = "extra_pomodoro_task_title"

        /**
         * The notification id / alarm request code for a session.
         * "pomodoro-" prefixes the session id so a pomodoro alarm can
         * never collide with a task reminder's request code, even
         * though both go through the same deterministic hash.
         */
        fun requestCode(sessionId: String): Int =
            ReminderManager.getRequestCode("pomodoro-$sessionId")
    }
}

class RealPomodoroAlarm @Inject constructor(
    @ApplicationContext private val context: Context
) : PomodoroAlarm {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun scheduleEnd(
        sessionId: String,
        kind: FocusSessionKind,
        taskTitle: String?,
        triggerEpochMillis: Long
    ): Boolean {
        if (triggerEpochMillis <= System.currentTimeMillis()) {
            Log.w("PomodoroAlarm", "Cannot schedule pomodoro end in the past ($triggerEpochMillis)")
            return false
        }
        val pendingIntent = endPendingIntent(sessionId, kind, taskTitle)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerEpochMillis, pendingIntent
                    )
                } else {
                    Log.w("PomodoroAlarm", "Exact alarms disallowed; using setAndAllowWhileIdle fallback")
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerEpochMillis, pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerEpochMillis, pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP, triggerEpochMillis, pendingIntent
                )
            }
            Log.d("PomodoroAlarm", "Scheduled $kind end for session $sessionId at $triggerEpochMillis")
            true
        } catch (e: SecurityException) {
            Log.w("PomodoroAlarm", "Exact alarm permission restricted: ${e.message}, executing fallback")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerEpochMillis, pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP, triggerEpochMillis, pendingIntent
                    )
                }
                true
            } catch (fallbackEx: Exception) {
                Log.e("PomodoroAlarm", "Fallback alarm scheduling failed", fallbackEx)
                false
            }
        }
    }

    override fun cancel(sessionId: String) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            PomodoroAlarm.requestCode(sessionId),
            Intent(context, ReminderReceiver::class.java).apply {
                action = PomodoroAlarm.ACTION_POMODORO_END
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun endPendingIntent(
        sessionId: String,
        kind: FocusSessionKind,
        taskTitle: String?
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = PomodoroAlarm.ACTION_POMODORO_END
            putExtra(PomodoroAlarm.EXTRA_SESSION_ID, sessionId)
            putExtra(PomodoroAlarm.EXTRA_SESSION_KIND, kind.name)
            putExtra(PomodoroAlarm.EXTRA_TASK_TITLE, taskTitle)
        }
        return PendingIntent.getBroadcast(
            context,
            PomodoroAlarm.requestCode(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
