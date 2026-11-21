package com.nextgentool.nudgy.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

data class FocusTimeSlot(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val durationMinutes: Int,
    val label: String,
    val reason: String
)

data class ConflictResult(
    val hasConflict: Boolean,
    val conflictingEvent: CalendarEventEntity? = null,
    val suggestedAlternativeSlot: FocusTimeSlot? = null
)

/**
 * Sprint 2-1 — deterministic id for an imported Google occurrence.
 *
 * The old code minted a fresh UUID per import, so the same occurrence
 * imported twice (two concurrent syncs, or two devices) became two
 * different local rows — and two different Firestore docs. A
 * deterministic id makes the import idempotent: the same occurrence
 * always lands on the same local row and the same Firestore doc
 * (Room REPLACE + Firestore upsert both key off the id).
 */
fun googleImportId(googleEventId: Long, startEpochMillis: Long): String =
    "gcal-$googleEventId-$startEpochMillis"

/** Marker written into an exported event's Google description (§7). */
fun exportMarker(localId: String): String = "[nudgy:$localId]"

private val EXPORT_MARKER_REGEX = Regex("""\[nudgy:([^\]]+)]""")

/** Extracts the local id from a Google event description, or null. */
fun parseExportMarker(description: String?): String? =
    description?.let { EXPORT_MARKER_REGEX.find(it)?.groupValues?.getOrNull(1) }

class CalendarRepository(
    private val context: Context,
    private val database: NudgyDatabase = NudgyDatabase.getInstance(context),
    // Sprint 2-1 — test seams (see CalendarEventSync and
    // CalendarProviderGateway). Production passes nothing and gets the
    // real implementations; tests inject fakes so no Firebase client
    // and no device calendar provider are ever touched on the JVM.
    eventSyncParam: CalendarEventSync? = null,
    providerGatewayParam: CalendarProviderGateway? = null
) {
    private val calendarDao = database.calendarDao()
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val eventSync: CalendarEventSync =
        eventSyncParam ?: RealCalendarEventSync(context, repoScope)
    private val providerGateway: CalendarProviderGateway =
        providerGatewayParam ?: RealCalendarProviderGateway(context)
    private var snapshotListener: ListenerRegistration? = null
    /** Single-flight for Google sync (§7): two concurrent syncs must not
     *  import the same occurrence twice. */
    private val syncMutex = Mutex()

    private fun syncPrefs() =
        context.getSharedPreferences("nudgy_sync_prefs", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- C14(a) pending deletes
    //
    // Same store approach as tasks (C13): a local event delete records the
    // id here in the SAME step (SharedPreferences file "nudgy_pending_deletes",
    // no Room schema change); the entry is removed only when the Firestore
    // delete actually succeeds. startCloudSync drains this store BEFORE
    // attaching the listener, and the listener skips ids still pending, so
    // an offline/failed cloud delete can never resurrect the event.
    private fun pendingDeletesPrefs() =
        context.getSharedPreferences("nudgy_pending_deletes", Context.MODE_PRIVATE)

    private fun recordPendingEventDelete(userId: String, eventId: String) {
        val prefs = pendingDeletesPrefs()
        val updated = prefs.getStringSet(KEY_PENDING_EVENT_DELETES, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        // commit() (synchronous): the record must be durable before the
        // fire-and-forget cloud attempt that follows it.
        if (updated.add("$userId/$eventId")) {
            prefs.edit().putStringSet(KEY_PENDING_EVENT_DELETES, updated).commit()
        }
    }

    private fun removePendingEventDelete(userId: String, eventId: String) {
        val prefs = pendingDeletesPrefs()
        val updated = prefs.getStringSet(KEY_PENDING_EVENT_DELETES, emptySet())
            ?.toMutableSet() ?: return
        if (updated.remove("$userId/$eventId")) {
            prefs.edit().putStringSet(KEY_PENDING_EVENT_DELETES, updated).apply()
        }
    }

    private fun pendingEventDeleteIds(userId: String): Set<String> {
        val prefix = "$userId/"
        return pendingDeletesPrefs().getStringSet(KEY_PENDING_EVENT_DELETES, emptySet())
            .orEmpty().filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }.toSet()
    }

    /** Retry every pending event delete for this user. Bounded per item: an
     *  offline delete().await() would otherwise suspend until connectivity
     *  returns and stall the sync that follows. A timed-out/failed entry
     *  stays pending for the next drain; the listener guard keeps the event
     *  from resurrecting in the meantime. */
    private suspend fun drainPendingEventDeletes(userId: String) {
        if (userId == com.nextgentool.nudgy.auth.AuthManager.GUEST_USER_ID) return
        for (eventId in pendingEventDeleteIds(userId)) {
            // Sprint 2-1: the Firestore delete goes through the seam.
            val deleted = withTimeoutOrNull(PENDING_DELETE_TIMEOUT_MS) {
                try {
                    eventSync.deleteEventAwaited(userId, eventId)
                } catch (e: Exception) {
                    Log.w(TAG, "Pending event delete failed for $eventId: ${e.message}")
                    false
                }
            } ?: false
            if (deleted) {
                removePendingEventDelete(userId, eventId)
            } else {
                Log.w(TAG, "Pending event delete still outstanding for $eventId; will retry on next sync")
            }
        }
    }

    // ——— Sprint 2-1 (§7): pending local edits (§8 Q8(a)) ———
    // A local edit whose provider update failed (0 rows, no permission,
    // provider error) is recorded here and retried at the start of the
    // next sync. Until the retry succeeds, the import skips that row —
    // Google's copy is stale and must not overwrite the local edit.
    // One JSONObject per event id: {title, start, end, location, type}.

    private fun pendingEventUpdateKey(userId: String) = "pending_event_updates_$userId"

    private fun pendingEventUpdates(userId: String): Map<String, JSONObject> {
        val raw = syncPrefs().getString(pendingEventUpdateKey(userId), null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getJSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt pending event updates; dropping: ${e.message}")
            emptyMap()
        }
    }

    private fun pendingEventUpdateIds(userId: String): Set<String> = pendingEventUpdates(userId).keys

    private fun recordPendingEventUpdate(
        userId: String,
        eventId: String,
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        locationOrLink: String?,
        eventType: String
    ) {
        val json = JSONObject(syncPrefs().getString(pendingEventUpdateKey(userId), "{}") ?: "{}")
        json.put(
            eventId,
            JSONObject().apply {
                put("title", title)
                put("start", startEpochMillis)
                put("end", endEpochMillis)
                if (locationOrLink != null) put("location", locationOrLink)
                put("type", eventType)
            }
        )
        syncPrefs().edit().putString(pendingEventUpdateKey(userId), json.toString()).apply()
    }

    private fun removePendingEventUpdate(userId: String, eventId: String) {
        val json = JSONObject(syncPrefs().getString(pendingEventUpdateKey(userId), "{}") ?: "{}")
        if (json.has(eventId)) {
            json.remove(eventId)
            syncPrefs().edit().putString(pendingEventUpdateKey(userId), json.toString()).apply()
        }
    }

    /**
     * Retries every pending local edit against the provider. A success
     * clears the record; a failure keeps it (and the local row keeps the
     * edit — the next sync tries again). Called at the start of every
     * sync, BEFORE the import, so Google sees the edit before the
     * import can overwrite it with Google's stale copy.
     */
    private suspend fun drainPendingEventUpdates(userId: String) {
        for ((eventId, edit) in pendingEventUpdates(userId)) {
            val local = calendarDao.getEventByIdForUser(userId, eventId)
            val gId = local?.googleCalendarEventId
            if (gId == null) {
                // The row (or its Google link) is gone — nothing to retry.
                removePendingEventUpdate(userId, eventId)
                continue
            }
            val ok = updateGoogleEvent(
                googleEventId = gId,
                title = edit.optString("title"),
                startEpochMillis = edit.optLong("start"),
                endEpochMillis = edit.optLong("end"),
                location = edit.optString("location").takeIf { it.isNotEmpty() },
                eventType = edit.optString("type", "EVENT")
            )
            if (ok) {
                removePendingEventUpdate(userId, eventId)
            } else {
                Log.w(TAG, "Pending event update still outstanding for $eventId; will retry on next sync")
            }
        }
    }

    /**
     * Sprint 2-1 (§7) — one-time de-dup sweep of pre-deterministic
     * imported rows. Before this PR every import minted a fresh UUID,
     * so the same Google occurrence could sit in Room as two (or more)
     * rows with different ids. The sweep groups Google-linked rows by
     * (googleCalendarEventId, startEpochMillis) and merges each group
     * into ONE row under the deterministic id
     * `gcal-<googleId>-<start>`: the keeper is renamed (delete + insert
     * under the new id — Room ids are the primary key, there is no
     * rename), the extras are deleted locally AND their Firestore docs
     * are deleted, and the keeper's Firestore doc is uploaded under the
     * deterministic id. The Google event itself is NEVER touched.
     *
     * Runs once per user (prefs flag). Group rows are never imported
     * rows, so they are untouched; unlinked rows are untouched.
     */
    private suspend fun dedupImportedRowsOnce(userId: String) {
        val flagKey = "gcal_dedup_sweep_done_$userId"
        if (syncPrefs().getBoolean(flagKey, false)) return
        val rows = calendarDao.getAllEventsOnce(userId)
            .filter { it.googleCalendarEventId != null && it.groupId == null }
        val groups = rows.groupBy { it.googleCalendarEventId!! to it.startEpochMillis }
            .filterValues { it.size > 1 }
        for ((key, dupes) in groups) {
            val deterministicId = googleImportId(key.first, key.second)
            // Keeper: prefer the row that already has the deterministic
            // id; otherwise the earliest-created row (stable choice).
            val keeper = dupes.firstOrNull { it.id == deterministicId } ?: dupes.first()
            val extras = dupes.filter { it.id != keeper.id }
            if (keeper.id != deterministicId) {
                val renamed = keeper.copy(id = deterministicId)
                calendarDao.deleteEventByIdForUser(userId, keeper.id)
                calendarDao.insertEvent(renamed)
                syncEventToFirestore(userId, renamed)
                deleteEventFromFirestore(userId, keeper.id)
            }
            for (extra in extras) {
                calendarDao.deleteEventByIdForUser(userId, extra.id)
                deleteEventFromFirestore(userId, extra.id)
            }
            Log.i(TAG, "De-dup sweep merged ${dupes.size} rows for Google event ${key.first} into $deterministicId")
        }
        syncPrefs().edit().putBoolean(flagKey, true).apply()
    }

    /**
     * Deletes one Google Calendar event by provider id. Returns true only
     * when the provider actually removed a row — a 0 return means the event
     * was already gone, and is treated as failure by callers (C14).
     */
    private fun deleteGoogleEventById(googleEventId: Long): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot delete Google Calendar event $googleEventId: WRITE_CALENDAR not granted")
            return false
        }
        return try {
            val rows = providerGateway.deleteEvent(googleEventId)
            if (rows == 0) {
                Log.w(TAG, "Google Calendar delete removed 0 rows for event $googleEventId (already gone?)")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing Google Calendar event: ${e.message}")
            false
        }
    }

    fun observeEventsForDay(userId: String, dayMillis: Long): Flow<List<CalendarEventEntity>> {
        val (startOfDay, endOfDay) = getDayBounds(dayMillis)
        return calendarDao.getEventsForDayFlow(userId, startOfDay, endOfDay)
    }

    fun observeAllEvents(userId: String): Flow<List<CalendarEventEntity>> =
        calendarDao.getAllEventsFlow(userId)

    fun hasCalendarPermissions(): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    private fun getPrimaryGoogleCalendarId(): Long? {
        // Pick the calendar exports actually belong in: a WRITABLE Google
        // calendar, preferring the account's primary one. The old picker
        // returned the first calendar flagged primary OR Google — on many
        // phones that is the device-local calendar (primary-flagged, never
        // syncs to Google) or a read-only Google calendar (Holidays etc.),
        // so app-created events never reached Google Calendar.
        var fallbackId: Long? = null
        for (calendar in providerGateway.queryCalendars()) {
            if (calendar.accountType != "com.google") continue
            if (calendar.accessLevel < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
            if (calendar.isPrimary) return calendar.id
            if (fallbackId == null) fallbackId = calendar.id
        }
        return fallbackId
    }

    /**
     * Exports one event to the device Google Calendar and returns the
     * provider id, or null when it could not be exported.
     *
     * Sprint 2-1 (§7): [localId] writes the `[nudgy:<localId>]` marker
     * into the Google description. The marker is what makes an export
     * idempotent: if the app crashes after this insert but before the
     * local row is recorded, the next import parses the marker and
     * links (or re-creates) the local row instead of exporting a
     * second copy. Callers that pass no localId get the old
     * marker-less description (only the export retry and the
     * add/block paths pass one).
     */
    private fun exportEventToGoogleCalendar(
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        location: String?,
        eventType: String,
        localId: String? = null
    ): Long? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val calId = getPrimaryGoogleCalendarId() ?: return null
        val description = buildString {
            append("Scheduled via Nudgy ($eventType)")
            if (localId != null) append(" ").append(exportMarker(localId))
        }
        return providerGateway.insertEvent(
            ProviderEventValues(
                calendarId = calId,
                title = title,
                startEpochMillis = startEpochMillis,
                endEpochMillis = endEpochMillis,
                location = location,
                description = description
            )
        )
    }

    /**
     * Sprint 2-1 (§7) — single-flight sync. Two concurrent calls (e.g.
     * a sign-in trigger racing a manual "Sync now") must not import the
     * same occurrence twice: the mutex serialises them, and the second
     * call sees the first call's rows through the deterministic import
     * id. The `taskRepository` parameter is unused in the rebuild (kept
     * for the call sites that pass it).
     */
    suspend fun syncGoogleCalendar(userId: String, taskRepository: TaskRepository? = null): Int =
        syncMutex.withLock { runSync(userId) }

    private suspend fun runSync(userId: String): Int = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return@withContext 0
        }
        var syncedCount = 0
        val now = System.currentTimeMillis()
        val searchStart = now - (7 * 24 * 60 * 60 * 1000L) // past 7 days
        val searchEnd = now + (30 * 24 * 60 * 60 * 1000L) // next 30 days

        // Sprint 2-1b — NO calendar is excluded: holiday calendars
        // (e.g. "Holidays in United States") import exactly like any
        // other calendar. The old exclusion dropped every Thanksgiving /
        // Veterans Day / Election Day, so the sync looked broken on
        // device (ci-604). All-day events render as labels at the top of
        // the day (CalendarScreen) and never block a slot (checkConflict
        // / findSuggestedFocusSlot filter them out below).

        // Query Instances (not Events) so recurring series expand into
        // their individual occurrences inside the window. No selection:
        // deleted events simply have no instances in this view, and the
        // reconciliation below keys off their absence.
        try {
            // Remote occurrences, keyed by (google event id, occurrence start)
            // so each occurrence of a recurring series is tracked separately.
            val remote = LinkedHashMap<Pair<Long, Long>, CalendarEventEntity>()
            // Sprint 2-1 (§7): marker local ids, keyed the same way —
            // the Google description of our own exports carries
            // [nudgy:<localId>], which links the occurrence to its row.
            val markerByKey = HashMap<Pair<Long, Long>, String>()
            for (instance in providerGateway.queryInstances(searchStart, searchEnd)) {
                val gId = instance.googleEventId
                val rawTitle = instance.title ?: "Untitled Event"
                val start = instance.beginEpochMillis
                var end = instance.endEpochMillis
                if (end <= start) end = start + (45 * 60 * 1000L)
                val loc = instance.location
                val isAllDay = instance.allDay
                parseExportMarker(instance.description)?.let { markerId ->
                    markerByKey[gId to start] = markerId
                }

                remote[gId to start] = CalendarEventEntity(
                    id = "",
                    userId = userId,
                    title = rawTitle,
                    startEpochMillis = start,
                    endEpochMillis = end,
                    locationOrLink = loc ?: "Google Calendar",
                    eventType = if (rawTitle.contains("Focus", ignoreCase = true) || rawTitle.contains("Deep work", ignoreCase = true)) "FOCUS_TIME" else "MEETING",
                    isAllDay = isAllDay,
                    googleCalendarEventId = gId,
                    isFromGoogleCalendar = true
                )
            }

            // Sprint 2-1 (§7): before importing, (1) retry any local
            // edits the provider rejected earlier — Google must see them
            // before the import overwrites local rows with Google's copy;
            // (2) merge pre-deterministic UUID duplicates into the
            // deterministic id, so the import below never sees two rows
            // for one occurrence. The reads below are fresh, after both.
            drainPendingEventUpdates(userId)
            dedupImportedRowsOnce(userId)

            val existingEvents = calendarDao.getAllEventsFlow(userId).first()
            val existingByKey = existingEvents
                .filter { it.googleCalendarEventId != null }
                .associateBy { it.googleCalendarEventId!! to it.startEpochMillis }
            val existingById = existingEvents.associateBy { it.id }
            // Rows with a pending edit: Google's copy is stale — the
            // import must NOT overwrite the local edit with it.
            val pendingUpdateIds = pendingEventUpdateIds(userId)

            // Import new occurrences; update ones whose details changed on
            // the Google side (title / end / location / all-day). Google is
            // truth — except for rows with a pending local edit.
            for ((key, remoteEvent) in remote) {
                val markerId = markerByKey[key]
                var local: CalendarEventEntity? = null
                if (markerId != null) {
                    // Our own export (marker in the Google description):
                    // link to the local row it belongs to, or re-create
                    // that row when the app crashed before the insert.
                    val linked = existingById[markerId]
                    if (linked == null) {
                        val recovered = remoteEvent.copy(id = markerId)
                        calendarDao.insertEvent(recovered)
                        syncEventToFirestore(userId, recovered)
                        syncedCount++
                        continue
                    }
                    local = if (linked.googleCalendarEventId == null) {
                        val attached = linked.copy(
                            googleCalendarEventId = key.first,
                            isFromGoogleCalendar = true
                        )
                        calendarDao.insertEvent(attached)
                        syncEventToFirestore(userId, attached)
                        attached
                    } else {
                        linked
                    }
                } else {
                    local = existingByKey[key]
                }
                if (local == null) {
                    // Legacy dedupe only against UNLINKED local events (e.g.
                    // exported before ids were tracked). A different Google
                    // event that merely shares title+minute is never swallowed.
                    val legacyDup = existingEvents.any { e ->
                        e.googleCalendarEventId == null &&
                        e.groupId == null &&
                        e.title.equals(remoteEvent.title, ignoreCase = true) &&
                        Math.abs(e.startEpochMillis - remoteEvent.startEpochMillis) < 60_000L
                    }
                    if (!legacyDup) {
                        // Sprint 2-1 (§7): deterministic id — the same
                        // occurrence imported twice lands on the same row.
                        val newEntity = remoteEvent.copy(id = googleImportId(key.first, key.second))
                        calendarDao.insertEvent(newEntity)
                        syncEventToFirestore(userId, newEntity)
                        syncedCount++
                    }
                } else if (local.id !in pendingUpdateIds &&
                    (local.title != remoteEvent.title ||
                    local.endEpochMillis != remoteEvent.endEpochMillis ||
                    local.locationOrLink != remoteEvent.locationOrLink ||
                    local.isAllDay != remoteEvent.isAllDay)
                ) {
                    val updated = local.copy(
                        title = remoteEvent.title,
                        endEpochMillis = remoteEvent.endEpochMillis,
                        locationOrLink = remoteEvent.locationOrLink,
                        isAllDay = remoteEvent.isAllDay
                    )
                    calendarDao.insertEvent(updated)
                    syncEventToFirestore(userId, updated)
                }
            }

            // Deletion reconciliation runs only after one fully successful
            // sync on this device. Right after a reinstall, restored cloud
            // events can land before the phone's own calendar provider has
            // finished syncing; reconciling against that still-empty remote
            // view would delete healthy events (and their cloud docs).
            val syncSeenKey = "gcal_sync_completed_$userId"
            val hasSyncedBefore = syncPrefs().getBoolean(syncSeenKey, false)

            // Reconcile deletions: a Google-linked local row inside the synced
            // window whose occurrence no longer exists on the device calendar
            // was deleted (or its series was deleted) in Google Calendar —
            // remove it here too. Rows outside the window are never touched.
            if (hasSyncedBefore) {
                existingEvents.filter { local ->
                    local.googleCalendarEventId != null &&
                    local.startEpochMillis in searchStart..searchEnd &&
                    (local.googleCalendarEventId!! to local.startEpochMillis) !in remote.keys
                }.forEach { stale ->
                    calendarDao.deleteEventByIdForUser(userId, stale.id)
                    deleteEventFromFirestore(userId, stale.id)
                }
            }
            // Export retry: app-created events that never reached Google
            // (export failed or permission was missing at creation) carry no
            // Google id — push them now and record the id, so the two-way
            // sync heals itself on the next sync.
            try {
                // Group v1: group schedules must NEVER reach Google Calendar —
                // without the groupId == null clause every class schedule
                // would land in every member's personal Google Calendar.
                calendarDao.getAllEventsOnce(userId)
                    .filter { it.googleCalendarEventId == null && !it.isFromGoogleCalendar && it.groupId == null }
                    .forEach { local ->
                        // Sprint 2-1 (§7): the marker links this export
                        // to the local row, so a crash before the id is
                        // recorded still heals instead of duplicating.
                        val gId = exportEventToGoogleCalendar(
                            title = local.title,
                            startEpochMillis = local.startEpochMillis,
                            endEpochMillis = local.endEpochMillis,
                            location = local.locationOrLink,
                            eventType = local.eventType,
                            localId = local.id
                        )
                        if (gId != null) {
                            val updated = local.copy(
                                googleCalendarEventId = gId,
                                isFromGoogleCalendar = true
                            )
                            calendarDao.insertEvent(updated)
                            syncEventToFirestore(userId, updated)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Export retry failed: ${e.message}")
            }
            syncPrefs().edit().putBoolean(syncSeenKey, true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing Google Calendar into Nudgy: ${e.message}")
        }
        syncedCount
    }

    suspend fun addEvent(
        userId: String,
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        locationOrLink: String? = "Personal",
        eventType: String = "EVENT"
    ): CalendarEventEntity = withContext(Dispatchers.IO) {
        // Sprint 2-1 (§7): the local id is minted BEFORE the export so
        // the Google description can carry the [nudgy:<id>] marker. If
        // the app crashes between the export and the local insert, the
        // next import parses the marker and re-creates this row instead
        // of exporting a second copy.
        val localId = UUID.randomUUID().toString()
        val googleEventId = exportEventToGoogleCalendar(
            title = title,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            location = locationOrLink,
            eventType = eventType,
            localId = localId
        )

        val event = CalendarEventEntity(
            id = localId,
            userId = userId,
            title = title,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            locationOrLink = locationOrLink,
            eventType = eventType,
            googleCalendarEventId = googleEventId,
            isFromGoogleCalendar = googleEventId != null
        )
        calendarDao.insertEvent(event)
        syncEventToFirestore(userId, event)
        event
    }

    /**
     * Sprint 2-1 (Q8(a)) — edits a local event and pushes the edit to
     * Google Calendar by provider id.
     *
     * Returns true when the edit is fully applied (local + provider, or
     * local-only when there is no Google link yet — the export retry
     * heals the link). Returns false only when the provider update
     * failed: the local row keeps the new values AND the Google id, the
     * edit is recorded as a pending update (retried on the next sync),
     * and the caller must inform the user. Group rows are never edited
     * from Calendar (they belong to the Groups tab) — false, no write.
     */
    suspend fun updateEvent(
        userId: String,
        eventId: String,
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        locationOrLink: String?,
        eventType: String
    ): Boolean = withContext(Dispatchers.IO) {
        val local = calendarDao.getEventByIdForUser(userId, eventId) ?: return@withContext false
        if (local.groupId != null) return@withContext false
        val updated = local.copy(
            title = title,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            locationOrLink = locationOrLink,
            eventType = eventType
        )
        // Optimistic local update first: the user's edit is never lost
        // to a provider failure.
        calendarDao.insertEvent(updated)
        syncEventToFirestore(userId, updated)
        val gId = local.googleCalendarEventId ?: run {
            // No Google link yet: export now (with the marker). If the
            // export fails the row stays unlinked and the export retry
            // heals it on the next sync — the edit itself already saved.
            val newGId = exportEventToGoogleCalendar(
                title = title,
                startEpochMillis = startEpochMillis,
                endEpochMillis = endEpochMillis,
                location = locationOrLink,
                eventType = eventType,
                localId = local.id
            )
            if (newGId != null) {
                val linked = updated.copy(
                    googleCalendarEventId = newGId,
                    isFromGoogleCalendar = true
                )
                calendarDao.insertEvent(linked)
                syncEventToFirestore(userId, linked)
            }
            return@withContext true
        }
        if (updateGoogleEvent(gId, title, startEpochMillis, endEpochMillis, locationOrLink, eventType)) {
            removePendingEventUpdate(userId, eventId)
            true
        } else {
            recordPendingEventUpdate(
                userId = userId,
                eventId = eventId,
                title = title,
                startEpochMillis = startEpochMillis,
                endEpochMillis = endEpochMillis,
                locationOrLink = locationOrLink,
                eventType = eventType
            )
            Log.w(TAG, "updateEvent: Google provider update failed for $gId; edit kept locally, will retry on next sync")
            false
        }
    }

    /**
     * Updates one Google Calendar event by provider id. Returns true
     * only when the provider actually changed a row — 0 rows means the
     * event is gone or read-only, and the edit must be retried later,
     * not silently dropped.
     *
     * DESCRIPTION is intentionally untouched: it may carry the
     * [nudgy:<localId>] marker (our exports) or the user's own text
     * (imports) — either way, overwriting it here would destroy
     * information the import relies on.
     */
    private fun updateGoogleEvent(
        googleEventId: Long,
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        location: String?,
        eventType: String
    ): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val rows = providerGateway.updateEvent(
                googleEventId,
                ProviderEventValues(
                    title = title,
                    startEpochMillis = startEpochMillis,
                    endEpochMillis = endEpochMillis,
                    location = location
                )
            )
            if (rows == 0) {
                Log.w(TAG, "Google Calendar update changed 0 rows for event $googleEventId")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Google Calendar event: ${e.message}")
            false
        }
    }

    suspend fun deleteEvent(eventId: String, userId: String? = null) = withContext(Dispatchers.IO) {
        val target = calendarDao.getEventById(eventId)
        // Group v1: group schedules are deleted from the shared group item by
        // the creator (GroupRepository). A member-side delete is local-only —
        // never touch Google Calendar or the personal events collection.
        if (target?.groupId != null) {
            val effectiveUserId = userId ?: target.userId
            calendarDao.deleteEventByIdForUser(effectiveUserId, eventId)
            return@withContext
        }
        target?.googleCalendarEventId?.let { gId ->
            // C14: check the provider delete's return value — 0 rows means
            // the Google event was NOT removed, and the next Google sync
            // would re-import it and undo this delete.
            if (!deleteGoogleEventById(gId)) {
                Log.w(TAG, "deleteEvent: Google Calendar event $gId was not removed; it may be re-imported by the next Google sync")
            }
        }
        val effectiveUserId = userId ?: target?.userId
        if (effectiveUserId != null) {
            calendarDao.deleteEventByIdForUser(effectiveUserId, eventId)
            deleteEventFromFirestore(effectiveUserId, eventId)
        } else {
            calendarDao.deleteEventById(eventId)
        }
    }

    suspend fun blockFocusTime(
        userId: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        title: String = "Deep work session"
    ): CalendarEventEntity = withContext(Dispatchers.IO) {
        // Sprint 2-1 (§7): the local id is minted BEFORE the export so
        // the Google description can carry the [nudgy:<id>] marker.
        val localId = UUID.randomUUID().toString()
        val googleEventId = exportEventToGoogleCalendar(
            title = title,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            location = "Focus Time",
            eventType = "FOCUS_TIME",
            localId = localId
        )

        val event = CalendarEventEntity(
            id = localId,
            userId = userId,
            title = title,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            locationOrLink = "Focus time",
            eventType = "FOCUS_TIME",
            googleCalendarEventId = googleEventId,
            isFromGoogleCalendar = googleEventId != null
        )
        calendarDao.insertEvent(event)
        syncEventToFirestore(userId, event)
        event
    }

    suspend fun checkConflict(
        userId: String,
        startEpochMillis: Long,
        endEpochMillis: Long
    ): ConflictResult = withContext(Dispatchers.IO) {
        val events = calendarDao.getAllEventsFlow(userId).first()
        // Sprint 2-1b — all-day events are labels, not blocks: a
        // 00:00–24:00 holiday must never count as a conflict.
        val conflict = events.firstOrNull { event ->
            !event.isAllDay &&
                event.startEpochMillis < endEpochMillis && event.endEpochMillis > startEpochMillis
        }

        if (conflict != null) {
            val altStart = conflict.endEpochMillis
            val altEnd = altStart + (endEpochMillis - startEpochMillis)
            val tf = SimpleDateFormat("h:mm a", Locale.getDefault())
            val altLabel = "${tf.format(java.util.Date(altStart))} – ${tf.format(java.util.Date(altEnd))}"
            ConflictResult(
                hasConflict = true,
                conflictingEvent = conflict,
                suggestedAlternativeSlot = FocusTimeSlot(
                    startEpochMillis = altStart,
                    endEpochMillis = altEnd,
                    durationMinutes = ((altEnd - altStart) / (60 * 1000)).toInt(),
                    label = altLabel,
                    reason = "Next available open slot"
                )
            )
        } else {
            ConflictResult(hasConflict = false)
        }
    }

    suspend fun checkAndSeedInitialEvents(userId: String) = withContext(Dispatchers.IO) {
        // Production: New users start with a clean schedule; events sync from real Google Calendar or manual creation.
    }

    suspend fun findSuggestedFocusSlot(userId: String, dayMillis: Long, busyIntervals: List<Pair<Long, Long>> = emptyList()): FocusTimeSlot? = withContext(Dispatchers.IO) {
        val (startOfDay, endOfDay) = getDayBounds(dayMillis)
        // Busy timeline = calendar events + any extra busy intervals (the
        // caller passes timed tasks, so a suggested slot never collides with
        // a task even though tasks are not shown on the schedule).
        // Sprint 2-1b — all-day events are labels, not blocks: a
        // 00:00–24:00 holiday would otherwise leave the day with no
        // suggested focus slot at all.
        val events = (calendarDao.getEventsForDayFlow(userId, startOfDay, endOfDay).first()
            .filter { !it.isAllDay }
            .map { it.startEpochMillis to it.endEpochMillis } +
            busyIntervals.filter { it.first in startOfDay..endOfDay })
            .sortedBy { it.first }

        val tf = SimpleDateFormat("h:mm a", Locale.getDefault())
        val now = System.currentTimeMillis()
        val isToday = now in startOfDay..endOfDay

        fun computeNextFutureSlot(baseTime: Long): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = maxOf(baseTime, now + 10 * 60 * 1000L) }
            val m = cal.get(Calendar.MINUTE)
            val rounded = if (m < 30) 30 else 0
            if (rounded == 0) cal.add(Calendar.HOUR_OF_DAY, 1)
            cal.set(Calendar.MINUTE, rounded)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        if (events.isEmpty()) {
            val slotStart = if (isToday) {
                computeNextFutureSlot(now)
            } else {
                val baseCal = Calendar.getInstance().apply { timeInMillis = dayMillis }
                baseCal.set(Calendar.HOUR_OF_DAY, 14)
                baseCal.set(Calendar.MINUTE, 0)
                baseCal.set(Calendar.SECOND, 0)
                baseCal.set(Calendar.MILLISECOND, 0)
                baseCal.timeInMillis
            }
            val slotEnd = slotStart + (45 * 60 * 1000L)
            val formattedSlot = "${tf.format(java.util.Date(slotStart))} – ${tf.format(java.util.Date(slotEnd))}"
            return@withContext FocusTimeSlot(
                startEpochMillis = slotStart,
                endEpochMillis = slotEnd,
                durationMinutes = 45,
                label = formattedSlot,
                reason = "45 minutes free in your open schedule"
            )
        }

        // Check for gap between busy blocks (only consider gaps that are in the future)
        for (i in 0 until events.size - 1) {
            val currentEnd = events[i].second
            val nextStart = events[i + 1].first
            val gapMillis = nextStart - currentEnd
            val candidateStart = currentEnd + (5 * 60 * 1000L)
            if (gapMillis >= 35 * 60 * 1000L && (!isToday || candidateStart > now)) {
                val slotStart = candidateStart
                val duration = minOf(((gapMillis - (5 * 60 * 1000L)) / (60 * 1000L)).toInt(), 45)
                val slotEnd = slotStart + (duration * 60 * 1000L)
                val formattedSlot = "${tf.format(java.util.Date(slotStart))} – ${tf.format(java.util.Date(slotEnd))}"
                return@withContext FocusTimeSlot(
                    startEpochMillis = slotStart,
                    endEpochMillis = slotEnd,
                    durationMinutes = duration,
                    label = formattedSlot,
                    reason = "$duration minutes free between meetings"
                )
            }
        }

        // Fallback: after the last busy block of the day or upcoming future window
        val rawStart = events.last().second + (15 * 60 * 1000L)
        val slotStart = if (isToday && rawStart <= now) computeNextFutureSlot(now) else rawStart
        val slotEnd = slotStart + (45 * 60 * 1000L)
        val formattedSlot = "${tf.format(java.util.Date(slotStart))} – ${tf.format(java.util.Date(slotEnd))}"
        return@withContext FocusTimeSlot(
            startEpochMillis = slotStart,
            endEpochMillis = slotEnd,
            durationMinutes = 45,
            label = formattedSlot,
            reason = "45 minutes free for deep focus"
        )
    }

    fun startCloudSync(userId: String) {
        if (userId == com.nextgentool.nudgy.auth.AuthManager.GUEST_USER_ID) return
        snapshotListener?.remove()
        // C14(a): drain pending event deletes BEFORE the listener attaches,
        // so its initial snapshot cannot resurrect an event whose cloud
        // delete failed. The drain is time-bounded; the listener
        // additionally skips any id still pending (see below).
        repoScope.launch {
            drainPendingEventDeletes(userId)
            try {
                val dbId = context.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                snapshotListener = firestore.collection("users")
                .document(userId)
                .collection("events")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Events cloud sync error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        repoScope.launch {
                            for (dc in snapshot.documentChanges) {
                                when (dc.type) {
                                    DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                        val doc = dc.document
                                        // C14(a): an event whose cloud delete is
                                        // still pending must never be re-inserted
                                        // by the initial snapshot (or any later one).
                                        if (doc.id in pendingEventDeleteIds(userId)) continue
                                        val title = doc.getString("title") ?: continue
                                        val start = doc.getLong("startEpochMillis") ?: continue
                                        val end = doc.getLong("endEpochMillis") ?: (start + 30 * 60 * 1000L)
                                        val location = doc.getString("locationOrLink")
                                        val eventType = doc.getString("eventType") ?: "EVENT"
                                        val isAllDay = doc.getBoolean("isAllDay") ?: false
                                        val gId = doc.getLong("googleCalendarEventId")
                                        // A cloud doc written without the Google
                                        // link (older payload) must not unlink a
                                        // locally linked event — otherwise the
                                        // export retry would duplicate it in
                                        // Google Calendar.
                                        val existingEvent = calendarDao.getEventById(doc.id)
                                        val effectiveGId = gId ?: existingEvent?.googleCalendarEventId
                                        val event = CalendarEventEntity(
                                            id = doc.id,
                                            userId = userId,
                                            title = title,
                                            startEpochMillis = start,
                                            endEpochMillis = end,
                                            locationOrLink = location,
                                            eventType = eventType,
                                            isAllDay = isAllDay,
                                            googleCalendarEventId = effectiveGId,
                                            isFromGoogleCalendar = doc.getBoolean("isFromGoogleCalendar") ?: existingEvent?.isFromGoogleCalendar ?: (effectiveGId != null),
                                            // Group v1: never let a personal-collection
                                            // upsert strip a group row's origin.
                                            groupId = existingEvent?.groupId
                                        )
                                        calendarDao.insertEvent(event)
                                    }
                                    DocumentChange.Type.REMOVED -> {
                                        // Group v1: a personal-doc delete must never
                                        // remove a group row sharing the id.
                                        val localRow = calendarDao.getEventById(dc.document.id)
                                        if (localRow?.groupId == null) {
                                            // C14(b): mirror deleteEvent — when the
                                            // local row carries a Google Calendar id,
                                            // delete that Google event too, or the
                                            // next Google sync re-imports it as a
                                            // brand-new event and undoes the delete.
                                            localRow?.googleCalendarEventId?.let { gId ->
                                                deleteGoogleEventById(gId)
                                            }
                                            calendarDao.deleteEventByIdForUser(userId, dc.document.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start calendar events sync: ${e.message}")
            }
        }
        // One-time backfill (per user, per device): events created while
        // cloud sync was unreachable (e.g. the retired applet database id
        // era) exist only locally. Re-push them all — the write is an
        // idempotent upsert by document id. The completion flag is set only
        // after EVERY upload has returned success; if any upload fails
        // (offline, Firestore error, app killed mid-upload) the flag stays
        // unset and the backfill retries on the next sync start. Without
        // the flag this would re-push everything on every sync start and
        // could resurrect events deleted on another device.
        val backfillKey = "events_backfill_v1_$userId"
        if (!syncPrefs().getBoolean(backfillKey, false)) {
            repoScope.launch {
                // Awaited per event: the flag below is set only if every
                // upload succeeded. A failure anywhere leaves the flag
                // unset, so the next startCloudSync retries the whole
                // backfill (re-pushing an already-uploaded event is a
                // harmless idempotent upsert by document id).
                var allSucceeded = true
                try {
                    // Group v1: group rows must never be pushed to the personal
                    // events collection (same rule as the export retry).
                    calendarDao.getAllEventsOnce(userId)
                        .filter { it.groupId == null }
                        .forEach { event ->
                            if (!uploadEventToFirestore(userId, event)) {
                                allSucceeded = false
                            }
                        }
                } catch (e: Exception) {
                    allSucceeded = false
                    Log.w(TAG, "Events backfill failed: ${e.message}")
                }
                if (allSucceeded) {
                    syncPrefs().edit().putBoolean(backfillKey, true).apply()
                } else {
                    Log.w(TAG, "Events backfill incomplete: flag left unset, will retry on next sync start")
                }
            }
        }
    }

    fun stopCloudSync() {
        snapshotListener?.remove()
        snapshotListener = null
    }

    // Fire-and-forget wrapper kept for the ordinary call sites
    // (Google Calendar import, addEvent, blockFocusTime, export retry):
    // their behaviour is unchanged — the upload runs in the background
    // and a failure is only logged. Only the backfill awaits outcomes
    // (see uploadEventToFirestore below and startCloudSync).
    private fun syncEventToFirestore(userId: String, event: CalendarEventEntity) {
        repoScope.launch { uploadEventToFirestore(userId, event) }
    }

    // Awaited single-event upload. Returns true only when the Firestore
    // write actually completed; false on any failure (including offline)
    // or when there is nothing to upload (guest user). Callers that need
    // to know the outcome — the backfill — must use this directly and
    // must never treat a launched coroutine as success.
    // Sprint 2-1: delegated to the CalendarEventSync seam (same
    // behaviour; tests inject a fake).
    private suspend fun uploadEventToFirestore(userId: String, event: CalendarEventEntity): Boolean =
        eventSync.uploadEventAwaited(userId, event)

    private fun deleteEventFromFirestore(userId: String, eventId: String) {
        if (userId == com.nextgentool.nudgy.auth.AuthManager.GUEST_USER_ID) return
        // C14(a): record the pending delete in the same step as the local
        // delete (this runs synchronously on the caller's IO thread, before
        // the fire-and-forget attempt below). Removed only when the cloud
        // delete actually succeeds.
        recordPendingEventDelete(userId, eventId)
        repoScope.launch {
            // Sprint 2-1: the Firestore delete goes through the seam.
            if (eventSync.deleteEventAwaited(userId, eventId)) {
                removePendingEventDelete(userId, eventId)
            } else {
                Log.w(TAG, "Delete event from Firestore failed: $eventId")
            }
        }
    }

    private fun getDayBounds(dayMillis: Long): Pair<Long, Long> {
        val calendar = Calendar.getInstance().apply { timeInMillis = dayMillis }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endOfDay = calendar.timeInMillis

        return Pair(startOfDay, endOfDay)
    }

    companion object {
        private const val TAG = "CalendarRepository"
        private const val KEY_PENDING_EVENT_DELETES = "pending_event_deletes"
        /** Per-item bound for a pending-delete retry (see drainPendingEventDeletes). */
        private const val PENDING_DELETE_TIMEOUT_MS = 10_000L
    }
}
