package com.example.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.local.NudgyDatabase
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
            Log.d(TAG, "Device rebooted or package replaced ($action); rescheduling active reminders...")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = NudgyDatabase.getInstance(context)
                    val reminderManager = ReminderManager(context)
                    val now = System.currentTimeMillis()
                    val tasks = db.taskDao().getPendingTasksWithReminders(now)
                    Log.d(TAG, "Found ${tasks.size} pending tasks to restore")
                    for (task in tasks) {
                        val triggerTime = task.reminderEpochMillis ?: task.dueDateEpochMillis
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
