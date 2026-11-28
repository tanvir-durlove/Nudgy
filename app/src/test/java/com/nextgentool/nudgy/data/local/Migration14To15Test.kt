package com.nextgentool.nudgy.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.entity.HabitEntity
import com.nextgentool.nudgy.data.local.entity.SharedListCommentEntity
import com.nextgentool.nudgy.data.local.entity.SharedListEntity
import com.nextgentool.nudgy.data.local.entity.SharedListItemEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Shared list, micro-task 1 — MIGRATION_14_15 test (MANDATORY: no merge
 * of this task without it).
 *
 * Same hand-rolled pattern as Migration13To14Test (exportSchema =
 * false, so no MigrationTestHelper): builds a real v14 database
 * from raw SQL (the exact v14 schema — v13 plus the isVoice column
 * MIGRATION_13_14 added to `chat_messages`), inserts tester-shaped
 * fixture rows, then opens it with Room + MIGRATION_14_15. Room runs
 * the migration AND validates the resulting schema against the v15
 * entities on open.
 *
 * MIGRATION_14_15 adds three new tables (`shared_lists`,
 * `shared_list_items`, `shared_list_comments`) and nothing else: the
 * test proves a v14 tester database opens as v15 with every row
 * intact (including a voice chat message, isVoice=true) and the new
 * tables empty, and that a fresh v15 install round-trips a list,
 * an item and a comment through the new DAOs — including the
 * CASCADE: deleting a list deletes its items and their comments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration14To15Test {

    private val dbName = "migration-14-15-test.db"
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
     * The v14 schema, verbatim: the v13 tables plus the isVoice
     * column MIGRATION_13_14 added to `chat_messages`. Room validates
     * ALL tables on open, so the fixture must contain every v14 table.
     */
    private fun createV14Database() {
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
                `assigneeUid` TEXT,
                `assigneeName` TEXT,
                `isSample` INTEGER NOT NULL DEFAULT 0,
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
                `isVoice` INTEGER NOT NULL DEFAULT 0,
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
        // pragma); the migration only runs if it reads 14.
        db.setVersion(14)

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
             `templateId`, `reminderRepeatMinutes`, `boardOrder`,
             `assigneeUid`, `assigneeName`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "t-1", "user-1", "Keep me", null,
                1000L, "MEDIUM", "WORK", 0, null, 0,
                null, null, 0, CREATED, CREATED, null, "TODO",
                null, null, 30, null, null, null, null, null, null, 2, "user-2", "Bob"
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
             `templateId`, `reminderRepeatMinutes`, `boardOrder`,
             `assigneeUid`, `assigneeName`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "t-group", "user-1", "Group homework", null,
                2000L, "HIGH", "WORK", 0, null, 0,
                null, null, 0, CREATED, CREATED, "g-1", "TODO",
                null, null, null, null, null, null, null, null, null, 5, null, null
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
        // A pre-migration chat message (v14 carries isVoice): a voice
        // brain-dump message the user sent before the update — it must
        // survive the step with its flag intact.
        db.execSQL(
            """
            INSERT INTO `chat_messages`
            (`id`, `userId`, `senderIsUser`, `text`, `timestampMillis`,
             `actionType`, `associatedTaskId`, `associatedTaskTitle`,
             `associatedTaskDueMillis`, `associatedEventId`,
             `associatedEventTitle`, `associatedEventStartMillis`,
             `associatedEventEndMillis`, `focusStartMillis`,
             `focusEndMillis`, `focusDurationMinutes`,
             `focusActionCompleted`, `quickSuggestionsRaw`, `isVoice`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "m-1", "user-1", 1, "Call Tasnim at 5pm", CREATED,
                "NONE", null, null, null, null, null, null, null,
                null, null, 45, 0, "", 1
            )
        )
        db.close()
    }

    private fun openMigratedDatabase(): NudgyDatabase {
        // Opening with the v15 entities + MIGRATION_14_15 runs the
        // migration and validates the whole schema. Any mismatch throws here.
        return Room.databaseBuilder(context, NudgyDatabase::class.java, dbName)
            .addMigrations(MIGRATION_14_15)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `migrate 14 to 15 keeps tester data and starts shared lists empty`() = runTest {
        createV14Database()
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
            // The v14 assignee and sample flag survive the migration untouched.
            assertEquals("user-2", task.assigneeUid)
            assertEquals("Bob", task.assigneeName)
            assertFalse(task.isSample)
            val groupTask = db.taskDao().getTaskById("t-group")
            checkNotNull(groupTask)
            assertEquals("Group homework", groupTask.title)
            assertEquals("g-1", groupTask.groupId)
            assertEquals(5, groupTask.boardOrder)
            assertNull(groupTask.assigneeUid)
            assertNull(groupTask.assigneeName)
            assertFalse(groupTask.isSample)
            val event = db.calendarDao().getEventById("e-1")
            checkNotNull(event)
            assertEquals("Standup", event.title)

            // The pre-migration voice message survives with its flag
            // intact — the migration adds tables, it touches no row.
            val entities = db.chatDao().getMessagesFlow("user-1").first()
            assertEquals(1, entities.size)
            assertEquals("Call Tasnim at 5pm", entities.single().text)
            assertTrue(entities.single().senderIsUser)
            assertTrue(entities.single().isVoice)

            // The S4 tables survive the migration too.
            assertEquals(emptyList<HabitEntity>(), db.habitDao().getHabitsOnce("user-1"))
            assertEquals(0L, db.focusSessionDao().getFocusMinutesInRange("user-1", 0L, Long.MAX_VALUE))

            // The three new tables exist and start empty — no tester
            // install has shared-list rows yet.
            assertEquals(emptyList<SharedListEntity>(), db.sharedListDao().getListsOnce("g-1"))
            assertEquals(emptyList<SharedListItemEntity>(), db.sharedListItemDao().getItemsOnce("l-1"))
            assertEquals(emptyList<SharedListCommentEntity>(), db.sharedListCommentDao().getCommentsOnce("i-1"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `fresh v15 install round-trips a list item and comment, cascade on delete`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // A list, two items (one done, one assigned) and a comment
            // round-trip through the new DAOs with every field intact.
            val list = SharedListEntity(
                id = "l-1", groupId = "g-1", name = "Monthly shopping",
                createdByUid = "user-1", createdByName = "Tanvir",
                createdAtEpochMillis = CREATED
            )
            db.sharedListDao().insertList(list)
            val milk = SharedListItemEntity(
                id = "i-1", listId = "l-1", groupId = "g-1", text = "Milk 2 L",
                addedByUid = "user-1", addedByName = "Tanvir",
                createdAtEpochMillis = CREATED, updatedAtEpochMillis = CREATED
            )
            val rice = SharedListItemEntity(
                id = "i-2", listId = "l-1", groupId = "g-1", text = "Rice 10 kg",
                addedByUid = "user-2", addedByName = "Karim",
                done = true, doneByUid = "user-2",
                assigneeUid = "user-2", assigneeName = "Karim",
                createdAtEpochMillis = CREATED, updatedAtEpochMillis = CREATED
            )
            db.sharedListItemDao().insertItems(listOf(milk, rice))
            val comment = SharedListCommentEntity(
                id = "c-1", itemId = "i-1", listId = "l-1", groupId = "g-1",
                authorUid = "user-2", authorName = "Karim",
                text = "The shop near the mosque is cheaper",
                createdAtEpochMillis = CREATED
            )
            db.sharedListCommentDao().insertComment(comment)

            val lists = db.sharedListDao().getListsOnce("g-1")
            assertEquals(1, lists.size)
            assertEquals("Monthly shopping", lists.single().name)
            assertEquals("user-1", lists.single().createdByUid)
            val items = db.sharedListItemDao().getItemsOnce("l-1")
            assertEquals(2, items.size)
            val milkBack = items.first { it.id == "i-1" }
            assertEquals("Milk 2 L", milkBack.text)
            assertFalse(milkBack.done)
            assertNull(milkBack.doneByUid)
            assertNull(milkBack.assigneeUid)
            val riceBack = items.first { it.id == "i-2" }
            assertTrue(riceBack.done)
            assertEquals("user-2", riceBack.doneByUid)
            assertEquals("user-2", riceBack.assigneeUid)
            assertEquals("Karim", riceBack.assigneeName)
            // The group-detail row counts come from the DAO, not memory.
            assertEquals(2, db.sharedListItemDao().getItemCount("l-1"))
            assertEquals(1, db.sharedListItemDao().getDoneCount("l-1"))
            val comments = db.sharedListCommentDao().getCommentsOnce("i-1")
            assertEquals(1, comments.size)
            assertEquals("The shop near the mosque is cheaper", comments.single().text)
            assertEquals("user-2", comments.single().authorUid)

            // CASCADE: deleting the list deletes its items, and
            // deleting those items deletes their comments. The
            // repository deletes the remote docs too; locally the
            // foreign key does the cleanup in one step.
            db.sharedListDao().deleteListById("l-1")
            assertEquals(emptyList<SharedListItemEntity>(), db.sharedListItemDao().getItemsOnce("l-1"))
            assertEquals(emptyList<SharedListCommentEntity>(), db.sharedListCommentDao().getCommentsOnce("i-1"))
            assertEquals(0, db.sharedListItemDao().getItemCount("l-1"))
        } finally {
            db.close()
        }
    }

    private companion object {
        const val CREATED = 1_779_900_000_000L
    }
}
