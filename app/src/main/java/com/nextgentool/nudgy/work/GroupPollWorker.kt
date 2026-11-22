package com.nextgentool.nudgy.work

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
import com.nextgentool.nudgy.MainActivity
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.repository.GroupNotice
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.filterNewNotices
import com.nextgentool.nudgy.data.repository.groupPollSummaryText
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
 * - Sprint 5-3 — notices (feature 32) poll the same way, on their own
 *   watermark (createdAt) + a seen-ids set: notices are NOT stored
 *   locally, so the dedup lives here, not in Room (see
 *   [filterNewNotices] — the overlap window is shared with the query).
 *   The seen set is replaced with each tick's window, never grown:
 *   the next tick's window is a subset of this one, so only ids from
 *   this tick can ever reappear.
 * - Post ONE summary notification per group per tick, and only when
 *   the tick found genuinely new items and/or notices. Updates to
 *   already-known items refresh the lists silently (v1 scope — see dev
 *   log). No notification is posted when nothing new arrived, or on a
 *   baseline tick.
 * - Sprint 5-3 — the tap opens the GROUP DETAIL (not just the Groups
 *   tab): the launch intent carries the group id, MainActivity feeds
 *   it through NudgyApp, and the Groups destination opens the detail.
 * - Honest delay (spec §6, Q1=(a)): the 1-hour periodic tick plus
 *   the night skip means a notice/task arrives ~1 hour late in the
 *   day and ~3 hours late at night (Doze) — NEVER "instant". The
 *   device test measures the real delay; the code never claims less.
 * - Deletes are invisible to the updatedAt query, so the worker
 *   reconciles against the group's lastDeleteAt signal (minimization):
 *   it fetches the group's item ids (limit 500; the mobile SDKs have no
 *   field projection, so these are full docs) and deletes local group
 *   rows (cancelling their alarms) for ids no longer present — but ONLY
 *   when lastDeleteAt advanced past what the last reconcile processed.
 *   Deletes are rare, so this removes ~99% of reconcile reads. A group
 *   doc without the field (created before gating) falls back to the old
 *   at-most-daily reconcile. The foreground listener already reports
 *   REMOVED in real time while the app is open.
 * - Existence check, at most once per day per group (minimization): the
 *   app-wide group-doc listener purges on app open, so this check only
 *   matters while the app is dead. The same doc get carries the
 *   lastDeleteAt signal, so gating costs no extra read.
 * - Inactive pause (minimization): when the app has not been opened for
 *   30 days (INACTIVE_PAUSE_DAYS), the tick is skipped entirely — no
 *   queries, no lastPoll advance, and the periodic work is never
 *   cancelled, so the next tick re-checks and the next app open resumes
 *   polling.
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
        // Inactive pause (minimization): a user who has not opened the
        // app in 30 days gets no poll ticks — background reads with $0
        // revenue. SKIP only, never cancel: the next tick re-checks and
        // the next app open resumes polling. lastAppOpen == 0 means
        // "never recorded" (upgrade before this release) — fail open.
        val lastAppOpen = prefs.getLong(KEY_LAST_APP_OPEN, 0L)
        if (lastAppOpen > 0L && now - lastAppOpen > INACTIVE_PAUSE_MS) {
            return Result.success()
        }
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
        // Existence check, at most once per day per group (minimization):
        // the app-wide group-doc listener already purges on app open, so
        // this only matters while the app is dead. The same doc get also
        // carries the lastDeleteAt signal for the reconcile gate below,
        // so gating costs no extra read. A deleted group must never
        // resurrect: its items query could still return docs if the
        // creator's cascade is mid-flight, and the baseline tick would
        // otherwise leave the group row forever.
        val existenceKey = KEY_LAST_EXISTENCE_CHECK_PREFIX + groupId
        val deleteAtKey = KEY_LAST_DELETE_AT_PREFIX + groupId
        var lastDeleteAt = prefs.getLong(deleteAtKey, UNKNOWN_DELETE_AT)
        if (now - prefs.getLong(existenceKey, 0L) > EXISTENCE_CHECK_INTERVAL_MS) {
            val (exists, signal) = repo.getGroupDeleteSignal(groupId)
            if (!exists) {
                repo.purgeLocalGroup(uid, groupId)
                return
            }
            prefs.edit()
                .putLong(existenceKey, now)
                .putLong(deleteAtKey, signal)
                .apply()
            lastDeleteAt = signal
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
            val ts = doc.updatedAtEpochMillis
            if (ts != null && ts > maxUpdatedAt) maxUpdatedAt = ts
        }
        val newCount = repo.applyPolledDocs(uid, groupId, docs)
        if (docs.isNotEmpty()) {
            prefs.edit().putLong(lastSeenKey, maxUpdatedAt).apply()
        }
        // Sprint 5-3 — notices (feature 32): same watermark pattern,
        // separate watermark + seen set (notices are not stored
        // locally). The baseline tick watermarks without notifying —
        // pre-existing notices must never all notify at once.
        val noticeSeenKey = KEY_LAST_SEEN_NOTICE_PREFIX + groupId
        val noticeSeenIdsKey = KEY_SEEN_NOTICE_IDS_PREFIX + groupId
        val lastSeenNotice = prefs.getLong(noticeSeenKey, 0L)
        var newNotices: List<GroupNotice> = emptyList()
        if (lastSeenNotice == 0L) {
            prefs.edit().putLong(noticeSeenKey, now).apply()
        } else {
            val seenIds = prefs.getStringSet(noticeSeenIdsKey, emptySet()) ?: emptySet()
            val noticeDocs = repo.queryGroupNoticesSince(groupId, lastSeenNotice)
            newNotices = filterNewNotices(noticeDocs, lastSeenNotice, seenIds)
            if (noticeDocs.isNotEmpty()) {
                var maxCreatedAt = lastSeenNotice
                for (doc in noticeDocs) {
                    if (doc.createdAtEpochMillis > maxCreatedAt) {
                        maxCreatedAt = doc.createdAtEpochMillis
                    }
                }
                prefs.edit()
                    .putLong(noticeSeenKey, maxCreatedAt)
                    .putStringSet(noticeSeenIdsKey, noticeDocs.map { it.id }.toSet())
                    .apply()
            }
        }
        if (newCount > 0 || newNotices.isNotEmpty()) {
            val groupName = repo.getGroupById(groupId)?.name ?: "A group"
            postSummaryNotification(context, groupId, groupName, newCount, newNotices.size)
        }
        // Reconcile gating (minimization): the full id fetch runs only
        // when a delete actually happened — lastDeleteAt advanced past
        // what the last reconcile processed. Unknown signal (pre-gating
        // group doc, field absent) keeps the old daily cadence as a safe
        // fallback instead of never reconciling.
        val reconcileKey = KEY_LAST_RECONCILE_PREFIX + groupId
        val processedKey = KEY_LAST_PROCESSED_DELETE_AT_PREFIX + groupId
        val lastProcessed = prefs.getLong(processedKey, 0L)
        val shouldReconcile = if (lastDeleteAt == UNKNOWN_DELETE_AT) {
            now - prefs.getLong(reconcileKey, 0L) > RECONCILE_INTERVAL_MS
        } else {
            lastDeleteAt > lastProcessed
        }
        if (shouldReconcile) {
            val remoteIds = repo.queryGroupItemIds(groupId).toSet()
            repo.reconcileGroupItems(uid, groupId, remoteIds)
            prefs.edit()
                .putLong(reconcileKey, now)
                .putLong(processedKey, lastDeleteAt.coerceAtLeast(0L))
                .apply()
        }
    }

    private fun postSummaryNotification(
        context: Context,
        groupId: String,
        groupName: String,
        newCount: Int,
        newNotices: Int = 0
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
        // Sprint 5-3 — the tap opens the GROUP DETAIL: the launch
        // intent carries the group id; MainActivity feeds it through
        // NudgyApp (onCreate for the fresh activity CLEAR_TASK
        // creates, onNewIntent for the singleTop re-tap) and the
        // Groups destination opens the detail and consumes it.
        // The channel stays the worker's own groups channel
        // (nudgy_groups_channel): group updates are not reminders,
        // and the v1 item notifications already live here — moving
        // them to the reminders channel would split one group's
        // notifications across two system channels.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_OPEN_GROUP_ID, groupId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            groupId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = groupPollSummaryText(newCount, newNotices)
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

        /**
         * Sprint 5-3 — intent extra: the notified group's id. The tap
         * opens that group's detail (MainActivity → NudgyApp → Groups
         * destination). Replaces the v1 EXTRA_OPEN_GROUPS boolean,
         * which nothing ever read (the tap only ever opened the app).
         */
        const val EXTRA_OPEN_GROUP_ID = "nudgy_open_group_id"

        private const val PREFS_NAME = "group_poll_prefs"
        private const val KEY_LAST_POLL = "last_poll_epoch_ms"
        private const val KEY_LAST_SEEN_PREFIX = "last_seen_"
        private const val KEY_LAST_SEEN_NOTICE_PREFIX = "last_seen_notice_"
        private const val KEY_SEEN_NOTICE_IDS_PREFIX = "seen_notice_ids_"
        private const val KEY_LAST_RECONCILE_PREFIX = "last_reconcile_"
        private const val KEY_LAST_EXISTENCE_CHECK_PREFIX = "last_existence_check_"
        private const val KEY_LAST_DELETE_AT_PREFIX = "last_delete_at_"
        private const val KEY_LAST_PROCESSED_DELETE_AT_PREFIX = "last_processed_delete_at_"
        private const val KEY_LAST_APP_OPEN = "last_app_open_epoch_ms"

        /** Unknown delete signal: field absent (pre-gating group doc) or read failed. */
        private const val UNKNOWN_DELETE_AT = -1L

        /** Existence check cadence (minimization): at most daily per group. */
        private const val EXISTENCE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /** Days without an app open after which polling pauses (minimization). */
        const val INACTIVE_PAUSE_DAYS = 30
        private const val INACTIVE_PAUSE_MS =
            INACTIVE_PAUSE_DAYS * 24L * 60L * 60L * 1000L

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

        /** Records an app open (call from MainActivity.onResume). Drives
         * the 30-day inactive poll pause; a fresh timestamp resumes
         * polling on the next tick. */
        fun recordAppOpen(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_APP_OPEN, System.currentTimeMillis()).apply()
        }

        /** Clears one group's poll watermarks (leave/delete/purge path),
         * so a later rejoin gets a fresh baseline tick, not a flood. */
        fun clearGroupState(context: Context, groupId: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_LAST_SEEN_PREFIX + groupId)
                .remove(KEY_LAST_SEEN_NOTICE_PREFIX + groupId)
                .remove(KEY_SEEN_NOTICE_IDS_PREFIX + groupId)
                .remove(KEY_LAST_RECONCILE_PREFIX + groupId)
                .remove(KEY_LAST_EXISTENCE_CHECK_PREFIX + groupId)
                .remove(KEY_LAST_DELETE_AT_PREFIX + groupId)
                .remove(KEY_LAST_PROCESSED_DELETE_AT_PREFIX + groupId)
                .apply()
        }

        /** Cancel the periodic poll (sign-out path only). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
