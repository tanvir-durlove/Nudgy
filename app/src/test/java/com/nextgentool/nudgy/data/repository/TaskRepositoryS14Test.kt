package com.nextgentool.nudgy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.domain.model.BuiltInTemplates
import com.nextgentool.nudgy.domain.model.TemplateDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.time.LocalDate

/**
 * Sprint 1-4 — repository tests for templates + attachments + Board
 * status wiring (Features 13, 10, 5).
 *
 * Real in-memory Room + real TaskRepository (no Hilt), following the
 * S1-3a RecurrenceTest pattern. Files land in Robolectric's filesDir
 * (a real temp dir) and are cleaned up in tearDown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskRepositoryS14Test {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(context, db)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "attachments").deleteRecursively()
        db.close()
    }

    private val userId get() = repository.currentUserId()

    // ------------------------------------------------------------ templates

    @Test
    fun `seeding gives exactly the 3 approved built-ins`() = runTest {
        repository.seedBuiltInTemplates(userId)
        val templates = repository.observeTemplates(userId).first()
        assertEquals(3, templates.size)
        assertEquals(
            listOf("দৈনিক রুটিন", "পড়াশোনা", "কাজ"),
            templates.map { it.name }
        )
        assertEquals(5, templates[0].drafts.size)
        assertEquals(110, templates[0].totalEstimatedMinutes)
        assertEquals(160, templates[1].totalEstimatedMinutes)
        assertEquals(180, templates[2].totalEstimatedMinutes)
    }

    @Test
    fun `seeding twice never duplicates the built-ins`() = runTest {
        repository.seedBuiltInTemplates(userId)
        repository.seedBuiltInTemplates(userId)
        assertEquals(3, repository.observeTemplates(userId).first().size)
    }

    @Test
    fun `seeded templates contain no prayer template`() = runTest {
        repository.seedBuiltInTemplates(userId)
        val templates = repository.observeTemplates(userId).first()
        val haystack = templates.joinToString(" ") { t ->
            t.name + " " + t.drafts.joinToString(" ") { it.title }
        }.lowercase()
        assertFalse(haystack.contains("নামাজ"))
        assertFalse(haystack.contains("prayer"))
    }

    @Test
    fun `create from template makes 5 tasks with one due, subtasks carried`() = runTest {
        repository.seedBuiltInTemplates(userId)
        val template = repository.observeTemplates(userId).first()
            .first { it.id == BuiltInTemplates.DAILY_ROUTINE_ID }
        val due = repository.planMyDayDefaultDueEpochMillis(LocalDate.now())
        val created = repository.createTasksFromTemplate(userId, template, due)

        assertEquals(5, created.size)
        assertTrue(created.all { it.dueDateEpochMillis == due })
        assertTrue(created.all { it.templateId == template.id })
        assertEquals(
            listOf(
                "Morning routine", "Exercise / walk", "Plan the day",
                "Family time", "Prepare tomorrow's list"
            ),
            created.map { it.title }
        )
        assertEquals(30, created[0].estimatedMinutes)
        assertEquals(Priority.HIGH, created[1].priority)
        // Subtasks: Morning routine 2 + Plan the day 1 + Prepare 1 = 4.
        val all = repository.getAllTasksOnce(userId)
        assertEquals(9, all.size)
        val subs = all.filter { it.parentTaskId != null }
        assertEquals(4, subs.size)
        assertEquals(
            setOf("Drink water", "Freshen up", "Review Today list", "Pick top 3 for tomorrow"),
            subs.map { it.title }.toSet()
        )
        assertEquals(created[0].id, subs.first { it.title == "Drink water" }.parentTaskId)
    }

    @Test
    fun `deleting a template keeps the tasks it created`() = runTest {
        repository.seedBuiltInTemplates(userId)
        val template = repository.observeTemplates(userId).first()
            .first { it.id == BuiltInTemplates.WORK_ID }
        repository.createTasksFromTemplate(
            userId, template,
            repository.planMyDayDefaultDueEpochMillis(LocalDate.now())
        )
        repository.deleteTemplate(userId, template.id)
        assertEquals(2, repository.observeTemplates(userId).first().size)
        assertEquals(5, repository.getAllTasksOnce(userId).count { it.parentTaskId == null })
    }

    @Test
    fun `own template can be created, edited and deleted`() = runTest {
        val created = repository.createTemplate(
            userId, "My list",
            listOf(TemplateDraft(title = "One", estimatedMinutes = 15, priority = Priority.LOW))
        )
        assertFalse(created.isBuiltIn)
        assertEquals(1, repository.observeTemplates(userId).first().size)

        repository.updateTemplate(userId, created.copy(name = "Renamed"))
        assertEquals("Renamed", repository.observeTemplates(userId).first()[0].name)

        repository.deleteTemplate(userId, created.id)
        assertEquals(0, repository.observeTemplates(userId).first().size)
    }

    @Test
    fun `updating a built-in template is refused`() = runTest {
        repository.seedBuiltInTemplates(userId)
        val builtIn = repository.observeTemplates(userId).first()[0]
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.updateTemplate(userId, builtIn.copy(name = "Hacked"))
            }
        }
    }

    // ----------------------------------------------------------- attachments

    private fun smallStream(): InputStream = "hello".byteInputStream()

    private suspend fun addSmallFile(taskId: String, name: String = "note.txt") =
        repository.addAttachmentFromStream(
            taskId = taskId,
            fileName = name,
            mimeType = "text/plain",
            sizeBytes = 5L,
            openStream = { smallStream() }
        )

    @Test
    fun `adding a file stores it in app-private storage`() = runTest {
        val task = repository.createTask(userId, "With file")
        val result = addSmallFile(task.id)
        assertTrue(result is TaskRepository.AddAttachmentResult.Added)
        val added = result as TaskRepository.AddAttachmentResult.Added
        assertTrue(File(added.attachment.localPath).exists())
        assertTrue(added.attachment.localPath.contains("attachments"))
        assertEquals(1, repository.observeAttachments(task.id).first().size)
    }

    @Test
    fun `a file larger than 25 MB is blocked with a message, nothing saved`() = runTest {
        val task = repository.createTask(userId, "Big file")
        val result = repository.addAttachmentFromStream(
            taskId = task.id,
            fileName = "big.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 26L * 1024L * 1024L,
            openStream = { smallStream() }
        )
        assertTrue(result is TaskRepository.AddAttachmentResult.Rejected)
        val reason = (result as TaskRepository.AddAttachmentResult.Rejected).reason
        assertTrue(reason.contains("25 MB"))
        assertEquals(0, repository.observeAttachments(task.id).first().size)
        assertEquals(0, File(context.filesDir, "attachments").listFiles()?.size ?: 0)
    }

    @Test
    fun `unknown-size stream over 25 MB is copied, measured, then removed`() = runTest {
        val task = repository.createTask(userId, "Unknown big file")
        val result = repository.addAttachmentFromStream(
            taskId = task.id,
            fileName = "big.bin",
            mimeType = "application/octet-stream",
            sizeBytes = null,
            openStream = { RepeatingInputStream(26L * 1024L * 1024L) }
        )
        assertTrue(result is TaskRepository.AddAttachmentResult.Rejected)
        assertTrue(
            (result as TaskRepository.AddAttachmentResult.Rejected).reason.contains("25 MB")
        )
        assertEquals(0, repository.observeAttachments(task.id).first().size)
        assertEquals(0, File(context.filesDir, "attachments").listFiles()?.size ?: 0)
    }

    @Test
    fun `the 11th attachment is blocked`() = runTest {
        val task = repository.createTask(userId, "Many files")
        repeat(10) { index ->
            val result = addSmallFile(task.id, "file$index.txt")
            assertTrue(result is TaskRepository.AddAttachmentResult.Added)
        }
        val eleventh = addSmallFile(task.id, "file10.txt")
        assertTrue(eleventh is TaskRepository.AddAttachmentResult.Rejected)
        assertTrue(
            (eleventh as TaskRepository.AddAttachmentResult.Rejected).reason.contains("10")
        )
        assertEquals(10, repository.observeAttachments(task.id).first().size)
    }

    @Test
    fun `deleting a task deletes its files too`() = runTest {
        val task = repository.createTask(userId, "Delete me")
        val result = addSmallFile(task.id)
        val path = (result as TaskRepository.AddAttachmentResult.Added).attachment.localPath
        assertTrue(File(path).exists())
        repository.deleteTask(task.id)
        assertFalse(File(path).exists())
        assertEquals(0, repository.observeAttachments(task.id).first().size)
    }

    @Test
    fun `clearing completed tasks deletes their files`() = runTest {
        val task = repository.createTask(userId, "Done file")
        val result = addSmallFile(task.id)
        val path = (result as TaskRepository.AddAttachmentResult.Added).attachment.localPath
        repository.toggleTaskCompletion(task)
        repository.clearCompletedTasks(userId)
        assertFalse(File(path).exists())
    }

    @Test
    fun `a group task cannot take attachments here`() = runTest {
        val groupTask = TaskEntity(
            id = "g1", userId = userId, title = "Group task", groupId = "group-1"
        )
        db.taskDao().insertTask(groupTask)
        val result = addSmallFile("g1")
        assertTrue(result is TaskRepository.AddAttachmentResult.Rejected)
    }

    // -------------------------------------------------------------- board

    @Test
    fun `dropping in Done sets isCompleted and completedAt`() = runTest {
        val task = repository.createTask(userId, "Drag me")
        repository.setTaskStatus(task, TaskStatus.DONE)
        val after = db.taskDao().getTaskById(task.id)!!
        assertEquals(TaskStatus.DONE, after.status)
        assertTrue(after.isCompleted)
        assertNotNull(after.completedAtEpochMillis)
    }

    @Test
    fun `dragging back to To do clears completion`() = runTest {
        val task = repository.createTask(userId, "Drag back")
        repository.setTaskStatus(task, TaskStatus.DONE)
        val done = db.taskDao().getTaskById(task.id)!!
        repository.setTaskStatus(done, TaskStatus.TODO)
        val after = db.taskDao().getTaskById(task.id)!!
        assertEquals(TaskStatus.TODO, after.status)
        assertFalse(after.isCompleted)
        assertNull(after.completedAtEpochMillis)
    }

    @Test
    fun `dragging to In progress keeps the task uncompleted`() = runTest {
        val task = repository.createTask(userId, "In progress")
        repository.setTaskStatus(task, TaskStatus.IN_PROGRESS)
        val after = db.taskDao().getTaskById(task.id)!!
        assertEquals(TaskStatus.IN_PROGRESS, after.status)
        assertFalse(after.isCompleted)
    }

    /** Generates bytes on the fly — no 26 MB array in memory. */
    private class RepeatingInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            remaining--
            return 'x'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            b.fill('x'.code.toByte(), off, off + n)
            remaining -= n
            return n
        }
    }
}
