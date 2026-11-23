package com.nextgentool.nudgy.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.entity.ProjectEntity
import com.nextgentool.nudgy.data.local.entity.SavedViewEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.model.TaskStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 1-1 — MIGRATION_7_8 test (MANDATORY: no S1-1 merge without it).
 *
 * Builds a real v7 database from raw SQL (the exact v7 schema, copied
 * from the pre-Sprint-1 entities), inserts tester-shaped fixture rows,
 * then opens it with Room + MIGRATION_7_8. Room runs the migration AND
 * validates the resulting schema against the v8 entities on open — so
 * a wrong ALTER/CREATE (a missing DEFAULT, a mismatched FK clause, a
 * column out of order) fails loudly here instead of on a tester's phone.
 *
 * No MigrationTestHelper: the project keeps exportSchema = false, so
 * there are no schema JSON files for the helper to read. The fixture
 * below is the helper, hand-rolled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration7To8Test {

    private val dbName = "migration-7-8-test.db"
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
     * The v7 schema, verbatim from the pre-Sprint-1 entities. Only the
     * tables matter here: MIGRATION_7_8 touches `tasks` and creates six
     * new tables, but Room validates ALL tables on open, so the fixture
     * must contain every v7 table.
     */
    private fun createV7Database() {
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
        // Room records the schema version in the file header (user_version
        // pragma); the migration only runs if it reads 7.
        db.setVersion(7)

        // Fixture rows — shaped like real tester data.
        val insertTask = """
            INSERT INTO `tasks`
            (`id`, `userId`, `title`, `description`, `dueDateEpochMillis`, `priority`,
             `category`, `isCompleted`, `completedAtEpochMillis`, `reminderSet`,
             `reminderEpochMillis`, `recurrenceRule`, `isSyncedToCloud`,
             `createdAtEpochMillis`, `updatedAtEpochMillis`, `groupId`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        // Pending personal task with a reminder + recurrence.
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "task-pending", "user-1", "Buy milk", "2 litres",
                DUE, "HIGH", "PERSONAL", 0, null, 1,
                REMINDER, "DAILY", 0, CREATED, CREATED, null
            )
        )
        // Completed personal task — must backfill to status DONE.
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "task-done", "user-1", "Read book", null,
                DUE, "MEDIUM", "STUDY", 1, DUE, 0,
                null, null, 1, CREATED, CREATED, null
            )
        )
        // Group task — groupId must survive the migration untouched.
        db.execSQL(
            insertTask,
            arrayOf<Any?>(
                "task-group", "user-1", "Group homework", null,
                DUE, "LOW", "STUDY", 0, null, 0,
                null, null, 1, CREATED, CREATED, "group-1"
            )
        )
        db.close()
    }

    private fun openMigratedDatabase(): NudgyDatabase {
        // Opening with the CURRENT entities + all migrations runs the
        // chain 7 -> 8 -> 9 -> 10 and validates the whole schema.
        // (S1-4g: the entities are v9 now — MIGRATION_7_8 alone leaves
        // no path 8 -> 9, and Room throws. S4-0: the entities are v10
        // now — without MIGRATION_9_10 there is no path 9 -> 10 either.
        // The 8 -> 9 and 9 -> 10 steps do not touch the v7 assertions
        // below: 9 -> 10 only adds three empty tables.)
        return Room.databaseBuilder(context, NudgyDatabase::class.java, dbName)
            .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `migrate 7 to 8 keeps old tasks and backfills status`() = runTest {
        createV7Database()
        val db = openMigratedDatabase()
        try {
            val dao = db.taskDao()

            val pending = dao.getTaskById("task-pending")
            checkNotNull(pending)
            assertEquals("Buy milk", pending.title)
            assertEquals("2 litres", pending.description)
            assertEquals(DUE, pending.dueDateEpochMillis)
            assertEquals(Priority.HIGH, pending.priority)
            assertEquals(TaskCategory.PERSONAL, pending.category)
            assertEquals(false, pending.isCompleted)
            assertEquals(true, pending.reminderSet)
            assertEquals(REMINDER, pending.reminderEpochMillis)
            assertEquals("DAILY", pending.recurrenceRule)
            assertEquals(TaskStatus.TODO, pending.status)
            assertNull(pending.plannedStartEpochMillis)
            assertNull(pending.plannedDate)
            assertNull(pending.estimatedMinutes)
            assertNull(pending.actualMinutes)
            assertNull(pending.parentTaskId)
            assertNull(pending.projectId)
            assertNull(pending.carriedOverFromDate)
            assertNull(pending.templateId)
            assertNull(pending.reminderRepeatMinutes)

            val done = dao.getTaskById("task-done")
            checkNotNull(done)
            assertEquals(true, done.isCompleted)
            assertEquals(TaskStatus.DONE, done.status)
            assertEquals(DUE, done.completedAtEpochMillis)

            val group = dao.getTaskById("task-group")
            checkNotNull(group)
            assertEquals("group-1", group.groupId)
            assertEquals(TaskStatus.TODO, group.status)
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrate 7 to 8 creates the new tables and cascades work`() = runTest {
        createV7Database()
        val db = openMigratedDatabase()
        try {
            // New tables are usable right after the migration.
            val tag = TagEntity(id = "tag-1", userId = "user-1", name = "Home", color = "#0F766E")
            db.tagDao().insertTag(tag)
            db.tagDao().setTagsForTask("task-pending", listOf("tag-1"))
            assertEquals(listOf(tag), db.tagDao().getTagsForTaskOnce("task-pending"))

            val project = ProjectEntity(id = "proj-1", userId = "user-1", name = "Work", color = "#F97360")
            db.projectDao().insertProject(project)
            assertEquals(project, db.projectDao().getProjectById("user-1", "proj-1"))

            val view = SavedViewEntity(
                id = "view-1", userId = "user-1", name = "High priority",
                filterJson = """{"priority":"HIGH"}"""
            )
            db.savedViewDao().insertSavedView(view)
            assertEquals(listOf(view), db.savedViewDao().getSavedViewsOnce("user-1"))

            // Deleting a tag removes its links but never the task.
            db.tagDao().deleteTagById("user-1", "tag-1")
            assertTrue(db.tagDao().getTagsForTaskOnce("task-pending").isEmpty())
            assertEquals("Buy milk", db.taskDao().getTaskById("task-pending")?.title)

            // Deleting a task removes its tag links (CASCADE).
            db.tagDao().insertTag(tag)
            db.tagDao().setTagsForTask("task-pending", listOf("tag-1"))
            db.taskDao().deleteTaskById("task-pending")
            assertTrue(db.tagDao().getTagsForTaskOnce("task-pending").isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `fresh v8 install round-trips the new columns`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val task = TaskEntity(
                id = "task-v8", userId = "user-1", title = "Plan the day",
                dueDateEpochMillis = 0L, // No date (Feature 1 sentinel)
                status = TaskStatus.IN_PROGRESS,
                estimatedMinutes = 30,
                projectId = "proj-1",
                parentTaskId = null
            )
            db.taskDao().insertTask(task)
            val read = db.taskDao().getTaskById("task-v8")
            checkNotNull(read)
            assertEquals(TaskStatus.IN_PROGRESS, read.status)
            assertEquals(30, read.estimatedMinutes)
            assertEquals(0L, read.dueDateEpochMillis)
            assertEquals(false, read.isCompleted)

            val subtask = TaskEntity(
                id = "task-sub", userId = "user-1", title = "Draft outline",
                parentTaskId = "task-v8"
            )
            db.taskDao().insertTask(subtask)
            assertEquals(1, db.taskDao().getSubtaskCount("task-v8"))
            assertEquals(0, db.taskDao().getSubtaskCompletedCount("task-v8"))
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DUE = 1_780_000_000_000L
        const val REMINDER = DUE - 2 * 60 * 60 * 1000L
        const val CREATED = 1_779_900_000_000L
    }
}
