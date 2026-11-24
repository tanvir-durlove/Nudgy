package com.nextgentool.nudgy.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nextgentool.nudgy.MainActivity
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.todayDateString
import com.nextgentool.nudgy.reminders.ReminderManager
import com.nextgentool.nudgy.reminders.ReminderNotification
import com.nextgentool.nudgy.settings.SettingsPreferences
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Sprint 6-2 — the Morning Brief (spec §2 Q8=(a), §8).
 *
 * v1's "Morning Brief Alert" toggle never had a scheduler behind
 * it (the toggle itself did not even persist). This worker IS the
 * scheduler: a 24-hour periodic work whose first tick lands at the
 * next 8:00 AM device-local, posting today's summary as a local
 * notification. The toggle gates the POST, not the schedule — the
 * work stays enqueued (KEEP) and no-ops while OFF, so flipping
 * the toggle on needs no re-schedule and no WorkManager seam in
 * the Settings ViewModel. Scheduled once at app start
 * (MainActivity.onCreate, same KEEP pattern as GroupPollWorker).
 *
 * Best-effort by design: a failure logs and succeeds (tomorrow's
 * tick comes anyway); the brief never retries in a loop.
 */
class MorningBriefWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!SettingsPreferences.isMorningBriefEnabled(applicationContext)) {
                return Result.success()
            }
            // Creates both reminder channels (no-op when they exist).
            ReminderManager(applicationContext)
            val userId = AuthManager.getActiveUserId(applicationContext)
            if (userId.isBlank()) return Result.success()
            val repository = TaskRepository(applicationContext)
            val today = todayDateString()
            val plannedToday = repository.observeAllTasks(userId).first()
                .filter { it.plannedDate == today && !it.isCompleted }
            val text = morningBriefText(plannedToday.size, plannedToday.firstOrNull()?.title)
            val soundEnabled = SettingsPreferences.isSoundEnabled(applicationContext)
            val contentIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                applicationContext,
                MORNING_BRIEF_NOTIFICATION_ID,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(
                applicationContext,
                ReminderNotification.channelFor(soundEnabled)
            )
                .setSmallIcon(R.drawable.ic_nudgy_notification)
                .setContentTitle("Good morning")
                .setContentText(text)
                .setContentIntent(contentPendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            val notificationManager = applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(MORNING_BRIEF_NOTIFICATION_ID, notification)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("MorningBriefWorker", "Morning brief failed: ${e.message}")
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "nudgy_morning_brief"
        const val MORNING_BRIEF_NOTIFICATION_ID = 9001
        const val BRIEF_HOUR = 8

        /**
         * Millis from [nowMillis] until the next 8:00 AM device-local
         * (strictly after now — at exactly 8:00 the next brief is
         * tomorrow, 24h away). Pure — unit-tested without Android.
         * No java.time (minSdk 24, no desugaring): Calendar only.
         */
        fun initialDelayToNext8am(nowMillis: Long): Long {
            val next = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, BRIEF_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
            }
            return next.timeInMillis - now.timeInMillis
        }

        /** The summary line: count + the first task, or the empty-day line. Pure. */
        fun morningBriefText(taskCount: Int, firstTitle: String?): String =
            if (taskCount <= 0) {
                "Nothing planned for today yet — open Nudgy to plan your day."
            } else if (firstTitle.isNullOrBlank()) {
                "You have $taskCount ${if (taskCount == 1) "task" else "tasks"} planned for today."
            } else {
                "You have $taskCount ${if (taskCount == 1) "task" else "tasks"} planned for today. First up: $firstTitle."
            }

        /** The 24-hour periodic request (first tick at the next 8:00 AM). Pure. */
        fun buildRequest(nowMillis: Long): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<MorningBriefWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayToNext8am(nowMillis), TimeUnit.MILLISECONDS)
                .build()

        /** Enqueue (KEEP) the daily brief. Safe to call on every app start. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                buildRequest(System.currentTimeMillis())
            )
        }

        /** Cancel the daily brief (unused by the toggle — the worker no-ops while OFF). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
