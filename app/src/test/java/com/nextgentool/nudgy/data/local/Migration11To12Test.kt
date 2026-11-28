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
 * Sprint 5-5 — MIGRATION_11_12 test (MANDATORY: no S5-5 merge without it).
 *
 * Same hand-rolled pattern as Migration10To11Test (exportSchema = false,
 * so no MigrationTestHelper): builds a real v11 database from raw SQL
 * (the exact v11 schema — identical to v10, MIGRATION_10_11 was a
 * no-op), inserts tester-shaped fixture rows, then opens it with Room
 * + MIGRATION_11_12. Room runs the migration AND validates the
 * resulting schema against the v12 entities on open.
 *
 * MIGRATION_11_12 adds two nullable columns to `tasks` —
 * `assigneeUid` and `assigneeName` (feature 36, assign): the test
 * proves a v11 tester database opens as v12 with every row intact
 * and the assignee defaulting to null (unassigned), and that a fresh
 * v12 install round-trips an assigned task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration11To12Test {

    private val dbName = "migration-11-12-test.db"
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
     * The v11 schema, verbatim: the v10 tables (MIGRATION_10_11 was a
     * no-op — same tables, same columns). Room validates ALL tables on
     * open, so the fixture must contain every v11 table.
     */
    private fun createV11Database() {
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
        // pragma); the migration only runs if it reads 11.
        db.setVersion(11)

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
        // Opening with the CURRENT (v15) entities + the chain from
        // MIGRATION_11_12 runs the migration and validates the whole schema. Any mismatch throws here.
        return Room.databaseBuilder(context, NudgyDatabase::class.java, dbName)
            .addMigrations(MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `migrate 11 to 12 keeps tester data and defaults the assignee to null`() = runTest {
        createV11Database()
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
            // Pre-migration rows had no assignee — the new columns
            // default to null (unassigned), never a phantom member.
            assertNull(task.assigneeUid)
            assertNull(task.assigneeName)
            val groupTask = db.taskDao().getTaskById("t-group")
            checkNotNull(groupTask)
            assertEquals("Group homework", groupTask.title)
            assertEquals("g-1", groupTask.groupId)
            assertEquals(5, groupTask.boardOrder)
            assertNull(groupTask.assigneeUid)
            assertNull(groupTask.assigneeName)
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
    fun `fresh v12 install round-trips group and personal tasks`() = runTest {
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
            // An assigned group task round-trips both assignee fields,
            // and unassigning (nulls) round-trips too.
            val assigned = groupTask.copy(assigneeUid = "user-2", assigneeName = "Bob")
            db.taskDao().insertTask(assigned)
            val readBack = db.taskDao().getTaskById("t-new-group")
            checkNotNull(readBack)
            assertEquals("user-2", readBack.assigneeUid)
            assertEquals("Bob", readBack.assigneeName)
            db.taskDao().insertTask(readBack.copy(assigneeUid = null, assigneeName = null))
            val unassigned = db.taskDao().getTaskById("t-new-group")
            checkNotNull(unassigned)
            assertNull(unassigned.assigneeUid)
            assertNull(unassigned.assigneeName)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val CREATED = 1_779_900_000_000L
    }
}
