package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `daily_briefs_new` (
                `userId` TEXT NOT NULL,
                `dateString` TEXT NOT NULL,
                `headline` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`userId`, `dateString`)
            )
        """.trimIndent())

        try {
            db.execSQL("""
                INSERT OR IGNORE INTO `daily_briefs_new` (`userId`, `dateString`, `headline`, `summary`, `createdAtEpochMillis`)
                SELECT `userId`, `dateString`, `headline`, `summary`, `createdAtEpochMillis` FROM `daily_briefs`
            """.trimIndent())
            db.execSQL("DROP TABLE `daily_briefs`")
        } catch (e: Exception) {
            // Table did not exist yet
        }

        db.execSQL("ALTER TABLE `daily_briefs_new` RENAME TO `daily_briefs`")
    }
}

@Database(
    entities = [
        TaskEntity::class,
        CalendarEventEntity::class,
        NudgyMemoryEntity::class,
        DailyBriefEntity::class,
        ChatMessageEntity::class
    ],
    version = 4,
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
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
