package com.nextgentool.nudgy.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.NudgyMemoryEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.local.entity.TaskTagCrossRef
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.settings.DataRestorer.RestoreOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sprint 6-6 (step 4c) — the JSON restore: id-merge over an
 * in-memory Room with the real repositories (the step-2b/2c pattern:
 * real repos, DAO seeding, `runTest`).
 *
 * Honest limit (same as the Glance / BiometricPrompt / export-picker
 * paths): the SAF file picker ([DataRestorer.createOpenDocumentIntent])
 * and the Uri read ([DataRestorer.restoreFromUri]) have no picker on
 * the JVM, so they are verified by CI compile + the batch-end device
 * test — not here. Everything below them is covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataRestoreTest {

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

    private fun task(
        id: String = "task-1",
        userId: String = this.userId,
        title: String = "Buy milk",
        reminderSet: Boolean = false,
        reminderEpochMillis: Long? = null
    ) = TaskEntity(
        id = id,
        userId = userId,
        title = title,
        reminderSet = reminderSet,
        reminderEpochMillis = reminderEpochMillis
    )

    private fun schedule(
        id: String = "event-1",
        userId: String = this.userId,
        title: String = "Dentist"
    ) = CalendarEventEntity(
        id = id,
        userId = userId,
        title = title,
        startEpochMillis = 1_700_001_000_000,
        endEpochMillis = 1_700_001_300_000
    )

    private fun memory(
        id: String = "memory-1",
        userId: String = this.userId,
        content: String = "Prefers mornings"
    ) = NudgyMemoryEntity(
        id = id,
        userId = userId,
        content = content
    )

    /** Step 2-1 — one tag; name/colour vary per test. */
    private fun tag(
        id: String = "tag-1",
        userId: String = this.userId,
        name: String = "Home",
        color: String = "#0F766E"
    ) = TagEntity(
        id = id,
        userId = userId,
        name = name,
        color = color
    )

    private fun exportJson(
        tasks: List<TaskEntity> = listOf(task()),
        schedules: List<CalendarEventEntity> = listOf(schedule()),
        memories: List<NudgyMemoryEntity> = listOf(memory()),
        tags: List<TagEntity> = emptyList(),
        taskTags: List<TaskTagCrossRef> = emptyList()
    ) = NudgyDataExport(
        tasks = tasks,
        schedules = schedules,
        memories = memories,
        tags = tags,
        taskTags = taskTags
    ).toJson()

    private suspend fun clearAll(userId: String) {
        db.taskDao().clearTasksForUser(userId)
        db.calendarDao().clearEventsForUser(userId)
        db.memoryDao().clearMemoriesForUser(userId)
    }

    private suspend fun restore(json: String, userId: String = this.userId) =
        DataRestorer.restoreFromJson(json, userId, taskRepository, calendarRepository, memoryRepository)

    @Test
    fun `restore round-trips an export into an empty database`() = runTest {
        db.taskDao().insertTask(task())
        db.calendarDao().insertEvent(schedule())
        db.memoryDao().insertMemory(memory())
        val json = DataExporter.collectExport(
            userId, taskRepository, calendarRepository, memoryRepository
        ).toJson()
        clearAll(userId)

        val outcome = restore(json)

        val summary = (outcome as RestoreOutcome.Restored).summary
        assertEquals(1, summary.tasks.inserted)
        assertEquals(0, summary.tasks.skipped)
        assertEquals(1, summary.schedules.inserted)
        assertEquals(1, summary.memories.inserted)
        assertEquals(3, summary.totalInserted)
        assertEquals(0, summary.totalSkipped)
        assertEquals(listOf("task-1"), taskRepository.getAllTasksOnce(userId).map { it.id })
        assertEquals("Buy milk", taskRepository.getAllTasksOnce(userId).single().title)
        assertEquals(listOf("event-1"), db.calendarDao().getAllEventsOnce(userId).map { it.id })
        assertEquals(listOf("memory-1"), memoryRepository.getMemories(userId).map { it.id })
    }

    @Test
    fun `restoring twice inserts nothing the second time`() = runTest {
        val json = exportJson()
        val first = restore(json) as RestoreOutcome.Restored
        assertEquals(1, first.summary.tasks.inserted)

        val second = restore(json) as RestoreOutcome.Restored

        assertEquals(0, second.summary.tasks.inserted)
        assertEquals(1, second.summary.tasks.skipped)
        assertEquals(0, second.summary.schedules.inserted)
        assertEquals(1, second.summary.schedules.skipped)
        assertEquals(0, second.summary.memories.inserted)
        assertEquals(1, second.summary.memories.skipped)
        assertEquals(1, taskRepository.getAllTasksOnce(userId).size)
        assertEquals(1, db.calendarDao().getAllEventsOnce(userId).size)
        assertEquals(1, memoryRepository.getMemories(userId).size)
    }

    @Test
    fun `an existing row is never overwritten`() = runTest {
        db.taskDao().insertTask(task(id = "task-keep", title = "Local title"))
        val json = exportJson(tasks = listOf(task(id = "task-keep", title = "Export title")))

        val outcome = restore(json) as RestoreOutcome.Restored

        assertEquals(0, outcome.summary.tasks.inserted)
        assertEquals(1, outcome.summary.tasks.skipped)
        assertEquals("Local title", taskRepository.getTaskById("task-keep")!!.title)
    }

    @Test
    fun `restored rows take the restoring user's id`() = runTest {
        val otherUser = "user-a"
        db.taskDao().insertTask(task(userId = otherUser))
        db.calendarDao().insertEvent(schedule(userId = otherUser))
        db.memoryDao().insertMemory(memory(userId = otherUser))
        val json = DataExporter.collectExport(
            otherUser, taskRepository, calendarRepository, memoryRepository
        ).toJson()
        clearAll(otherUser)

        val outcome = restore(json, userId = "user-b") as RestoreOutcome.Restored

        assertEquals(1, outcome.summary.tasks.inserted)
        val restored = taskRepository.getAllTasksOnce("user-b")
        assertEquals(listOf("task-1"), restored.map { it.id })
        assertEquals("user-b", restored.single().userId)
        assertEquals("Buy milk", restored.single().title)
        assertEquals(0, taskRepository.getAllTasksOnce(otherUser).size)
        assertEquals(1, db.calendarDao().getAllEventsOnce("user-b").size)
        assertEquals(1, memoryRepository.getMemories("user-b").size)
    }

    @Test
    fun `malformed json and a future version are NotAnExportFile and touch nothing`() = runTest {
        val futureJson = exportJson().replace(
            "\"schemaVersion\":${NudgyDataExport.CURRENT_SCHEMA_VERSION}",
            "\"schemaVersion\":99"
        )

        assertTrue(restore("not json") is RestoreOutcome.NotAnExportFile)
        assertTrue(restore("") is RestoreOutcome.NotAnExportFile)
        assertTrue(restore(futureJson) is RestoreOutcome.NotAnExportFile)

        assertEquals(0, taskRepository.getAllTasksOnce(userId).size)
        assertEquals(0, db.calendarDao().getAllEventsOnce(userId).size)
        assertEquals(0, memoryRepository.getMemories(userId).size)
    }

    @Test
    fun `a restored future reminder is re-scheduled, a past one is not`() = runTest {
        val futureTrigger = System.currentTimeMillis() + 3_600_000
        val pastTrigger = System.currentTimeMillis() - 3_600_000
        val json = exportJson(
            tasks = listOf(
                task(id = "task-future", reminderSet = true, reminderEpochMillis = futureTrigger),
                task(id = "task-past", reminderSet = true, reminderEpochMillis = pastTrigger)
            ),
            schedules = emptyList(),
            memories = emptyList()
        )

        restore(json)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarms = shadowOf(alarmManager).scheduledAlarms
        assertEquals(1, alarms.size)
        assertEquals(futureTrigger, alarms.single().triggerAtMs)
        assertEquals(2, taskRepository.getAllTasksOnce(userId).size)
    }

    @Test
    fun `restore counts inserts and skips per table`() = runTest {
        db.taskDao().insertTask(task(id = "task-old", title = "Already here"))
        val json = exportJson(
            tasks = listOf(
                task(id = "task-old", title = "Export copy"),
                task(id = "task-new", title = "Fresh row")
            ),
            schedules = emptyList(),
            memories = emptyList()
        )

        val outcome = restore(json) as RestoreOutcome.Restored

        assertEquals(1, outcome.summary.tasks.inserted)
        assertEquals(1, outcome.summary.tasks.skipped)
        assertEquals(0, outcome.summary.schedules.inserted)
        assertEquals("Already here", taskRepository.getTaskById("task-old")!!.title)
        assertEquals("Fresh row", taskRepository.getTaskById("task-new")!!.title)
    }

    @Test
    fun `createOpenDocumentIntent has the open-document shape`() {
        val intent = DataRestorer.createOpenDocumentIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.categories!!.contains(Intent.CATEGORY_OPENABLE))
        assertEquals("application/json", intent.type)
    }

    // ------------------------------------------------------------ Step 2-1: tags

    @Test
    fun `restore inserts tags and links and counts them`() = runTest {
        val json = exportJson(
            tasks = listOf(task()),
            schedules = emptyList(),
            memories = emptyList(),
            tags = listOf(tag(), tag(id = "tag-2", name = "Work", color = "#F97360")),
            taskTags = listOf(
                TaskTagCrossRef("task-1", "tag-1"),
                TaskTagCrossRef("task-1", "tag-2")
            )
        )

        val outcome = restore(json) as RestoreOutcome.Restored

        assertEquals(2, outcome.summary.tags.inserted)
        assertEquals(0, outcome.summary.tags.skipped)
        assertEquals(2, outcome.summary.taskTags.inserted)
        assertEquals(0, outcome.summary.taskTags.skipped)
        assertEquals(5, outcome.summary.totalInserted)
        assertEquals(listOf("tag-1", "tag-2"), taskRepository.getTagsOnce(userId).map { it.id })
        assertEquals(
            setOf(TaskTagCrossRef("task-1", "tag-1"), TaskTagCrossRef("task-1", "tag-2")),
            db.tagDao().getCrossRefsForTasks(listOf("task-1")).toSet()
        )
    }

    @Test
    fun `restoring twice inserts no tags or links the second time`() = runTest {
        val json = exportJson(
            tasks = listOf(task()),
            schedules = emptyList(),
            memories = emptyList(),
            tags = listOf(tag()),
            taskTags = listOf(TaskTagCrossRef("task-1", "tag-1"))
        )
        val first = restore(json) as RestoreOutcome.Restored
        assertEquals(1, first.summary.tags.inserted)
        assertEquals(1, first.summary.taskTags.inserted)

        val second = restore(json) as RestoreOutcome.Restored

        assertEquals(0, second.summary.tags.inserted)
        assertEquals(1, second.summary.tags.skipped)
        assertEquals(0, second.summary.taskTags.inserted)
        assertEquals(1, second.summary.taskTags.skipped)
        assertEquals(1, taskRepository.getTagsOnce(userId).size)
        assertEquals(1, db.tagDao().getCrossRefsForTasks(listOf("task-1")).size)
    }

    @Test
    fun `a link to a missing task or tag is skipped, never a crash`() = runTest {
        val json = exportJson(
            tasks = listOf(task()),
            schedules = emptyList(),
            memories = emptyList(),
            tags = listOf(tag()),
            taskTags = listOf(
                // The task is not in the file; the tag is not either.
                TaskTagCrossRef("task-missing", "tag-1"),
                TaskTagCrossRef("task-1", "tag-missing")
            )
        )

        val outcome = restore(json) as RestoreOutcome.Restored

        assertEquals(1, outcome.summary.tags.inserted)
        assertEquals(0, outcome.summary.taskTags.inserted)
        assertEquals(2, outcome.summary.taskTags.skipped)
        assertTrue(db.tagDao().getCrossRefsForTasks(listOf("task-1")).isEmpty())
    }

    @Test
    fun `a tag id already owned by another user is skipped, never replaced`() = runTest {
        db.tagDao().insertTag(tag(id = "tag-shared", userId = "other-user", name = "Other name"))
        val json = exportJson(
            tasks = emptyList(),
            schedules = emptyList(),
            memories = emptyList(),
            tags = listOf(tag(id = "tag-shared", name = "My name")),
            taskTags = emptyList()
        )

        val outcome = restore(json) as RestoreOutcome.Restored

        assertEquals(0, outcome.summary.tags.inserted)
        assertEquals(1, outcome.summary.tags.skipped)
        val kept = db.tagDao().getTagByIdAnyUser("tag-shared")
        assertEquals("Other name", kept!!.name)
        assertEquals("other-user", kept.userId)
    }

    @Test
    fun `a v1 file restores tasks with empty tags`() = runTest {
        // A genuine v1 document: schemaVersion 1, no tags keys.
        val v1Json = """{"schemaVersion":1,"exportedAtEpochMillis":1700002000000,"tasks":[{"id":"task-v1-1","userId":"test-user-1","title":"Old task"}],"schedules":[],"memories":[]}"""

        val outcome = restore(v1Json) as RestoreOutcome.Restored

        assertEquals(1, outcome.summary.tasks.inserted)
        assertEquals(0, outcome.summary.tags.inserted)
        assertEquals(0, outcome.summary.taskTags.inserted)
        assertEquals(1, outcome.summary.totalInserted)
        assertEquals(listOf("task-v1-1"), taskRepository.getAllTasksOnce(userId).map { it.id })
        assertTrue(taskRepository.getTagsOnce(userId).isEmpty())
    }
}
