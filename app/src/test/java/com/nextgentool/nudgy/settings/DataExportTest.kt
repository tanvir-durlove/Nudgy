package com.nextgentool.nudgy.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.NudgyMemoryEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.local.entity.TaskTagCrossRef
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Sprint 6-6 (step 4b) — the JSON export: pure round-trip tests on
 * [NudgyDataExport] plus one real-repository [DataExporter.collectExport]
 * test over an in-memory Room (the step-2b/2c pattern: real repos,
 * DAO seeding, `runTest`).
 *
 * Honest limit (same as the Glance / BiometricPrompt paths): the SAF
 * file picker ([DataExporter.createExportDocumentIntent]) and the Uri
 * write ([DataExporter.exportToUri]) have no picker on the JVM, so
 * they are verified by CI compile + the batch-end device test — not
 * here. Everything below them is covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataExportTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var memoryRepository: MemoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = TaskRepository(context, db)
        calendarRepository = CalendarRepository(context, db)
        memoryRepository = MemoryRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val userId get() = "test-user-1"

    /** Every field set, so the round-trip must preserve all of them. */
    private fun fullTask() = TaskEntity(
        id = "task-full-1",
        userId = userId,
        title = "বাজার করা — \"তাজা\" মাছ\nআজ",
        description = "Description with \"quotes\", a backslash \\ and a newline\ntoo",
        dueDateEpochMillis = 1_700_000_000_000,
        priority = Priority.HIGH,
        category = TaskCategory.PERSONAL,
        isCompleted = true,
        completedAtEpochMillis = 1_700_000_100_000,
        reminderSet = true,
        reminderEpochMillis = 1_700_000_050_000,
        recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        isSyncedToCloud = true,
        createdAtEpochMillis = 1_699_999_000_000,
        updatedAtEpochMillis = 1_699_999_500_000,
        groupId = "group-7",
        status = TaskStatus.DONE,
        plannedStartEpochMillis = 1_700_000_200_000,
        plannedDate = "2023-11-14",
        estimatedMinutes = 45,
        actualMinutes = 30,
        parentTaskId = "task-parent-1",
        projectId = "project-1",
        carriedOverFromDate = "2023-11-13",
        templateId = "template-1",
        reminderRepeatMinutes = 15,
        boardOrder = 3,
        assigneeUid = "uid-9",
        assigneeName = "Ayesha",
        isSample = true
    )

    private fun fullSchedule() = CalendarEventEntity(
        id = "event-full-1",
        userId = userId,
        title = "Dentist — দাঁতের ডাক্তার",
        startEpochMillis = 1_700_001_000_000,
        endEpochMillis = 1_700_001_300_000,
        locationOrLink = "City Dental, Room 4",
        eventType = "MEETING",
        isAllDay = false,
        googleCalendarEventId = 987654321L,
        isFromGoogleCalendar = true,
        groupId = null
    )

    private fun fullMemory() = NudgyMemoryEntity(
        id = "memory-full-1",
        userId = userId,
        content = "Prefers focus time in the morning — সকাল ৭টা",
        createdAtEpochMillis = 1_699_998_000_000
    )

    /** Step 2-1 — every field set, so the round-trip must preserve all of them. */
    private fun fullTag(id: String = "tag-full-1", name: String = "Home") = TagEntity(
        id = id,
        userId = userId,
        name = name,
        color = "#0F766E"
    )

    private fun fullExport() = NudgyDataExport(
        schemaVersion = NudgyDataExport.CURRENT_SCHEMA_VERSION,
        exportedAtEpochMillis = 1_700_002_000_000,
        tasks = listOf(fullTask()),
        schedules = listOf(fullSchedule()),
        memories = listOf(fullMemory()),
        tags = listOf(fullTag()),
        taskTags = listOf(TaskTagCrossRef(taskId = "task-full-1", tagId = "tag-full-1"))
    )

    @Test
    fun `round-trip preserves every field of a full task schedule and memory`() {
        val export = fullExport()
        val parsed = NudgyDataExport.fromJson(export.toJson())
        assertEquals(export, parsed)
    }

    @Test
    fun `round-trip preserves an empty export`() {
        val export = NudgyDataExport(exportedAtEpochMillis = 1_700_002_000_000)
        assertEquals(export, NudgyDataExport.fromJson(export.toJson()))
    }

    @Test
    fun `json carries the schema version and every record id`() {
        val json = fullExport().toJson()
        assertTrue(json.contains("\"schemaVersion\":${NudgyDataExport.CURRENT_SCHEMA_VERSION}"))
        assertTrue(json.contains("task-full-1"))
        assertTrue(json.contains("event-full-1"))
        assertTrue(json.contains("memory-full-1"))
        assertTrue(json.contains("tag-full-1"))
    }

    @Test
    fun `unknown enum names degrade to defaults and unknown keys are skipped`() {
        val json = fullExport().toJson()
            .replace("\"priority\":\"HIGH\"", "\"priority\":\"EXTREME\"")
            .replace(
                "\"schemaVersion\":${NudgyDataExport.CURRENT_SCHEMA_VERSION}",
                "\"schemaVersion\":${NudgyDataExport.CURRENT_SCHEMA_VERSION},\"futureField\":1"
            )
        val parsed = NudgyDataExport.fromJson(json)
        assertNotNull(parsed)
        assertEquals(Priority.MEDIUM, parsed!!.tasks.single().priority)
        assertEquals("task-full-1", parsed.tasks.single().id)
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(NudgyDataExport.fromJson("not json"))
        assertNull(NudgyDataExport.fromJson(""))
        assertNull(NudgyDataExport.fromJson(fullExport().toJson().substring(0, 20)))
    }

    @Test
    fun `a future schema version returns null`() {
        val json = fullExport().toJson()
            .replace(
                "\"schemaVersion\":${NudgyDataExport.CURRENT_SCHEMA_VERSION}",
                "\"schemaVersion\":99"
            )
        assertNull(NudgyDataExport.fromJson(json))
    }

    @Test
    fun `a missing schema version returns null`() {
        val json = fullExport().toJson()
            .replace("\"schemaVersion\":${NudgyDataExport.CURRENT_SCHEMA_VERSION},", "")
        assertNull(NudgyDataExport.fromJson(json))
    }

    @Test
    fun `collectExport reads all three tables for one user only`() = runTest {
        db.taskDao().insertTask(fullTask())
        db.taskDao().insertTask(
            TaskEntity(id = "task-other-user", userId = "other-user", title = "Not mine")
        )
        db.calendarDao().insertEvent(fullSchedule())
        db.memoryDao().insertMemory(fullMemory())

        val export = DataExporter.collectExport(
            userId, taskRepository, calendarRepository, memoryRepository
        )

        assertEquals(NudgyDataExport.CURRENT_SCHEMA_VERSION, export.schemaVersion)
        assertEquals(listOf("task-full-1"), export.tasks.map { it.id })
        assertEquals(listOf("event-full-1"), export.schedules.map { it.id })
        assertEquals(listOf("memory-full-1"), export.memories.map { it.id })
        assertEquals(fullTask(), export.tasks.single())
        assertEquals(fullSchedule(), export.schedules.single())
        assertEquals(fullMemory(), export.memories.single())
    }

    @Test
    fun `exportToStream writes bytes that parse back to the same export`() = runTest {
        val export = fullExport()
        val out = ByteArrayOutputStream()
        DataExporter.exportToStream(export, out)
        val parsed = NudgyDataExport.fromJson(out.toString(Charsets.UTF_8))
        assertEquals(export, parsed)
    }

    @Test
    fun `defaultFileName has the nudgy-export date shape`() {
        val name = DataExporter.defaultFileName(1_700_000_000_000)
        assertTrue(name.matches(Regex("nudgy-export-\\d{4}-\\d{2}-\\d{2}\\.json")))
    }

    // ------------------------------------------------------------ Step 2-1: tags

    @Test
    fun `round-trip preserves tags and task links in schema v2`() {
        val export = fullExport()
        val parsed = NudgyDataExport.fromJson(export.toJson())
        assertEquals(export, parsed)
        assertEquals(2, parsed!!.schemaVersion)
        assertEquals(listOf(fullTag()), parsed.tags)
        assertEquals(
            listOf(TaskTagCrossRef(taskId = "task-full-1", tagId = "tag-full-1")),
            parsed.taskTags
        )
    }

    @Test
    fun `a v1 file without tags keys reads with empty tags and task tags`() {
        // A genuine v1 document: schemaVersion 1, no "tags" / "taskTags"
        // keys at all. The reader accepts the omitted keys (its own
        // contract) and the tasks still parse.
        val v1Json = """{"schemaVersion":1,"exportedAtEpochMillis":1700002000000,"tasks":[{"id":"task-v1-1","userId":"test-user-1","title":"Old task"}],"schedules":[],"memories":[]}"""
        val parsed = NudgyDataExport.fromJson(v1Json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.schemaVersion)
        assertEquals(listOf("task-v1-1"), parsed.tasks.map { it.id })
        assertEquals("Old task", parsed.tasks.single().title)
        assertTrue(parsed.tags.isEmpty())
        assertTrue(parsed.taskTags.isEmpty())
    }

    @Test
    fun `collectExport reads only the user's tags and only their tasks links`() = runTest {
        db.taskDao().insertTask(fullTask())
        db.taskDao().insertTask(
            TaskEntity(id = "task-other-user", userId = "other-user", title = "Not mine")
        )
        db.tagDao().insertTag(fullTag(id = "tag-1", name = "Home"))
        db.tagDao().insertTag(fullTag(id = "tag-2", name = "Work").copy(color = "#F97360"))
        db.tagDao().insertTag(
            TagEntity(id = "tag-other", userId = "other-user", name = "Other", color = "#0F766E")
        )
        // Both links of the user's task, plus a link from the user's
        // tag to the OTHER user's task — that one must not be exported.
        db.tagDao().insertCrossRef(TaskTagCrossRef("task-full-1", "tag-1"))
        db.tagDao().insertCrossRef(TaskTagCrossRef("task-full-1", "tag-2"))
        db.tagDao().insertCrossRef(TaskTagCrossRef("task-other-user", "tag-1"))
        db.tagDao().insertCrossRef(TaskTagCrossRef("task-other-user", "tag-other"))

        val export = DataExporter.collectExport(
            userId, taskRepository, calendarRepository, memoryRepository
        )

        assertEquals(listOf("tag-1", "tag-2"), export.tags.map { it.id })
        // No ORDER BY on the cross-ref query, so compare as a set.
        assertEquals(
            setOf(
                TaskTagCrossRef("task-full-1", "tag-1"),
                TaskTagCrossRef("task-full-1", "tag-2")
            ),
            export.taskTags.toSet()
        )
    }
}
