package com.nextgentool.nudgy.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.TaskRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sprint 6-5, step 3c — the widget refresh seam: every
 * TaskRepository task mutation invokes the injected
 * [WidgetRefresher] exactly once (after its write).
 *
 * Real in-memory Room + real TaskRepository (no Hilt), following
 * the TaskRepositorySearchTest pattern; the refresher is a
 * recording fake, so no Glance code runs here.
 *
 * What this does NOT prove (honest limitation, same as steps
 * 3a/3b): the Glance rendering itself — this harness cannot
 * render a Glance widget (no Glance test infra in this repo), so
 * the re-provide path (WidgetRefresher.system → GlanceAppWidgetManager
 * + per-id update) is verified by CI compile + the batch-end
 * device test. The MainActivity.onResume refresh is likewise not
 * (MainActivity is @AndroidEntryPoint and not launchable without
 * Hilt test infra) — its one line is verified by reading + CI
 * compile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetRefreshTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var refresher: RecordingRefresher
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        refresher = RecordingRefresher()
        repository = TaskRepository(context, db, refresher)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val userId get() = repository.currentUserId()

    private class RecordingRefresher : WidgetRefresher {
        val refreshCount = AtomicInteger(0)
        override suspend fun refresh() {
            refreshCount.incrementAndGet()
        }
    }

    @Test
    fun `createTask refreshes the widget once`() = runTest {
        repository.createTask(userId = userId, title = "Buy milk")
        assertEquals(1, refresher.refreshCount.get())
    }

    @Test
    fun `toggleTaskCompletion refreshes the widget`() = runTest {
        val created = repository.createTask(userId = userId, title = "Buy milk")
        repository.toggleTaskCompletion(created)
        assertEquals(2, refresher.refreshCount.get())
    }

    @Test
    fun `updateTask refreshes the widget`() = runTest {
        val created = repository.createTask(userId = userId, title = "Buy milk")
        repository.updateTask(created.copy(title = "Buy oat milk"))
        assertEquals(2, refresher.refreshCount.get())
    }

    @Test
    fun `setTaskStatus refreshes the widget`() = runTest {
        val created = repository.createTask(userId = userId, title = "Buy milk")
        repository.setTaskStatus(created, TaskStatus.DONE)
        assertEquals(2, refresher.refreshCount.get())
    }

    @Test
    fun `moveTaskToPosition refreshes the widget`() = runTest {
        val a = repository.createTask(userId = userId, title = "Task A")
        val b = repository.createTask(userId = userId, title = "Task B")
        val moved = repository.moveTaskToPosition(a, TaskStatus.TODO, 1, listOf(b))
        assertTrue(moved)
        assertEquals(3, refresher.refreshCount.get())
    }

    @Test
    fun `deleteTask refreshes the widget`() = runTest {
        val created = repository.createTask(userId = userId, title = "Buy milk")
        repository.deleteTask(created.id)
        assertEquals(2, refresher.refreshCount.get())
        assertTrue(repository.getAllTasksOnce(userId).isEmpty())
    }

    @Test
    fun `snoozeTask refreshes the widget`() = runTest {
        val created = repository.createTask(userId = userId, title = "Buy milk")
        repository.snoozeTask(created.id, System.currentTimeMillis() + 10 * 60_000L)
        assertEquals(2, refresher.refreshCount.get())
    }

    @Test
    fun `clearCompletedTasks refreshes the widget`() = runTest {
        val created = repository.createTask(userId = userId, title = "Buy milk")
        repository.toggleTaskCompletion(created)
        repository.clearCompletedTasks(userId)
        assertEquals(3, refresher.refreshCount.get())
        assertTrue(repository.getAllTasksOnce(userId).isEmpty())
    }

    @Test
    fun `default refresher is a no-op — manual constructions stay Glance-free`() = runTest {
        val plain = TaskRepository(context, db)
        val created = plain.createTask(userId = plain.currentUserId(), title = "Plain task")
        assertEquals("Plain task", created.title)
    }
}
