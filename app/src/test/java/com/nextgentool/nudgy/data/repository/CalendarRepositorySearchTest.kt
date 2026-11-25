package com.nextgentool.nudgy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-4, step 2c — the Schedules search: one Room LIKE query over
 * the calendar_events title + location/link.
 *
 * Real in-memory Room + real CalendarRepository (no Hilt), following
 * the step-2b TaskRepositorySearchTest pattern. The query runs through
 * [CalendarRepository.searchEvents] so the shared LIKE-pattern
 * escaping is covered too (a typed '%' must match literally, not as
 * a wildcard). The repository is built with its default sync/gateway
 * (both fetch their clients lazily) — a read never touches them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarRepositorySearchTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var repository: CalendarRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CalendarRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val userId get() = "test-user-1"

    private suspend fun seed() {
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                userId = userId,
                title = "Dentist appointment",
                startEpochMillis = 1_700_000_000_000,
                endEpochMillis = 1_700_000_600_000,
                locationOrLink = "City Dental Clinic"
            )
        )
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                userId = userId,
                title = "Team standup",
                startEpochMillis = 1_700_001_000_000,
                endEpochMillis = 1_700_001_300_000,
                locationOrLink = "Office Room 4"
            )
        )
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                userId = userId,
                title = "Yoga class",
                startEpochMillis = 1_700_002_000_000,
                endEpochMillis = 1_700_002_600_000
            )
        )
    }

    @Test
    fun `title match returns only the title hit`() = runTest {
        seed()
        val results = repository.searchEvents(userId, "dentist").first()
        assertEquals(listOf("Dentist appointment"), results.map { it.title })
    }

    @Test
    fun `location match returns the location hit`() = runTest {
        seed()
        val results = repository.searchEvents(userId, "clinic").first()
        assertEquals(listOf("Dentist appointment"), results.map { it.title })
        val roomResults = repository.searchEvents(userId, "room").first()
        assertEquals(listOf("Team standup"), roomResults.map { it.title })
    }

    @Test
    fun `no match returns an empty list`() = runTest {
        seed()
        val results = repository.searchEvents(userId, "zzz-no-such").first()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `match is case-insensitive`() = runTest {
        seed()
        val results = repository.searchEvents(userId, "DENTIST").first()
        assertEquals(listOf("Dentist appointment"), results.map { it.title })
    }

    @Test
    fun `another user's event never leaks into the results`() = runTest {
        seed()
        db.calendarDao().insertEvent(
            CalendarEventEntity(
                userId = "other-user",
                title = "Dentist checkup",
                startEpochMillis = 1_700_003_000_000,
                endEpochMillis = 1_700_003_600_000
            )
        )
        val results = repository.searchEvents(userId, "Dentist").first()
        assertEquals(listOf("Dentist appointment"), results.map { it.title })
    }

    @Test
    fun `a typed percent matches literally, not as a wildcard`() = runTest {
        seed()
        // Without escaping, "%" would match every row.
        val results = repository.searchEvents(userId, "%").first()
        assertTrue(results.isEmpty())
    }
}
