package com.nextgentool.nudgy.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.entity.HabitEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 5-0 — MIGRATION_10_11 test (MANDATORY: no S5-0 merge without it).
 *
 * Same hand-rolled pattern as Migration9To10Test (exportSchema = false,
 * so no MigrationTestHelper): builds a real v10 database from raw SQL
 * (the exact v10 schema — v9 tables + MIGRATION_9_10's focus_sessions,
 * habits and habit_check_ins tables), inserts tester-shaped fixture
 * rows, then opens it with Room + MIGRATION_10_11. Room runs the
 * migration AND validates the resulting schema against the v11
 * entities on open.
 *
 * MIGRATION_10_11 makes NO schema change (TaskEntity.groupId already
 * exists since MIGRATION_4_5 — the spec's assumption was wrong, verified
 * in code): the test proves a v10 tester database opens as v11 with
 * every row — group rows and personal rows — intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration10To11Test {

    private val dbName = "migration-10-11-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    /**
     * The v10 schema, verbatim: the v9 tables (as in Migration9To10Test)
     * + MIGRATION_9_10's three tables. Room validates ALL tables on
     * open, so the fixture must contain every v10 table.
     */
    private fun createV10Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tasks` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `description` TEXT,
                `dueDateEpochMillis` INTEGER NOT NULL,
                `priority` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `isCompleted` INTEGER NOT NULL,
                `completedAtEpochMillis` INTEGER,
                `reminderSet` INTEGER NOT NULL,
                `reminderEpochMillis` INTEGER,
                `recurrenceRule` TEXT,
                `isSyncedToCloud` INTEGER NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                `groupId` TEXT,
                `status` TEXT NOT NULL DEFAULT 'TODO',
                `plannedStartEpochMillis` INTEGER,
                `plannedDate` TEXT,
                `estimatedMinutes` INTEGER,
                `actualMinutes` INTEGER,
                `parentTaskId` TEXT,
                `projectId` TEXT,
                `carriedOverFromDate` TEXT,
                `templateId` TEXT,
                `reminderRepeatMinutes` INTEGER,
                `boardOrder` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `calendar_events` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `startEpochMillis` INTEGER NOT NULL,
                `endEpochMillis` INTEGER NOT NULL,
                `locationOrLink` TEXT,
                `eventType` TEXT NOT NULL,
                `isAllDay` INTEGER NOT NULL,
                `googleCalendarEventId` INTEGER,
                `isFromGoogleCalendar` INTEGER NOT NULL,
                `groupId` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `group_members` (
                `groupId` TEXT NOT NULL,
                `uid` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `cachedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`groupId`, `uid`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `groups` (
                `groupId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `code` TEXT NOT NULL,
                `creatorUid` TEXT NOT NULL,
                `joinedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`groupId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `nudgy_memories` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `daily_briefs` (
                `userId` TEXT NOT NULL,
                `dateString` TEXT NOT NULL,
                `headline` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`userId`, `dateString`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `senderIsUser` INTEGER NOT NULL,
                `text` TEXT NOT NULL,
                `timestampMillis` INTEGER NOT NULL,
                `actionType` TEXT NOT NULL,
                `associatedTaskId` TEXT,
                `associatedTaskTitle` TEXT,
                `associatedTaskDueMillis` INTEGER,
                `associatedEventId` TEXT,
                `associatedEventTitle` TEXT,
                `associatedEventStartMillis` INTEGER,
                `associatedEventEndMillis` INTEGER,
                `focusStartMillis` INTEGER,
                `focusEndMillis` INTEGER,
                `focusDurationMinutes` INTEGER NOT NULL,
                `focusActionCompleted` INTEGER NOT NULL,
                `quickSuggestionsRaw` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tags` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `color` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `task_tags` (
                `taskId` TEXT NOT NULL,
                `tagId` TEXT NOT NULL,
                PRIMARY KEY(`taskId`, `tagId`),
                FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `projects` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `color` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_views` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `filterJson` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
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
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `task_templates` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `templateJson` TEXT NOT NULL,
                `isBuiltIn` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
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
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `habits` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `habit_check_ins` (
                `habitId` TEXT NOT NULL,
                `localDate` TEXT NOT NULL,
                `checkedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`habitId`, `localDate`),
                FOREIGN KEY(`habitId`) REFERENCES `habits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        // Room records the schema version in the file header (user_version
        // pragma); the migration only runs if it reads 10.
        db.setVersion(10)

        // Fixture rows — shaped like real tester data.
        db.execSQL(
            """
            INSERT INTO `tasks`
            (`id`, `userId`, `title`, `description`, `dueDateEpochMillis`, `priority`,
             `category`, `isCompleted`, `completedAtEpochMillis`, `reminderSet`,
             `reminderEpochMillis`, `recurrenceRule`, `isSyncedToCloud`,
             `createdAtEpochMillis`, `updatedAtEpochMillis`, `groupId`, `status`,
             `plannedStartEpochMillis`, `plannedDate`, `estimatedMinutes`,
             `actualMinutes`, `parentTaskId`, `projectId`, `carriedOverFromDate`,
             `templateId`, `reminderRepeatMinutes`, `boardOrder`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "t-1", "user-1", "Keep me", null,
                1000L, "MEDIUM", "WORK", 0, null, 0,
                null, null, 0, CREATED, CREATED, null, "TODO",
                null, null, 30, null, null, null, null, null, null, 2
            )
        )
        db.execSQL(
            """
            INSERT INTO `tasks`
            (`id`, `userId`, `title`, `description`, `dueDateEpochMillis`, `priority`,
             `category`, `isCompleted`, `completedAtEpochMillis`, `reminderSet`,
             `reminderEpochMillis`, `recurrenceRule`, `isSyncedToCloud`,
             `createdAtEpochMillis`, `updatedAtEpochMillis`, `groupId`, `status`,
             `plannedStartEpochMillis`, `plannedDate`, `estimatedMinutes`,
             `actualMinutes`, `parentTaskId`, `projectId`, `carriedOverFromDate`,
             `templateId`, `reminderRepeatMinutes`, `boardOrder`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "t-group", "user-1", "Group homework", null,
                2000L, "HIGH", "WORK", 0, null, 0,
                null, null, 0, CREATED, CREATED, "g-1", "TODO",
                null, null, null, null, null, null, null, null, null, 5
            )
        )
        db.execSQL(
            """
            INSERT INTO `calendar_events`
            (`id`, `userId`, `title`, `startEpochMillis`, `endEpochMillis`,
             `locationOrLink`, `eventType`, `isAllDay`, `googleCalendarEventId`,
             `isFromGoogleCalendar`, `groupId`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "e-1", "user-1", "Standup", 1000L, 2000L,
                null, "MEETING", 0, null, 0, null
            )
        )
        db.close()
    }

    private fun openMigratedDatabase(): NudgyDatabase {
        // Opening with the v11 entities + MIGRATION_10_11 runs the
        // migration and validates the whole schema. Any mismatch throws here.
        return Room.databaseBuilder(context, NudgyDatabase::class.java, dbName)
            .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `migrate 10 to 11 keeps tester data including group rows`() = runTest {
        createV10Database()
        val db = openMigratedDatabase()
        try {
            // Old data survives intact — personal row and group row alike.
            val task = db.taskDao().getTaskById("t-1")
            checkNotNull(task)
            assertEquals("Keep me", task.title)
            assertEquals(TaskStatus.TODO, task.status)
            assertEquals(30, task.estimatedMinutes)
            assertEquals(2, task.boardOrder)
            assertNull(task.groupId)
            val groupTask = db.taskDao().getTaskById("t-group")
            checkNotNull(groupTask)
            assertEquals("Group homework", groupTask.title)
            assertEquals("g-1", groupTask.groupId)
            assertEquals(5, groupTask.boardOrder)
            val event = db.calendarDao().getEventById("e-1")
            checkNotNull(event)
            assertEquals("Standup", event.title)

            // The S4 tables survive the (empty) migration too.
            assertEquals(emptyList<HabitEntity>(), db.habitDao().getHabitsOnce("user-1"))
            assertEquals(0L, db.focusSessionDao().getFocusMinutesInRange("user-1", 0L, Long.MAX_VALUE))
        } finally {
            db.close()
        }
    }

    @Test
    fun `fresh v11 install round-trips group and personal tasks`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val groupTask = com.nextgentool.nudgy.data.local.entity.TaskEntity(
                id = "t-new-group", userId = "user-1", title = "Group task",
                groupId = "g-9"
            )
            db.taskDao().insertTask(groupTask)
            val personalTask = com.nextgentool.nudgy.data.local.entity.TaskEntity(
                id = "t-new-personal", userId = "user-1", title = "Personal task"
            )
            db.taskDao().insertTask(personalTask)
            assertEquals("g-9", db.taskDao().getTaskById("t-new-group")?.groupId)
            assertNull(db.taskDao().getTaskById("t-new-personal")?.groupId)
            assertEquals(
                listOf("t-new-group"),
                db.taskDao().getTaskIdsByGroupId("user-1", "g-9")
            )
        } finally {
            db.close()
        }
    }

    private companion object {
        const val CREATED = 1_779_900_000_000L
    }
}
