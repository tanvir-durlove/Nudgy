package com.nextgentool.nudgy.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.entity.FocusSessionEntity
import com.nextgentool.nudgy.data.local.entity.HabitCheckInEntity
import com.nextgentool.nudgy.data.local.entity.HabitEntity
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.data.model.TaskStatus
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
 * Sprint 4-0 — MIGRATION_9_10 test (MANDATORY: no S4-0 merge without it).
 *
 * Same hand-rolled pattern as Migration8To9Test (exportSchema = false,
 * so no MigrationTestHelper): builds a real v9 database from raw SQL
 * (the exact v9 schema — v8 tables + MIGRATION_8_9's boardOrder
 * column), inserts tester-shaped fixture rows, then opens it with Room
 * + MIGRATION_9_10. Room runs the migration AND validates the resulting
 * schema against the v10 entities on open.
 *
 * The migration only ADDS three tables — no tester data may change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration9To10Test {

    private val dbName = "migration-9-10-test.db"
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
     * The v9 schema, verbatim: the v8 tables (as in Migration8To9Test)
     * + MIGRATION_8_9's boardOrder column on tasks. Room validates ALL
     * tables on open, so the fixture must contain every v9 table.
     */
    private fun createV9Database() {
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
        // Room records the schema version in the file header (user_version
        // pragma); the migration only runs if it reads 9.
        db.setVersion(9)

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
        // Opening with the v10 entities + MIGRATION_9_10 runs the
        // migration and validates the whole schema. Any mismatch throws here.
        return Room.databaseBuilder(context, NudgyDatabase::class.java, dbName)
            .addMigrations(MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `migrate 9 to 10 keeps tester data and creates empty focus and habit tables`() = runTest {
        createV9Database()
        val db = openMigratedDatabase()
        try {
            // Old data survives intact.
            val task = db.taskDao().getTaskById("t-1")
            checkNotNull(task)
            assertEquals("Keep me", task.title)
            assertEquals(TaskStatus.TODO, task.status)
            assertEquals(30, task.estimatedMinutes)
            assertEquals(2, task.boardOrder)
            val event = db.calendarDao().getEventById("e-1")
            checkNotNull(event)
            assertEquals("Standup", event.title)

            // New tables exist and start empty.
            assertEquals(emptyList<HabitEntity>(), db.habitDao().getHabitsOnce("user-1"))
            assertEquals(0L, db.focusSessionDao().getFocusMinutesInRange("user-1", 0L, Long.MAX_VALUE))
            assertEquals(emptyList<String>(), db.habitDao().getCheckInDates("no-such-habit"))

            // And they round-trip through the DAOs on the migrated database.
            val session = FocusSessionEntity(
                id = "s-1", userId = "user-1", taskId = "t-1",
                startedAtEpochMillis = 1000L, plannedEndEpochMillis = 1000L + 25 * 60_000L,
                endedAtEpochMillis = 1000L + 25 * 60_000L, durationMinutes = 25,
                completed = true, kind = FocusSessionKind.FOCUS
            )
            db.focusSessionDao().insertSession(session)
            assertEquals(25L, db.focusSessionDao().getFocusMinutesInRange("user-1", 0L, Long.MAX_VALUE))
            val habit = HabitEntity(id = "h-1", userId = "user-1", title = "Read")
            db.habitDao().insertHabit(habit)
            assertEquals(1L, db.habitDao().insertCheckIn(HabitCheckInEntity("h-1", "2026-11-21", 1000L)))
            // One check-in per day: the second insert for the same day is ignored.
            assertEquals(-1L, db.habitDao().insertCheckIn(HabitCheckInEntity("h-1", "2026-11-21", 2000L)))
            assertEquals(listOf("2026-11-21"), db.habitDao().getCheckInDates("h-1"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `fresh v10 install round-trips focus sessions, habits and check-ins`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val focusDao = db.focusSessionDao()
            val habitDao = db.habitDao()

            // A break session round-trips with its kind, and never counts.
            val rest = FocusSessionEntity(
                id = "s-break", userId = "user-1",
                startedAtEpochMillis = 1000L, plannedEndEpochMillis = 1000L + 5 * 60_000L,
                durationMinutes = 5, kind = FocusSessionKind.BREAK
            )
            focusDao.insertSession(rest)
            val read = focusDao.getSessionById("s-break")
            checkNotNull(read)
            assertEquals(FocusSessionKind.BREAK, read.kind)
            assertFalse(read.completed)
            assertNull(read.endedAtEpochMillis)
            assertNull(read.taskId)
            assertEquals(0L, focusDao.getFocusMinutesInRange("user-1", 0L, Long.MAX_VALUE))

            // Completing it still counts nothing (breaks never count).
            focusDao.completeSession("s-break", 1000L + 5 * 60_000L)
            assertTrue(focusDao.getSessionById("s-break")!!.completed)
            assertEquals(0L, focusDao.getFocusMinutesInRange("user-1", 0L, Long.MAX_VALUE))

            // A running session is findable until it ends.
            val running = FocusSessionEntity(
                id = "s-run", userId = "user-1",
                startedAtEpochMillis = 2000L, plannedEndEpochMillis = 2000L + 25 * 60_000L,
                durationMinutes = 25
            )
            focusDao.insertSession(running)
            assertEquals("s-run", focusDao.getRunningSession("user-1")?.id)
            focusDao.endSession("s-run", 3000L)
            assertNull(focusDao.getRunningSession("user-1"))

            // Habit + check-ins, and delete cascades the check-ins.
            habitDao.insertHabit(HabitEntity(id = "h-1", userId = "user-1", title = "Read"))
            habitDao.insertCheckIn(HabitCheckInEntity("h-1", "2026-11-20", 1000L))
            habitDao.insertCheckIn(HabitCheckInEntity("h-1", "2026-11-21", 2000L))
            assertEquals(2, habitDao.getCheckInCount("h-1"))
            assertTrue(habitDao.hasCheckIn("h-1", "2026-11-21"))
            assertFalse(habitDao.hasCheckIn("h-1", "2026-11-22"))
            habitDao.deleteHabitById("h-1")
            assertNull(habitDao.getHabitById("h-1"))
            assertEquals(emptyList<String>(), habitDao.getCheckInDates("h-1"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `an unknown focus kind stored by hand reads back as FOCUS, never crashes`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // A hand-edited (or future-version) row with a kind this build
            // does not know: the converter falls back to FOCUS.
            db.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO `focus_sessions`
                (`id`, `userId`, `taskId`, `startedAtEpochMillis`, `plannedEndEpochMillis`,
                 `endedAtEpochMillis`, `durationMinutes`, `completed`, `kind`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>("s-weird", "user-1", null, 1000L, 2000L, null, 25, 0, "WEIRD")
            )
            val read = db.focusSessionDao().getSessionById("s-weird")
            checkNotNull(read)
            assertEquals(FocusSessionKind.FOCUS, read.kind)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val CREATED = 1_779_900_000_000L
    }
}
