package com.nextgentool.nudgy.data.repository

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import java.util.Collections

/** One Google Calendar event in the fake provider. */
data class FakeGoogleEvent(
    val id: Long,
    var calendarId: Long = 1L,
    var title: String,
    var start: Long,
    var end: Long,
    var location: String? = null,
    var description: String? = null,
    var allDay: Boolean = false
)

/**
 * Sprint 2-1 — a fake `com.android.calendar` provider for the sync
 * tests, registered via ShadowContentResolver.registerProviderInternal.
 *
 * Serves the Instances view (including DESCRIPTION, so the import can
 * parse the [nudgy:<id>] marker), the Calendars list (one writable
 * Google calendar, so exports have somewhere to go), and Events
 * insert/update/delete. Every write is counted so the tests can prove
 * exactly-once behaviour: no second export, no provider touch from
 * the de-dup sweep, one retry after a failed update.
 *
 * All access is synchronized: the concurrent-sync test drives two
 * syncs on different IO threads against the same provider.
 */
class FakeCalendarProvider : ContentProvider() {

    private val events = mutableListOf<FakeGoogleEvent>()
    private var nextId = 100L
    var insertAttempts = 0
        private set
    var updateAttempts = 0
        private set
    var updateSuccesses = 0
        private set
    var deleteAttempts = 0
        private set
    var deleteSuccesses = 0
        private set
    /** When true, update() returns 0 rows (the provider rejected the edit). */
    var failUpdates = false

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = null

    fun addEvent(event: FakeGoogleEvent) {
        synchronized(events) {
            events.add(event)
            if (event.id >= nextId) nextId = event.id + 1
        }
    }

    fun removeEvent(id: Long) {
        synchronized(events) { events.removeAll { it.id == id } }
    }

    fun eventById(id: Long): FakeGoogleEvent? =
        synchronized(events) { events.firstOrNull { it.id == id } }

    fun eventCount(): Int = synchronized(events) { events.size }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val segments = uri.pathSegments
        return when {
            segments.firstOrNull() == "instances" -> queryInstances(uri, projection)
            uri == CalendarContract.Calendars.CONTENT_URI -> queryCalendars(projection)
            else -> null
        }
    }

    private fun queryInstances(uri: Uri, projection: Array<String>?): Cursor {
        val segments = uri.pathSegments
        val windowStart = segments.getOrNull(1)?.toLongOrNull() ?: 0L
        val windowEnd = segments.getOrNull(2)?.toLongOrNull() ?: Long.MAX_VALUE
        val cols = projection ?: arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DESCRIPTION
        )
        val cursor = MatrixCursor(cols)
        synchronized(events) {
            for (e in events) {
                // The real Instances view returns occurrences overlapping
                // the window.
                if (e.start >= windowEnd || e.end <= windowStart) continue
                cursor.addRow(
                    cols.map { col ->
                        when (col) {
                            CalendarContract.Instances.EVENT_ID -> e.id
                            CalendarContract.Instances.TITLE -> e.title
                            CalendarContract.Instances.BEGIN -> e.start
                            CalendarContract.Instances.END -> e.end
                            CalendarContract.Instances.EVENT_LOCATION -> e.location
                            CalendarContract.Instances.ALL_DAY -> if (e.allDay) 1 else 0
                            CalendarContract.Instances.CALENDAR_ID -> e.calendarId
                            CalendarContract.Instances.DESCRIPTION -> e.description
                            else -> null
                        }
                    }.toTypedArray()
                )
            }
        }
        return cursor
    }

    private fun queryCalendars(projection: Array<String>?): Cursor {
        val cols = projection ?: arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        val cursor = MatrixCursor(cols)
        cursor.addRow(
            cols.map { col ->
                when (col) {
                    CalendarContract.Calendars._ID -> 1L
                    CalendarContract.Calendars.ACCOUNT_TYPE -> "com.google"
                    CalendarContract.Calendars.IS_PRIMARY -> 1
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL ->
                        CalendarContract.Calendars.CAL_ACCESS_OWNER
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME -> "test@gmail.com"
                    else -> null
                }
            }.toTypedArray()
        )
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uri.pathSegments.firstOrNull() != "events") return null
        insertAttempts++
        val id = synchronized(events) {
            val newId = nextId++
            events.add(
                FakeGoogleEvent(
                    id = newId,
                    calendarId = values?.getAsLong(CalendarContract.Events.CALENDAR_ID) ?: 1L,
                    title = values?.getAsString(CalendarContract.Events.TITLE) ?: "Untitled",
                    start = values?.getAsLong(CalendarContract.Events.DTSTART) ?: 0L,
                    end = values?.getAsLong(CalendarContract.Events.DTEND) ?: 0L,
                    location = values?.getAsString(CalendarContract.Events.EVENT_LOCATION),
                    description = values?.getAsString(CalendarContract.Events.DESCRIPTION)
                )
            )
            newId
        }
        return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
        updateAttempts++
        if (failUpdates) return 0
        val event = synchronized(events) { events.firstOrNull { it.id == id } } ?: return 0
        values?.let {
            if (it.containsKey(CalendarContract.Events.TITLE)) {
                event.title = it.getAsString(CalendarContract.Events.TITLE)
            }
            if (it.containsKey(CalendarContract.Events.DTSTART)) {
                event.start = it.getAsLong(CalendarContract.Events.DTSTART)
            }
            if (it.containsKey(CalendarContract.Events.DTEND)) {
                event.end = it.getAsLong(CalendarContract.Events.DTEND)
            }
            if (it.containsKey(CalendarContract.Events.EVENT_LOCATION)) {
                event.location = it.getAsString(CalendarContract.Events.EVENT_LOCATION)
            }
            if (it.containsKey(CalendarContract.Events.DESCRIPTION)) {
                event.description = it.getAsString(CalendarContract.Events.DESCRIPTION)
            }
        }
        updateSuccesses++
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
        deleteAttempts++
        val removed = synchronized(events) { events.removeAll { it.id == id } }
        if (removed) deleteSuccesses++
        return if (removed) 1 else 0
    }
}

/**
 * Sprint 2-1 — a recording CalendarEventSync for the sync tests. No
 * Firebase client is ever touched: uploads and deletes are recorded
 * in memory (synchronized — the repository fires them from its IO
 * scope) so the tests can assert exactly which Firestore docs an
 * operation targets.
 */
class FakeCalendarEventSync : CalendarEventSync {
    val uploads: MutableList<CalendarEventEntity> =
        Collections.synchronizedList(mutableListOf())
    val deletes: MutableList<String> =
        Collections.synchronizedList(mutableListOf())

    override suspend fun uploadEventAwaited(userId: String, event: CalendarEventEntity): Boolean {
        uploads.add(event)
        return true
    }

    override suspend fun deleteEventAwaited(userId: String, eventId: String): Boolean {
        deletes.add(eventId)
        return true
    }
}
