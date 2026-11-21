package com.nextgentool.nudgy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nextgentool.nudgy.data.local.entity.FocusSessionEntity
import com.nextgentool.nudgy.data.local.entity.HabitCheckInEntity
import com.nextgentool.nudgy.data.local.entity.HabitEntity
import kotlinx.coroutines.flow.Flow

/**
 * Sprint 4-0 — Pomodoro session storage (spec §3, §4).
 *
 * Minutes only ever count from COMPLETED focus sessions: a cancelled
 * session is ended but not completed, and break sessions never count.
 * All ranges are half-open [start, end) on startedAtEpochMillis, so a
 * day's minutes and a week's minutes never double-count a session that
 * starts exactly on a boundary.
 */
@Dao
interface FocusSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: FocusSessionEntity)

    @Update
    suspend fun updateSession(session: FocusSessionEntity)

    @Query("SELECT * FROM focus_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: String): FocusSessionEntity?

    /**
     * The single running session (at most one — the repository cancels
     * any running session before starting a new one, spec §4).
     */
    @Query("SELECT * FROM focus_sessions WHERE userId = :userId AND completed = 0 AND endedAtEpochMillis IS NULL ORDER BY startedAtEpochMillis DESC LIMIT 1")
    suspend fun getRunningSession(userId: String): FocusSessionEntity?

    /** The timer ran to its end: completed, with the real end time. */
    @Query("UPDATE focus_sessions SET completed = 1, endedAtEpochMillis = :endedAt WHERE id = :id")
    suspend fun completeSession(id: String, endedAt: Long)

    /** Cancelled / superseded: ended but NOT completed — minutes never count. */
    @Query("UPDATE focus_sessions SET endedAtEpochMillis = :endedAt WHERE id = :id")
    suspend fun endSession(id: String, endedAt: Long)

    /**
     * Completed FOCUS minutes whose session started in [start, end).
     * SQLite SUM returns a Long; COALESCE keeps it 0 (never null) when
     * there is no session in the range.
     */
    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM focus_sessions WHERE userId = :userId AND completed = 1 AND kind = 'FOCUS' AND startedAtEpochMillis >= :start AND startedAtEpochMillis < :end")
    suspend fun getFocusMinutesInRange(userId: String, start: Long, end: Long): Long

    /** Same as [getFocusMinutesInRange], for one task only (spec Q3=(a)). */
    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM focus_sessions WHERE userId = :userId AND taskId = :taskId AND completed = 1 AND kind = 'FOCUS' AND startedAtEpochMillis >= :start AND startedAtEpochMillis < :end")
    suspend fun getFocusMinutesForTask(userId: String, taskId: String, start: Long, end: Long): Long

    /**
     * Completed FOCUS sessions since [since], newest first. S4-1 counts
     * these for the long-break rule (4 completed sessions → 15-minute
     * break, spec §4).
     */
    @Query("SELECT * FROM focus_sessions WHERE userId = :userId AND completed = 1 AND kind = 'FOCUS' AND startedAtEpochMillis >= :since ORDER BY startedAtEpochMillis DESC")
    suspend fun getCompletedFocusSessionsSince(userId: String, since: Long): List<FocusSessionEntity>

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE userId = :userId AND completed = 1 AND kind = 'FOCUS' AND startedAtEpochMillis >= :since")
    suspend fun getCompletedFocusCountSince(userId: String, since: Long): Int
}

/**
 * Sprint 4-0 — habit storage (spec §3, §5).
 *
 * One check-in per habit per day is enforced by the (habitId,
 * localDate) primary key + IGNORE: a second insert for the same day
 * returns -1 and changes nothing (see insertCheckIn).
 */
@Dao
interface HabitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHabit(habit: HabitEntity)

    @Update
    suspend fun updateHabit(habit: HabitEntity)

    /** Check-ins go with it (CASCADE, spec §5). */
    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun deleteHabitById(id: String)

    @Query("SELECT * FROM habits WHERE userId = :userId ORDER BY createdAtEpochMillis ASC")
    fun getHabitsFlow(userId: String): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE userId = :userId ORDER BY createdAtEpochMillis ASC")
    suspend fun getHabitsOnce(userId: String): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id = :id LIMIT 1")
    suspend fun getHabitById(id: String): HabitEntity?

    /**
     * Returns the row id, or -1 when a check-in for that habit and day
     * already exists (IGNORE + primary key). The repository turns -1
     * into "already checked in" — never a second row.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCheckIn(checkIn: HabitCheckInEntity): Long

    /** Undo today's check-in (spec §5 — undo is allowed within the day). */
    @Query("DELETE FROM habit_check_ins WHERE habitId = :habitId AND localDate = :localDate")
    suspend fun deleteCheckIn(habitId: String, localDate: String)

    /** Local dates, newest first — the streak input (see HabitStreakUseCase). */
    @Query("SELECT localDate FROM habit_check_ins WHERE habitId = :habitId ORDER BY localDate DESC")
    suspend fun getCheckInDates(habitId: String): List<String>

    @Query("SELECT COUNT(*) FROM habit_check_ins WHERE habitId = :habitId")
    suspend fun getCheckInCount(habitId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM habit_check_ins WHERE habitId = :habitId AND localDate = :localDate)")
    suspend fun hasCheckIn(habitId: String, localDate: String): Boolean
}
