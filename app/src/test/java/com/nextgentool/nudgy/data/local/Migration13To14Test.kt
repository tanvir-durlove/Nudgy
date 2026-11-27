package com.nextgentool.nudgy.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.entity.HabitEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.ChatRepository
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
 * Chat isVoice follow-up — MIGRATION_13_14 test (MANDATORY: no merge
 * of this task without it).
 *
 * Same hand-rolled pattern as Migration12To13Test (exportSchema =
 * false, so no MigrationTestHelper): builds a real v13 database
 * from raw SQL (the exact v13 schema — v12 plus the isSample column
 * MIGRATION_12_13 added to `tasks`), inserts tester-shaped fixture
 * rows, then opens it with Room + MIGRATION_13_14. Room runs the
 * migration AND validates the resulting schema against the v14
 * entities on open.
 *
 * MIGRATION_13_14 adds one NOT NULL column to `chat_messages` —
 * `isVoice` (voice brain-dump messages): the test proves a v13
 * tester database opens as v14 with every row intact (the assignee
 * and the sample flag surviving the step) and isVoice defaulting to
 * false, and that a fresh v14 install round-trips a voice message
 * with isVoice=true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration13To14Test {

    private val dbName = "migration-13-14-test.db"
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
     * The v13 schema, verbatim: the v12 tables plus the isSample
     * column MIGRATION_12_13 added to `tasks`. Room validates ALL
     * tables on open, so the fixture must contain every v13 table.
     */
    private fun createV13Database() {
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
        // pragma); the migration only runs if it reads 13.
        db.setVersion(13)

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
        // A pre-migration chat message (v13 has no isVoice column):
        // a typed message the user sent before the update.
        db.execSQL(
            """
            INSERT INTO `chat_messages`
            (`id`, `userId`, `senderIsUser`, `text`, `timestampMillis`,
             `actionType`, `associatedTaskId`, `associatedTaskTitle`,
             `associatedTaskDueMillis`, `associatedEventId`,
             `associatedEventTitle`, `associatedEventStartMillis`,
             `associatedEventEndMillis`, `focusStartMillis`,
             `focusEndMillis`, `focusDurationMinutes`,
             `focusActionCompleted`, `quickSuggestionsRaw`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "m-1", "user-1", 1, "Call Tasnim at 5pm", CREATED,
                "NONE", null, null, null, null, null, null, null,
                null, null, 45, 0, ""
            )
        )
        db.close()
    }

    private fun openMigratedDatabase(): NudgyDatabase {
        // Opening with the v14 entities + MIGRATION_13_14 runs the
        // migration and validates the whole schema. Any mismatch throws here.
        return Room.databaseBuilder(context, NudgyDatabase::class.java, dbName)
            .addMigrations(MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `migrate 13 to 14 keeps tester data and defaults isVoice to false`() = runTest {
        createV13Database()
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
            // The v13 assignee and sample flag survive the migration untouched.
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

            // The pre-migration chat message survives, and the new
            // column defaults to false — a typed message from before
            // the update never shows "YOU · VOICE" by mistake. Both
            // levels are pinned: the entity (the column default) and
            // the repository mapping back out to the UI message.
            val entities = db.chatDao().getMessagesFlow("user-1").first()
            assertEquals(1, entities.size)
            assertEquals("Call Tasnim at 5pm", entities.single().text)
            assertTrue(entities.single().senderIsUser)
            assertFalse(entities.single().isVoice)
            val uiMessages = ChatRepository(context, db).observeMessages("user-1").first()
            assertEquals(1, uiMessages.size)
            assertEquals("Call Tasnim at 5pm", uiMessages.single().text)
            assertTrue(uiMessages.single().senderIsUser)
            assertFalse(uiMessages.single().isVoice)

            // The S4 tables survive the migration too.
            assertEquals(emptyList<HabitEntity>(), db.habitDao().getHabitsOnce("user-1"))
            assertEquals(0L, db.focusSessionDao().getFocusMinutesInRange("user-1", 0L, Long.MAX_VALUE))
        } finally {
            db.close()
        }
    }

    @Test
    fun `fresh v14 install round-trips a voice chat message`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // A voice message round-trips the flag through the
            // repository both ways (entity in, UI message out), and
            // a typed message defaults to false.
            val repository = ChatRepository(context, db)
            val voice = com.nextgentool.nudgy.ui.screens.ChatMessage(
                id = "m-voice", senderIsUser = true, text = "buy milk",
                isVoice = true
            )
            repository.addMessage("user-1", voice)
            val typed = com.nextgentool.nudgy.ui.screens.ChatMessage(
                id = "m-typed", senderIsUser = true, text = "call tasnim at 5pm"
            )
            repository.addMessage("user-1", typed)
            val entities = db.chatDao().getMessagesFlow("user-1").first()
            assertEquals(2, entities.size)
            assertEquals(true, entities.first { it.id == "m-voice" }.isVoice)
            assertEquals(false, entities.first { it.id == "m-typed" }.isVoice)
            val uiMessages = repository.observeMessages("user-1").first()
            assertEquals(2, uiMessages.size)
            assertEquals(true, uiMessages.first { it.id == "m-voice" }.isVoice)
            assertEquals(false, uiMessages.first { it.id == "m-typed" }.isVoice)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val CREATED = 1_779_900_000_000L
    }
}
