package com.nextgentool.nudgy.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nextgentool.nudgy.data.local.NudgyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Automatically reschedules pending exact and inexact alarms following device reboot or app update.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val activeUserId = com.nextgentool.nudgy.auth.AuthManager.getActiveUserId(context)
            if (activeUserId.isBlank()) {
                Log.d(TAG, "No active user logged in on boot, skipping reminder restoration.")
                return
            }
            Log.d(TAG, "Device rebooted or package replaced ($action); restoring reminders for user $activeUserId...")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = NudgyDatabase.getInstance(context)
                    val reminderManager = ReminderManager(context)
                    val now = System.currentTimeMillis()
                    val tasks = db.taskDao().getPendingTasksWithRemindersForUser(activeUserId, now)
                    Log.d(TAG, "Found ${tasks.size} pending tasks to restore for active user")
                    for (task in tasks) {
                        val triggerTime = ReminderManager.dueReminderTriggerMillis(task.reminderEpochMillis, task.dueDateEpochMillis)
                        if (triggerTime > now) {
                            reminderManager.scheduleReminder(
                                taskId = task.id,
                                taskTitle = task.title,
                                triggerEpochMillis = triggerTime
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore reminders on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
