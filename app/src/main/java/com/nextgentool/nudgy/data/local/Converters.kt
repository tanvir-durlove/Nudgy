package com.nextgentool.nudgy.data.local

import androidx.room.TypeConverter
import com.nextgentool.nudgy.data.model.Priority
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
}
