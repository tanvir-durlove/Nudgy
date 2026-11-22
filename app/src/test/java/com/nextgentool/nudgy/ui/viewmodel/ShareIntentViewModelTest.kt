package com.nextgentool.nudgy.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.intent.IncomingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * Sprint 2-3 — share-intent state (Feature 12, spec §5).
 *
 * Proves the consumed-once rule (double share = one sheet,
 * rotation/process-death restore never re-creates), and that a
 * confirmed share creates exactly one task with the values the
 * sheet passes — no due date and reminder off for a share (Q5(a)).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class ShareIntentViewModelTest {

    private lateinit var db: NudgyDatabase
    private val viewModelStore = ViewModelStore()
    private lateinit var taskRepository: TaskRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", "test-user-1").apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries().build()
        taskRepository = TaskRepository(context, db)
    }

    @After
    fun tearDown() {
        // PR #86 rule: cancel the ViewModel scopes before the database
        // closes, so no viewModelScope coroutine can run Room work
        // against the closed DB and poison the next test's TestScope.
        viewModelStore.clear()
        // S4-1 round 7 — drain in-flight Room IO before closing the DB.
        // clear() cancels the ViewModels' scopes, but a Room call already
        // running on a real IO thread (a ViewModel init sync, or a
        // never-ending Room Flow collection re-querying after a write)
        // does not notice the cancellation until it returns; closing the
        // DB first makes that call throw IllegalStateException, which the
        // global coroutines-test ExceptionCollector buffers and replays
        // into the next test's runTest enter() as UncaughtExceptionsBeforeTest
        // (the compose rule wraps every test method in runTest, so the
        // victim is the next compose test — BoardDragTest). 500ms is ample
        // for in-memory Room calls.
        Thread.sleep(500)
        db.close()
    }

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()): ShareIntentViewModel =
        ShareIntentViewModel(handle, taskRepository).also { viewModelStore.put("share", it) }

    private fun shareIntent(subject: String? = null, text: String?): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }

    private suspend fun waitForTasks(
        predicate: (List<TaskEntity>) -> Boolean
    ): List<TaskEntity> =
        taskRepository.observeAllTasks("test-user-1").first(predicate)

    // ------------------------------------------------------- handle / pending

    @Test
    fun `handleIntent with a share sets pendingShare`() = runTest {
        val vm = viewModel()
        val accepted = vm.handleIntent(shareIntent(text = "Buy milk\n2% fat"))
        assertTrue(accepted)
        val pending = vm.pendingShare.value
        assertEquals("Buy milk", pending?.title)
        assertEquals("2% fat", pending?.description)
    }

    @Test
    fun `double share opens one sheet - the second is consumed, not queued`() = runTest {
        val vm = viewModel()
        val intent = shareIntent(text = "Buy milk\n2% fat")
        assertTrue(vm.handleIntent(intent))
        assertFalse(vm.handleIntent(intent))
        assertEquals("Buy milk", vm.pendingShare.value?.title)
    }

    @Test
    fun `empty share is not accepted and opens no sheet`() = runTest {
        val vm = viewModel()
        assertFalse(vm.handleIntent(shareIntent(text = "   ")))
        assertFalse(vm.handleIntent(shareIntent(text = null)))
        assertNull(vm.pendingShare.value)
    }

    @Test
    fun `a restored SavedStateHandle blocks re-parsing the same share`() = runTest {
        val share = IncomingIntent.ShareText("Buy milk", "2% fat")
        val vm1 = viewModel()
        assertTrue(vm1.handleIntent(shareIntent(text = "Buy milk\n2% fat")))
        // Simulate a process-death restore: a fresh ViewModel whose
        // SavedStateHandle already carries the consumed key.
        val restored = SavedStateHandle(
            initialState = mapOf(
                ShareIntentViewModel.KEY_CONSUMED_SHARE to
                    ShareIntentViewModel.shareKey(share)
            )
        )
        val vm2 = viewModel(restored)
        assertFalse(vm2.handleIntent(shareIntent(text = "Buy milk\n2% fat")))
        assertNull(vm2.pendingShare.value)
    }

    @Test
    fun `a different share after the first is accepted once the sheet is dismissed`() = runTest {
        val vm = viewModel()
        assertTrue(vm.handleIntent(shareIntent(text = "Buy milk")))
        vm.dismissShare()
        assertNull(vm.pendingShare.value)
        assertTrue(vm.handleIntent(shareIntent(text = "Call the dentist")))
        assertEquals("Call the dentist", vm.pendingShare.value?.title)
    }

    // ------------------------------------------------------- confirm / create

    @Test
    fun `confirmShare creates one task with no due date and reminder off`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val vm = viewModel()
            assertTrue(vm.handleIntent(shareIntent(text = "Buy milk\n2% fat")))
            vm.confirmShare(
                title = "Buy milk",
                description = "2% fat",
                dueEpochMillis = TaskRepository.NO_DUE_DATE,
                priority = Priority.MEDIUM,
                category = TaskCategory.WORK,
                reminderSet = false
            )
            assertNull(vm.pendingShare.value)
            val created = waitForTasks { it.any { task -> task.title == "Buy milk" } }
                .first { it.title == "Buy milk" }
            assertEquals(TaskRepository.NO_DUE_DATE, created.dueDateEpochMillis)
            assertEquals("2% fat", created.description)
            assertFalse(created.reminderSet)
            assertNull(created.reminderEpochMillis)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `confirmShare with a picked due date and reminder on keeps both`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val vm = viewModel()
            assertTrue(vm.handleIntent(shareIntent(text = "Call the dentist")))
            val due = System.currentTimeMillis() + 24 * 60 * 60_000L
            vm.confirmShare(
                title = "Call the dentist",
                description = null,
                dueEpochMillis = due,
                priority = Priority.HIGH,
                category = TaskCategory.PERSONAL,
                reminderSet = true
            )
            val created = waitForTasks { it.any { task -> task.title == "Call the dentist" } }
                .first { it.title == "Call the dentist" }
            assertEquals(due, created.dueDateEpochMillis)
            assertTrue(created.reminderSet)
            assertEquals(due - 2 * 60 * 60_000L, created.reminderEpochMillis)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `confirmShare twice creates exactly one task`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val vm = viewModel()
            assertTrue(vm.handleIntent(shareIntent(text = "Buy milk")))
            vm.confirmShare(
                title = "Buy milk",
                description = null,
                dueEpochMillis = TaskRepository.NO_DUE_DATE,
                priority = Priority.MEDIUM,
                category = TaskCategory.WORK,
                reminderSet = false
            )
            // Wait for the one task to land, then confirm again — the
            // second call is a no-op (pending was already cleared).
            val afterFirst = waitForTasks { it.count { task -> task.title == "Buy milk" } == 1 }
                .first { it.title == "Buy milk" }
            assertEquals(
                1,
                taskRepository.observeAllTasks("test-user-1").first()
                    .count { it.title == "Buy milk" }
            )
            vm.confirmShare(
                title = "Buy milk",
                description = null,
                dueEpochMillis = TaskRepository.NO_DUE_DATE,
                priority = Priority.MEDIUM,
                category = TaskCategory.WORK,
                reminderSet = false
            )
            val afterSecond = taskRepository.observeAllTasks("test-user-1").first()
            assertEquals(1, afterSecond.count { it.title == "Buy milk" })
            assertEquals(afterFirst.id, afterSecond.first { it.title == "Buy milk" }.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `dismissShare clears pending without creating anything`() = runTest {
        val vm = viewModel()
        assertTrue(vm.handleIntent(shareIntent(text = "Buy milk")))
        vm.dismissShare()
        assertNull(vm.pendingShare.value)
        assertTrue(taskRepository.observeAllTasks("test-user-1").first().isEmpty())
    }

    // -------------------------------------- Sprint 2-4: date link / insert

    private fun dateLinkIntent(
        date: String = "2026-11-20",
        time: String? = "15:00",
        title: String? = "Doctor"
    ): Intent = Intent(Intent.ACTION_VIEW).apply {
        val query = listOfNotNull(
            "d=$date",
            time?.let { "t=$it" },
            title?.let { "title=$it" }
        ).joinToString("&")
        data = android.net.Uri.parse("https://tanvir-durlove.github.io/nudgy/date?$query")
    }

    private fun insertEventIntent(
        title: String? = "Design review",
        begin: Long? = 1_764_000_000_000L,
        end: Long? = 1_764_003_600_000L
    ): Intent = Intent(Intent.ACTION_INSERT).apply {
        setDataAndType(
            android.provider.CalendarContract.Events.CONTENT_URI,
            "vnd.android.cursor.dir/event"
        )
        title?.let { putExtra(android.provider.CalendarContract.Events.TITLE, it) }
        begin?.let { putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        end?.let { putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, it) }
    }

    @Test
    fun `handleIntent with a date link sets pendingDateLink`() = runTest {
        val vm = viewModel()
        assertTrue(vm.handleIntent(dateLinkIntent()))
        val pending = vm.pendingDateLink.value
        assertEquals("2026-11-20", pending?.date)
        assertEquals("15:00", pending?.time)
        assertEquals("Doctor", pending?.title)
        // The share lane is untouched by a link.
        assertNull(vm.pendingShare.value)
    }

    @Test
    fun `double date link opens one jump - the second is consumed, not queued`() = runTest {
        val vm = viewModel()
        val intent = dateLinkIntent()
        assertTrue(vm.handleIntent(intent))
        assertFalse(vm.handleIntent(intent))
        assertEquals("2026-11-20", vm.pendingDateLink.value?.date)
    }

    @Test
    fun `consumeDateLink clears pending and a new link is accepted after`() = runTest {
        val vm = viewModel()
        assertTrue(vm.handleIntent(dateLinkIntent()))
        vm.consumeDateLink()
        assertNull(vm.pendingDateLink.value)
        assertTrue(vm.handleIntent(dateLinkIntent(date = "2026-11-21", time = null, title = null)))
        assertEquals("2026-11-21", vm.pendingDateLink.value?.date)
        assertNull(vm.pendingDateLink.value?.time)
    }

    @Test
    fun `a restored SavedStateHandle blocks re-parsing the same date link`() = runTest {
        val link = IncomingIntent.DateLink("2026-11-20", "15:00", "Doctor")
        val vm1 = viewModel()
        assertTrue(vm1.handleIntent(dateLinkIntent()))
        // Simulate a process-death restore: a fresh ViewModel whose
        // SavedStateHandle already carries the consumed key.
        val restored = SavedStateHandle(
            initialState = mapOf(
                ShareIntentViewModel.KEY_CONSUMED_DATE_LINK to
                    ShareIntentViewModel.dateLinkKey(link)
            )
        )
        val vm2 = viewModel(restored)
        assertFalse(vm2.handleIntent(dateLinkIntent()))
        assertNull(vm2.pendingDateLink.value)
    }

    @Test
    fun `handleIntent with ACTION_INSERT sets pendingInsertEvent`() = runTest {
        val vm = viewModel()
        assertTrue(vm.handleIntent(insertEventIntent()))
        val pending = vm.pendingInsertEvent.value
        assertEquals("Design review", pending?.title)
        assertEquals(1_764_000_000_000L, pending?.beginMillis)
        assertEquals(1_764_003_600_000L, pending?.endMillis)
        assertNull(vm.pendingShare.value)
    }

    @Test
    fun `consumeInsertEvent clears pending and a new insert is accepted after`() = runTest {
        val vm = viewModel()
        assertTrue(vm.handleIntent(insertEventIntent()))
        vm.consumeInsertEvent()
        assertNull(vm.pendingInsertEvent.value)
        assertTrue(vm.handleIntent(insertEventIntent(title = "Standup")))
        assertEquals("Standup", vm.pendingInsertEvent.value?.title)
    }

    @Test
    fun `a group-join VIEW intent is not claimed by the handler`() = runTest {
        val vm = viewModel()
        val joinIntent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(
                "https://tanvir-durlove.github.io/nudgy/join?code=ABC123"
            )
        }
        assertFalse(vm.handleIntent(joinIntent))
        assertNull(vm.pendingDateLink.value)
        assertNull(vm.pendingInsertEvent.value)
        assertNull(vm.pendingShare.value)
    }
}
