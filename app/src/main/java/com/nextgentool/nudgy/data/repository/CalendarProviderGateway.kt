package com.nextgentool.nudgy.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.util.TimeZone

/**
 * One occurrence from the device calendar's Instances view.
 *
 * DESCRIPTION is included: Sprint 2-1 (§7) writes the `[nudgy:<id>]`
 * marker into exported events' descriptions, and the import parses it
 * to link (or re-create) the local row.
 */
data class ProviderInstance(
    val googleEventId: Long,
    val title: String?,
    val beginEpochMillis: Long,
    val endEpochMillis: Long,
    val location: String?,
    val allDay: Boolean,
    val calendarId: Long,
    val description: String?
)

/** One row of the device calendar's Calendars list. */
data class ProviderCalendar(
    val id: Long,
    val accountType: String?,
    val isPrimary: Boolean,
    val accessLevel: Int,
    val displayName: String?
)

/**
 * Values for an Events insert / update. [description] is the FINAL
 * description string (the repository builds it, marker included);
 * update ignores it — a provider update never rewrites the Google
 * description. [calendarId] is insert-only; an update keeps the
 * event's own calendar.
 */
data class ProviderEventValues(
    val calendarId: Long? = null,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val location: String?,
    val description: String? = null
)

/**
 * Sprint 2-1 — the device-calendar access seam.
 *
 * CalendarRepository talks to the device Google Calendar ONLY through
 * this interface. Production gets [RealCalendarProviderGateway]
 * (thin ContentResolver forwarding, same URIs/projections/values the
 * repository used before); tests inject a fake, so no
 * ContentResolver — and no Robolectric ContentProvider registration,
 * which proved unreachable in this harness (rounds 4–6: neither
 * ShadowContentResolver.registerProviderInternal nor
 * Robolectric.buildContentProvider made a fake provider answer a
 * direct query) — is ever involved.
 *
 * No permission checks live here: the repository checks
 * READ/WRITE_CALENDAR before calling, exactly as before.
 */
interface CalendarProviderGateway {
    /** Occurrences overlapping [startEpochMillis, endEpochMillis), BEGIN order. */
    fun queryInstances(startEpochMillis: Long, endEpochMillis: Long): List<ProviderInstance>

    /** Every calendar on the device. */
    fun queryCalendars(): List<ProviderCalendar>

    /** Inserts the event; returns the new Google event id, or null. */
    fun insertEvent(values: ProviderEventValues): Long?

    /** Returns the number of rows changed (0 = the provider rejected it). */
    fun updateEvent(googleEventId: Long, values: ProviderEventValues): Int

    /** Returns the number of rows removed (0 = already gone). */
    fun deleteEvent(googleEventId: Long): Int
}

/**
 * The production gateway: the exact ContentResolver calls the
 * repository made before the seam existed, moved verbatim.
 */
class RealCalendarProviderGateway(
    private val context: Context
) : CalendarProviderGateway {

    override fun queryInstances(startEpochMillis: Long, endEpochMillis: Long): List<ProviderInstance> {
        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            appendPath(startEpochMillis.toString())
            appendPath(endEpochMillis.toString())
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DESCRIPTION
        )
        return try {
            context.contentResolver.query(
                instancesUri,
                projection,
                null,
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
                val descIndex = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
                val rows = mutableListOf<ProviderInstance>()
                while (cursor.moveToNext()) {
                    rows.add(
                        ProviderInstance(
                            googleEventId = cursor.getLong(idIndex),
                            title = if (titleIndex >= 0) cursor.getString(titleIndex) else null,
                            beginEpochMillis = cursor.getLong(beginIndex),
                            endEpochMillis = cursor.getLong(endIndex),
                            location = if (locIndex >= 0) cursor.getString(locIndex) else null,
                            allDay = if (allDayIndex >= 0) cursor.getInt(allDayIndex) == 1 else false,
                            calendarId = if (calIdIndex >= 0) cursor.getLong(calIdIndex) else -1L,
                            description = if (descIndex >= 0) cursor.getString(descIndex) else null
                        )
                    )
                }
                rows
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error querying calendar instances: ${e.message}")
            emptyList()
        }
    }

    override fun queryCalendars(): List<ProviderCalendar> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val typeIndex = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                val accessIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                val nameIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val rows = mutableListOf<ProviderCalendar>()
                while (cursor.moveToNext()) {
                    if (idIndex < 0) continue
                    rows.add(
                        ProviderCalendar(
                            id = cursor.getLong(idIndex),
                            accountType = if (typeIndex >= 0) cursor.getString(typeIndex) else null,
                            isPrimary = if (primaryIndex >= 0) cursor.getInt(primaryIndex) == 1 else false,
                            accessLevel = if (accessIndex >= 0) cursor.getInt(accessIndex) else 0,
                            displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                        )
                    )
                }
                rows
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error querying calendars: ${e.message}")
            emptyList()
        }
    }

    override fun insertEvent(values: ProviderEventValues): Long? {
        return try {
            val contentValues = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, values.calendarId)
                put(CalendarContract.Events.TITLE, values.title)
                put(CalendarContract.Events.DTSTART, values.startEpochMillis)
                put(CalendarContract.Events.DTEND, values.endEpochMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (!values.location.isNullOrBlank()) {
                    put(CalendarContract.Events.EVENT_LOCATION, values.location)
                }
                put(CalendarContract.Events.DESCRIPTION, values.description)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, contentValues)
            val insertedId = uri?.lastPathSegment?.toLongOrNull()
            Log.d(TAG, "Successfully exported event '${values.title}' to Google Calendar, ID: $insertedId")
            insertedId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export event to Google Calendar: ${e.message}")
            null
        }
    }

    override fun updateEvent(googleEventId: Long, values: ProviderEventValues): Int {
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, googleEventId)
            val contentValues = ContentValues().apply {
                put(CalendarContract.Events.TITLE, values.title)
                put(CalendarContract.Events.DTSTART, values.startEpochMillis)
                put(CalendarContract.Events.DTEND, values.endEpochMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (!values.location.isNullOrBlank()) {
                    put(CalendarContract.Events.EVENT_LOCATION, values.location)
                }
            }
            context.contentResolver.update(uri, contentValues, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Google Calendar event: ${e.message}")
            0
        }
    }

    override fun deleteEvent(googleEventId: Long): Int {
        return try {
            val deleteUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, googleEventId)
            context.contentResolver.delete(deleteUri, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing Google Calendar event: ${e.message}")
            0
        }
    }

    companion object {
        private const val TAG = "CalendarRepository"
    }
}
