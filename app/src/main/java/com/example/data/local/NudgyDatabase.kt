package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.data.local.dao.CalendarDao
import com.example.data.local.dao.ChatDao
import com.example.data.local.dao.DailyBriefDao
import com.example.data.local.dao.MemoryDao
import com.example.data.local.dao.TaskDao
import com.example.data.local.entity.CalendarEventEntity
import com.example.data.local.entity.ChatMessageEntity
import com.example.data.local.entity.DailyBriefEntity
import com.example.data.local.entity.NudgyMemoryEntity
import com.example.data.local.entity.TaskEntity

@Database(
    entities = [
        TaskEntity::class,
        CalendarEventEntity::class,
        NudgyMemoryEntity::class,
        DailyBriefEntity::class,
        ChatMessageEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NudgyDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun calendarDao(): CalendarDao
    abstract fun memoryDao(): MemoryDao
    abstract fun dailyBriefDao(): DailyBriefDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: NudgyDatabase? = null

        fun getInstance(context: Context): NudgyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NudgyDatabase::class.java,
                    "nudgy_local_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
