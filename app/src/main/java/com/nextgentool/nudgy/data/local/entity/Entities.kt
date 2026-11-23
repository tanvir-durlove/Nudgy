package com.nextgentool.nudgy.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.model.TaskCategory
import java.util.UUID

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val title: String,
    val description: String? = null,
    val dueDateEpochMillis: Long = System.currentTimeMillis(),
    val priority: Priority = Priority.MEDIUM,
    val category: TaskCategory = TaskCategory.WORK,
    val isCompleted: Boolean = false,
    val completedAtEpochMillis: Long? = null,
    val reminderSet: Boolean = false,
    val reminderEpochMillis: Long? = null,
    val recurrenceRule: String? = null,
    val isSyncedToCloud: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
    // Group feature (v5): null for personal tasks. A non-null groupId marks
    // a group row — it is local-only for completion/sync purposes and must
    // never be uploaded via the personal sync path (see TaskRepository).
    val groupId: String? = null,
    // Sprint 1-1 (MIGRATION_7_8, spec §4). status is NOT NULL with a SQL
    // DEFAULT 'TODO' so the migrated column matches Room's expected schema
    // (migration backfills isCompleted = 1 -> 'DONE'). DONE ⇔ isCompleted.
    @ColumnInfo(defaultValue = "'TODO'")
    val status: TaskStatus = TaskStatus.TODO,
    /** Start time when the task is slotted into a Plan (null = not planned). */
    val plannedStartEpochMillis: Long? = null,
    /** Date (yyyy-MM-dd) whose Plan picked this task; null = in no Plan. */
    val plannedDate: String? = null,
    /** Estimated minutes (Feature 18, S1-5 UI); null = no estimate (Q8). */
    val estimatedMinutes: Int? = null,
    /** Actual minutes spent, entered by hand (Feature 18); null = not logged. */
    val actualMinutes: Int? = null,
    /** Subtask's parent task id; null = top-level task. ONE level only. */
    val parentTaskId: String? = null,
    /** Project/list id (Feature 2); null = no project. */
    val projectId: String? = null,
    /** Date (yyyy-MM-dd) the task was carried over from (Feature 16). */
    val carriedOverFromDate: String? = null,
    /** Template this task was created from (Feature 13); null = not from a template. */
    val templateId: String? = null,
    /** Repeat the reminder every N minutes until completed; null = once (Feature 6). */
    val reminderRepeatMinutes: Int? = null,
    /**
     * Board order (S1-4g, MIGRATION_8_9): the task's position inside its
     * status column — 0 = top, dense 0..n-1 per status, renumbered on
     * every drop. Appended LAST so the migration's ADD COLUMN matches
     * Room's expected column order. Subtasks keep 0 (they have no Board
     * presence); the Board only ever orders top-level tasks.
     */
    val boardOrder: Int = 0,
    /**
     * Sprint 5-5 (feature 36, Q10=(a)): the assigned member. UID is
     * the source of truth; the name is denormalized display text (same
     * pattern as creatorName/authorName on group/notice docs) so the
     * member's own list can show the name from the local row alone.
     * Both null = unassigned. Appended after boardOrder (same
     * last-column convention).
     */
    val assigneeUid: String? = null,
    val assigneeName: String? = null
)

@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val locationOrLink: String? = null,
    val eventType: String = "MEETING", // "MEETING", "FOCUS_TIME"
    val isAllDay: Boolean = false,
    val googleCalendarEventId: Long? = null,
    val isFromGoogleCalendar: Boolean = false,
    // Group feature (v5): null for personal events. A non-null groupId marks
    // a group schedule — it must never be exported to Google Calendar and
    // never be uploaded via the personal sync path (see CalendarRepository).
    val groupId: String? = null
)

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "uid"]
)
data class GroupMemberEntity(
    val groupId: String,
    val uid: String,
    val displayName: String,
    val cachedAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val code: String,
    val creatorUid: String,
    val joinedAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "nudgy_memories")
data class NudgyMemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val content: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "daily_briefs",
    primaryKeys = ["userId", "dateString"]
)
data class DailyBriefEntity(
    val userId: String,
    val dateString: String,
    val headline: String,
    val summary: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val senderIsUser: Boolean,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val actionType: String = "NONE",
    val associatedTaskId: String? = null,
    val associatedTaskTitle: String? = null,
    val associatedTaskDueMillis: Long? = null,
    val associatedEventId: String? = null,
    val associatedEventTitle: String? = null,
    val associatedEventStartMillis: Long? = null,
    val associatedEventEndMillis: Long? = null,
    val focusStartMillis: Long? = null,
    val focusEndMillis: Long? = null,
    val focusDurationMinutes: Int = 45,
    val focusActionCompleted: Boolean = false,
    val quickSuggestionsRaw: String = ""
)
