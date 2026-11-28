package com.nextgentool.nudgy.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nextgentool.nudgy.data.local.dao.CalendarDao
import com.nextgentool.nudgy.data.local.dao.ChatDao
import com.nextgentool.nudgy.data.local.dao.DailyBriefDao
import com.nextgentool.nudgy.data.local.dao.FocusSessionDao
import com.nextgentool.nudgy.data.local.dao.GroupDao
import com.nextgentool.nudgy.data.local.dao.GroupMemberDao
import com.nextgentool.nudgy.data.local.dao.HabitDao
import com.nextgentool.nudgy.data.local.dao.MemoryDao
import com.nextgentool.nudgy.data.local.dao.ProjectDao
import com.nextgentool.nudgy.data.local.dao.SavedViewDao
import com.nextgentool.nudgy.data.local.dao.SharedListCommentDao
import com.nextgentool.nudgy.data.local.dao.SharedListDao
import com.nextgentool.nudgy.data.local.dao.SharedListItemDao
import com.nextgentool.nudgy.data.local.dao.TagDao
import com.nextgentool.nudgy.data.local.dao.TaskAttachmentDao
import com.nextgentool.nudgy.data.local.dao.TaskDao
import com.nextgentool.nudgy.data.local.dao.TaskTemplateDao
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.ChatMessageEntity
import com.nextgentool.nudgy.data.local.entity.DailyBriefEntity
import com.nextgentool.nudgy.data.local.entity.FocusSessionEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.GroupMemberEntity
import com.nextgentool.nudgy.data.local.entity.HabitCheckInEntity
import com.nextgentool.nudgy.data.local.entity.HabitEntity
import com.nextgentool.nudgy.data.local.entity.NudgyMemoryEntity
import com.nextgentool.nudgy.data.local.entity.ProjectEntity
import com.nextgentool.nudgy.data.local.entity.SavedViewEntity
import com.nextgentool.nudgy.data.local.entity.SharedListCommentEntity
import com.nextgentool.nudgy.data.local.entity.SharedListEntity
import com.nextgentool.nudgy.data.local.entity.SharedListItemEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.local.entity.TaskAttachmentEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.local.entity.TaskTagCrossRef
import com.nextgentool.nudgy.data.local.entity.TaskTemplateEntity

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

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Sprint 1-1 (spec §4): the whole Sprint 1 schema change, once.
        // tasks + 10 columns, then 6 new tables. Every new column is
        // nullable or DEFAULTed, so tester rows survive untouched.
        //
        // Column order below MUST match TaskEntity's field order — Room
        // validates the migrated schema against the entities on open.
        // status carries a SQL DEFAULT 'TODO' (matching the entity's
        // @ColumnInfo defaultValue); the backfill then flips completed
        // rows to 'DONE'. New tables' CREATE TABLE statements must match
        // the entity definitions exactly (column order, FK clauses).
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'TODO'")
        db.execSQL("UPDATE `tasks` SET `status` = 'DONE' WHERE `isCompleted` = 1")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `plannedStartEpochMillis` INTEGER")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `plannedDate` TEXT")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `estimatedMinutes` INTEGER")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `actualMinutes` INTEGER")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `parentTaskId` TEXT")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `projectId` TEXT")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `carriedOverFromDate` TEXT")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `templateId` TEXT")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `reminderRepeatMinutes` INTEGER")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `tags` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `color` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `task_tags` (
                `taskId` TEXT NOT NULL,
                `tagId` TEXT NOT NULL,
                PRIMARY KEY(`taskId`, `tagId`),
                FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `projects` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `color` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `saved_views` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `filterJson` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `task_attachments` (
                `id` TEXT NOT NULL,
                `taskId` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `localPath` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `task_templates` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `templateJson` TEXT NOT NULL,
                `isBuiltIn` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
    }
}

// MIGRATION_8_9's backfill row. Top-level (not inside the anonymous
// object): a class declared in an object expression is treated as a
// local class, and local classes cannot be private.
private data class BoardOrderRow(
    val id: String,
    val status: String,
    val due: Long,
    val completedAt: Long?
)

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Sprint 1-4g (board reorder): one new column, appended LAST
        // (matching TaskEntity's field order — Room validates the
        // migrated schema against the entities on open).
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `boardOrder` INTEGER NOT NULL DEFAULT 0")
        // Backfill in Kotlin, NOT ROW_NUMBER() — the window function
        // needs SQLite 3.25+ (Android API 30+) and minSdk is 24.
        // Per status, in TODAY'S visible order, so nobody's board
        // changes on upgrade: TODO / IN_PROGRESS by due date ASC
        // (no-date, 0L, last — the dueSortKey rule); DONE by
        // completedAt DESC (most recently completed on top). Top-level
        // tasks only — subtasks keep 0 (they have no Board presence).
        val cursor = db.query(
            "SELECT `id`, `status`, `dueDateEpochMillis`, `completedAtEpochMillis` " +
                "FROM `tasks` WHERE `parentTaskId` IS NULL"
        )
        cursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val statusCol = it.getColumnIndexOrThrow("status")
            val dueCol = it.getColumnIndexOrThrow("dueDateEpochMillis")
            val completedAtCol = it.getColumnIndexOrThrow("completedAtEpochMillis")
            val rows = mutableListOf<BoardOrderRow>()
            while (it.moveToNext()) {
                rows += BoardOrderRow(
                    id = it.getString(idCol),
                    status = it.getString(statusCol),
                    due = it.getLong(dueCol),
                    completedAt = if (it.isNull(completedAtCol)) null else it.getLong(completedAtCol)
                )
            }
            for (status in listOf("TODO", "IN_PROGRESS", "DONE")) {
                val column = rows.filter { row -> row.status == status }
                val ordered = if (status == "DONE") {
                    column.sortedByDescending { row -> row.completedAt ?: Long.MIN_VALUE }
                } else {
                    column.sortedBy { row -> if (row.due == 0L) Long.MAX_VALUE else row.due }
                }
                ordered.forEachIndexed { index, row ->
                    db.execSQL(
                        "UPDATE `tasks` SET `boardOrder` = ? WHERE `id` = ?",
                        arrayOf<Any?>(index, row.id)
                    )
                }
            }
        }
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Sprint 4-0 (spec §3): three new tables, no column changes.
        // No backfill — a tester install has no focus/habit rows yet.
        // Every CREATE TABLE below MUST match its entity definition
        // exactly (column order, types, FK clauses) — Room validates
        // the migrated schema against the entities on open.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `focus_sessions` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `taskId` TEXT,
                `startedAtEpochMillis` INTEGER NOT NULL,
                `plannedEndEpochMillis` INTEGER NOT NULL,
                `endedAtEpochMillis` INTEGER,
                `durationMinutes` INTEGER NOT NULL,
                `completed` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `habits` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `habit_check_ins` (
                `habitId` TEXT NOT NULL,
                `localDate` TEXT NOT NULL,
                `checkedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`habitId`, `localDate`),
                FOREIGN KEY(`habitId`) REFERENCES `habits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Sprint 5-0 (spec §3): NO schema change. The spec assumed
        // TaskEntity still needed a groupId column, but it already exists
        // — MIGRATION_4_5 added `groupId` to `tasks` and
        // `calendar_events` in v1, and the rebuild carried it over.
        // The version bump to 11 marks the Sprint 5 baseline (same
        // precedent as MIGRATION_6_7 for the rebuild baseline): every
        // tester install moves 10 -> 11 with their data intact, and
        // later Sprint 5 migrations build on v11. Never edit this
        // migration after a v11 build ships.
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Sprint 5-5 (feature 36, Q10=(a)): the assigned member on a
        // group task. Two nullable columns, appended last (the
        // boardOrder convention): assigneeUid is the source of truth,
        // assigneeName is denormalized display text so the member's
        // own list can show the name from the local row alone (no
        // member lookup exists in the Tasks/Today/Board/Matrix rows).
        // Old rows migrate with both null = unassigned; personal
        // tasks never carry an assignee.
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `assigneeUid` TEXT")
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `assigneeName` TEXT")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Sprint 6-3 (feature 40, Q3=(a)): the onboarding sample
        // marker. One NOT NULL column with DEFAULT 0, appended last
        // (the boardOrder/assignee convention): every pre-existing
        // row migrates false — only tasks the S6-3 tutorial seeds
        // carry true, so no tester ever sees a phantom "Sample" chip
        // on their own tasks.
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `isSample` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Chat isVoice follow-up (approved 2026-11-27): whether a
        // user chat message came from the voice brain dump. One NOT
        // NULL column with DEFAULT 0, appended last (the
        // boardOrder/assignee convention): every pre-existing row
        // migrates false — only messages the voice path stores carry
        // true, so no old bubble ever shows "YOU · VOICE" by mistake.
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `isVoice` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Shared list, micro-task 1 (spec s7-shared-list-scope-spec,
        // approved 2026-11-27): three new tables, no column changes.
        // No backfill — no tester install has shared-list rows yet.
        // Every CREATE TABLE below MUST match its entity definition
        // exactly (column order, types, FK clauses) — Room validates
        // the migrated schema against the entities on open.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `shared_lists` (
                `id` TEXT NOT NULL,
                `groupId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `createdByUid` TEXT NOT NULL,
                `createdByName` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `shared_list_items` (
                `id` TEXT NOT NULL,
                `listId` TEXT NOT NULL,
                `groupId` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `addedByUid` TEXT NOT NULL,
                `addedByName` TEXT NOT NULL,
                `done` INTEGER NOT NULL,
                `doneByUid` TEXT,
                `assigneeUid` TEXT,
                `assigneeName` TEXT,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`listId`) REFERENCES `shared_lists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `shared_list_comments` (
                `id` TEXT NOT NULL,
                `itemId` TEXT NOT NULL,
                `listId` TEXT NOT NULL,
                `groupId` TEXT NOT NULL,
                `authorUid` TEXT NOT NULL,
                `authorName` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`itemId`) REFERENCES `shared_list_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
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
        GroupMemberEntity::class,
        TagEntity::class,
        TaskTagCrossRef::class,
        ProjectEntity::class,
        SavedViewEntity::class,
        TaskAttachmentEntity::class,
        TaskTemplateEntity::class,
        FocusSessionEntity::class,
        HabitEntity::class,
        HabitCheckInEntity::class,
        SharedListEntity::class,
        SharedListItemEntity::class,
        SharedListCommentEntity::class
    ],
    version = 15,
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
    abstract fun tagDao(): TagDao
    abstract fun projectDao(): ProjectDao
    abstract fun savedViewDao(): SavedViewDao
    abstract fun taskAttachmentDao(): TaskAttachmentDao
    abstract fun taskTemplateDao(): TaskTemplateDao
    // Sprint 4-0.
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun habitDao(): HabitDao
    // Shared list, micro-task 1.
    abstract fun sharedListDao(): SharedListDao
    abstract fun sharedListItemDao(): SharedListItemDao
    abstract fun sharedListCommentDao(): SharedListCommentDao

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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
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
