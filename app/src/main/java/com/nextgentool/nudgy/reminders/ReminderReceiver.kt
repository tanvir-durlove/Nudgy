package com.nextgentool.nudgy.reminders

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.focus.PomodoroAlarm
import com.nextgentool.nudgy.settings.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // Sprint 4-1 — the Pomodoro end signal: it carries a session
        // id, not a task id, so it is handled before the task-id
        // extraction below (which returns early without one).
        if (action == PomodoroAlarm.ACTION_POMODORO_END) {
            handlePomodoroEnd(context, intent)
            return
        }
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
                            // Repeat (Feature 6): keep firing every N minutes
                            // until the task is completed. Completing cancels
                            // the alarm (toggleTaskCompletion), and a fresh
                            // read here stops the chain the moment it is done.
                            scheduleNextRepeat(context, taskId, taskTitle)
                        } else {
                            // Signed out or a different account is active:
                            // never surface another account's reminder, and
                            // drop the stale alarm so it cannot fire again.
                            ReminderManager(context).cancelReminder(taskId)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ReminderReceiver", "Error showing reminder: ${e.message}")
                    } finally {
                        pendingResult?.finish()
                    }
                }
            }

            ReminderManager.ACTION_MARK_DONE -> {
                val notifId = ReminderManager.getRequestCode(taskId)
                notificationManager.cancel(notifId)
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repository = com.nextgentool.nudgy.data.repository.TaskRepository(context)
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
                        pendingResult?.finish()
                    }
                }
            }

            ReminderManager.ACTION_SNOOZE_30M -> {
                val notifId = ReminderManager.getRequestCode(taskId)
                notificationManager.cancel(notifId)
                // S1-3a: snooze is 10 minutes (spec). The ACTION_SNOOZE_30M
                // action name is historical (v1 used 30m); the string stays
                // for compat with in-flight PendingIntents.
                val snoozeTime = System.currentTimeMillis() + ReminderManager.SNOOZE_MILLIS
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repository = com.nextgentool.nudgy.data.repository.TaskRepository(context)
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
                        pendingResult?.finish()
                    }
                }
            }
        }
    }

    /**
     * Sprint 4-1 — the Pomodoro end signal (spec §4, Q2=(a), Q4=(a)).
     * Fires at the session's persisted planned end even when the app
     * is backgrounded or dead: the session is completed in Room (the
     * minutes count from that moment) and the end notification is
     * posted. Completing is idempotent — when the timer screen's
     * ticker already completed the session (app open), this only
     * posts the notification. A session that belongs to another
     * account, or one already cancelled, never notifies.
     */
    private fun handlePomodoroEnd(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(PomodoroAlarm.EXTRA_SESSION_ID) ?: return
        val kindName = intent.getStringExtra(PomodoroAlarm.EXTRA_SESSION_KIND)
            ?: FocusSessionKind.FOCUS.name
        val taskTitle = intent.getStringExtra(PomodoroAlarm.EXTRA_TASK_TITLE)
        val kind = runCatching { FocusSessionKind.valueOf(kindName) }
            .getOrDefault(FocusSessionKind.FOCUS)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = FocusHabitRepository(context)
                val session = repository.getSessionById(sessionId)
                if (session != null && session.userId == AuthManager.getActiveUserId(context)) {
                    val wasRunning = session.endedAtEpochMillis == null
                    // A cancelled session has an end time but is not
                    // completed: a stale alarm (e.g. one that survived
                    // a crash) must never complete or notify for it.
                    if (wasRunning) {
                        repository.completeSession(sessionId, System.currentTimeMillis())
                    }
                    // Notify when the session ran to its end — either
                    // just now, or already completed by the timer
                    // screen's ticker while the app was open.
                    if (wasRunning || session.completed) {
                        showPomodoroEndNotification(
                            context, notificationManager, sessionId, kind, taskTitle
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ReminderReceiver", "Error completing pomodoro session: ${e.message}")
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun showPomodoroEndNotification(
        context: Context,
        notificationManager: NotificationManager,
        sessionId: String,
        kind: FocusSessionKind,
        taskTitle: String?
    ) {
        // Sprint 6-2 — the builder lives in ReminderNotification now
        // (same text, same intent); the channel follows the persisted
        // Sound & Haptics toggle.
        val soundEnabled = SettingsPreferences.isSoundEnabled(context)
        val notification = ReminderNotification.pomodoroNotification(
            context, sessionId, kind, taskTitle, soundEnabled
        )
        notificationManager.notify(PomodoroAlarm.requestCode(sessionId), notification)
    }

    /**
     * Sprint 1-3a — repeat (Feature 6). After a reminder fires, schedule
     * its next fire at now + reminderRepeatMinutes — unless the task is
     * completed (or the interval is unset), in which case the chain ends.
     * The repeat alarm reuses the task's request code, so it replaces the
     * just-fired alarm; completing cancels it outright.
     */
    private suspend fun scheduleNextRepeat(context: Context, taskId: String, taskTitle: String) {
        try {
            val repository = com.nextgentool.nudgy.data.repository.TaskRepository(context)
            val task = repository.getTaskById(taskId) ?: return
            val next = ReminderManager.nextRepeatFireMillis(
                task.reminderRepeatMinutes,
                task.isCompleted,
                System.currentTimeMillis()
            ) ?: return
            ReminderManager(context).scheduleReminder(taskId, taskTitle, next)
        } catch (e: Exception) {
            android.util.Log.w("ReminderReceiver", "Error scheduling repeat: ${e.message}")
        }
    }

    /**
     * A reminder may only be shown or acted on when its task belongs to the
     * currently active account on this device.
     */
    private suspend fun isTaskVisibleToActiveUser(context: Context, taskId: String): Boolean {
        val repository = com.nextgentool.nudgy.data.repository.TaskRepository(context)
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
        // Sprint 6-2 — the builder lives in ReminderNotification now
        // (same title, actions and intent); the channel follows the
        // persisted Sound & Haptics toggle — OFF posts silently, the
        // notification itself is never suppressed.
        val soundEnabled = SettingsPreferences.isSoundEnabled(context)
        val notification = ReminderNotification.reminderNotification(
            context, taskId, taskTitle, soundEnabled
        )
        notificationManager.notify(ReminderManager.getRequestCode(taskId), notification)
    }
}
