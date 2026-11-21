package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.ai.ClientIntentPreFilter
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.ai.VoiceAssistantManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.ChatRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.ui.screens.ChatActionType
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Sprint 3-0 — ChatViewModel send-pipeline tests over real in-memory
 * Room + the real prefilter (same harness as
 * ClientIntentPreFilterTest / CalendarViewModelTest, no Hilt).
 *
 * What is pinned here (spec §8, S3-0 half):
 *  - guardrails run FIRST: a refused message stores nothing (not even
 *    the user's own message) and creates nothing.
 *  - a handled message stores user + assistant messages and the
 *    prefilter's creation lands in the repository.
 *  - an Unhandled message gets a LOCAL fallback answer — no LLM call
 *    (the ViewModel takes no GeminiClient at all), no crash.
 *  - every assistant reply plays the reply sound exactly once
 *    (FakeReplySoundPlayer counts it); the welcome seed is silent.
 *  - isSpeaking stays false: the chat never speaks (§4.4).
 *
 * The Main dispatcher is set per test (CalendarViewModelTest pattern):
 * StandardTestDispatcher(testScheduler), ViewModel built after setMain,
 * assertions via `messages.first { ... }`, resetMain() in finally.
 * Calendar permissions are deliberately NOT granted: the chat flow
 * never syncs, and blockFocusTime's Google export no-ops without them
 * while the local event is still inserted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatViewModelTest {

    private class FakeReplySoundPlayer : ReplySoundPlayer {
        var playCount = 0
        override fun playReplySound() {
            playCount++
        }
    }

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var chatRepository: ChatRepository
    private lateinit var preFilter: ClientIntentPreFilter
    private lateinit var taskRepository: TaskRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var voiceAssistantManager: VoiceAssistantManager
    private lateinit var fakeSound: FakeReplySoundPlayer

    private val userId = "test-user-1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_user_id", userId).apply()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository(context, db)
        taskRepository = TaskRepository(context, db)
        calendarRepository = CalendarRepository(context, db)
        preFilter = ClientIntentPreFilter(
            taskRepository, calendarRepository, MemoryRepository(context, db), context.assets
        )
        voiceAssistantManager = VoiceAssistantManager(context)
        fakeSound = FakeReplySoundPlayer()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newViewModel() = ChatViewModel(
        chatRepository, preFilter, taskRepository, calendarRepository,
        voiceAssistantManager, fakeSound
    )

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    @Test
    fun `welcome message greets neutrally with no hardcoded name`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            val messages = viewModel.messages.first { it.size == 1 }
            assertEquals("Hi! What can I help you get done today?", messages.single().text)
            assertFalse(messages.single().senderIsUser)
            // The welcome seed is silent — no reply sound for it.
            assertEquals(0, fakeSound.playCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `guardrails refusal stores only the refusal and creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("i want to kill somebody")
            val messages = viewModel.messages.first { it.size == 2 }
            // The refused user's message is NOT stored: only the
            // welcome + the refusal are in history.
            assertTrue(messages.none { it.senderIsUser })
            assertEquals("I can't help with that.", messages.last().text)
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(1, fakeSound.playCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // Sprint 3-2 (Q2 = (a)) — draft-then-confirm: the send now returns
    // a PREVIEW, not a creation. The task exists only after Confirm.
    // The old name asserted immediate creation; the new name states
    // the new flow, for the same reason as the prefilter tests.
    @Test
    fun `handled task creation drafts first and creates only on confirm`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("call tasnim at 5pm")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[1].senderIsUser)
            assertEquals("call tasnim at 5pm", messages[1].text)
            assertFalse(messages[2].senderIsUser)
            assertEquals(ChatActionType.DRAFT_PREVIEW, messages[2].actionType)
            assertNotNull(viewModel.pendingDraft.value)
            assertEquals(17, hourOf(viewModel.pendingDraft.value!!.startEpochMillis))
            // Nothing is in the DB yet — the preview is only a question.
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(1, fakeSound.playCount)
            // Confirm saves it and posts the receipt.
            viewModel.confirmDraft()
            val afterConfirm = viewModel.messages.first { it.size == 4 }
            assertEquals(ChatActionType.TASK_PREVIEW, afterConfirm[3].actionType)
            assertTrue(afterConfirm[3].text.contains("asnim", ignoreCase = true))
            val tasks = taskRepository.getAllTasksOnce(userId)
            assertEquals(1, tasks.size)
            assertEquals(17, hourOf(tasks.single().dueDateEpochMillis))
            assertTrue(tasks.single().title.contains("tasnim", ignoreCase = true))
            assertNull(viewModel.pendingDraft.value)
            assertEquals(2, fakeSound.playCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `discarding a draft creates nothing and posts a discarded note`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("call tasnim at 5pm")
            viewModel.messages.first { it.size == 3 }
            assertNotNull(viewModel.pendingDraft.value)
            viewModel.discardDraft()
            val messages = viewModel.messages.first { it.size == 4 }
            assertTrue(messages.last().text.contains("discarded", ignoreCase = true))
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertNull(viewModel.pendingDraft.value)
            // A second discard is a no-op — no extra message appears.
            viewModel.discardDraft()
            testScheduler.advanceUntilIdle()
            assertEquals(4, viewModel.messages.value.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `editing the draft changes what confirm creates`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("call tasnim at 5pm")
            viewModel.messages.first { it.size == 3 }
            val draft = viewModel.pendingDraft.value!!
            viewModel.editDraft(draft.withTitle("Call Tasnim about the launch"))
            assertEquals("Call Tasnim about the launch", viewModel.pendingDraft.value!!.title)
            viewModel.confirmDraft()
            viewModel.messages.first { it.size == 4 }
            val tasks = taskRepository.getAllTasksOnce(userId)
            assertEquals(1, tasks.size)
            assertEquals("Call Tasnim about the launch", tasks.single().title)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a new message abandons the pending draft`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("call tasnim at 5pm")
            viewModel.messages.first { it.size == 3 }
            assertNotNull(viewModel.pendingDraft.value)
            // The first send must fully finish (isThinking false)
            // before the second send, or the thinking guard ignores it.
            viewModel.isThinking.first { !it }
            viewModel.sendMessage("show today's tasks")
            viewModel.messages.first { it.size == 5 }
            assertNull(viewModel.pendingDraft.value)
            // Confirm now is a no-op: nothing was ever created.
            viewModel.confirmDraft()
            testScheduler.advanceUntilIdle()
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(5, viewModel.messages.value.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `unhandled small talk gets a local fallback and creates nothing`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("hello")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[1].senderIsUser)
            assertTrue(messages[2].text.contains("didn't quite catch"))
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
            assertEquals(1, fakeSound.playCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `unhandled Bengali small talk gets a Bengali fallback`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("হ্যালো")
            val messages = viewModel.messages.first { it.size == 3 }
            assertTrue(messages[2].text.contains("বুঝতে পারিনি"))
            assertTrue(taskRepository.getAllTasksOnce(userId).isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `isSpeaking stays false - the chat never speaks`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            assertFalse(viewModel.isSpeaking.value)
            viewModel.sendMessage("call tasnim at 5pm")
            viewModel.messages.first { it.size == 3 }
            // A reply arrived and the mascot still never talks (§4.4).
            assertFalse(viewModel.isSpeaking.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `focus proposal can be blocked from the message`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("find free time")
            val messages = viewModel.messages.first { it.size == 3 }
            val proposal = messages[2]
            assertEquals(ChatActionType.FOCUS_TIME_PROPOSAL, proposal.actionType)
            assertNotNull(proposal.focusStartMillis)
            assertNotNull(proposal.focusEndMillis)
            viewModel.onBlockFocusTime(
                proposal.focusStartMillis!!, proposal.focusEndMillis!!,
                "Focus Time", proposal.id
            )
            val today = startOfDayMillis(System.currentTimeMillis())
            val events = calendarRepository.observeEventsForDay(userId, today)
                .first { it.any { event -> event.title == "Focus Time" } }
            assertTrue(events.any { it.title == "Focus Time" })
            val updated = viewModel.messages.first { list ->
                list.any { it.id == proposal.id && it.focusActionCompleted }
            }
            assertTrue(updated.any { it.id == proposal.id && it.focusActionCompleted })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `blank message is ignored`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val viewModel = newViewModel()
            viewModel.messages.first { it.size == 1 } // welcome seeded
            viewModel.sendMessage("   ")
            // No new message appears and thinking never engages.
            assertFalse(viewModel.isThinking.value)
            assertEquals(1, viewModel.messages.value.size)
            assertEquals(0, fakeSound.playCount)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
