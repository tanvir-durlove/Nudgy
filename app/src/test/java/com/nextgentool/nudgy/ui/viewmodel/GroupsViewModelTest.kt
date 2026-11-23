package com.nextgentool.nudgy.ui.viewmodel

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.GroupMemberEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FakeGroupRemoteGateway
import com.nextgentool.nudgy.data.repository.GroupActivityType
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.GroupResult
import com.nextgentool.nudgy.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.robolectric.Robolectric
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

    private fun buildViewModel(fakeAuth: GroupAuth = FakeGroupAuth()): GroupsViewModel {
        val viewModel = GroupsViewModel(groupRepository, taskRepository, calendarRepository, fakeAuth)
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

        assertTrue(viewModel.user.value.isGuest)
        assertEquals(AuthManager.GUEST_USER_ID, viewModel.user.value.uid)
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
        // Let the ViewModel settle on the created group (its own flow)
        // before measuring the join-write counter — the listener attach
        // is fire-and-forget on real IO and racy to await here; the
        // S5-0 repository tests already cover startGroupSync itself.
        viewModel.awaitGroup("DIU 41")
        val joinsBefore = fake.joinMemberDocsCalls

        val result = viewModel.joinGroup(entity.code)

        assertTrue(result is GroupResult.Ok)
        assertEquals(entity.groupId, (result as GroupResult.Ok).value.groupId)
        assertEquals(joinsBefore, fake.joinMemberDocsCalls)
    }

    // ---- Restore on sign-in ----

    @Test
    fun `signing in restores groups from the mirror after a fresh install`() = vmTest {
        // Setup through the repository as user-1, then wipe the local
        // row (fresh install) — the mirror in Firestore survives.
        val created = groupRepository.createGroup(userId, "Creator", "DIU 41")
        val entity = (created as GroupResult.Ok).value
        db.groupDao().deleteGroupById(entity.groupId)
        // The ViewModel starts as a guest (no active user in prefs)...
        setActiveUser(null)
        val auth = FakeGroupAuth()
        val viewModel = buildViewModel(auth)
        // ...then the auth flow emits the signed-in user, and the
        // init collector restores from the mirror before syncing.
        auth.userStateFlow.value = NudgyUser(
            uid = userId, displayName = "Creator", email = "creator@example.com"
        )

        val state = viewModel.awaitGroup("DIU 41")
        assertEquals(entity.groupId, state.groups.single().groupId)
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

    // ---- Sprint 5-2: group tasks (Q3=(b) admin-only) + completion (feature 31)

    @Test
    fun `createGroupTask as creator writes the shared doc and the local row`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value

        viewModel.createGroupTask(
            entity.groupId, "Homework", "Chapter 3",
            5000L, Priority.HIGH, TaskCategory.WORK, true
        )
        val state = viewModel.awaitGroup("DIU 41")
        val task = state.allTasks.single { it.groupId == entity.groupId }
        assertEquals("Homework", task.title)
        assertEquals("TASK", fake.items[entity.groupId]?.get(task.id)?.get("kind"))
    }

    @Test
    fun `createGroupTask as a member emits the creator-only error`() = vmTest {
        val created = groupRepository.createGroup(userId, "Creator", "DIU 41")
        val entity = (created as GroupResult.Ok).value
        setActiveUser("user-2")
        val viewModel = buildViewModel()
        val errorDeferred = backgroundScope.async { viewModel.groupTaskError.first() }

        viewModel.createGroupTask(
            entity.groupId, "Homework", null,
            5000L, Priority.MEDIUM, TaskCategory.WORK, false
        )

        assertEquals("Only the creator can change group tasks.", errorDeferred.await())
        assertTrue(fake.items[entity.groupId].isNullOrEmpty())
    }

    @Test
    fun `toggleGroupTask completes and reopens through the repository`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value
        val taskResult = groupRepository.createGroupTask(
            entity.groupId, userId, "Homework", null,
            5000L, Priority.MEDIUM, TaskCategory.WORK, false
        )
        val task = (taskResult as GroupResult.Ok).value

        viewModel.toggleGroupTask(task)
        val done = viewModel.uiState.first { state ->
            state.allTasks.any { it.id == task.id && it.isCompleted }
        }
        assertTrue(done.allTasks.single { it.id == task.id }.isCompleted)
        assertEquals(
            listOf(userId),
            fake.items[entity.groupId]?.get(task.id)?.get("completedBy")
        )

        viewModel.toggleGroupTask(done.allTasks.single { it.id == task.id })
        val reopened = viewModel.uiState.first { state ->
            state.allTasks.any { it.id == task.id && !it.isCompleted }
        }
        assertTrue(!reopened.allTasks.single { it.id == task.id }.isCompleted)
        assertEquals(
            emptyList<String>(),
            fake.items[entity.groupId]?.get(task.id)?.get("completedBy")
        )
    }

    @Test
    fun `observeCompletions emits the shared count after a toggle`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value
        val taskResult = groupRepository.createGroupTask(
            entity.groupId, userId, "Homework", null,
            5000L, Priority.MEDIUM, TaskCategory.WORK, false
        )
        val task = (taskResult as GroupResult.Ok).value

        viewModel.toggleGroupTask(task)
        val map = viewModel.observeCompletions().first {
            it[task.id]?.completedCount == 1
        }
        assertEquals(listOf(userId), map[task.id]?.completedBy)
    }

    // ---- Sprint 5-3: notices (feature 32, Q2=(a) text-only) ----

    @Test
    fun `sendNotice as creator appears in observeNotices at once`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value

        viewModel.sendNotice(entity.groupId, "Class at 10")

        val notices = viewModel.observeNotices(entity.groupId).first { it.isNotEmpty() }
        assertEquals(listOf("Class at 10"), notices.map { it.text })
        assertEquals(
            "Class at 10",
            fake.notices[entity.groupId]?.values?.single()?.get("text")
        )
    }

    @Test
    fun `sendNotice as a member emits the creator-only error`() = vmTest {
        val created = groupRepository.createGroup(userId, "Creator", "DIU 41")
        val entity = (created as GroupResult.Ok).value
        setActiveUser("user-2")
        val viewModel = buildViewModel()
        val errorDeferred = backgroundScope.async { viewModel.groupTaskError.first() }

        viewModel.sendNotice(entity.groupId, "Hello")

        assertEquals("Only the creator can send a notice.", errorDeferred.await())
        assertTrue(fake.notices[entity.groupId].isNullOrEmpty())
    }

    @Test
    fun `refreshNotices returns the fresh list from the gateway`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value
        groupRepository.createGroupNotice(entity.groupId, userId, "Creator", "Class at 10")

        val refreshed = viewModel.refreshNotices(entity.groupId)

        assertEquals(listOf("Class at 10"), refreshed.map { it.text })
    }

    // ---- Sprint 5-4: delete / leave (feature 33, spec §7)

    @Test
    fun `deleteGroup as creator deletes the group everywhere`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value
        viewModel.uiState.first { state -> state.groups.any { it.groupId == entity.groupId } }

        viewModel.deleteGroup(entity.groupId)

        val state = viewModel.uiState.first { state -> state.groups.none { it.groupId == entity.groupId } }
        assertTrue(state.groups.isEmpty())
        assertTrue(fake.groups.isEmpty())
        assertTrue(fake.codes.isEmpty())
    }

    @Test
    fun `deleteGroup as member emits the creator-only error`() = vmTest {
        val created = groupRepository.createGroup(userId, "Creator", "DIU 41")
        val entity = (created as GroupResult.Ok).value
        groupRepository.joinGroup("user-2", "Bob", entity.code)
        setActiveUser("user-2")
        val viewModel = buildViewModel()
        val errorDeferred = backgroundScope.async { viewModel.groupTaskError.first() }

        viewModel.deleteGroup(entity.groupId)

        assertEquals("Only the creator can delete this group.", errorDeferred.await())
        checkNotNull(fake.groups[entity.groupId])
    }

    @Test
    fun `leaveGroup as member leaves and purges locally`() = vmTest {
        val created = groupRepository.createGroup(userId, "Creator", "DIU 41")
        val entity = (created as GroupResult.Ok).value
        groupRepository.joinGroup("user-2", "Bob", entity.code)
        setActiveUser("user-2")
        val viewModel = buildViewModel()
        viewModel.uiState.first { state -> state.groups.any { it.groupId == entity.groupId } }

        viewModel.leaveGroup(entity.groupId)

        val state = viewModel.uiState.first { state -> state.groups.none { it.groupId == entity.groupId } }
        assertTrue(state.groups.isEmpty())
        assertNull(fake.members[entity.groupId]?.get("user-2"))
        assertNull(fake.mirrors["user-2"]?.get(entity.groupId))
    }

    @Test
    fun `leaveGroup as creator emits the delete-instead error`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value
        val errorDeferred = backgroundScope.async { viewModel.groupTaskError.first() }

        viewModel.leaveGroup(entity.groupId)

        assertEquals("The creator can't leave — delete the group instead.", errorDeferred.await())
        checkNotNull(fake.groups[entity.groupId])
    }

    // ---- Sprint 5-5: assign + activity history (feature 36, Q10=(a))

    @Test
    fun `assignGroupTask as creator shows the assignee name on the task`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value
        // One repo instance plays both users: drop the local row so
        // user-2's join takes the real remote path (otherwise the
        // re-join no-op returns the cached row and user-2 never
        // becomes a remote member — the S5-1 join-test pattern).
        db.groupDao().deleteGroupById(entity.groupId)
        groupRepository.joinGroup("user-2", "Bob", entity.code)
        viewModel.refreshMembers(entity.groupId)
        val taskResult = groupRepository.createGroupTask(
            entity.groupId, userId, "Homework", null,
            5000L, Priority.MEDIUM, TaskCategory.WORK, false
        )
        val task = (taskResult as GroupResult.Ok).value

        viewModel.assignGroupTask(entity.groupId, task.id, "user-2")

        val state = viewModel.uiState.first { state ->
            state.allTasks.any { it.id == task.id && it.assigneeUid == "user-2" }
        }
        val assigned = state.allTasks.single { it.id == task.id }
        assertEquals("Bob", assigned.assigneeName)
    }

    @Test
    fun `assignGroupTask as a member emits the creator-only error`() = vmTest {
        val created = groupRepository.createGroup(userId, "Creator", "DIU 41")
        val entity = (created as GroupResult.Ok).value
        val taskResult = groupRepository.createGroupTask(
            entity.groupId, userId, "Homework", null,
            5000L, Priority.MEDIUM, TaskCategory.WORK, false
        )
        val task = (taskResult as GroupResult.Ok).value
        setActiveUser("user-2")
        val viewModel = buildViewModel()
        val errorDeferred = backgroundScope.async { viewModel.groupTaskError.first() }

        viewModel.assignGroupTask(entity.groupId, task.id, "user-2")

        assertEquals("Only the creator can change group tasks.", errorDeferred.await())
        assertNull(fake.items[entity.groupId]?.get(task.id)?.get("assigneeUid"))
    }

    @Test
    fun `refreshActivity returns the recorded history`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value
        groupRepository.createGroupTask(
            entity.groupId, userId, "Homework", null,
            5000L, Priority.MEDIUM, TaskCategory.WORK, false
        )

        val history = viewModel.refreshActivity(entity.groupId)

        assertTrue(history.any { it.type == GroupActivityType.GROUP_CREATED })
        assertTrue(history.any { it.type == GroupActivityType.TASK_CREATED })
    }

    @Test
    fun `observeActivity emits the history after attach`() = vmTest {
        val viewModel = buildViewModel()
        val created = viewModel.createGroup("DIU 41")
        val entity = (created as GroupResult.Ok).value

        val history = viewModel.observeActivity(entity.groupId)
            .first { entries -> entries.any { it.type == GroupActivityType.GROUP_CREATED } }

        assertTrue(history.any { it.actorUid == userId })
    }

    // ---- Sign-in (Sprint 5 fix round) ----

    @Test
    fun `uiState user flips guest to signed-in when auth state changes`() = vmTest {
        setActiveUser(AuthManager.GUEST_USER_ID)
        val fakeAuth = FakeGroupAuth()
        val viewModel = buildViewModel(fakeAuth)

        val guestState = viewModel.uiState.first { it.user.isGuest }
        assertEquals(AuthManager.GUEST_USER_ID, guestState.user.uid)
        assertFalse(guestState.user.displayName.isNotBlank())

        fakeAuth.userStateFlow.value =
            NudgyUser(uid = userId, displayName = "Ayesha", email = "ayesha@example.com")
        val signedIn = viewModel.uiState.first { !it.user.isGuest }

        assertEquals(userId, signedIn.user.uid)
        assertEquals("Ayesha", signedIn.user.displayName)
        assertFalse(signedIn.user.isGuest)
    }

    @Test
    fun `sign-in error surfaces on groupTaskError instead of failing silently`() = vmTest {
        val fakeAuth = FakeGroupAuth().apply {
            signInError = "No Google account found on this device."
        }
        val viewModel = buildViewModel(fakeAuth)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val error = async { viewModel.groupTaskError.first() }

        viewModel.signIn(activity, this)

        assertEquals("No Google account found on this device.", error.await())
        assertEquals(1, fakeAuth.signInCalls)
    }
}

/**
 * Sprint 5 fix round — a [GroupAuth] the tests can drive: the real
 * AuthManager's user state only moves on a real Firebase sign-in, so
 * the guest→signed-in flip and the sign-in error path are proven
 * here instead of on a device.
 */
private class FakeGroupAuth : GroupAuth {
    val userStateFlow = MutableStateFlow<NudgyUser?>(null)
    override val userState: StateFlow<NudgyUser?> = userStateFlow

    var signInError: String? = null
    var signInCalls = 0
    var silentSignInCalls = 0

    override fun signIn(
        activity: Activity,
        scope: CoroutineScope,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        signInCalls++
        signInError?.let { onError(it) } ?: onSuccess()
    }

    override fun attemptSilentSignIn(scope: CoroutineScope) {
        silentSignInCalls++
    }
}
