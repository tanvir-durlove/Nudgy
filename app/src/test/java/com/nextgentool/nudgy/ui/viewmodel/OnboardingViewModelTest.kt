package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-3 — the onboarding finish (feature 40, Q3=(a), spec §6).
 *
 * Follows the SettingsViewModelTest pattern: real in-memory Room
 * repository, the ViewModel built inside each test (never in setUp —
 * the init coroutines need the test Main), the ViewModel in a
 * ViewModelStore cleared BEFORE the database closes, and in-flight
 * Room IO drained first (S4-1 round 7). The seed runs on
 * Dispatchers.IO inside the repository, so the test polls the real
 * database (with a real-time deadline) instead of assuming
 * advanceUntilIdle covers IO threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingViewModelTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var settingsPreferences: SettingsPreferences
    private val store = ViewModelStore()
    private val guestUid = AuthManager.GUEST_USER_ID

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", guestUid).apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = TaskRepository(context, db)
        settingsPreferences = SettingsPreferences(
            context.getSharedPreferences("test_s6_3_onboarding", Context.MODE_PRIVATE)
                .also { it.edit().clear().commit() }
        )
    }

    @After
    fun tearDown() {
        store.clear()
        Thread.sleep(500)
        db.close()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        context.getSharedPreferences("test_s6_3_onboarding", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /** runTest with Main on the test dispatcher (CalendarViewModelTest pattern). */
    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun buildViewModel(): OnboardingViewModel =
        OnboardingViewModel(taskRepository, settingsPreferences)
            .also { store.put("onboarding", it) }

    /**
     * The seed runs on Dispatchers.IO inside the repository, which
     * advanceUntilIdle does not cover — poll the real database until
     * the expected sample count lands (or the deadline passes).
     */
    private suspend fun awaitSamples(
        expected: Int,
        timeoutMs: Long = 5_000
    ): List<TaskEntity> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val samples = taskRepository.getAllTasksOnce(guestUid).filter { it.isSample }
            if (samples.size == expected) return samples
            Thread.sleep(100)
        }
        return taskRepository.getAllTasksOnce(guestUid).filter { it.isSample }
    }

    @Test
    fun `finish with samples seeds exactly three sample tasks and sets the flag`() = vmTest {
        val viewModel = buildViewModel()

        viewModel.finish(includeSamples = true)
        advanceUntilIdle()

        val samples = awaitSamples(3)
        assertEquals(3, samples.size)
        assertEquals(
            OnboardingViewModel.ONBOARDING_SAMPLE_TASKS.map { it.title }.sorted(),
            samples.map { it.title }.sorted()
        )
        assertTrue(samples.all { it.isSample })
        assertTrue(samples.all { it.groupId == null })
        assertTrue(samples.all { !it.reminderSet })
        assertTrue(samples.all { it.reminderEpochMillis == null })
        val now = System.currentTimeMillis()
        assertTrue(samples.all { it.dueDateEpochMillis > now })
        assertTrue(samples.all { it.dueDateEpochMillis < now + 7 * 60 * 60 * 1000L })

        assertTrue(settingsPreferences.hasSeenOnboarding.value)
        // The flag persists across a store rebuild (a fresh instance
        // reads the same prefs file).
        val rebuilt = SettingsPreferences(
            context.getSharedPreferences("test_s6_3_onboarding", Context.MODE_PRIVATE)
        )
        assertTrue(rebuilt.hasSeenOnboarding.value)
    }

    @Test
    fun `finish without samples seeds nothing but sets the flag`() = vmTest {
        val viewModel = buildViewModel()

        viewModel.finish(includeSamples = false)
        advanceUntilIdle()

        assertEquals(0, awaitSamples(0).size)
        assertEquals(0, taskRepository.getAllTasksOnce(guestUid).size)
        assertTrue(settingsPreferences.hasSeenOnboarding.value)
    }

    @Test
    fun `finish with samples twice seeds only once`() = vmTest {
        val viewModel = buildViewModel()

        viewModel.finish(includeSamples = true)
        advanceUntilIdle()
        assertEquals(3, awaitSamples(3).size)

        // Same instance, second finish: the in-memory guard holds.
        viewModel.finish(includeSamples = true)
        advanceUntilIdle()
        assertEquals(3, awaitSamples(3).size)

        // A fresh instance (e.g. after process death): the
        // already-has-samples query guards it.
        val second = OnboardingViewModel(taskRepository, settingsPreferences)
            .also { store.put("onboarding-2", it) }
        second.finish(includeSamples = true)
        advanceUntilIdle()
        assertEquals(3, awaitSamples(3).size)
    }

    @Test
    fun `finish with samples when samples already exist does not duplicate`() = vmTest {
        taskRepository.createTask(
            userId = guestUid,
            title = "Morning walk",
            isSample = true
        )
        assertEquals(1, awaitSamples(1).size)
        val viewModel = buildViewModel()

        viewModel.finish(includeSamples = true)
        advanceUntilIdle()

        assertEquals(1, awaitSamples(1).size)
        assertTrue(settingsPreferences.hasSeenOnboarding.value)
    }

    @Test
    fun `sample tasks are ordinary personal tasks apart from the flag`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.finish(includeSamples = true)
        advanceUntilIdle()

        val samples = awaitSamples(3)
        assertEquals(3, samples.size)
        // No group side, no template, not completed, in TODO.
        assertTrue(samples.all { it.status.name == "TODO" })
        assertTrue(samples.all { !it.isCompleted })
        assertTrue(samples.all { it.templateId == null })
        // A normal task created alongside keeps isSample false.
        taskRepository.createTask(userId = guestUid, title = "Real task")
        val all = taskRepository.getAllTasksOnce(guestUid)
        assertEquals(4, all.size)
        assertFalse(all.first { it.title == "Real task" }.isSample)
    }
}
