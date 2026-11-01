package com.example.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.auth.AuthManager
import com.example.data.repository.GroupRepository
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Group v1 polling worker (Phase 1, plan §3.5).
 *
 * Schedule: 1-hour periodic work (ExistingPeriodicWorkPolicy.KEEP, so
 * re-scheduling on every sign-in never duplicates it). The worker itself
 * enforces the day/night rule: during the proposed night window
 * (11 PM–7 AM device-local — PROPOSAL from plan §3.5, to confirm at
 * device test) it returns early unless the last real poll was >= 3h ago.
 * The 15-minute option from the plan is the same worker with a 15-minute
 * interval; it is NOT the default (daytime default is 1 hour per the
 * 2026-10-24 polling-schedule decision).
 *
 * Per joined group, per tick:
 * - Baseline: a group with no stored watermark gets lastSeen = now and
 *   no query — pre-existing items must never all notify at once.
 * - Otherwise query items with updatedAt > lastSeen − 60s (overlap
 *   window: batch writes share server timestamps, so a watermark landing
 *   on a shared timestamp could otherwise skip items; re-delivery is
 *   harmless because Room upserts REPLACE and alarm scheduling is
 *   idempotent for the same trigger).
 * - Advance lastSeen to the max returned updatedAt only when docs were
 *   returned. When the query is empty, lastSeen is left alone: advancing
 *   it to device-now could skip items whose server timestamps lag a
 *   fast device clock, and an empty re-query costs at most 1 read.
 * - Post ONE summary notification per group per tick, and only when the
 *   tick inserted genuinely new item ids. Updates to already-known items
 *   refresh the lists silently (v1 scope — see dev log). No notification
 *   is posted when nothing new arrived, or on the baseline tick.
 * - Deletes are invisible to the updatedAt query, so at most once per
 *   day per group the worker reconciles: it fetches the group's item ids
 *   (limit 500; the mobile SDKs have no field projection, so these are
 *   full docs — bounded by the limit and the daily cadence) and deletes
 *   local group rows (cancelling their alarms) for ids no longer present.
 *   Creator deletes are rare; daily reconciliation is enough, and the
 *   foreground listener already reports REMOVED in real time while the
 *   app is open.
 *
 * This schedule affects ONLY new-item discovery. Reminders for group
 * tasks at their scheduled time fire via the exact-alarm path once the
 * item is on the device, independent of polling.
 *
 * Guest handling: the worker no-ops for guests (no signed-in uid) —
 * cheap return, no queries. Scheduling is KEEP-policy: sign-in
 * re-schedules, sign-out cancels (see NudgyMainFlow). The worker is
 * never cancelled on activity destroy, so polling survives the app
 * being swiped away.
 *
 * Notification permission: if POST_NOTIFICATIONS was not granted,
 * NotificationManager drops the post silently (no crash). The app
 * requests the permission at startup; the worker does not re-request
 * it from the background.
 */
class GroupPollWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val uid = AuthManager.getActiveUserId(context)
        if (uid.isBlank() || uid == AuthManager.GUEST_USER_ID) {
            return Result.success()
        }
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastPoll = prefs.getLong(KEY_LAST_POLL, 0L)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNight = hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
        if (isNight && lastPoll > 0L && now - lastPoll < NIGHT_MIN_INTERVAL_MS) {
            // Night skip: do NOT advance lastPoll, so the next hourly
            // tick re-checks against the same watermark.
            return Result.success()
        }
        prefs.edit().putLong(KEY_LAST_POLL, now).apply()
        val repo = GroupRepository(context)
        for (groupId in repo.getJoinedGroupIds()) {
            try {
                pollGroup(context, repo, prefs, uid, groupId, now)
            } catch (e: Exception) {
                // One group's failure must not kill the other groups'
                // ticks. The next hourly tick retries; never Result.retry()
                // here, or a persistent error would backoff-storm.
                Log.w(TAG, "Poll failed for group $groupId: ${e.message}")
            }
        }
        return Result.success()
    }

    private suspend fun pollGroup(
        context: Context,
        repo: GroupRepository,
        prefs: android.content.SharedPreferences,
        uid: String,
        groupId: String,
        now: Long
    ) {
        // A deleted group must never resurrect: its items query could
        // still return docs if the creator's cascade is mid-flight, and
        // the baseline tick would otherwise leave the group row forever.
        if (!repo.groupDocExists(groupId)) {
            repo.purgeLocalGroup(uid, groupId)
            return
        }
        val lastSeenKey = KEY_LAST_SEEN_PREFIX + groupId
        val lastSeen = prefs.getLong(lastSeenKey, 0L)
        if (lastSeen == 0L) {
            // Baseline tick for this group: watermark now, no query,
            // no notification. Items already in the group predate the
            // member's polling and appear in lists via the listener.
            prefs.edit().putLong(lastSeenKey, now).apply()
            return
        }
        val docs = repo.queryGroupItemsSince(groupId, lastSeen)
        var maxUpdatedAt = lastSeen
        for (doc in docs) {
            val ts = doc.getTimestamp("updatedAt")?.toDate()?.time
            if (ts != null && ts > maxUpdatedAt) maxUpdatedAt = ts
        }
        val newCount = repo.applyPolledDocs(uid, groupId, docs)
        if (docs.isNotEmpty()) {
            prefs.edit().putLong(lastSeenKey, maxUpdatedAt).apply()
        }
        if (newCount > 0) {
            val groupName = repo.getGroupById(groupId)?.name ?: "A group"
            postSummaryNotification(context, groupId, groupName, newCount)
        }
        val reconcileKey = KEY_LAST_RECONCILE_PREFIX + groupId
        if (now - prefs.getLong(reconcileKey, 0L) > RECONCILE_INTERVAL_MS) {
            val remoteIds = repo.queryGroupItemIds(groupId).toSet()
            repo.reconcileGroupItems(uid, groupId, remoteIds)
            prefs.edit().putLong(reconcileKey, now).apply()
        }
    }

    private fun postSummaryNotification(
        context: Context,
        groupId: String,
        groupName: String,
        newCount: Int
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Nudgy Groups",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Updates from your groups"
                }
            )
        }
        // Tap opens the Groups tab. CLEAR_TASK guarantees a fresh
        // MainActivity that reads the extra in onCreate (the activity
        // has no onNewIntent routing); the trade-off is that tapping
        // resets the back stack.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_OPEN_GROUPS, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            groupId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (newCount == 1) "1 new item" else "$newCount new items"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nudgy_notification)
            .setContentTitle(groupName)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // Stable per-group id: a later tick replaces the previous summary.
        notificationManager.notify(groupId.hashCode(), notification)
    }

    companion object {
        private const val TAG = "GroupPollWorker"

        /** Unique periodic work name (plan §3.5). */
        const val UNIQUE_WORK_NAME = "nudgy_group_poll"

        /** Summary-notification channel (plan §3.5). */
        const val CHANNEL_ID = "nudgy_groups_channel"

        /** Intent extra: notification tap opens the Groups tab. */
        const val EXTRA_OPEN_GROUPS = "nudgy_open_groups"

        private const val PREFS_NAME = "group_poll_prefs"
        private const val KEY_LAST_POLL = "last_poll_epoch_ms"
        private const val KEY_LAST_SEEN_PREFIX = "last_seen_"
        private const val KEY_LAST_RECONCILE_PREFIX = "last_reconcile_"

        /** Proposed night window, device-local (plan §3.5 proposal). */
        private const val NIGHT_START_HOUR = 23
        private const val NIGHT_END_HOUR = 7
        private const val NIGHT_MIN_INTERVAL_MS = 3L * 60L * 60L * 1000L
        private const val RECONCILE_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /** Enqueue (KEEP) the 1-hour periodic poll. Safe to call on every sign-in. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GroupPollWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Clears one group's poll watermarks (leave/delete/purge path),
         * so a later rejoin gets a fresh baseline tick, not a flood. */
        fun clearGroupState(context: Context, groupId: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_LAST_SEEN_PREFIX + groupId)
                .remove(KEY_LAST_RECONCILE_PREFIX + groupId)
                .apply()
        }

        /** Cancel the periodic poll (sign-out path only). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
