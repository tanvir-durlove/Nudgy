package com.nextgentool.nudgy.data.local

import androidx.room.TypeConverter
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.model.TaskCategory

class Converters {
    @TypeConverter
    fun fromPriority(priority: Priority): String = priority.name

    @TypeConverter
    fun toPriority(value: String): Priority = try {
        Priority.valueOf(value)
    } catch (e: Exception) {
        Priority.MEDIUM
    }

    @TypeConverter
    fun fromCategory(category: TaskCategory): String = category.name

    @TypeConverter
    fun toCategory(value: String): TaskCategory = try {
        TaskCategory.valueOf(value)
    } catch (e: Exception) {
        TaskCategory.WORK
    }

    @TypeConverter
    fun fromTaskStatus(status: TaskStatus): String = status.name

    @TypeConverter
    fun toTaskStatus(value: String): TaskStatus = try {
        TaskStatus.valueOf(value)
    } catch (e: Exception) {
        TaskStatus.TODO
    }

    // Sprint 4-0 — FocusSessionKind, same TEXT pattern. An unknown value
    // (e.g. a hand-edited DB) falls back to FOCUS, never crashes.
    @TypeConverter
    fun fromFocusSessionKind(kind: FocusSessionKind): String = kind.name

    @TypeConverter
    fun toFocusSessionKind(value: String): FocusSessionKind = try {
        FocusSessionKind.valueOf(value)
    } catch (e: Exception) {
        FocusSessionKind.FOCUS
    }
}
