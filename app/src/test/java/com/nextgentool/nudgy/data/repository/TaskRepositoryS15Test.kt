package com.nextgentool.nudgy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 1-5 — repository tests for estimate + actual persistence
 * (Feature 18, spec §6).
 *
 * Real in-memory Room + real TaskRepository (no Hilt), following the
 * S1-4 TaskRepositoryS14Test pattern. The write path is the generic
 * updateTask (whole-entity copy) — these pin that estimate and actual
 * survive it, independently of each other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskRepositoryS15Test {

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

    @Test
    fun `estimate set at creation is kept`() = runTest {
        val created = repository.createTask(
            userId = userId,
            title = "With estimate",
            estimatedMinutes = 30
        )
        assertEquals(30, repository.getTaskById(created.id)?.estimatedMinutes)
    }

    @Test
    fun `estimate and actual persist through updateTask`() = runTest {
        val created = repository.createTask(userId = userId, title = "Timed")
        repository.updateTask(
            created.copy(estimatedMinutes = 45, actualMinutes = 50)
        )
        val stored = repository.getTaskById(created.id)
        assertEquals(45, stored?.estimatedMinutes)
        assertEquals(50, stored?.actualMinutes)
    }

    @Test
    fun `clearing the estimate leaves actual untouched`() = runTest {
        val created = repository.createTask(userId = userId, title = "Timed")
        repository.updateTask(
            created.copy(estimatedMinutes = 45, actualMinutes = 50)
        )
        val stored = repository.getTaskById(created.id)!!
        repository.updateTask(stored.copy(estimatedMinutes = null))
        val after = repository.getTaskById(created.id)
        assertNull(after?.estimatedMinutes)
        assertEquals(50, after?.actualMinutes)
    }
}
