package com.nextgentool.nudgy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.domain.dateStringOf
import com.nextgentool.nudgy.domain.startOfWeekMillis
import kotlinx.coroutines.flow.first
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
import java.util.TimeZone

/**
 * Sprint 4-0 — FocusHabitRepository against a real in-memory Room
 * database (spec §3, §4, §5). No ViewModels are built here, so the
 * PR #86 tearDown rule (ViewModelStore.clear() before db.close())
 * does not apply — the database is simply closed.
 *
 * All instants are built with Calendar in the default timezone and all
 * expectations are built the same way, so the tests hold in any
 * timezone. "Now" is pinned per test; the midnight-boundary test
 * computes its instants from the effective default zone at runtime
 * (Robolectric may or may not honour TimeZone.setDefault — the
 * Dhaka-vs-UTC discrimination itself is pinned by the plain-JUnit
 * HabitStreakUseCaseTest, which runs on the bare JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FocusHabitRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var repository: FocusHabitRepository
    private var savedTimeZone: TimeZone? = null

    private val userId = "user-1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        savedTimeZone = TimeZone.getDefault()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FocusHabitRepository(context, db)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedTimeZone)
        db.close()
    }

    /** An instant in the default timezone (10:00 local — safely inside the day). */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ---- Focus sessions ----

    @Test
    fun `startSession records a running session with the planned end`() = runTest {
        val start = at(2026, 11, 21, 10)
        val session = repository.startSession(userId, taskId = null, durationMinutes = 25, now = start)
        assertEquals(25, session.durationMinutes)
        assertEquals(start + 25 * 60_000L, session.plannedEndEpochMillis)
        assertFalse(session.completed)
        assertNull(session.endedAtEpochMillis)
        assertEquals(FocusSessionKind.FOCUS, session.kind)

        val running = repository.getRunningSession(userId)
        assertNotNull(running)
        assertEquals(session.id, running?.id)
    }

    @Test
    fun `starting a second session ends the first without completing it`() = runTest {
        val first = repository.startSession(userId, null, 25, now = at(2026, 11, 21, 10))
        val second = repository.startSession(userId, null, 5, FocusSessionKind.BREAK, now = at(2026, 11, 21, 10, 1))
        assertEquals(second.id, repository.getRunningSession(userId)?.id)

        val ended = repository.getSessionById(first.id)
        assertNotNull(ended)
        assertFalse(ended!!.completed)
        assertNotNull(ended.endedAtEpochMillis)
    }

    @Test
    fun `completeSession marks completed and its minutes count`() = runTest {
        val session = repository.startSession(userId, null, 25, now = at(2026, 11, 21, 10))
        val done = repository.completeSession(session.id, now = at(2026, 11, 21, 10, 25))
        assertNotNull(done)
        assertTrue(done!!.completed)
        assertEquals(at(2026, 11, 21, 10, 25), done.endedAtEpochMillis)
        assertEquals(25, repository.getFocusMinutesForDay(userId, at(2026, 11, 21, 10)))
    }

    @Test
    fun `a cancelled session contributes no minutes`() = runTest {
        val session = repository.startSession(userId, null, 25, now = at(2026, 11, 21, 10))
        val cancelled = repository.cancelSession(session.id, now = at(2026, 11, 21, 10, 5))
        assertNotNull(cancelled)
        assertFalse(cancelled!!.completed)
        assertEquals(0, repository.getFocusMinutesForDay(userId, at(2026, 11, 21, 10)))
        assertNull(repository.getRunningSession(userId))
    }

    @Test
    fun `break minutes never count toward focus minutes`() = runTest {
        val focus = repository.startSession(userId, null, 25, now = at(2026, 11, 21, 10))
        repository.completeSession(focus.id, now = at(2026, 11, 21, 10, 25))
        val rest = repository.startSession(userId, null, 5, FocusSessionKind.BREAK, now = at(2026, 11, 21, 10, 26))
        repository.completeSession(rest.id, now = at(2026, 11, 21, 10, 31))
        assertEquals(25, repository.getFocusMinutesForDay(userId, at(2026, 11, 21, 10)))
        assertEquals(1, repository.getCompletedFocusCountSince(userId, at(2026, 11, 21, 0)))
    }

    @Test
    fun `focus minutes are bucketed by day and by week`() = runTest {
        val yesterday = repository.startSession(userId, null, 25, now = at(2026, 11, 20, 10))
        repository.completeSession(yesterday.id, now = at(2026, 11, 20, 10, 25))
        val today = repository.startSession(userId, null, 25, now = at(2026, 11, 21, 10))
        repository.completeSession(today.id, now = at(2026, 11, 21, 10, 25))

        assertEquals(25, repository.getFocusMinutesForDay(userId, at(2026, 11, 20, 10)))
        assertEquals(25, repository.getFocusMinutesForDay(userId, at(2026, 11, 21, 10)))
        assertEquals(0, repository.getFocusMinutesForDay(userId, at(2026, 11, 22, 10)))
        // Nov 16–22 is one Mon–Sun week; both sessions fall in it.
        assertEquals(50, repository.getFocusMinutesForWeek(userId, at(2026, 11, 21, 12)))
    }

    @Test
    fun `a session from last week does not count in this week`() = runTest {
        val now = at(2026, 11, 18, 15) // Wednesday; week is Nov 16–22.
        val monday = startOfWeekMillis(now)
        val lastMonday = monday - 7 * 24 * 60 * 60 * 1000L
        val old = repository.startSession(userId, null, 25, now = lastMonday + 10 * 60 * 60 * 1000L)
        repository.completeSession(old.id, now = lastMonday + 10 * 60 * 60 * 1000L + 25 * 60_000L)
        val thisWeek = repository.startSession(userId, null, 25, now = monday + 10 * 60 * 60 * 1000L)
        repository.completeSession(thisWeek.id, now = monday + 10 * 60 * 60 * 1000L + 25 * 60_000L)
        assertEquals(25, repository.getFocusMinutesForWeek(userId, now))
    }

    @Test
    fun `focus minutes per task keep tasks separate`() = runTest {
        val day = at(2026, 11, 21, 10)
        val forA = repository.startSession(userId, "task-a", 25, now = day)
        repository.completeSession(forA.id, now = day + 25 * 60_000L)
        val forB = repository.startSession(userId, "task-b", 25, now = day + 30 * 60_000L)
        repository.completeSession(forB.id, now = day + 55 * 60_000L)
        val standalone = repository.startSession(userId, null, 25, now = day + 60 * 60_000L)
        repository.completeSession(standalone.id, now = day + 85 * 60_000L)

        val start = day - 60_000L
        val end = day + 2 * 60 * 60_000L
        assertEquals(25, repository.getFocusMinutesForTask(userId, "task-a", start, end))
        assertEquals(25, repository.getFocusMinutesForTask(userId, "task-b", start, end))
        assertEquals(75, repository.getFocusMinutesInRange(userId, start, end))
    }

    @Test
    fun `completed focus sessions since returns newest first`() = runTest {
        val day = at(2026, 11, 21, 10)
        val first = repository.startSession(userId, null, 25, now = day)
        repository.completeSession(first.id, now = day + 25 * 60_000L)
        val second = repository.startSession(userId, null, 25, now = day + 30 * 60_000L)
        repository.completeSession(second.id, now = day + 55 * 60_000L)
        val sessions = repository.getCompletedFocusSessionsSince(userId, day)
        assertEquals(listOf(second.id, first.id), sessions.map { it.id })
    }

    // ---- Habits ----

    @Test
    fun `check-in once per day is enforced`() = runTest {
        val habit = repository.createHabit(userId, "Read", now = at(2026, 11, 21, 9))
        assertTrue(repository.checkIn(habit.id, now = at(2026, 11, 21, 10)))
        assertFalse(repository.checkIn(habit.id, now = at(2026, 11, 21, 18)))
        assertEquals(listOf(dateStringOf(at(2026, 11, 21, 10))), repository.getCheckInDates(habit.id))
        assertEquals(1, repository.getCurrentStreak(habit.id, now = at(2026, 11, 21, 20)))
        assertTrue(repository.hasCheckedInToday(habit.id, now = at(2026, 11, 21, 20)))
    }

    @Test
    fun `undo removes todays check-in`() = runTest {
        val habit = repository.createHabit(userId, "Read", now = at(2026, 11, 21, 9))
        assertTrue(repository.checkIn(habit.id, now = at(2026, 11, 21, 10)))
        repository.undoCheckIn(habit.id, now = at(2026, 11, 21, 12))
        assertFalse(repository.hasCheckedInToday(habit.id, now = at(2026, 11, 21, 12)))
        assertEquals(0, repository.getCurrentStreak(habit.id, now = at(2026, 11, 21, 12)))
        assertEquals(emptyList<String>(), repository.getCheckInDates(habit.id))
    }

    @Test
    fun `streak counts consecutive days and breaks on a miss`() = runTest {
        val habit = repository.createHabit(userId, "Read", now = at(2026, 11, 18, 9))
        repository.checkIn(habit.id, now = at(2026, 11, 18, 10))
        repository.checkIn(habit.id, now = at(2026, 11, 19, 10))
        repository.checkIn(habit.id, now = at(2026, 11, 20, 10))
        assertEquals(3, repository.getCurrentStreak(habit.id, now = at(2026, 11, 20, 12)))
        assertEquals(3, repository.getLongestStreak(habit.id))
        // Nov 21 missed; Nov 22 checked in: current restarts at 1, longest stays 3.
        repository.checkIn(habit.id, now = at(2026, 11, 22, 10))
        assertEquals(1, repository.getCurrentStreak(habit.id, now = at(2026, 11, 22, 12)))
        assertEquals(3, repository.getLongestStreak(habit.id))
    }

    @Test
    fun `delete habit removes its check-ins`() = runTest {
        val habit = repository.createHabit(userId, "Read", now = at(2026, 11, 18, 9))
        repository.checkIn(habit.id, now = at(2026, 11, 18, 10))
        repository.checkIn(habit.id, now = at(2026, 11, 19, 10))
        repository.deleteHabit(habit.id)
        assertNull(repository.getHabitById(habit.id))
        assertEquals(emptyList<String>(), repository.getCheckInDates(habit.id))
    }

    @Test
    fun `rename habit keeps its check-ins`() = runTest {
        val habit = repository.createHabit(userId, "Read", now = at(2026, 11, 18, 9))
        repository.checkIn(habit.id, now = at(2026, 11, 18, 10))
        repository.renameHabit(habit.id, "Read a book")
        val renamed = repository.getHabitById(habit.id)
        assertNotNull(renamed)
        assertEquals("Read a book", renamed?.title)
        assertEquals(1, repository.getCurrentStreak(habit.id, now = at(2026, 11, 18, 12)))
    }

    @Test
    fun `habits with streaks report checked-in-today per habit`() = runTest {
        val done = repository.createHabit(userId, "Read", now = at(2026, 11, 21, 9))
        val pending = repository.createHabit(userId, "Walk", now = at(2026, 11, 21, 9))
        repository.checkIn(done.id, now = at(2026, 11, 21, 10))
        val rows = repository.getHabitsWithStreaks(userId, now = at(2026, 11, 21, 12))
        assertEquals(2, rows.size)
        val doneRow = rows.first { it.habit.id == done.id }
        val pendingRow = rows.first { it.habit.id == pending.id }
        assertTrue(doneRow.checkedInToday)
        assertEquals(1, doneRow.currentStreak)
        assertFalse(pendingRow.checkedInToday)
        assertEquals(0, pendingRow.currentStreak)
        assertEquals(listOf("Read", "Walk"), repository.getHabitsFlow(userId).first().map { it.title })
    }

    @Test
    fun `habits with streaks carry only this weeks check-in dates`() = runTest {
        // Sprint 7 (Habits data) — the week dots ride the same read
        // that computes the streaks: Mon–Wed of the week containing
        // Wed 2026-11-18, and NOT the previous Friday's check-in.
        val habit = repository.createHabit(userId, "Read", now = at(2026, 11, 13, 9))
        repository.checkIn(habit.id, now = at(2026, 11, 13, 10))
        repository.checkIn(habit.id, now = at(2026, 11, 16, 10))
        repository.checkIn(habit.id, now = at(2026, 11, 17, 10))
        repository.checkIn(habit.id, now = at(2026, 11, 18, 10))
        val rows = repository.getHabitsWithStreaks(userId, now = at(2026, 11, 18, 12))
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(
            setOf("2026-11-16", "2026-11-17", "2026-11-18"),
            row.thisWeekCheckInDates
        )
        // The streak math is untouched by the new field.
        assertEquals(3, row.currentStreak)
        assertTrue(row.checkedInToday)
    }

    @Test
    fun `midnight boundary - check-ins either side of local midnight are two days`() = runTest {
        // Built from the EFFECTIVE default zone at runtime: whether
        // Robolectric honours setDefault (Dhaka) or keeps its own zone,
        // 23:30 and next-day 00:30 local are two different local days.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"))
        try {
            val zone = TimeZone.getDefault()
            val lateNight = Calendar.getInstance(zone).apply {
                set(Calendar.YEAR, 2026)
                set(Calendar.MONTH, Calendar.NOVEMBER)
                set(Calendar.DAY_OF_MONTH, 20)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val nextMorning = Calendar.getInstance(zone).apply {
                set(Calendar.YEAR, 2026)
                set(Calendar.MONTH, Calendar.NOVEMBER)
                set(Calendar.DAY_OF_MONTH, 21)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val habit = repository.createHabit(userId, "Read", now = lateNight)
            assertTrue(repository.checkIn(habit.id, now = lateNight))
            assertTrue(repository.checkIn(habit.id, now = nextMorning))
            val dates = repository.getCheckInDates(habit.id)
            assertEquals(2, dates.size)
            // Newest first (DAO orders by localDate DESC).
            assertEquals(dateStringOf(nextMorning), dates[0])
            assertEquals(dateStringOf(lateNight), dates[1])
            assertEquals(2, repository.getCurrentStreak(habit.id, now = nextMorning))
        } finally {
            TimeZone.setDefault(savedTimeZone)
        }
    }
}
