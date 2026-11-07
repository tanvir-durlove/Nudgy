package com.example.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.NudgyDatabase
import com.example.data.local.entity.CalendarEventEntity
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
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

class CalendarRepository(
    private val context: Context,
    private val database: NudgyDatabase = NudgyDatabase.getInstance(context)
) {
    private val calendarDao = database.calendarDao()
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var snapshotListener: ListenerRegistration? = null

    private fun syncPrefs() =
        context.getSharedPreferences("nudgy_sync_prefs", Context.MODE_PRIVATE)

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
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val uri = CalendarContract.Calendars.CONTENT_URI
        var fallbackId: Long? = null
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val typeIndex = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                val accessIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)

                while (cursor.moveToNext()) {
                    if (idIndex < 0) continue
                    val acctType = if (typeIndex >= 0) cursor.getString(typeIndex) else null
                    if (acctType != "com.google") continue
                    val access = if (accessIndex >= 0) cursor.getInt(accessIndex) else 0
                    if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    val calId = cursor.getLong(idIndex)
                    val isPrimary = if (primaryIndex >= 0) cursor.getInt(primaryIndex) == 1 else false
                    if (isPrimary) return calId
                    if (fallbackId == null) fallbackId = calId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding primary Google Calendar ID: ${e.message}")
        }
        return fallbackId
    }

    private fun exportEventToGoogleCalendar(
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        location: String?,
        eventType: String
    ): Long? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val calId = getPrimaryGoogleCalendarId() ?: return null
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startEpochMillis)
                put(CalendarContract.Events.DTEND, endEpochMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (!location.isNullOrBlank()) {
                    put(CalendarContract.Events.EVENT_LOCATION, location)
                }
                put(CalendarContract.Events.DESCRIPTION, "Scheduled via Nudgy ($eventType)")
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val insertedId = uri?.lastPathSegment?.toLongOrNull()
            Log.d(TAG, "Successfully exported event '$title' to Google Calendar, ID: $insertedId")
            insertedId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export event to Google Calendar: ${e.message}")
            null
        }
    }

    suspend fun syncGoogleCalendar(userId: String, taskRepository: TaskRepository? = null): Int = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return@withContext 0
        }
        var syncedCount = 0
        val now = System.currentTimeMillis()
        val searchStart = now - (7 * 24 * 60 * 60 * 1000L) // past 7 days
        val searchEnd = now + (30 * 24 * 60 * 60 * 1000L) // next 30 days

        // Query Instances (not Events) so recurring series expand into their
        // individual occurrences inside the window.
        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            appendPath(searchStart.toString())
            appendPath(searchEnd.toString())
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID
        )
        // No selection: deleted events simply have no instances in this
        // view, and the reconciliation below keys off their absence.
        val selection: String? = null

        // Holiday calendars (e.g. "Holidays in Bangladesh") are read-only
        // Google calendars whose events would otherwise pollute the
        // schedule. Exclude them by calendar id: the name contains
        // "holiday" AND access is below contributor, so writable calendars
        // are never treated as holidays, even if oddly named. If this
        // lookup fails, the set stays empty and sync behaves as before.
        val holidayCalendarIds = mutableSetOf<Long>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
                ),
                null, null, null
            )?.use { calCursor ->
                val hIdIndex = calCursor.getColumnIndex(CalendarContract.Calendars._ID)
                val hNameIndex = calCursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val hAccessIndex = calCursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                while (calCursor.moveToNext()) {
                    if (hIdIndex < 0) continue
                    val hName = if (hNameIndex >= 0) calCursor.getString(hNameIndex) else null
                    val hAccess = if (hAccessIndex >= 0) calCursor.getInt(hAccessIndex) else 0
                    if (hName != null && hName.contains("holiday", ignoreCase = true) &&
                        hAccess < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                    ) {
                        holidayCalendarIds.add(calCursor.getLong(hIdIndex))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding holiday calendars: ${e.message}")
        }

        try {
            // Remote occurrences, keyed by (google event id, occurrence start)
            // so each occurrence of a recurring series is tracked separately.
            val remote = LinkedHashMap<Pair<Long, Long>, CalendarEventEntity>()
            context.contentResolver.query(
                instancesUri,
                projection,
                selection,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val calIdIndex = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
                val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndex(CalendarContract.Instances.END)
                val locIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val allDayIndex = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)

                while (cursor.moveToNext()) {
                    val gId = cursor.getLong(idIndex)
                    if (calIdIndex >= 0 && cursor.getLong(calIdIndex) in holidayCalendarIds) continue
                    val rawTitle = cursor.getString(titleIndex) ?: "Untitled Event"
                    val start = cursor.getLong(beginIndex)
                    var end = cursor.getLong(endIndex)
                    if (end <= start) end = start + (45 * 60 * 1000L)
                    val loc = if (locIndex >= 0) cursor.getString(locIndex) else null
                    val isAllDay = if (allDayIndex >= 0) cursor.getInt(allDayIndex) == 1 else false

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
            }

            val existingEvents = calendarDao.getAllEventsFlow(userId).first()
            val existingByKey = existingEvents
                .filter { it.googleCalendarEventId != null }
                .associateBy { it.googleCalendarEventId!! to it.startEpochMillis }

            // Import new occurrences; update ones whose details changed on the
            // Google side (title / end / location / all-day).
            for ((key, remoteEvent) in remote) {
                val local = existingByKey[key]
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
                        val newEntity = remoteEvent.copy(id = UUID.randomUUID().toString())
                        calendarDao.insertEvent(newEntity)
                        syncEventToFirestore(userId, newEntity)
                        syncedCount++
                    }
                } else if (local.title != remoteEvent.title ||
                    local.endEpochMillis != remoteEvent.endEpochMillis ||
                    local.locationOrLink != remoteEvent.locationOrLink ||
                    local.isAllDay != remoteEvent.isAllDay
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
                        val gId = exportEventToGoogleCalendar(
                            title = local.title,
                            startEpochMillis = local.startEpochMillis,
                            endEpochMillis = local.endEpochMillis,
                            location = local.locationOrLink,
                            eventType = local.eventType
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
        val googleEventId = exportEventToGoogleCalendar(
            title = title,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            location = locationOrLink,
            eventType = eventType
        )

        val event = CalendarEventEntity(
            id = UUID.randomUUID().toString(),
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
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val deleteUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, gId)
                    context.contentResolver.delete(deleteUri, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing Google Calendar event: ${e.message}")
                }
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
        val googleEventId = exportEventToGoogleCalendar(
            title = title,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            location = "Focus Time",
            eventType = "FOCUS_TIME"
        )

        val event = CalendarEventEntity(
            id = UUID.randomUUID().toString(),
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
        val conflict = events.firstOrNull { event ->
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
        val events = (calendarDao.getEventsForDayFlow(userId, startOfDay, endOfDay).first()
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
        if (userId == com.example.auth.AuthManager.GUEST_USER_ID) return
        snapshotListener?.remove()
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
    private suspend fun uploadEventToFirestore(userId: String, event: CalendarEventEntity): Boolean {
        if (userId == com.example.auth.AuthManager.GUEST_USER_ID) return true
        return try {
            val dbId = context.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(dbId)
            val payload = hashMapOf<String, Any>(
                "id" to event.id,
                "userId" to event.userId,
                "title" to event.title,
                "startEpochMillis" to event.startEpochMillis,
                "endEpochMillis" to event.endEpochMillis,
                "eventType" to event.eventType,
                "isAllDay" to event.isAllDay,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (event.locationOrLink != null) payload["locationOrLink"] = event.locationOrLink
            event.googleCalendarEventId?.let { payload["googleCalendarEventId"] = it }
            payload["isFromGoogleCalendar"] = event.isFromGoogleCalendar
            firestore.collection("users")
                .document(userId)
                .collection("events")
                .document(event.id)
                .set(payload, SetOptions.merge())
                .await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Sync event to Firestore failed: ${e.message}")
            false
        }
    }

    private fun deleteEventFromFirestore(userId: String, eventId: String) {
        if (userId == com.example.auth.AuthManager.GUEST_USER_ID) return
        repoScope.launch {
            try {
                val dbId = context.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                firestore.collection("users")
                    .document(userId)
                    .collection("events")
                    .document(eventId)
                    .delete()
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Delete event from Firestore failed: ${e.message}")
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
    }
}
