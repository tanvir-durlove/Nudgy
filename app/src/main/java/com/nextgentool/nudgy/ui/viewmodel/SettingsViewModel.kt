package com.nextgentool.nudgy.ui.viewmodel

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.NudgyMemoryEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.settings.ByokKeyStore
import com.nextgentool.nudgy.settings.CalendarConnection
import com.nextgentool.nudgy.settings.DataExporter
import com.nextgentool.nudgy.settings.DataRestorer
import com.nextgentool.nudgy.settings.SettingsAccountActions
import com.nextgentool.nudgy.settings.SettingsPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sprint 6-1 — the Settings screen state (spec §4–§5).
 *
 * The LIVE user (GroupsViewModel pattern, PR #98/#101): the
 * fallback is the TaskRepository-derived user (blank display name);
 * the auth flow overrides it the moment it emits a signed-in user,
 * so a sign-in flips the profile row with no restart. The fallback
 * is recomputed at collection time, not captured once — after a
 * sign-out the stored uid is gone, and a stale fallback would keep
 * reading the old user's memories.
 *
 * Sign-in goes through the [GroupAuth] seam (PR #98 pattern —
 * RealGroupAuth over the single AuthManager). Sign-out and account
 * deletion go through [SettingsAccountActions] (its own seam: the
 * GroupAuth contract is the Groups sign-in contract and stays
 * untouched). The delete runs in AuthManager's own scope, never a
 * composable's (v1 C7) — so a success here only reports back; the
 * MainActivity gate swaps the whole screen to SignInScreen once
 * the user state goes null.
 *
 * Memories follow the live user too: a flip re-reads the new uid's
 * memories (a guest's memories stay the guest's; a signed-in user's
 * stay theirs). The BYOK key and the calendar connection are read
 * through their seams; the central LLM gate
 * (llm_central_enabled, default false) is untouched.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val memoryRepository: MemoryRepository? = null,
    // Sprint 6-1 — the live-user seam (GroupsViewModel precedent):
    // Hilt always injects the real seam in production (AuthModule
    // provides it); call sites that don't pass one keep the previous
    // frozen-uid behaviour.
    private val groupAuth: GroupAuth? = null,
    private val accountActions: SettingsAccountActions? = null,
    private val byokKeyStore: ByokKeyStore? = null,
    private val calendarConnection: CalendarConnection? = null,
    // Sprint 6-2 — the Settings store (S6-0 @Singleton, Hilt
    // injects it in production): the notification toggles, the
    // theme mode and the language all read/write through it, so a
    // change recomposes every collector with no restart. Nullable
    // with a default on purpose (the S6-1 seams' pattern): call
    // sites that don't pass one keep the previous defaults.
    private val settingsPreferences: SettingsPreferences? = null,
    // Sprint 6-6 (step 4d) — the calendar repository for export /
    // restore (TaskModule provides it in production). Nullable with
    // a default on purpose (the S6-1 seams' pattern): call sites
    // that don't pass one get the missing-repository outcome
    // (export false / restore ReadFailed) instead of a crash.
    private val calendarRepository: CalendarRepository? = null
) : ViewModel() {

    /**
     * The TaskRepository-derived user (blank display name); the auth
     * flow overrides it the moment it emits a signed-in user. The
     * equality guard cannot skip a real Google sign-in: the fallback
     * has a blank email while a real sign-in always carries a
     * non-blank one.
     */
    private fun fallbackUser(): NudgyUser {
        val uid = taskRepository.currentUserId()
        return NudgyUser(
            uid = uid,
            displayName = "",
            email = "",
            isGuest = uid == AuthManager.GUEST_USER_ID
        )
    }

    private val userFlow = MutableStateFlow(fallbackUser())

    /** Live user for the screen (guest until the auth flow says otherwise). */
    val user: StateFlow<NudgyUser> = userFlow.asStateFlow()

    private val currentUser: NudgyUser get() = userFlow.value
    private val userId: String get() = currentUser.uid

    init {
        groupAuth?.let { auth ->
            viewModelScope.launch {
                auth.userState.collect { authUser ->
                    val newUser =
                        if (authUser == null || authUser.isGuest) fallbackUser() else authUser
                    if (newUser == userFlow.value) return@collect
                    userFlow.value = newUser
                }
            }
        }
    }

    /** The signed-in/guest user's memories, newest first (live — Room feeds it). */
    val memories: StateFlow<List<NudgyMemoryEntity>> = userFlow.flatMapLatest { user ->
        memoryRepository?.observeMemories(user.uid) ?: flowOf(emptyList())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val _byokKey = MutableStateFlow(byokKeyStore?.getKey() ?: "")
    /** The stored BYOK key ("" when unset — the dialog prefills from it, v1 behaviour). */
    val byokKey: StateFlow<String> = _byokKey.asStateFlow()

    /** True when a BYOK key is stored — the row subtitle reads it. */
    val byokKeySet: StateFlow<Boolean> = _byokKey
        .map { it.isNotBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _byokKey.value.isNotBlank()
        )

    /** The device-calendar connection (v1: connected = permission granted). */
    val calendarConnected: StateFlow<Boolean> =
        calendarConnection?.isConnected ?: MutableStateFlow(false)

    private val _isCalendarSyncing = MutableStateFlow(false)
    val isCalendarSyncing: StateFlow<Boolean> = _isCalendarSyncing.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    /** True while the account deletion runs — the dialog stays up (v1 C7). */
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    /**
     * One-shot Settings errors (sign-in, delete, calendar sync): the
     * rows have nowhere to show inline — the NavHost toasts these.
     * Replay 0, buffer 1: an error emitted while nothing collects
     * is dropped, never queued stale.
     */
    private val _settingsError = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val settingsError: SharedFlow<String> = _settingsError

    /** Emitted when the account deletion completes — the dialog closes on it. */
    private val _deleteCompleted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val deleteCompleted: SharedFlow<Unit> = _deleteCompleted

    // ---- Sign-in / sign-out (GroupsViewModel pattern) ----

    /** Starts the Google sign-in flow; errors surface on [settingsError], never silently. */
    fun signIn(activity: Activity, scope: CoroutineScope) {
        groupAuth?.signIn(
            activity, scope,
            onSuccess = {},
            onError = { message ->
                viewModelScope.launch { _settingsError.emit(message) }
            }
        )
    }

    /** Signs out (v1: clears auth prefs + the BYOK key, sets KEY_EXPLICITLY_SIGNED_OUT). */
    fun signOut() {
        accountActions?.signOut(viewModelScope)
    }

    // ---- Account deletion (v1 full purge, Q12=(a)) ----

    /**
     * Deletes the account: confirm dialog first (UI), then the full
     * v1 purge — Firestore tasks/memories/events + group memberships
     * + user doc + local Room + Firebase user.delete() LAST, llmUsage
     * deliberately KEPT. The dialog closes on [deleteCompleted];
     * a failure surfaces on [settingsError] and the dialog stays
     * open so the user can retry.
     */
    fun deleteAccount() {
        val actions = accountActions ?: return
        if (_isDeleting.value) return
        _isDeleting.value = true
        actions.deleteAccount(
            onComplete = {
                _isDeleting.value = false
                viewModelScope.launch { _deleteCompleted.emit(Unit) }
            },
            onError = { message ->
                _isDeleting.value = false
                viewModelScope.launch { _settingsError.emit(message) }
            }
        )
    }

    // ---- Memories (v1 "Nudgy Memories & Knowledge", Q11=(a)) ----

    /** Adds a memory for the live user; blank input is ignored. */
    fun addMemory(content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { memoryRepository?.addMemory(userId, trimmed) }
    }

    fun deleteMemory(memoryId: String) {
        viewModelScope.launch { memoryRepository?.deleteMemory(userId, memoryId) }
    }

    // ---- Gemini key (BYOK, v1 dialog) ----

    /** Saves the key (blank removes it — "Using Nudgy's built-in AI"). */
    fun saveByokKey(key: String) {
        val trimmed = key.trim()
        byokKeyStore?.setKey(trimmed.ifBlank { null })
        _byokKey.value = trimmed
    }

    /** Removes the key — "Using Nudgy's built-in AI". */
    fun resetByokKey() {
        byokKeyStore?.setKey(null)
        _byokKey.value = ""
    }

    // ---- Notifications / Appearance / Language (Sprint 6-2, spec §2 Q8/Q9/Q10) ----

    /**
     * The persisted notification toggles (v1's were `remember`
     * only — these survive a restart). Defaults (true/true) match
     * the store's defaults when no store is injected (older tests).
     */
    val morningBriefEnabled: StateFlow<Boolean> =
        settingsPreferences?.morningBriefEnabled ?: MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> =
        settingsPreferences?.soundEnabled ?: MutableStateFlow(true)

    /** The theme mode ("system" | "light" | "dark") — MainActivity collects it live. */
    val themeMode: StateFlow<String> =
        settingsPreferences?.themeMode ?: MutableStateFlow(SettingsPreferences.THEME_SYSTEM)

    /** The UI language ("en" | "bn"). */
    val language: StateFlow<String> =
        settingsPreferences?.language ?: MutableStateFlow(SettingsPreferences.LANGUAGE_ENGLISH)

    // ---- App lock / export / restore (Sprint 6-6, spec §2 Q6/Q7, §8) ----

    /**
     * The optional app lock (step 4a key, default OFF). The Settings
     * toggle (step 4d) is the only writer besides [setAppLockEnabled].
     */
    val appLockEnabled: StateFlow<Boolean> =
        settingsPreferences?.appLockEnabled ?: MutableStateFlow(false)

    /**
     * Writes the lock preference. A plain synchronous write (no
     * coroutine): the enable-time biometric/PIN prompt lives in the
     * Settings route (it needs the FragmentActivity), and only calls
     * this after the user proves ownership.
     */
    fun setAppLockEnabled(enabled: Boolean) {
        settingsPreferences?.setAppLockEnabled(enabled)
    }

    /**
     * Exports the live user's tasks + schedules + memories to a SAF
     * document Uri (step 4b's [DataExporter]). Plain suspend
     * function — no viewModelScope inside, so a test can call it
     * with runBlocking. False when a repository is missing (nothing
     * to collect) or the write fails — the caller toasts it, never a
     * crash. The SAF picker itself is device-gated (no file picker
     * on the JVM): the happy path is verified by CI compile + the
     * batch-end device test; the merge logic below is covered by
     * DataExportTest.
     */
    suspend fun exportToUri(context: Context, uri: Uri): Boolean {
        val calendar = calendarRepository ?: return false
        val memory = memoryRepository ?: return false
        val export = DataExporter.collectExport(userId, taskRepository, calendar, memory)
        return DataExporter.exportToUri(context, uri, export)
    }

    /**
     * Restores a SAF document Uri into the database for the live
     * user (step 4c's [DataRestorer]): id-merge, no duplicates, the
     * restored rows take the restoring user's id. Plain suspend
     * function, same testability note as [exportToUri]; the picker
     * and the Uri read are device-gated, the merge itself is
     * covered by DataRestoreTest.
     */
    suspend fun restoreFromUri(context: Context, uri: Uri): DataRestorer.RestoreOutcome {
        val calendar = calendarRepository ?: return DataRestorer.RestoreOutcome.ReadFailed
        val memory = memoryRepository ?: return DataRestorer.RestoreOutcome.ReadFailed
        return DataRestorer.restoreFromUri(context, uri, userId, taskRepository, calendar, memory)
    }

    /** Morning Brief ON/OFF — the worker no-ops while OFF (no re-schedule needed). */
    fun setMorningBriefEnabled(enabled: Boolean) {
        settingsPreferences?.setMorningBriefEnabled(enabled)
    }

    /** Sound & Haptics ON/OFF — the reminder channel follows it on the next fire. */
    fun setSoundEnabled(enabled: Boolean) {
        settingsPreferences?.setSoundEnabled(enabled)
    }

    /** Writes theme_mode — the app recomposes with no restart (MainActivity flow). */
    fun setThemeMode(mode: String) {
        settingsPreferences?.setThemeMode(mode)
    }

    /**
     * Writes the language preference. The NavHost restarts the
     * Activity after this (AppLocale.applyAndRecreate), so the whole
     * app re-renders in the chosen language.
     */
    fun setLanguage(code: String) {
        settingsPreferences?.setLanguage(code)
    }

    // ---- Calendar connection (v1: device permission + Sync Now) ----

    /** Re-reads the permission state (after the user grants it). */
    fun refreshCalendar() {
        calendarConnection?.refresh()
    }

    /** The permission was granted: refresh, then sync (CalendarViewModel pattern). */
    fun onCalendarPermissionGranted() {
        refreshCalendar()
        syncCalendarNow()
    }

    /** Manual "Sync now" trigger. */
    fun syncCalendarNow() {
        viewModelScope.launch {
            val connection = calendarConnection ?: return@launch
            if (_isCalendarSyncing.value) return@launch
            _isCalendarSyncing.value = true
            try {
                connection.sync(userId)
                refreshCalendar()
            } catch (e: Exception) {
                _settingsError.emit(e.message ?: "Calendar sync failed")
            } finally {
                _isCalendarSyncing.value = false
            }
        }
    }
}
