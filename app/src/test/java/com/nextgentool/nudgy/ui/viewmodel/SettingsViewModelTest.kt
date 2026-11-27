package com.nextgentool.nudgy.ui.viewmodel

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.auth.UpdateDisplayNameResult
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FakeGroupRemoteGateway
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.settings.ByokKeyStore
import com.nextgentool.nudgy.settings.CalendarConnection
import com.nextgentool.nudgy.settings.DataRestorer
import com.nextgentool.nudgy.settings.SettingsAccountActions
import com.nextgentool.nudgy.settings.SettingsPreferences
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
            // Sprint 7 — the resetMain race (the PR #128 / FocusViewModelTest
            // pattern, same stack): the ViewModel is still alive here; its
            // init coroutines do Room work on real Dispatchers.IO threads
            // and resume back onto Main from those threads, so resetMain()
            // must wait until the scope is cancelled and the IO drained —
            // otherwise a resume in flight throws "Dispatchers.Main is used
            // concurrently with setting it". The tearDown drain runs AFTER
            // resetMain, too late to help — so cancel + drain HERE, first.
            store.clear()
            Thread.sleep(500)
            Dispatchers.resetMain()
        }
    }

    private fun buildViewModel(
        fakeAuth: GroupAuth = FlipGroupAuth(),
        fakeActions: SettingsAccountActions = FakeSettingsAccountActions(),
        fakeByok: ByokKeyStore = FakeByokKeyStore(),
        fakeCalendar: CalendarConnection = FakeCalendarConnection(),
        settingsPrefs: SettingsPreferences = SettingsPreferences(
            context.getSharedPreferences("test_s6_2_settings", Context.MODE_PRIVATE)
                .also { it.edit().clear().commit() }
        ),
        // Sprint 6-6 (step 4d) — the export/restore repositories:
        // null by default (the missing-repository outcome), so the
        // guard tests can pass nulls explicitly without a real DB
        // calendar repo.
        memoryRepo: MemoryRepository? = memoryRepository,
        calendarRepo: CalendarRepository? = null,
        // Step 2-2 — the group fan-out repository: null by default
        // (no fan-out), so the rename tests can pass a real one.
        groupRepo: GroupRepository? = null
    ): SettingsViewModel = SettingsViewModel(
        taskRepository = taskRepository,
        memoryRepository = memoryRepo,
        groupAuth = fakeAuth,
        accountActions = fakeActions,
        byokKeyStore = fakeByok,
        calendarConnection = fakeCalendar,
        settingsPreferences = settingsPrefs,
        calendarRepository = calendarRepo,
        groupRepository = groupRepo
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

    // ---- Profile Edit (Step 2-2) ----
    //
    // The rename itself is faked at the seam (the real
    // AuthManager.updateDisplayName touches Firebase and is not
    // unit-testable in Robolectric — device-test item); what is
    // proven here is the ViewModel around it: trim, blank-guard,
    // optimistic row update, error surfacing, and the group fan-out
    // through a real GroupRepository over the fake gateway.

    @Test
    fun `rename trims and updates the profile row at once`() = vmTest {
        val fakeAuth = FlipGroupAuth()
        val fakeActions = FakeSettingsAccountActions()
        val viewModel = buildViewModel(fakeAuth = fakeAuth, fakeActions = fakeActions)
        flip(fakeAuth)
        viewModel.user.first { !it.isGuest }

        val completed = async { viewModel.renameCompleted.first() }
        viewModel.updateDisplayName("  Tanvir  ")
        completed.await()

        assertEquals(1, fakeActions.updateDisplayNameCalls)
        assertEquals("Tanvir", fakeActions.lastUpdateName)
        assertEquals("Tanvir", viewModel.user.value.displayName)
        assertEquals("TA", viewModel.user.value.initials)
        assertFalse(viewModel.isRenaming.value)
    }

    @Test
    fun `rename ignores a blank name without calling the seam`() = vmTest {
        val fakeAuth = FlipGroupAuth()
        val fakeActions = FakeSettingsAccountActions()
        val viewModel = buildViewModel(fakeAuth = fakeAuth, fakeActions = fakeActions)
        flip(fakeAuth)
        viewModel.user.first { !it.isGuest }

        viewModel.updateDisplayName("   ")

        assertEquals(0, fakeActions.updateDisplayNameCalls)
        assertEquals("Tanvir Ahmed", viewModel.user.value.displayName)
        assertFalse(viewModel.isRenaming.value)
    }

    @Test
    fun `rename failure surfaces the error and keeps the old name`() = vmTest {
        val fakeAuth = FlipGroupAuth()
        val fakeActions = FakeSettingsAccountActions().apply {
            updateResult = UpdateDisplayNameResult.Failed("Please sign in again to change your name.")
        }
        val viewModel = buildViewModel(fakeAuth = fakeAuth, fakeActions = fakeActions)
        flip(fakeAuth)
        viewModel.user.first { !it.isGuest }

        val error = async { viewModel.settingsError.first() }
        viewModel.updateDisplayName("New Name")
        assertEquals("Please sign in again to change your name.", error.await())

        assertEquals(1, fakeActions.updateDisplayNameCalls)
        assertEquals("Tanvir Ahmed", viewModel.user.value.displayName)
        assertFalse(viewModel.isRenaming.value)
    }

    @Test
    fun `rename fans out to the group member docs and the local cache rows`() = vmTest {
        val fakeGateway = FakeGroupRemoteGateway()
        val groupRepo = GroupRepository(context, db, fakeGateway)
        fakeGateway.mirrors["user-1"] = mutableMapOf(
            "g-1" to mutableMapOf("groupId" to "g-1", "groupName" to "DIU 41")
        )
        fakeGateway.members["g-1"] = mutableMapOf(
            "user-1" to mutableMapOf("uid" to "user-1", "displayName" to "Tanvir Ahmed")
        )
        groupRepo.refreshGroupMembers("g-1")
        assertEquals(
            "Tanvir Ahmed",
            db.groupMemberDao().getMembersOnce("g-1").single().displayName
        )

        val fakeAuth = FlipGroupAuth()
        val viewModel = buildViewModel(fakeAuth = fakeAuth, groupRepo = groupRepo)
        flip(fakeAuth)
        viewModel.user.first { !it.isGuest }

        val completed = async { viewModel.renameCompleted.first() }
        viewModel.updateDisplayName("Tanvir")
        completed.await()

        assertEquals("Tanvir", fakeGateway.members["g-1"]?.get("user-1")?.get("displayName"))
        assertEquals(
            "Tanvir",
            db.groupMemberDao().getMembersOnce("g-1").single().displayName
        )
        groupRepo.stopGroupSync()
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

    // ---- Notifications / Appearance / Language (Sprint 6-2) ----

    @Test
    fun `notification toggles default ON and persist through the store`() = vmTest {
        val viewModel = buildViewModel()
        assertTrue(viewModel.morningBriefEnabled.first())
        assertTrue(viewModel.soundEnabled.first())

        viewModel.setMorningBriefEnabled(false)
        viewModel.setSoundEnabled(false)
        assertFalse(viewModel.morningBriefEnabled.first())
        assertFalse(viewModel.soundEnabled.first())
        val prefs = context.getSharedPreferences("test_s6_2_settings", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("morning_brief_enabled", true))
        assertFalse(prefs.getBoolean("sound_enabled", true))
    }

    // ---- App lock / export / restore (Sprint 6-6, step 4d) ----

    /**
     * The lock toggle persists through the store (the S6-2
     * notification-toggles test's shape): default OFF (v1 had no
     * lock), set ON/OFF round-trips the flow and the prefs. The
     * write is synchronous — no viewModelScope.launch inside — so
     * no runTest machinery is needed for the assertion itself.
     */
    @Test
    fun `app lock toggle defaults OFF and persists through the store`() = vmTest {
        val viewModel = buildViewModel()
        assertFalse(viewModel.appLockEnabled.first())

        viewModel.setAppLockEnabled(true)
        assertTrue(viewModel.appLockEnabled.first())
        val prefs = context.getSharedPreferences("test_s6_2_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("app_lock_enabled", false))

        viewModel.setAppLockEnabled(false)
        assertFalse(viewModel.appLockEnabled.first())
        assertFalse(prefs.getBoolean("app_lock_enabled", true))
    }

    /**
     * Export/restore fail closed when a repository is missing: the
     * guard returns before any ContentResolver is touched, so no
     * real file or picker is needed here. The happy paths are
     * device-gated (SAF picker + Uri read/write — no file picker
     * on the JVM); the merge logic below them is covered by
     * DataExportTest / DataRestoreTest.
     */
    @Test
    fun `export and restore fail closed when a repository is missing`() = vmTest {
        val noCalendar = buildViewModel(calendarRepo = null)
        val uri = Uri.parse("content://invalid/export.json")
        assertFalse(noCalendar.exportToUri(context, uri))
        assertEquals(
            DataRestorer.RestoreOutcome.ReadFailed,
            noCalendar.restoreFromUri(context, uri)
        )

        val noMemory = buildViewModel(
            memoryRepo = null,
            calendarRepo = CalendarRepository(context, db)
        )
        assertFalse(noMemory.exportToUri(context, uri))
        assertEquals(
            DataRestorer.RestoreOutcome.ReadFailed,
            noMemory.restoreFromUri(context, uri)
        )
    }

    @Test
    fun `theme choice writes theme_mode and the resolved darkTheme flips`() = vmTest {
        val viewModel = buildViewModel()
        assertEquals(SettingsPreferences.THEME_SYSTEM, viewModel.themeMode.first())
        // System + light system = light.
        assertFalse(SettingsPreferences.resolveDarkTheme(viewModel.themeMode.first(), false))

        viewModel.setThemeMode(SettingsPreferences.THEME_DARK)
        assertEquals(SettingsPreferences.THEME_DARK, viewModel.themeMode.first())
        assertTrue(SettingsPreferences.resolveDarkTheme(viewModel.themeMode.first(), false))

        viewModel.setThemeMode(SettingsPreferences.THEME_LIGHT)
        assertEquals(SettingsPreferences.THEME_LIGHT, viewModel.themeMode.first())
        // Light + dark system = still light.
        assertFalse(SettingsPreferences.resolveDarkTheme(viewModel.themeMode.first(), true))
    }

    @Test
    fun `language choice writes the preference`() = vmTest {
        val viewModel = buildViewModel()
        assertEquals(SettingsPreferences.LANGUAGE_ENGLISH, viewModel.language.first())
        viewModel.setLanguage(SettingsPreferences.LANGUAGE_BENGALI)
        assertEquals(SettingsPreferences.LANGUAGE_BENGALI, viewModel.language.first())
        val prefs = context.getSharedPreferences("test_s6_2_settings", Context.MODE_PRIVATE)
        assertEquals("bn", prefs.getString("language", null))
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
    var updateDisplayNameCalls = 0
    var lastUpdateName: String? = null
    var updateResult: UpdateDisplayNameResult = UpdateDisplayNameResult.Success

    override fun signOut(scope: CoroutineScope, onComplete: () -> Unit) {
        signOutCalls++
        onComplete()
    }

    override fun deleteAccount(onComplete: () -> Unit, onError: (String) -> Unit) {
        deleteCalls++
        if (deleteError != null) onError(deleteError!!) else onComplete()
    }

    override suspend fun updateDisplayName(displayName: String): UpdateDisplayNameResult {
        updateDisplayNameCalls++
        lastUpdateName = displayName
        return updateResult
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
