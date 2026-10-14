package com.example.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.local.NudgyDatabase
import com.example.data.local.entity.CalendarEventEntity
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
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

    fun observeEventsForDay(userId: String, dayMillis: Long): Flow<List<CalendarEventEntity>> {
        val (startOfDay, endOfDay) = getDayBounds(dayMillis)
        return calendarDao.getEventsForDayFlow(userId, startOfDay, endOfDay)
    }

    fun hasCalendarPermissions(): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    private fun getPrimaryGoogleCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val uri = CalendarContract.Calendars.CONTENT_URI
        var selectedId: Long? = null
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val typeIndex = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)

                while (cursor.moveToNext()) {
                    val calId = cursor.getLong(idIndex)
                    val acctType = if (typeIndex >= 0) cursor.getString(typeIndex) else null
                    val isPrimary = if (primaryIndex >= 0) cursor.getInt(primaryIndex) == 1 else false

                    if (isPrimary || acctType == "com.google") {
                        return calId
                    }
                    if (selectedId == null) {
                        selectedId = calId
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding primary Google Calendar ID: ${e.message}")
        }
        return selectedId
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

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY
        )

        val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?) AND (${CalendarContract.Events.DELETED} = 0)"
        val selectionArgs = arrayOf(searchStart.toString(), searchEnd.toString())

        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Events._ID)
                val titleIndex = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val startIndex = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIndex = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                val locIndex = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                val allDayIndex = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)

                val existingEvents = calendarDao.getAllEventsFlow(userId).first()

                while (cursor.moveToNext()) {
                    val gId = cursor.getLong(idIndex)
                    val rawTitle = cursor.getString(titleIndex) ?: "Untitled Event"
                    val start = cursor.getLong(startIndex)
                    var end = cursor.getLong(endIndex)
                    if (end <= start) end = start + (45 * 60 * 1000L)
                    val loc = if (locIndex >= 0) cursor.getString(locIndex) else null
                    val isAllDay = if (allDayIndex >= 0) cursor.getInt(allDayIndex) == 1 else false

                    // Deduplicate against existing events
                    val alreadyExists = existingEvents.any { local ->
                        local.googleCalendarEventId == gId ||
                        (local.title.equals(rawTitle, ignoreCase = true) && Math.abs(local.startEpochMillis - start) < 60_000L)
                    }

                    if (!alreadyExists) {
                        val eventType = if (rawTitle.contains("Focus", ignoreCase = true) || rawTitle.contains("Deep work", ignoreCase = true)) "FOCUS_TIME" else "MEETING"
                        val newEntity = CalendarEventEntity(
                            id = UUID.randomUUID().toString(),
                            userId = userId,
                            title = rawTitle,
                            startEpochMillis = start,
                            endEpochMillis = end,
                            locationOrLink = loc ?: "Google Calendar",
                            eventType = eventType,
                            isAllDay = isAllDay,
                            googleCalendarEventId = gId,
                            isFromGoogleCalendar = true
                        )
                        calendarDao.insertEvent(newEntity)

                        // Also mirror to tasks for immediate visibility on Tasks tab
                        taskRepository?.createTask(
                            userId = userId,
                            title = rawTitle,
                            description = "Google Calendar (${loc ?: "Meeting"})",
                            dueEpochMillis = start,
                            priority = Priority.HIGH,
                            category = TaskCategory.WORK,
                            reminderSet = true
                        )
                        syncedCount++
                    }
                }
            }
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
        event
    }

    suspend fun deleteEvent(eventId: String, userId: String? = null) = withContext(Dispatchers.IO) {
        val target = calendarDao.getEventById(eventId)
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
            ConflictResult(
                hasConflict = true,
                conflictingEvent = conflict,
                suggestedAlternativeSlot = FocusTimeSlot(
                    startEpochMillis = altStart,
                    endEpochMillis = altEnd,
                    durationMinutes = ((altEnd - altStart) / (60 * 1000)).toInt(),
                    label = "3:00 – 4:00 PM",
                    reason = "Next available open slot"
                )
            )
        } else {
            ConflictResult(hasConflict = false)
        }
    }

    suspend fun checkAndSeedInitialEvents(userId: String) = withContext(Dispatchers.IO) {
        val currentEvents = calendarDao.getAllEventsFlow(userId).first()
        if (currentEvents.isEmpty()) {
            val calendar = Calendar.getInstance()

            // 1. Design team sync (11:30 - 12:00)
            calendar.set(Calendar.HOUR_OF_DAY, 11)
            calendar.set(Calendar.MINUTE, 30)
            calendar.set(Calendar.SECOND, 0)
            val meetStart = calendar.timeInMillis
            calendar.set(Calendar.HOUR_OF_DAY, 12)
            calendar.set(Calendar.MINUTE, 0)
            val meetEnd = calendar.timeInMillis

            // 2. Deep work session (2:00 PM - 3:00 PM)
            calendar.set(Calendar.HOUR_OF_DAY, 14)
            calendar.set(Calendar.MINUTE, 0)
            val deepStart = calendar.timeInMillis
            calendar.set(Calendar.HOUR_OF_DAY, 15)
            calendar.set(Calendar.MINUTE, 0)
            val deepEnd = calendar.timeInMillis

            val sampleEvents = listOf(
                CalendarEventEntity(
                    id = "seed_event_1",
                    userId = userId,
                    title = "Design team sync",
                    startEpochMillis = meetStart,
                    endEpochMillis = meetEnd,
                    locationOrLink = "Google Meet",
                    eventType = "MEETING"
                ),
                CalendarEventEntity(
                    id = "seed_event_2",
                    userId = userId,
                    title = "Deep work session",
                    startEpochMillis = deepStart,
                    endEpochMillis = deepEnd,
                    locationOrLink = "Focus time",
                    eventType = "FOCUS_TIME"
                )
            )
            calendarDao.insertEvents(sampleEvents)
        }
    }

    suspend fun findSuggestedFocusSlot(userId: String, dayMillis: Long): FocusTimeSlot? = withContext(Dispatchers.IO) {
        val (startOfDay, endOfDay) = getDayBounds(dayMillis)
        val events = calendarDao.getEventsForDayFlow(userId, startOfDay, endOfDay).first()
            .sortedBy { it.startEpochMillis }

        val firstMeeting = events.firstOrNull { it.eventType == "MEETING" }
        if (firstMeeting != null) {
            val slotStart = firstMeeting.endEpochMillis + (15 * 60 * 1000L)
            val slotEnd = slotStart + (45 * 60 * 1000L)
            return@withContext FocusTimeSlot(
                startEpochMillis = slotStart,
                endEpochMillis = slotEnd,
                durationMinutes = 45,
                label = "12:15 – 1:00 PM",
                reason = "45 minutes free after your meeting"
            )
        }

        null
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
