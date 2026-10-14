package com.example.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class ReminderManager(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nudgy Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Smart actionable reminders from Nudgy"
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun scheduleReminder(
        taskId: String,
        taskTitle: String,
        triggerEpochMillis: Long
    ): Boolean {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_REMINDER
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TASK_TITLE, taskTitle)
        }

        val requestCode = getRequestCode(taskId)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerEpochMillis,
                        pendingIntent
                    )
                } else {
                    Log.w("ReminderManager", "Exact alarms disallowed; using setAndAllowWhileIdle fallback")
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerEpochMillis,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerEpochMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerEpochMillis,
                    pendingIntent
                )
            }
            Log.d("ReminderManager", "Scheduled reminder for '$taskTitle' at $triggerEpochMillis")
            return true
        } catch (e: SecurityException) {
            Log.w("ReminderManager", "Exact alarm permission restricted: ${e.message}, executing fallback")
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerEpochMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerEpochMillis,
                        pendingIntent
                    )
                }
                true
            } catch (fallbackEx: Exception) {
                Log.e("ReminderManager", "Fallback alarm scheduling failed", fallbackEx)
                false
            }
        }
    }

    fun cancelReminder(taskId: String) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_REMINDER
        }
        val requestCode = getRequestCode(taskId)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    companion object {
        const val CHANNEL_ID = "nudgy_reminders_channel"
        const val ACTION_TRIGGER_REMINDER = "com.example.nudgy.ACTION_TRIGGER_REMINDER"
        const val ACTION_MARK_DONE = "com.example.nudgy.ACTION_MARK_DONE"
        const val ACTION_SNOOZE_30M = "com.example.nudgy.ACTION_SNOOZE_30M"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TASK_TITLE = "extra_task_title"

        /**
         * Generates a deterministic positive 31-bit request code to avoid negative PendingIntent IDs
         * and prevent cross-task collision overwrites.
         */
        fun getRequestCode(taskId: String): Int {
            return taskId.hashCode() and 0x7FFFFFFF
        }
    }
}
