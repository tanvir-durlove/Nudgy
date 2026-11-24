package com.nextgentool.nudgy.ui.viewmodel

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.settings.ByokKeyStore
import com.nextgentool.nudgy.settings.CalendarConnection
import com.nextgentool.nudgy.settings.SettingsAccountActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
 * Sprint 6-1 — the Settings core: live user, Memories, BYOK, delete,
 * calendar connection.
 *
 * Follows the LiveUserViewModelFlipTest pattern: the user is set
 * through the auth prefs, the ViewModel is built inside each test
 * (never in setUp — the init coroutines need the test Main), the
 * ViewModel lives in a ViewModelStore cleared BEFORE the database
 * closes, and in-flight Room IO is drained first (S4-1 round 7).
 * The delete path is faked — no real account is ever deleted here
 * (the device test only goes as far as the confirm dialog).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsViewModelTest {

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var memoryRepository: MemoryRepository
    private val store = ViewModelStore()
    private val signedInUid = "user-1"
    private val guestUid = AuthManager.GUEST_USER_ID

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        setActiveUser(guestUid)
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = TaskRepository(context, db)
        memoryRepository = MemoryRepository(context, db)
    }

    @After
    fun tearDown() {
        store.clear()
        Thread.sleep(500)
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

    private fun buildViewModel(
        fakeAuth: GroupAuth = FlipGroupAuth(),
        fakeActions: SettingsAccountActions = FakeSettingsAccountActions(),
        fakeByok: ByokKeyStore = FakeByokKeyStore(),
        fakeCalendar: CalendarConnection = FakeCalendarConnection()
    ): SettingsViewModel = SettingsViewModel(
        taskRepository = taskRepository,
        memoryRepository = memoryRepository,
        groupAuth = fakeAuth,
        accountActions = fakeActions,
        byokKeyStore = fakeByok,
        calendarConnection = fakeCalendar
    ).also { store.put("settings", it) }

    private fun flip(auth: FlipGroupAuth) {
        auth.userStateFlow.value =
            NudgyUser(uid = signedInUid, displayName = "Tanvir Ahmed", email = "tanvir@example.com")
    }

    // ---- Live user ----

    @Test
    fun `profile follows the guest to signed-in flip with no restart`() = vmTest {
        val fakeAuth = FlipGroupAuth()
        val viewModel = buildViewModel(fakeAuth = fakeAuth)

        assertTrue(viewModel.user.first { it.isGuest }.isGuest)

        flip(fakeAuth)

        val signedIn = viewModel.user.first { !it.isGuest }
        assertEquals(signedInUid, signedIn.uid)
        assertEquals("Tanvir Ahmed", signedIn.displayName)
        assertEquals("tanvir@example.com", signedIn.email)
    }

    @Test
    fun `sign-in calls the auth seam`() = vmTest {
        val fakeAuth = FlipGroupAuth()
        val viewModel = buildViewModel(fakeAuth = fakeAuth)
        viewModel.signIn(mockActivity(), this)
        assertEquals(1, fakeAuth.signInCalls)
    }

    @Test
    fun `sign-in error surfaces on settingsError`() = vmTest {
        val failingAuth = FailingGroupAuth()
        val viewModel = buildViewModel(fakeAuth = failingAuth)
        val error = async { viewModel.settingsError.first() }
        viewModel.signIn(mockActivity(), this)
        assertEquals("sign-in failed", error.await())
    }

    @Test
    fun `sign-out calls the account actions`() = vmTest {
        val fakeActions = FakeSettingsAccountActions()
        val viewModel = buildViewModel(fakeActions = fakeActions)
        viewModel.signOut()
        assertEquals(1, fakeActions.signOutCalls)
    }

    // ---- Memories ----

    @Test
    fun `memories follow the live user across the flip`() = vmTest {
        memoryRepository.addMemory(guestUid, "Guest likes morning focus")
        memoryRepository.addMemory(signedInUid, "Tanvir prefers evening study")

        val fakeAuth = FlipGroupAuth()
        val viewModel = buildViewModel(fakeAuth = fakeAuth)

        val guestMemories = viewModel.memories.first { list ->
            list.any { it.content == "Guest likes morning focus" }
        }
        assertFalse(guestMemories.any { it.content == "Tanvir prefers evening study" })

        flip(fakeAuth)

        val signedInMemories = viewModel.memories.first { list ->
            list.any { it.content == "Tanvir prefers evening study" }
        }
        assertFalse(signedInMemories.any { it.content == "Guest likes morning focus" })
    }

    @Test
    fun `addMemory stores for the live user and blank input is ignored`() = vmTest {
        val viewModel = buildViewModel()
        viewModel.addMemory("  ")
        viewModel.addMemory("Prefers tea over coffee")
        val memories = viewModel.memories.first { it.size == 1 }
        assertEquals("Prefers tea over coffee", memories.single().content)
        assertEquals(guestUid, memories.single().userId)
    }

    @Test
    fun `deleteMemory removes the memory`() = vmTest {
        memoryRepository.addMemory(guestUid, "To be deleted")
        val viewModel = buildViewModel()
        val stored = viewModel.memories.first { it.size == 1 }.single()
        viewModel.deleteMemory(stored.id)
        viewModel.memories.first { it.isEmpty() }
    }

    // ---- BYOK ----

    @Test
    fun `byok save and reset round-trip the key`() = vmTest {
        val fakeByok = FakeByokKeyStore()
        val viewModel = buildViewModel(fakeByok = fakeByok)

        // byokKeySet is a derived stateIn flow: a bare first() returns
        // the stale replayed value before the sharing coroutine
        // propagates the change, so await the predicate instead
        // (the PR #101 Plan flip-test lesson).
        viewModel.byokKeySet.first { !it }
        viewModel.saveByokKey("  AIzaSy-test-key  ")
        viewModel.byokKeySet.first { it }
        assertEquals("AIzaSy-test-key", viewModel.byokKey.first())
        assertEquals("AIzaSy-test-key", fakeByok.getKey())

        viewModel.saveByokKey("   ")
        viewModel.byokKeySet.first { !it }
        assertEquals(null, fakeByok.getKey())

        viewModel.saveByokKey("AIzaSy-second")
        viewModel.byokKeySet.first { it }
        viewModel.resetByokKey()
        viewModel.byokKeySet.first { !it }
        assertEquals("", viewModel.byokKey.first())
    }

    // ---- Account deletion (faked — no real account is touched) ----

    @Test
    fun `deleteAccount completes and emits deleteCompleted`() = vmTest {
        val fakeActions = FakeSettingsAccountActions()
        val viewModel = buildViewModel(fakeActions = fakeActions)
        val completed = async { viewModel.deleteCompleted.first() }
        viewModel.deleteAccount()
        completed.await()
        assertEquals(1, fakeActions.deleteCalls)
        assertFalse(viewModel.isDeleting.first())
    }

    @Test
    fun `deleteAccount failure surfaces the error and stays open`() = vmTest {
        val fakeActions = FakeSettingsAccountActions().also { it.deleteError = "network down" }
        val viewModel = buildViewModel(fakeActions = fakeActions)
        val error = async { viewModel.settingsError.first() }
        viewModel.deleteAccount()
        assertEquals("network down", error.await())
        assertFalse(viewModel.isDeleting.first())
        assertEquals(1, fakeActions.deleteCalls)
    }

    // ---- Calendar connection ----

    @Test
    fun `calendar sync uses the live uid and reports errors`() = vmTest {
        val fakeCalendar = FakeCalendarConnection()
        val viewModel = buildViewModel(fakeCalendar = fakeCalendar)

        assertFalse(viewModel.calendarConnected.first())
        fakeCalendar.isConnectedFlow.value = true
        assertTrue(viewModel.calendarConnected.first { it })

        viewModel.syncCalendarNow()
        advanceUntilIdle()
        assertEquals(listOf(guestUid), fakeCalendar.syncCalls)

        fakeCalendar.syncThrows = RuntimeException("sync failed")
        val error = async { viewModel.settingsError.first() }
        viewModel.syncCalendarNow()
        assertEquals("sync failed", error.await())
        assertFalse(viewModel.isCalendarSyncing.first())
    }

    private fun mockActivity(): Activity =
        org.robolectric.Robolectric.buildActivity(
            androidx.activity.ComponentActivity::class.java
        ).get()
}

/** The auth seam that always fails sign-in (error-path coverage). */
private class FailingGroupAuth : GroupAuth {
    private val _userState = MutableStateFlow<NudgyUser?>(null)
    override val userState: StateFlow<NudgyUser?> = _userState

    override fun signIn(
        activity: Activity,
        scope: CoroutineScope,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        onError("sign-in failed")
    }

    override fun attemptSilentSignIn(scope: CoroutineScope) = Unit
}

private class FakeSettingsAccountActions : SettingsAccountActions {
    var signOutCalls = 0
    var deleteCalls = 0
    var deleteError: String? = null

    override fun signOut(scope: CoroutineScope, onComplete: () -> Unit) {
        signOutCalls++
        onComplete()
    }

    override fun deleteAccount(onComplete: () -> Unit, onError: (String) -> Unit) {
        deleteCalls++
        if (deleteError != null) onError(deleteError!!) else onComplete()
    }
}

private class FakeByokKeyStore : ByokKeyStore {
    private var stored: String? = null
    override fun getKey(): String? = stored
    override fun setKey(key: String?) {
        stored = key?.trim()?.takeIf { it.isNotBlank() }
    }
}

private class FakeCalendarConnection : CalendarConnection {
    val isConnectedFlow = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = isConnectedFlow
    val syncCalls = mutableListOf<String>()
    var syncThrows: Exception? = null

    override fun refresh() = Unit

    override suspend fun sync(userId: String): Int {
        syncCalls += userId
        syncThrows?.let { throw it }
        return 1
    }
}
