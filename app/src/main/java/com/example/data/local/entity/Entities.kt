package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
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
    val groupId: String? = null
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
