package com.example.reminders

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.auth.AuthManager
import com.example.R
import com.example.data.local.NudgyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val taskId = intent.getStringExtra(ReminderManager.EXTRA_TASK_ID) ?: return
        val taskTitle = intent.getStringExtra(ReminderManager.EXTRA_TASK_TITLE) ?: "Task Reminder"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (action) {
            ReminderManager.ACTION_TRIGGER_REMINDER -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (isTaskVisibleToActiveUser(context, taskId)) {
                            showActionableNotification(context, notificationManager, taskId, taskTitle)
                        } else {
                            // Signed out or a different account is active:
                            // never surface another account's reminder, and
                            // drop the stale alarm so it cannot fire again.
                            ReminderManager(context).cancelReminder(taskId)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ReminderReceiver", "Error showing reminder: ${e.message}")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ReminderManager.ACTION_MARK_DONE -> {
                val notifId = ReminderManager.getRequestCode(taskId)
                notificationManager.cancel(notifId)
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repository = com.example.data.repository.TaskRepository(context)
                        val task = repository.getTaskById(taskId)
                        if (task != null && task.userId != AuthManager.getActiveUserId(context)) {
                            // Not the active account's task — ignore the action.
                        } else if (task != null) {
                            repository.toggleTaskCompletion(task.copy(isCompleted = false)) // sets to true and syncs
                        } else {
                            val db = NudgyDatabase.getInstance(context)
                            db.taskDao().setTaskCompletion(
                                id = taskId,
                                isCompleted = true,
                                completedAt = System.currentTimeMillis(),
                                now = System.currentTimeMillis()
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ReminderReceiver", "Error marking task done: ${e.message}")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ReminderManager.ACTION_SNOOZE_30M -> {
                val notifId = ReminderManager.getRequestCode(taskId)
                notificationManager.cancel(notifId)
                val snoozeTime = System.currentTimeMillis() + (30 * 60 * 1000L)
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repository = com.example.data.repository.TaskRepository(context)
                        val task = repository.getTaskById(taskId)
                        if (task == null || task.userId == AuthManager.getActiveUserId(context)) {
                            repository.snoozeTask(taskId, snoozeTime)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ReminderReceiver", "Error snoozing task: ${e.message}")
                        // Fallback purely via ReminderManager
                        val reminderManager = ReminderManager(context)
                        reminderManager.scheduleReminder(taskId, taskTitle, snoozeTime)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    /**
     * A reminder may only be shown or acted on when its task belongs to the
     * currently active account on this device.
     */
    private suspend fun isTaskVisibleToActiveUser(context: Context, taskId: String): Boolean {
        val repository = com.example.data.repository.TaskRepository(context)
        val task = repository.getTaskById(taskId) ?: return false
        val activeUserId = AuthManager.getActiveUserId(context)
        return activeUserId.isNotEmpty() && task.userId == activeUserId
    }

    private fun showActionableNotification(
        context: Context,
        notificationManager: NotificationManager,
        taskId: String,
        taskTitle: String
    ) {
        val notifId = ReminderManager.getRequestCode(taskId)

        // Tap to open app
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: "Done"
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

        // Action 2: "Snooze 30m"
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

        val notification = NotificationCompat.Builder(context, ReminderManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nudgy_notification)
            .setContentTitle(taskTitle)
            .setContentText("Time to get this done! 💪")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Done", donePendingIntent)
            .addAction(0, "Snooze 30m", snoozePendingIntent)
            .build()

        notificationManager.notify(notifId, notification)
    }
}
