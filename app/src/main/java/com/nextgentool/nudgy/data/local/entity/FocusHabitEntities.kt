package com.nextgentool.nudgy.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.nextgentool.nudgy.data.model.FocusSessionKind
import java.util.UUID

/**
 * Sprint 4-0 — one Pomodoro session (spec §3, §4). Foundation only:
 * S4-1 owns the timer UI and the end alarm; this table only records
 * what happened, so a session survives process death.
 *
 * - [plannedEndEpochMillis] is the end-time the timer counts down to;
 *   on reopen, a session whose planned end has passed is treated as
 *   finished (S4-1), never lost.
 * - [endedAtEpochMillis] is null while the session is running.
 * - [completed] is true only when the timer ran to its end. A cancelled
 *   session is ended but not completed, and its minutes never count.
 * - [kind] FOCUS minutes count toward focus stats; BREAK minutes never
 *   do (spec §4). Stored as TEXT (see Converters).
 * - [taskId] is null for a standalone session (spec Q3=(a)).
 */
@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val taskId: String? = null,
    val startedAtEpochMillis: Long,
    val plannedEndEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val durationMinutes: Int,
    val completed: Boolean = false,
    val kind: FocusSessionKind = FocusSessionKind.FOCUS
)

/**
 * Sprint 4-0 — a habit (spec §3, §5). Habits are a separate list from
 * tasks (Q5=(a)): no task field, no recurring-task machinery.
 */
@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val title: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

/**
 * Sprint 4-0 — one habit check-in, one per habit per local day.
 *
 * - [localDate] is "yyyy-MM-dd" in DEVICE-LOCAL time (see
 *   HabitStreakUseCase — never UTC; the day boundary is local
 *   midnight, Q6=(a)). The (habitId, localDate) primary key enforces
 *   one check-in per day; a second insert for the same day is ignored
 *   (see HabitDao.insertCheckIn).
 * - Deleting a habit deletes its check-ins (CASCADE, spec §5).
 */
@Entity(
    tableName = "habit_check_ins",
    primaryKeys = ["habitId", "localDate"],
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class HabitCheckInEntity(
    val habitId: String,
    val localDate: String,
    val checkedAtEpochMillis: Long = System.currentTimeMillis()
)
