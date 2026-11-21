package com.nextgentool.nudgy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Sprint 2-1c — the sync window is one year forward. Device-verified
 * on ci-607: the S2-1b label worked (Dec 16 Victory Day arrived), but
 * Dec 24/25/26 Christmas events never imported — runSync only queried
 * Instances over past 7 days → next 30 days, and from Nov 21 the
 * window ended Dec 21. These tests pin the 365-day window: events
 * ~60 and ~340 days out import, an event beyond 365 days does not,
 * and de-dup still holds on a second sync.
 * Same harness as CalendarSyncHolidayLabelTest (fake gateway +
 * recording Firestore seam + in-memory Room, permissions granted
 * explicitly).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarSyncWindowTest {

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
    }

    private fun daysFromNow(days: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, days)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `event 60 days out imports`() = runTest {
        val start = daysFromNow(60, 10)
        provider.addEvent(
            FakeGoogleEvent(
                id = 21L, title = "Quarterly review",
                start = start, end = start + 60 * 60 * 1000L
            )
        )
        assertEquals(1, repository.syncGoogleCalendar(userId))
        val rows = db.calendarDao().getAllEventsOnce(userId)
        assertEquals(1, rows.size)
        assertEquals("Quarterly review", rows.single().title)
        // De-dup as before: a second sync imports nothing more.
        assertEquals(0, repository.syncGoogleCalendar(userId))
        assertEquals(1, db.calendarDao().getAllEventsOnce(userId).size)
    }

    @Test
    fun `event 340 days out imports`() = runTest {
        val start = daysFromNow(340, 10)
        provider.addEvent(
            FakeGoogleEvent(
                id = 22L, title = "Next year planning",
                start = start, end = start + 60 * 60 * 1000L
            )
        )
        assertEquals(1, repository.syncGoogleCalendar(userId))
        val rows = db.calendarDao().getAllEventsOnce(userId)
        assertEquals(1, rows.size)
        assertEquals("Next year planning", rows.single().title)
    }

    @Test
    fun `event beyond 365 days does not import`() = runTest {
        val start = daysFromNow(400, 10)
        provider.addEvent(
            FakeGoogleEvent(
                id = 23L, title = "Far future",
                start = start, end = start + 60 * 60 * 1000L
            )
        )
        assertEquals(0, repository.syncGoogleCalendar(userId))
        assertEquals(0, db.calendarDao().getAllEventsOnce(userId).size)
    }
}
