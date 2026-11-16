
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
import com.example.data.local.dao.GroupDao
import com.example.data.local.dao.GroupMemberDao
import com.example.data.local.dao.MemoryDao
import com.example.data.local.dao.TaskDao
import com.example.data.local.entity.CalendarEventEntity
import com.example.data.local.entity.ChatMessageEntity
import com.example.data.local.entity.DailyBriefEntity
import com.example.data.local.entity.GroupEntity
import com.example.data.local.entity.GroupMemberEntity
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

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Group feature: groupId marks group rows on tasks and events.
        // Added as a nullable column so existing personal rows are untouched.
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `groupId` TEXT")
        db.execSQL("ALTER TABLE `calendar_events` ADD COLUMN `groupId` TEXT")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `groups` (
                `groupId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `code` TEXT NOT NULL,
                `creatorUid` TEXT NOT NULL,
                `joinedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`groupId`)
            )
        """.trimIndent())
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Member-list cache (minimization): group_members holds the last
        // fetched member list per group; detail opens read it instantly
        // and refresh in the background.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `group_members` (
                `groupId` TEXT NOT NULL,
                `uid` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `cachedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`groupId`, `uid`)
            )
        """.trimIndent())
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Sprint 0 (scratch rebuild v2): NO schema change yet.
        // The version bump to 7 marks the rebuild baseline: every
        // tester install moves 6 -> 7 with their data intact.
        // Sprint 1 entity changes MUST go in a new MIGRATION_7_8 -
        // never edit this migration after a v7 build ships.
    }
}

@Database(
    entities = [
        TaskEntity::class,
        CalendarEventEntity::class,
        NudgyMemoryEntity::class,
        DailyBriefEntity::class,
        ChatMessageEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class
    ],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NudgyDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun calendarDao(): CalendarDao
    abstract fun memoryDao(): MemoryDao
    abstract fun dailyBriefDao(): DailyBriefDao
    abstract fun chatDao(): ChatDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMemberDao(): GroupMemberDao

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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    // Public-launch policy (revisited from the pre-launch
                    // note): destructive fallback is RETIRED from v5.
                    // Every distributed install is v4 (1.0.2 was the first
                    // Play closed-test release), so MIGRATION_4_5 and
                    // MIGRATION_5_6 cover every real install (4 -> 5 -> 6).
                    // A missing migration now fails loudly instead of
                    // silently wiping user data.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
