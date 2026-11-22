package com.nextgentool.nudgy.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.GroupMemberEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FakeGroupRemoteGateway
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.GroupResult
import com.nextgentool.nudgy.data.repository.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 5-1 — Groups tab state over real in-memory Room + the S5-0
 * fake gateway (no FirebaseApp, no live project).
 *
 * Follows the HabitViewModelTest pattern: the ViewModel is built inside
 * each test (never in setUp — the init coroutines need the test Main),
 * the ViewModel lives in a ViewModelStore cleared BEFORE the database
 * closes, and in-flight Room IO is drained first (S4-1 round 7).
 * The user is set through the auth prefs, the CalendarViewModelTest
 * precedent (nudgy_auth_prefs / active_user_id).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupsViewModelTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var fake: FakeGroupRemoteGateway
    private lateinit var groupRepository: GroupRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var calendarRepository: CalendarRepository
    private val store = ViewModelStore()
    private val userId = "user-1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        setActiveUser(userId)
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fake = FakeGroupRemoteGateway()
        groupRepository = GroupRepository(context, db, fake)
        taskRepository = TaskRepository(context, db)
        calendarRepository = CalendarRepository(context, db)
    }

    @After
    fun tearDown() {
        // PR #86 rule: cancel the ViewModel scopes before the database
        // closes, then drain in-flight Room IO (S4-1 round 7), then stop
        // the repository's own listener scope before closing.
        store.clear()
        Thread.sleep(500)
        groupRepository.stopGroupSync()
        db.close()
        context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun setActiveUser(uid: String?) {
        val prefs = context.getSharedPreferences("nudgy_auth_prefs", Context.MODE_PRIVATE)
        if (uid == null) {
            prefs.edit().clear().apply()
        } else {
            prefs.edit().putString("active_user_id", uid).apply()
        }
    }

    private fun buildViewModel(): GroupsViewModel {
        val viewModel = GroupsViewModel(groupRepository, taskRepository, calendarRepository)
        store.put("groups", viewModel)
        return viewModel
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

    /** GroupRepositoryTest pattern: the listener attach runs on real IO. */
    private suspend fun awaitCondition(timeoutMs: Long = 5_000L, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > deadline) {
                error("condition not met within ${timeoutMs}ms")
            }
            delay(25)
        }
    }

    private suspend fun GroupsViewModel.awaitGroup(name: String): GroupsUiState =
        uiState.first { state -> state.groups.any { it.name == name } }

    // ---- Create ----

    @Test
    fun `createGroup creates the group with creator as first member and a code mapping`() = vmTest {
        val viewModel = buildViewModel()

        val result = viewModel.createGroup("DIU 41")
        val entity = (result as GroupResult.Ok).value

        assertEquals(6, entity.code.length)
        assertTrue(entity.code.all { it.isLetterOrDigit() })
        assertEquals(userId, entity.creatorUid)
        assertEquals(entity.groupId, fake.codes[entity.code]?.get("groupId"))
        assertTrue(fake.members[entity.groupId]?.containsKey(userId) == true)
        val state = viewModel.awaitGroup("DIU 41")
        assertEquals(entity.groupId, state.groups.single().groupId)
        awaitCondition { fake.itemListenerCount(entity.groupId) == 1 }
    }

    @Test
    fun `createGroup with a blank name is an Err without touching the gateway`() = vmTest {
        val viewModel = buildViewModel()

        val result = viewModel.createGroup("   ")

        assertTrue(result is GroupResult.Err)
        assertEquals("Enter a group name.", (result as GroupResult.Err).message)
        assertTrue(fake.groups.isEmpty())
    }

    @Test
    fun `createGroup as guest is an Err`() = vmTest {
        setActiveUser(null)
        val viewModel = buildViewModel()

        assertTrue(viewModel.user.isGuest)
        assertEquals(AuthManager.GUEST_USER_ID, viewModel.user.uid)
        val result = viewModel.createGroup("DIU 41")

        assertTrue(result is GroupResult.Err)
        assertEquals("Sign in to create a group.", (result as GroupResult.Err).message)
    }

    // ---- Join ----

    @Test
    fun `joinGroup by code succeeds and writes the member docs`() = vmTest {
        // Setup through the repository as user-1, then remove the local
        // row so the join exercises the real remote path (otherwise the
        // repo returns the cached row as a no-op).
        val created = groupRepository.createGroup(userId, "Creator", "DIU 41")
        val entity = (created as GroupResult.Ok).value
        db.groupDao().deleteGroupById(entity.groupId)
        setActiveUser("user-2")
        val viewModel = buildViewModel()

        val result = viewModel.joinGroup(entity.code)

        val joined = (result as GroupResult.Ok).value
        assertEquals(entity.groupId, joined.groupId)
        assertTrue(fake.members[entity.groupId]?.containsKey("user-2") == true)
        val state = viewModel.awaitGroup("DIU 41")
        assertEquals(entity.groupId, state.groups.single().groupId)
        awaitCondition { fake.itemListenerCount(entity.groupId) == 1 }
    }

    @Test
    fun `joinGroup with a wrong code is an Err naming the code`() = vmTest {
        val viewModel = buildViewModel()

        val result = viewModel.joinGroup("ZZZZZZ")

        assertTrue(result is GroupResult.Err)
        assertTrue((result as GroupResult.Err).message.contains("doesn't match"))
        assertTrue(viewModel.uiState.value.groups.isEmpty())
    }

    @Test
    fun `joinGroup with a blank code is an Err`() = vmTest {
        val viewModel = buildViewModel()

        val result = viewModel.joinGroup("   ")

        assertTrue(result is GroupResult.Err)
        assertEquals("Enter a join code.", (result as GroupResult.Err).message)
    }

    @Test
    fun `re-joining an already-joined group is a no-op success`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value
        awaitCondition { fake.itemListenerCount(entity.groupId) == 1 }
        val joinsBefore = fake.joinMemberDocsCalls

        val result = viewModel.joinGroup(entity.code)

        assertTrue(result is GroupResult.Ok)
        assertEquals(entity.groupId, (result as GroupResult.Ok).value.groupId)
        assertEquals(joinsBefore, fake.joinMemberDocsCalls)
    }

    // ---- Prefill ----

    @Test
    fun `prefill code is stored normalized and consumed`() = vmTest {
        val viewModel = buildViewModel()

        viewModel.setPrefillJoinCode("k7q2xa")

        assertEquals("K7Q2XA", viewModel.uiState.first { it.prefillJoinCode != null }.prefillJoinCode)
        viewModel.consumePrefillJoinCode()
        assertNull(viewModel.uiState.first { it.prefillJoinCode == null }.prefillJoinCode)
    }

    @Test
    fun `prefill code is dropped for guests`() = vmTest {
        setActiveUser(null)
        val viewModel = buildViewModel()

        viewModel.setPrefillJoinCode("K7Q2XA")

        assertNull(viewModel.uiState.value.prefillJoinCode)
    }

    // ---- Members ----

    @Test
    fun `members with a blank display name read as Member`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value
        db.groupMemberDao().replaceMembers(
            entity.groupId,
            listOf(GroupMemberEntity(entity.groupId, "user-9", ""))
        )

        val members = viewModel.observeMembers(entity.groupId).first()

        assertEquals(listOf("Member"), members.map { it.displayName })
    }

    @Test
    fun `refreshMembers maps blank display names to Member`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value
        fake.members[entity.groupId]?.put(
            "user-9",
            mutableMapOf<String, Any?>("uid" to "user-9", "displayName" to "")
        )

        val members = viewModel.refreshMembers(entity.groupId)

        assertTrue(members.any { it.uid == "user-9" && it.displayName == "Member" })
    }
}
