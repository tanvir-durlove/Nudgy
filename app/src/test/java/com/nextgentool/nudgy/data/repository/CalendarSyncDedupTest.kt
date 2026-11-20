package com.nextgentool.nudgy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Sprint 2-1 (§7) — the idempotency gate. Every test here proves a
 * "one row / one doc / one Google event" invariant against a fake
 * provider gateway and a recording Firestore seam, so no Firebase
 * client, network, or real calendar is ever touched.
 *
 * The user is a signed-in (non-guest) test id: the guest path
 * early-returns before the Firestore seam, which would hide the
 * upload/delete assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarSyncDedupTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var provider: FakeCalendarProviderGateway
    private lateinit var fakeSync: FakeCalendarEventSync
    private lateinit var repository: CalendarRepository
    private lateinit var taskRepository: TaskRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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

    private fun tomorrowAt(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** The repository fires Firestore uploads/deletes from its own IO
     *  scope, so tests that assert on the fake must wait for them. */
    private fun awaitCondition(message: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(message, condition())
    }

    // ------------------------------------------------------------ diagnostics
    // Round-7 CI: the same 10 sync tests failed even with the gateway
    // fake (no ContentResolver anywhere). The only early exit before
    // the gateway is the READ_CALENDAR permission check, so these two
    // pinpoint whether Robolectric grants it or the sync path itself
    // drops the rows. Temporary — removed once the cause is proven.

    @Test
    fun `diagnostic calendar permissions are granted in Robolectric`() {
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CALENDAR
            )
        )
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_CALENDAR
            )
        )
    }

    @Test
    fun `diagnostic sync returns the imported count`() = runTest {
        val start = tomorrowAt(10)
        provider.addEvent(
            FakeGoogleEvent(id = 42L, title = "Design review", start = start, end = start + 60 * 60 * 1000L)
        )
        assertEquals(1, repository.syncGoogleCalendar(userId))
    }

    // ------------------------------------------------------------ pure helpers

    @Test
    fun `deterministic import id and marker round-trip`() {
        assertEquals("gcal-42-1000", googleImportId(42L, 1000L))
        assertEquals("[nudgy:abc-123]", exportMarker("abc-123"))
        assertEquals("abc-123", parseExportMarker("Scheduled via Nudgy (MEETING) [nudgy:abc-123]"))
        assertNull(parseExportMarker("Scheduled via Nudgy (MEETING)"))
        assertNull(parseExportMarker(null))
    }

    // ------------------------------------------------------------ required: concurrent sync

    @Test
    fun `two concurrent syncs import one occurrence exactly once`() = runTest {
        val start = tomorrowAt(10)
        provider.addEvent(
            FakeGoogleEvent(id = 42L, title = "Design review", start = start, end = start + 60 * 60 * 1000L)
        )
        val first = async { repository.syncGoogleCalendar(userId) }
        val second = async { repository.syncGoogleCalendar(userId) }
        assertEquals(1, first.await() + second.await())
        val rows = db.calendarDao().getAllEventsOnce(userId)
        assertEquals(1, rows.size)
        assertEquals(googleImportId(42L, start), rows.single().id)
        assertEquals(42L, rows.single().googleCalendarEventId)
        awaitCondition("expected exactly one Firestore upload") { fakeSync.uploads.size == 1 }
        assertEquals(googleImportId(42L, start), fakeSync.uploads.single().id)
        assertEquals(0, provider.insertAttempts)
    }

    // ------------------------------------------------------------ required: two devices

    @Test
    fun `same occurrence on two devices uploads to one Firestore doc`() = runTest {
        val start = tomorrowAt(10)
        provider.addEvent(
            FakeGoogleEvent(id = 42L, title = "Design review", start = start, end = start + 60 * 60 * 1000L)
        )
        // Device B: its own local DB and its own sync fake, same user.
        val dbB = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val fakeSyncB = FakeCalendarEventSync()
        // Same Google calendar (same gateway), different local DB.
        val repositoryB = CalendarRepository(context, dbB, fakeSyncB, provider)
        try {
            repository.syncGoogleCalendar(userId)
            repositoryB.syncGoogleCalendar(userId)
            awaitCondition("device A upload") { fakeSync.uploads.size == 1 }
            awaitCondition("device B upload") { fakeSyncB.uploads.size == 1 }
            val docA = fakeSync.uploads.single().id
            val docB = fakeSyncB.uploads.single().id
            assertEquals(googleImportId(42L, start), docA)
            assertEquals(docA, docB)
        } finally {
            dbB.close()
        }
    }

    // ------------------------------------------------------------ required: crash before insert

    @Test
    fun `export then crash before local insert is healed by the marker`() = runTest {
        val start = tomorrowAt(10)
        // The export succeeded (the Google event carries the marker) but
        // the app crashed before the local insert — no local row exists.
        provider.addEvent(
            FakeGoogleEvent(
                id = 42L, title = "Design review", start = start, end = start + 60 * 60 * 1000L,
                description = "Scheduled via Nudgy (MEETING) [nudgy:local-123]"
            )
        )
        assertEquals(1, repository.syncGoogleCalendar(userId))
        val rows = db.calendarDao().getAllEventsOnce(userId)
        assertEquals(1, rows.size)
        assertEquals("local-123", rows.single().id)
        assertEquals(42L, rows.single().googleCalendarEventId)
        // No second export was attempted, and the healed row was uploaded
        // under its marker id.
        assertEquals(0, provider.insertAttempts)
        awaitCondition("expected the healed row to be uploaded") {
            fakeSync.uploads.any { it.id == "local-123" }
        }
    }

    @Test
    fun `marker links an existing unlinked row instead of duplicating it`() = runTest {
        val start = tomorrowAt(10)
        // Local row exists (created before the export's id was recorded)
        // but has no Google link; the Google event carries its marker.
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                id = "local-123", userId = userId, title = "Design review",
                startEpochMillis = start, endEpochMillis = start + 60 * 60 * 1000L,
                locationOrLink = "Personal", googleCalendarEventId = null, isFromGoogleCalendar = false
            )
        )
        provider.addEvent(
            FakeGoogleEvent(
                id = 42L, title = "Design review", start = start, end = start + 60 * 60 * 1000L,
                description = "Scheduled via Nudgy (MEETING) [nudgy:local-123]"
            )
        )
        repository.syncGoogleCalendar(userId)
        val rows = db.calendarDao().getAllEventsOnce(userId)
        assertEquals(1, rows.size)
        assertEquals("local-123", rows.single().id)
        assertEquals(42L, rows.single().googleCalendarEventId)
        assertTrue(rows.single().isFromGoogleCalendar)
        assertEquals(0, provider.insertAttempts)
    }

    // ------------------------------------------------------------ required: export retry

    @Test
    fun `export retry exports an unlinked row once and links it with the marker`() = runTest {
        val start = tomorrowAt(10)
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                id = "local-only", userId = userId, title = "Team sync",
                startEpochMillis = start, endEpochMillis = start + 30 * 60 * 1000L,
                locationOrLink = "Personal", googleCalendarEventId = null, isFromGoogleCalendar = false
            )
        )
        repository.syncGoogleCalendar(userId)
        assertEquals(1, provider.insertAttempts)
        val exported = provider.eventById(100L)
        assertNotNull(exported)
        assertEquals("[nudgy:local-only]", parseExportMarker(exported!!.description))
        val row = db.calendarDao().getEventByIdForUser(userId, "local-only")
        assertEquals(100L, row?.googleCalendarEventId)
        assertTrue(row?.isFromGoogleCalendar == true)
        // A second sync exports nothing more.
        repository.syncGoogleCalendar(userId)
        assertEquals(1, provider.insertAttempts)
        assertEquals(1, provider.eventCount())
    }

    // ------------------------------------------------------------ required: de-dup sweep

    @Test
    fun `one-time sweep merges old UUID duplicates and never touches Google`() = runTest {
        val start = tomorrowAt(10)
        val dao = db.calendarDao()
        dao.insertEvent(
            CalendarEventEntity(
                id = "uuid-a", userId = userId, title = "Design review",
                startEpochMillis = start, endEpochMillis = start + 60 * 60 * 1000L,
                googleCalendarEventId = 42L, isFromGoogleCalendar = true
            )
        )
        dao.insertEvent(
            CalendarEventEntity(
                id = "uuid-b", userId = userId, title = "Design review",
                startEpochMillis = start, endEpochMillis = start + 60 * 60 * 1000L,
                googleCalendarEventId = 42L, isFromGoogleCalendar = true
            )
        )
        provider.addEvent(
            FakeGoogleEvent(id = 42L, title = "Design review", start = start, end = start + 60 * 60 * 1000L)
        )
        repository.syncGoogleCalendar(userId)
        val rows = dao.getAllEventsOnce(userId)
        assertEquals(1, rows.size)
        assertEquals(googleImportId(42L, start), rows.single().id)
        assertEquals(42L, rows.single().googleCalendarEventId)
        // The Google event was never touched by the sweep.
        assertEquals(0, provider.insertAttempts)
        assertEquals(0, provider.updateAttempts)
        assertEquals(0, provider.deleteAttempts)
        // Both old Firestore docs were deleted, and the merged row was
        // uploaded under the deterministic id.
        awaitCondition("expected both old docs to be deleted") {
            fakeSync.deletes.containsAll(listOf("uuid-a", "uuid-b"))
        }
        awaitCondition("expected the merged row to be uploaded") {
            fakeSync.uploads.any { it.id == googleImportId(42L, start) }
        }
        // A second sync changes nothing.
        repository.syncGoogleCalendar(userId)
        assertEquals(1, dao.getAllEventsOnce(userId).size)
    }

    // ------------------------------------------------------------ required: provider delete

    @Test
    fun `provider delete removes the local row on next sync and it does not come back`() = runTest {
        val start = tomorrowAt(10)
        provider.addEvent(
            FakeGoogleEvent(id = 42L, title = "Design review", start = start, end = start + 60 * 60 * 1000L)
        )
        repository.syncGoogleCalendar(userId)
        assertEquals(1, db.calendarDao().getAllEventsOnce(userId).size)
        // The event is deleted in Google Calendar.
        provider.removeEvent(42L)
        repository.syncGoogleCalendar(userId)
        assertEquals(0, db.calendarDao().getAllEventsOnce(userId).size)
        awaitCondition("expected the Firestore doc to be deleted") {
            fakeSync.deletes.contains(googleImportId(42L, start))
        }
        // A third sync does not bring it back.
        repository.syncGoogleCalendar(userId)
        assertEquals(0, db.calendarDao().getAllEventsOnce(userId).size)
    }

    @Test
    fun `deleteEvent on a row whose Google event is already gone still deletes locally`() = runTest {
        val start = tomorrowAt(10)
        val rowId = googleImportId(42L, start)
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                id = rowId, userId = userId, title = "Design review",
                startEpochMillis = start, endEpochMillis = start + 60 * 60 * 1000L,
                googleCalendarEventId = 42L, isFromGoogleCalendar = true
            )
        )
        // The Google event is already gone: the provider delete returns
        // 0 rows (the C14 return-value check) — the local delete must
        // still happen, and the row must not come back on the next sync.
        assertEquals(0, provider.eventCount())
        repository.deleteEvent(rowId, userId)
        assertEquals(0, db.calendarDao().getAllEventsOnce(userId).size)
        assertEquals(1, provider.deleteAttempts)
        assertEquals(0, provider.deleteSuccesses)
        awaitCondition("expected the Firestore doc to be deleted") {
            fakeSync.deletes.contains(rowId)
        }
        repository.syncGoogleCalendar(userId)
        assertEquals(0, db.calendarDao().getAllEventsOnce(userId).size)
    }

    // ------------------------------------------------------------ Q8(a): event edit

    @Test
    fun `failed provider update keeps the local edit and retries on next sync`() = runTest {
        val start = tomorrowAt(10)
        val end = start + 60 * 60 * 1000L
        provider.addEvent(FakeGoogleEvent(id = 42L, title = "Design review", start = start, end = end))
        repository.syncGoogleCalendar(userId)
        val rowId = googleImportId(42L, start)
        provider.failUpdates = true
        val ok = repository.updateEvent(userId, rowId, "Design review — moved", start, end, null, "MEETING")
        assertFalse(ok)
        // The edit is saved locally and the Google id is kept.
        val local = db.calendarDao().getEventByIdForUser(userId, rowId)
        assertNotNull(local)
        assertEquals("Design review — moved", local!!.title)
        assertEquals(42L, local.googleCalendarEventId)
        // The provider still has the old title.
        assertEquals("Design review", provider.eventById(42L)?.title)
        // The next sync retries the pending edit — and only once.
        provider.failUpdates = false
        repository.syncGoogleCalendar(userId)
        assertEquals("Design review — moved", provider.eventById(42L)?.title)
        assertEquals(2, provider.updateAttempts)
        repository.syncGoogleCalendar(userId)
        assertEquals(2, provider.updateAttempts)
    }

    @Test
    fun `successful provider update applies immediately`() = runTest {
        val start = tomorrowAt(10)
        val end = start + 60 * 60 * 1000L
        provider.addEvent(FakeGoogleEvent(id = 42L, title = "Design review", start = start, end = end))
        repository.syncGoogleCalendar(userId)
        val rowId = googleImportId(42L, start)
        val ok = repository.updateEvent(userId, rowId, "Design review — moved", start, end, null, "MEETING")
        assertTrue(ok)
        assertEquals("Design review — moved", provider.eventById(42L)?.title)
        assertEquals(1, provider.updateAttempts)
        assertEquals("Design review — moved", db.calendarDao().getEventByIdForUser(userId, rowId)?.title)
    }

    @Test
    fun `updateEvent refuses group rows`() = runTest {
        val start = tomorrowAt(10)
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                id = "group-row", userId = userId, title = "Class schedule",
                startEpochMillis = start, endEpochMillis = start + 60 * 60 * 1000L,
                groupId = "group-1"
            )
        )
        val ok = repository.updateEvent(userId, "group-row", "Changed", start, start + 60 * 60 * 1000L, null, "MEETING")
        assertFalse(ok)
        assertEquals("Class schedule", db.calendarDao().getEventByIdForUser(userId, "group-row")?.title)
        assertEquals(0, provider.updateAttempts)
    }
}
