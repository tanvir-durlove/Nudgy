package com.nextgentool.nudgy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-4, step 2b — the Tasks search: one Room LIKE query over
 * title + description ("notes", spec Q5) + tag name.
 *
 * Real in-memory Room + real TaskRepository (no Hilt), following the
 * S1-5 TaskRepositoryS15Test pattern. The query runs through
 * [TaskRepository.searchTasks] so the LIKE-pattern escaping is covered
 * too (a typed '%' must match literally, not as a wildcard).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskRepositorySearchTest {

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
        db.close()
    }

    private val userId get() = repository.currentUserId()

    /**
     * Three tasks: "Buy groceries" matches on title and on the
     * "shopping" tag; "Call mom" and "Read book" match on description
     * ("garden" / "gardening").
     */
    private suspend fun seed() {
        repository.createTask(
            userId = userId,
            title = "Buy groceries",
            description = "Weekly shop at the market"
        )
        repository.createTask(
            userId = userId,
            title = "Call mom",
            description = "Ask about the garden"
        )
        repository.createTask(
            userId = userId,
            title = "Read book",
            description = "Chapter on gardening"
        )
        val tag = repository.createTag(userId, "shopping", "#0F766E")
        val groceries = repository.getAllTasksOnce(userId)
            .first { it.title == "Buy groceries" }
        repository.setTaskTags(groceries.id, listOf(tag.id))
    }

    @Test
    fun `title match returns only the title hit`() = runTest {
        seed()
        val results = repository.searchTasks(userId, "groceries").first()
        assertEquals(listOf("Buy groceries"), results.map { it.title })
    }

    @Test
    fun `description match returns both description hits`() = runTest {
        seed()
        val results = repository.searchTasks(userId, "garden").first()
        assertEquals(listOf("Call mom", "Read book"), results.map { it.title }.sorted())
    }

    @Test
    fun `tag name match returns the tagged task`() = runTest {
        seed()
        val results = repository.searchTasks(userId, "shopping").first()
        assertEquals(listOf("Buy groceries"), results.map { it.title })
    }

    @Test
    fun `no match returns an empty list`() = runTest {
        seed()
        val results = repository.searchTasks(userId, "zzz-no-such").first()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `match is case-insensitive`() = runTest {
        seed()
        val results = repository.searchTasks(userId, "BUY").first()
        assertEquals(listOf("Buy groceries"), results.map { it.title })
    }

    @Test
    fun `another user's task never leaks into the results`() = runTest {
        seed()
        db.taskDao().insertTask(
            TaskEntity(userId = "other-user", title = "Buy car")
        )
        val results = repository.searchTasks(userId, "Buy").first()
        assertEquals(listOf("Buy groceries"), results.map { it.title })
    }

    @Test
    fun `a typed percent matches literally, not as a wildcard`() = runTest {
        seed()
        // Without escaping, "%" would match every row.
        val results = repository.searchTasks(userId, "%").first()
        assertTrue(results.isEmpty())
    }
}
