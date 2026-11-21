package com.nextgentool.nudgy.data.repository

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
 * Sprint 2-1 — a fake [CalendarProviderGateway] for the sync tests.
 *
 * Serves the Instances view (including DESCRIPTION, so the import can
 * parse the [nudgy:<id>] marker), the Calendars list (one writable
 * Google calendar, so exports have somewhere to go), and Events
 * insert/update/delete. Every write is counted so the tests can prove
 * exactly-once behaviour: no second export, no provider touch from
 * the de-dup sweep, one retry after a failed update.
 *
 * Replaces the earlier fake ContentProvider: neither
 * ShadowContentResolver.registerProviderInternal nor
 * Robolectric.buildContentProvider made a fake provider answer a
 * direct query in this harness (rounds 4–6), so the tests drive the
 * gateway directly and no ContentResolver is involved at all.
 *
 * All access is synchronized: the concurrent-sync test drives two
 * syncs on different IO threads against the same gateway.
 */
class FakeCalendarProviderGateway : CalendarProviderGateway {

    private val events = mutableListOf<FakeGoogleEvent>()
    private var nextId = 100L
    /** Extra calendars beyond the primary one (e.g. a read-only holiday calendar). */
    private val extraCalendars = mutableListOf<ProviderCalendar>()

    fun addCalendar(calendar: ProviderCalendar) {
        synchronized(extraCalendars) { extraCalendars.add(calendar) }
    }
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

    override fun queryInstances(startEpochMillis: Long, endEpochMillis: Long): List<ProviderInstance> {
        synchronized(events) {
            return events
                // The real Instances view returns occurrences overlapping
                // the window.
                .filter { it.start < endEpochMillis && it.end > startEpochMillis }
                .map {
                    ProviderInstance(
                        googleEventId = it.id,
                        title = it.title,
                        beginEpochMillis = it.start,
                        endEpochMillis = it.end,
                        location = it.location,
                        allDay = it.allDay,
                        calendarId = it.calendarId,
                        description = it.description
                    )
                }
        }
    }

    override fun queryCalendars(): List<ProviderCalendar> = listOf(
        ProviderCalendar(
            id = 1L,
            accountType = "com.google",
            isPrimary = true,
            accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER,
            displayName = "test@gmail.com"
        )
    ) + synchronized(extraCalendars) { extraCalendars.toList() }

    override fun insertEvent(values: ProviderEventValues): Long? {
        insertAttempts++
        val id = synchronized(events) {
            val newId = nextId++
            events.add(
                FakeGoogleEvent(
                    id = newId,
                    calendarId = values.calendarId ?: 1L,
                    title = values.title,
                    start = values.startEpochMillis,
                    end = values.endEpochMillis,
                    location = values.location,
                    description = values.description
                )
            )
            newId
        }
        return id
    }

    override fun updateEvent(googleEventId: Long, values: ProviderEventValues): Int {
        updateAttempts++
        if (failUpdates) return 0
        val event = synchronized(events) { events.firstOrNull { it.id == googleEventId } } ?: return 0
        event.title = values.title
        event.start = values.startEpochMillis
        event.end = values.endEpochMillis
        event.location = values.location
        updateSuccesses++
        return 1
    }

    override fun deleteEvent(googleEventId: Long): Int {
        deleteAttempts++
        val removed = synchronized(events) { events.removeAll { it.id == googleEventId } }
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
