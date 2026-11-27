package com.nextgentool.nudgy.data.repository

import android.content.Context
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.FocusSessionEntity
import com.nextgentool.nudgy.data.local.entity.HabitCheckInEntity
import com.nextgentool.nudgy.data.local.entity.HabitEntity
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.domain.dateStringOf
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.startOfWeekMillis
import com.nextgentool.nudgy.domain.weekDateStrings
import com.nextgentool.nudgy.domain.usecase.FocusStatsUseCase
import com.nextgentool.nudgy.domain.usecase.HabitStreakUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A habit with its streak state, for the Habits section (S4-2). */
data class HabitStreak(
    val habit: HabitEntity,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val checkedInToday: Boolean = false,
    // Sprint 7 (Habits data) — the habit's check-in dates inside the
    // Mon–Sun week containing `now` (the "This week" dots). Real
    // dates only, carried from the same read that computes the
    // streaks — never derived from a streak count.
    val thisWeekCheckInDates: Set<String> = emptySet()
)

/**
 * Sprint 4-0 — the Focus + Habit data layer (spec §3). Foundation
 * only: S4-1 owns the timer, S4-2 the habit UI, S4-4 the stats — this
 * class only reads and writes the two new tables.
 *
 * Room is the single source of truth, same as TaskRepository: the UI
 * only ever sees Flows / results from here and never touches a DAO
 * directly. Every method takes its clock as a parameter (defaulting to
 * now) so tests can pin a date instead of depending on the real today.
 * All day math is device-local (see HabitStreakUseCase — never UTC).
 *
 * Constructor shape follows TaskRepository: Hilt builds it from the
 * database singleton, and tests can pass a test database directly.
 */
@Singleton
class FocusHabitRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: NudgyDatabase = NudgyDatabase.getInstance(context)
) {
    private val focusDao = database.focusSessionDao()
    private val habitDao = database.habitDao()
    private val streakUseCase = HabitStreakUseCase()
    private val focusStatsUseCase = FocusStatsUseCase(streakUseCase)

    // ---- Focus sessions (spec §4) ----

    /**
     * Starts a session. At most one runs at a time: a running session
     * is ended first (not completed — its minutes never count).
     * [durationMinutes] is the planned length (25 / 5 / 15, spec Q1).
     */
    suspend fun startSession(
        userId: String,
        taskId: String?,
        durationMinutes: Int,
        kind: FocusSessionKind = FocusSessionKind.FOCUS,
        now: Long = System.currentTimeMillis()
    ): FocusSessionEntity = withContext(Dispatchers.IO) {
        focusDao.getRunningSession(userId)?.let { running ->
            focusDao.endSession(running.id, now)
        }
        val session = FocusSessionEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            taskId = taskId,
            startedAtEpochMillis = now,
            plannedEndEpochMillis = now + durationMinutes * 60_000L,
            durationMinutes = durationMinutes,
            kind = kind
        )
        focusDao.insertSession(session)
        session
    }

    /** The timer ran to its end. Returns the completed session, or null when unknown. */
    suspend fun completeSession(
        sessionId: String,
        now: Long = System.currentTimeMillis()
    ): FocusSessionEntity? = withContext(Dispatchers.IO) {
        focusDao.completeSession(sessionId, now)
        focusDao.getSessionById(sessionId)
    }

    /** Cancelled: ended but NOT completed — minutes never count. Returns the session, or null. */
    suspend fun cancelSession(
        sessionId: String,
        now: Long = System.currentTimeMillis()
    ): FocusSessionEntity? = withContext(Dispatchers.IO) {
        focusDao.endSession(sessionId, now)
        focusDao.getSessionById(sessionId)
    }

    suspend fun getRunningSession(userId: String): FocusSessionEntity? =
        withContext(Dispatchers.IO) { focusDao.getRunningSession(userId) }

    suspend fun getSessionById(sessionId: String): FocusSessionEntity? =
        withContext(Dispatchers.IO) { focusDao.getSessionById(sessionId) }

    /**
     * Sprint 4-1 — pause/resume freezes the timer: on resume the
     * planned end moves to now + the remaining time, so the pause
     * itself never eats into the session. The session keeps its
     * original startedAt + durationMinutes, so a completed session
     * still counts its full planned minutes (spec §4).
     */
    suspend fun updateSessionPlannedEnd(sessionId: String, plannedEndEpochMillis: Long) =
        withContext(Dispatchers.IO) {
            val session = focusDao.getSessionById(sessionId) ?: return@withContext
            focusDao.updateSession(session.copy(plannedEndEpochMillis = plannedEndEpochMillis))
        }

    /**
     * Completed FOCUS minutes in [start, end). Breaks never count, and
     * cancelled sessions never count (spec §4).
     */
    suspend fun getFocusMinutesInRange(userId: String, start: Long, end: Long): Int =
        withContext(Dispatchers.IO) { focusDao.getFocusMinutesInRange(userId, start, end).toInt() }

    /** Completed FOCUS minutes for one local day (device-local midnight to midnight). */
    suspend fun getFocusMinutesForDay(userId: String, dayMillis: Long): Int {
        val start = startOfDayMillis(dayMillis)
        // Next local midnight, stepping with Calendar.add (DST-safe) —
        // not start + 24h, which miscounts across a DST transition.
        val end = startOfDayMillis(1, start)
        return getFocusMinutesInRange(userId, start, end)
    }

    /**
     * Completed FOCUS minutes for the Mon–Sun week containing [now] —
     * the same week [com.nextgentool.nudgy.domain.usecase.WeeklyReviewUseCase]
     * reports on, so S4-4's numbers always match the review (spec §7).
     */
    suspend fun getFocusMinutesForWeek(userId: String, now: Long = System.currentTimeMillis()): Int {
        val start = startOfWeekMillis(now)
        val end = start + 7 * 24 * 60 * 60 * 1000L
        return getFocusMinutesInRange(userId, start, end)
    }

    /** Completed FOCUS minutes for one task in [start, end) (spec Q3=(a)). */
    suspend fun getFocusMinutesForTask(userId: String, taskId: String, start: Long, end: Long): Int =
        withContext(Dispatchers.IO) {
            focusDao.getFocusMinutesForTask(userId, taskId, start, end).toInt()
        }

    /** Completed FOCUS sessions since [since], newest first (S4-1: long-break counting). */
    suspend fun getCompletedFocusSessionsSince(userId: String, since: Long): List<FocusSessionEntity> =
        withContext(Dispatchers.IO) { focusDao.getCompletedFocusSessionsSince(userId, since) }

    suspend fun getCompletedFocusCountSince(userId: String, since: Long): Int =
        withContext(Dispatchers.IO) { focusDao.getCompletedFocusCountSince(userId, since) }

    /**
     * Sprint 7 (Focus data) — the week completion % (completed ÷ all
     * ENDED FOCUS sessions in the Mon–Sun week containing [now]).
     * Null when nothing ended yet — the screen shows "–" (never a
     * fabricated 0% or 100%). Same week as [getFocusMinutesForWeek],
     * so the two numbers always agree about which week they cover.
     */
    suspend fun getFocusWeekCompletionPercent(
        userId: String,
        now: Long = System.currentTimeMillis()
    ): Int? = withContext(Dispatchers.IO) {
        val start = startOfWeekMillis(now)
        val end = start + 7 * 24 * 60 * 60 * 1000L
        val completed = focusDao.getCompletedFocusCountInRange(userId, start, end)
        val endedNotCompleted = focusDao.getEndedNotCompletedFocusCountInRange(userId, start, end)
        focusStatsUseCase.completionPercent(completed, endedNotCompleted)
    }

    /**
     * Sprint 7 (Focus data) — the focus streak: consecutive days
     * ending today-or-yesterday with ≥1 completed FOCUS session.
     * Reads the last 366 days of completed sessions (a generous
     * window — a run longer than that cannot be proven from a
     * shorter one, and the streak walk stops at the first missing
     * day anyway).
     */
    suspend fun getFocusStreakDays(
        userId: String,
        now: Long = System.currentTimeMillis()
    ): Int = withContext(Dispatchers.IO) {
        val since = now - 366L * 24 * 60 * 60 * 1000
        val sessions = focusDao.getCompletedFocusSessionsSince(userId, since)
        focusStatsUseCase.focusStreakDays(sessions.map { it.startedAtEpochMillis }, now)
    }

    /** Sprint 7 (Focus data) — the newest ENDED FOCUS sessions, newest first. */
    suspend fun getRecentEndedFocusSessions(
        userId: String,
        limit: Int = 3
    ): List<FocusSessionEntity> =
        withContext(Dispatchers.IO) { focusDao.getEndedFocusSessions(userId, limit) }

    // ---- Habits (spec §5) ----

    suspend fun createHabit(
        userId: String,
        title: String,
        now: Long = System.currentTimeMillis()
    ): HabitEntity = withContext(Dispatchers.IO) {
        val habit = HabitEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            title = title,
            createdAtEpochMillis = now
        )
        habitDao.insertHabit(habit)
        habit
    }

    suspend fun renameHabit(habitId: String, title: String) = withContext(Dispatchers.IO) {
        val habit = habitDao.getHabitById(habitId) ?: return@withContext
        habitDao.updateHabit(habit.copy(title = title))
    }

    /** Check-ins go with the habit (CASCADE, spec §5). */
    suspend fun deleteHabit(habitId: String) = withContext(Dispatchers.IO) {
        habitDao.deleteHabitById(habitId)
    }

    fun getHabitsFlow(userId: String): Flow<List<HabitEntity>> = habitDao.getHabitsFlow(userId)

    suspend fun getHabitsOnce(userId: String): List<HabitEntity> =
        withContext(Dispatchers.IO) { habitDao.getHabitsOnce(userId) }

    suspend fun getHabitById(habitId: String): HabitEntity? =
        withContext(Dispatchers.IO) { habitDao.getHabitById(habitId) }

    /**
     * Checks in for the local day of [now]. Returns true when this call
     * created the check-in, false when the day was already checked in
     * (one per day, enforced by the primary key — spec §5).
     */
    suspend fun checkIn(habitId: String, now: Long = System.currentTimeMillis()): Boolean =
        withContext(Dispatchers.IO) {
            val checkIn = HabitCheckInEntity(
                habitId = habitId,
                localDate = dateStringOf(now),
                checkedAtEpochMillis = now
            )
            habitDao.insertCheckIn(checkIn) != -1L
        }

    /** Undo the check-in for the local day of [now] (spec §5). */
    suspend fun undoCheckIn(habitId: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            habitDao.deleteCheckIn(habitId, dateStringOf(now))
        }

    suspend fun hasCheckedInToday(habitId: String, now: Long = System.currentTimeMillis()): Boolean =
        withContext(Dispatchers.IO) { habitDao.hasCheckIn(habitId, dateStringOf(now)) }

    suspend fun getCheckInDates(habitId: String): List<String> =
        withContext(Dispatchers.IO) { habitDao.getCheckInDates(habitId) }

    /** Current streak for the local day of [now] (spec §5 — "at risk", not broken, today). */
    suspend fun getCurrentStreak(habitId: String, now: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            streakUseCase.currentStreak(
                habitDao.getCheckInDates(habitId).toSet(),
                dateStringOf(now)
            )
        }

    suspend fun getLongestStreak(habitId: String): Int =
        withContext(Dispatchers.IO) {
            streakUseCase.longestStreak(habitDao.getCheckInDates(habitId).toSet())
        }

    /** Every habit with its streak state, for the Habits section (S4-2). */
    suspend fun getHabitsWithStreaks(
        userId: String,
        now: Long = System.currentTimeMillis()
    ): List<HabitStreak> = withContext(Dispatchers.IO) {
        val today = dateStringOf(now)
        // Sprint 7 (Habits data) — the week's dates come from the
        // same read that already computes the streaks: the check-in
        // dates are in hand here and were thrown away before. No new
        // DAO query, no new read — the dots ride this one.
        val weekDates = weekDateStrings(now).toSet()
        habitDao.getHabitsOnce(userId).map { habit ->
            val dates = habitDao.getCheckInDates(habit.id).toSet()
            HabitStreak(
                habit = habit,
                currentStreak = streakUseCase.currentStreak(dates, today),
                longestStreak = streakUseCase.longestStreak(dates),
                checkedInToday = today in dates,
                thisWeekCheckInDates = dates.intersect(weekDates)
            )
        }
    }
}
