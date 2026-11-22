package com.nextgentool.nudgy.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.model.TaskStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 1-4g — MIGRATION_8_9 test (MANDATORY: no S1-4g merge without it).
 *
 * Same hand-rolled pattern as Migration7To8Test (exportSchema = false,
 * so no MigrationTestHelper): builds a real v8 database from raw SQL
 * (the exact v8 schema — v7 tables + MIGRATION_7_8's columns and six
 * tables), inserts tester-shaped fixture rows, then opens it with Room
 * + MIGRATION_8_9. Room runs the migration AND validates the resulting
 * schema against the v9 entities on open.
 *
 * The backfill must NOT change anyone's board: TODO / IN_PROGRESS keep
 * today's due-date order (no-date last), DONE keeps completedAt DESC.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration8To9Test {

    private val dbName = "migration-8-9-test.db"
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
     * The v8 schema, verbatim: the v7 tables (as in Migration7To8Test)
     * + MIGRATION_7_8's ten task columns and six new tables. Room
     * validates ALL tables on open, so the fixture must contain every
     * v8 table.
     */
    private fun createV8Database() {
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
        // Room records the schema version in the file header (user_version
        // pragma); the migration only runs if it reads 8.
        db.setVersion(8)

        // Fixture rows — shaped like real tester data, one per backfill rule.
        val insertTask = """
            INSERT INTO `tasks`
            (`id`, `userId`, `title`, `description`, `dueDateEpochMillis`, `priority`,
             `category`, `isCompleted`, `completedAtEpochMillis`, `reminderSet`,
             `reminderEpochMillis`, `recurrenceRule`, `isSyncedToCloud`,
             `createdAtEpochMillis`, `updatedAtEpochMillis`, `groupId`, `status`,
             `plannedStartEpochMillis`, `plannedDate`, `estimatedMinutes`,
             `actualMinutes`, `parentTaskId`, `projectId`, `carriedOverFromDate`,
             `templateId`, `reminderRepeatMinutes`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        // TODO, due order: early (1000) < group (1500) < late (3000) < no-date (0).
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "t-early", "user-1", "Early", null,
                1000L, "MEDIUM", "WORK", 0, null, 0,
                null, null, 0, CREATED, CREATED, null, "TODO",
                null, null, 30, null, null, null, null, null, null
            )
        )
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "t-late", "user-1", "Late", null,
                3000L, "MEDIUM", "WORK", 0, null, 0,
                null, null, 0, CREATED, CREATED, null, "TODO",
                null, null, null, null, null, null, null, null, null
            )
        )
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "t-nodate", "user-1", "No date", null,
                0L, "LOW", "PERSONAL", 0, null, 0,
                null, null, 0, CREATED, CREATED, null, "TODO",
                null, null, null, null, null, null, null, null, null
            )
        )
        // IN_PROGRESS, due order: p-2 (500) before p-1 (2000).
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "p-1", "user-1", "In progress late", null,
                2000L, "HIGH", "WORK", 0, null, 0,
                null, null, 0, CREATED, CREATED, null, "IN_PROGRESS",
                null, null, null, null, null, null, null, null, null
            )
        )
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "p-2", "user-1", "In progress early", null,
                500L, "HIGH", "WORK", 0, null, 0,
                null, null, 0, CREATED, CREATED, null, "IN_PROGRESS",
                null, null, null, null, null, null, null, null, null
            )
        )
        // DONE, completedAt DESC: d-new (4000) before d-old (1000).
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "d-old", "user-1", "Done old", null,
                1000L, "MEDIUM", "STUDY", 1, 1000L, 0,
                null, null, 1, CREATED, CREATED, null, "DONE",
                null, null, null, null, null, null, null, null, null
            )
        )
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "d-new", "user-1", "Done new", null,
                1000L, "MEDIUM", "STUDY", 1, 4000L, 0,
                null, null, 1, CREATED, CREATED, null, "DONE",
                null, null, null, null, null, null, null, null, null
            )
        )
        // Subtask: backfill must NOT touch it (no Board presence).
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "sub-1", "user-1", "Subtask", null,
                1000L, "LOW", "WORK", 0, null, 0,
                null, null, 0, CREATED, CREATED, null, "TODO",
                null, null, null, null, "t-early", null, null, null, null
            )
        )
        // Group row: survives with its groupId, ordered with the TODOs.
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "g-1", "user-1", "Group homework", null,
                1500L, "LOW", "STUDY", 0, null, 0,
                null, null, 1, CREATED, CREATED, "group-1", "TODO",
                null, null, null, null, null, null, null, null, null
            )
        )
        db.close()
    }

    private fun openMigratedDatabase(): NudgyDatabase {
        // Opening with the CURRENT entities + both migrations runs the
        // chain 8 -> 9 -> 10 and validates the whole schema. (S4-0:
        // the entities are v10 now — MIGRATION_8_9 alone leaves no
        // path 9 -> 10, and Room throws. The 9 -> 10 step only adds
        // three empty tables and does not touch the v8 assertions below.)
        // Any mismatch throws here.
        return Room.databaseBuilder(context, NudgyDatabase::class.java, dbName)
            .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `migrate 8 to 9 backfills boardOrder in todays visible order`() = runTest {
        createV8Database()
        val db = openMigratedDatabase()
        try {
            val dao = db.taskDao()

            // TODO by due ASC, no-date last.
            assertEquals(0, dao.getTaskById("t-early")?.boardOrder)
            assertEquals(1, dao.getTaskById("g-1")?.boardOrder)
            assertEquals(2, dao.getTaskById("t-late")?.boardOrder)
            assertEquals(3, dao.getTaskById("t-nodate")?.boardOrder)
            // IN_PROGRESS by due ASC.
            assertEquals(0, dao.getTaskById("p-2")?.boardOrder)
            assertEquals(1, dao.getTaskById("p-1")?.boardOrder)
            // DONE by completedAt DESC (most recent on top).
            assertEquals(0, dao.getTaskById("d-new")?.boardOrder)
            assertEquals(1, dao.getTaskById("d-old")?.boardOrder)
            // Subtask untouched.
            assertEquals(0, dao.getTaskById("sub-1")?.boardOrder)
            assertEquals("t-early", dao.getTaskById("sub-1")?.parentTaskId)

            // Old columns survive intact.
            val early = dao.getTaskById("t-early")
            checkNotNull(early)
            assertEquals("Early", early.title)
            assertEquals(TaskStatus.TODO, early.status)
            assertEquals(30, early.estimatedMinutes)
            assertEquals(Priority.MEDIUM, early.priority)
            assertEquals(TaskCategory.WORK, early.category)
            val group = dao.getTaskById("g-1")
            checkNotNull(group)
            assertEquals("group-1", group.groupId)
            assertEquals(TaskStatus.TODO, group.status)
            val done = dao.getTaskById("d-new")
            checkNotNull(done)
            assertEquals(true, done.isCompleted)
            assertEquals(4000L, done.completedAtEpochMillis)
        } finally {
            db.close()
        }
    }

    @Test
    fun `fresh v9 install round-trips boardOrder`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val task = TaskEntity(
                id = "task-v9", userId = "user-1", title = "Plan the day",
                dueDateEpochMillis = 0L,
                status = TaskStatus.IN_PROGRESS,
                boardOrder = 2
            )
            db.taskDao().insertTask(task)
            val read = db.taskDao().getTaskById("task-v9")
            checkNotNull(read)
            assertEquals(2, read.boardOrder)
            assertEquals(TaskStatus.IN_PROGRESS, read.status)

            // Default is 0 (append path sets it explicitly; the default
            // only matters for rows that never went through createTask).
            val plain = TaskEntity(id = "task-default", userId = "user-1", title = "Plain")
            db.taskDao().insertTask(plain)
            assertEquals(0, db.taskDao().getTaskById("task-default")?.boardOrder)
            assertNull(db.taskDao().getTaskById("task-default")?.parentTaskId)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val CREATED = 1_779_900_000_000L
    }
}
