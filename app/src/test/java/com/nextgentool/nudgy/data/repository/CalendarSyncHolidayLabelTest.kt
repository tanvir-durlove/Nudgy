package com.nextgentool.nudgy.data.repository

import android.content.Context
import android.provider.CalendarContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Sprint 2-1b — holiday / all-day events are labels, not schedule
 * items. Device-verified on ci-604: new Google Calendar events
 * imported fine, but Thanksgiving / Veterans Day / Election Day never
 * arrived — runSync dropped every instance from a calendar named
 * "*holiday*" with read-only access. These tests pin the fix:
 * holiday calendars import like any other, all-day events land with
 * isAllDay=true, and they never block a slot or count as a conflict.
 * Same harness as CalendarSyncDedupTest (fake gateway + recording
 * Firestore seam + in-memory Room, permissions granted explicitly).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarSyncHolidayLabelTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var provider: FakeCalendarProviderGateway
    private lateinit var fakeSync: FakeCalendarEventSync
    private lateinit var repository: CalendarRepository
    private lateinit var taskRepository: TaskRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR
            )
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", TEST_USER_ID).apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = FakeCalendarProviderGateway()
        fakeSync = FakeCalendarEventSync()
        repository = CalendarRepository(context, db, fakeSync, provider)
        taskRepository = TaskRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val userId get() = taskRepository.currentUserId()

    companion object {
        private const val TEST_USER_ID = "test-user-1"
        private const val HOLIDAY_CALENDAR_ID = 2L
    }

    private fun tomorrowAt(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun holidayCalendar(): ProviderCalendar = ProviderCalendar(
        id = HOLIDAY_CALENDAR_ID,
        accountType = "com.google",
        isPrimary = false,
        accessLevel = CalendarContract.Calendars.CAL_ACCESS_READ,
        displayName = "Holidays in United States"
    )

    @Test
    fun `holiday calendar event imports as an all-day label`() = runTest {
        provider.addCalendar(holidayCalendar())
        val start = tomorrowAt(0)
        provider.addEvent(
            FakeGoogleEvent(
                id = 7L, calendarId = HOLIDAY_CALENDAR_ID, title = "Thanksgiving",
                start = start, end = start + 24 * 60 * 60 * 1000L, allDay = true
            )
        )
        assertEquals(1, repository.syncGoogleCalendar(userId))
        val rows = db.calendarDao().getAllEventsOnce(userId)
        assertEquals(1, rows.size)
        assertEquals("Thanksgiving", rows.single().title)
        assertTrue(rows.single().isAllDay)
        assertEquals(7L, rows.single().googleCalendarEventId)
        // De-dup as before: a second sync imports nothing more.
        repository.syncGoogleCalendar(userId)
        assertEquals(1, db.calendarDao().getAllEventsOnce(userId).size)
    }

    @Test
    fun `all-day event on the primary calendar imports with isAllDay true`() = runTest {
        val start = tomorrowAt(0)
        provider.addEvent(
            FakeGoogleEvent(
                id = 8L, title = "Day off",
                start = start, end = start + 24 * 60 * 60 * 1000L, allDay = true
            )
        )
        assertEquals(1, repository.syncGoogleCalendar(userId))
        val rows = db.calendarDao().getAllEventsOnce(userId)
        assertEquals(1, rows.size)
        assertTrue(rows.single().isAllDay)
    }

    @Test
    fun `findSuggestedFocusSlot returns a slot on an all-day-only day`() = runTest {
        val dayStart = tomorrowAt(0)
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                id = "allday-1", userId = userId, title = "Thanksgiving",
                startEpochMillis = dayStart, endEpochMillis = dayStart + 24 * 60 * 60 * 1000L,
                isAllDay = true, googleCalendarEventId = 7L, isFromGoogleCalendar = true
            )
        )
        val slot = repository.findSuggestedFocusSlot(userId, dayStart)
        assertNotNull(slot)
        // The default future-day slot is 2:00 PM — the all-day label
        // must not push it past midnight (the old busy timeline was
        // 00:00–24:00, whose fallback slot landed at 00:15 next day).
        val cal = Calendar.getInstance().apply { timeInMillis = slot!!.startEpochMillis }
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `checkConflict ignores an all-day event`() = runTest {
        val dayStart = tomorrowAt(0)
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                id = "allday-1", userId = userId, title = "Thanksgiving",
                startEpochMillis = dayStart, endEpochMillis = dayStart + 24 * 60 * 60 * 1000L,
                isAllDay = true, googleCalendarEventId = 7L, isFromGoogleCalendar = true
            )
        )
        val result = repository.checkConflict(
            userId, dayStart + 14 * 60 * 60 * 1000L, dayStart + 15 * 60 * 60 * 1000L
        )
        assertFalse(result.hasConflict)
    }

    @Test
    fun `checkConflict still flags a timed event`() = runTest {
        // Guard: the isAllDay filter must not swallow real conflicts.
        val start = tomorrowAt(14)
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                id = "timed-1", userId = userId, title = "Design review",
                startEpochMillis = start, endEpochMillis = start + 60 * 60 * 1000L,
                googleCalendarEventId = 9L, isFromGoogleCalendar = true
            )
        )
        val result = repository.checkConflict(
            userId, start + 30 * 60 * 1000L, start + 90 * 60 * 1000L
        )
        assertTrue(result.hasConflict)
    }
}
