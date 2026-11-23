package com.nextgentool.nudgy.ui.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.GroupActivityEntry
import com.nextgentool.nudgy.data.repository.GroupCompletion
import com.nextgentool.nudgy.data.repository.GroupMember
import com.nextgentool.nudgy.data.repository.GroupNotice
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.GroupResult
import com.nextgentool.nudgy.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupsUiState(
    val user: NudgyUser,
    val groups: List<GroupEntity> = emptyList(),
    val allTasks: List<TaskEntity> = emptyList(),
    val allEvents: List<CalendarEventEntity> = emptyList(),
    /** One-shot App Links join-code prefill (null = none pending). */
    val prefillJoinCode: String? = null
)

/**
 * Sprint 5-1 — Groups tab state (spec §4, Q1–Q10 all (a)).
 *
 * The screen is the v1 [GroupsScreen] re-wired onto the S5-0 seam:
 * every Firestore access goes through [GroupRepository] (which itself
 * only talks to Firestore via [GroupRemoteGateway]) — no direct
 * Firestore calls from the ViewModel or the UI.
 *
 * Display name: "" until the Sprint 6 auth flow (the ChatViewModel
 * precedent — no name is invented here). A member doc written with a
 * blank name reads back as "Member" in the member list below, so the
 * UI never shows a blank row.
 *
 * Guests cannot create or join (the repository guards both, same as
 * the notice guard in [GroupRepository.createGroupNotice]); the screen
 * shows the sign-in prompt instead of the controls.
 *
 * Deep-link join: [setPrefillJoinCode] stores a one-shot code from the
 * NavHost deep link; the screen opens the join sheet prefilled and
 * the user still taps Join — never auto-joined. The code is dropped
 * for guests (the signed-out prefill gap already noted in
 * MainActivity).
 *
 * Sprint 5-2 — group tasks (spec §5, Q3=(b) admin-only): the creator
 * creates/edits/deletes group tasks and schedules; every member
 * toggles their own completion (shared count via [observeCompletions],
 * offline-capable — Q7=(a)). Failures surface on [groupTaskError]
 * (the sheets close on submit, so there is no result to show inline).
 */
@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val taskRepository: TaskRepository,
    private val calendarRepository: CalendarRepository,
    private val groupAuth: GroupAuth
) : ViewModel() {

    /**
     * Sprint 5 fix round — the LIVE user (device test on ci-792: the
     * guest prompt never left because the user was captured once at
     * construction). The fallback is the TaskRepository-derived user
     * (ChatViewModel precedent, prefs active_user_id, blank display
     * name); the auth flow overrides it the moment it emits a
     * signed-in user, so a sign-in flips the tab with no restart.
     * A guest auth emission keeps the fallback (the S5-1 blank-name
     * decision — a guest member doc reads back as "Member").
     */
    private val fallbackUser: NudgyUser = run {
        val uid = taskRepository.currentUserId()
        NudgyUser(
            uid = uid,
            displayName = "",
            email = "",
            isGuest = uid == AuthManager.GUEST_USER_ID
        )
    }
    private val userFlow = MutableStateFlow(fallbackUser)

    /** Live user for the screen (guest until the auth flow says otherwise). */
    val user: StateFlow<NudgyUser> = userFlow.asStateFlow()

    private val currentUser: NudgyUser get() = userFlow.value
    private val userId: String get() = currentUser.uid

    private val prefillJoinCodeFlow = MutableStateFlow<String?>(null)

    /**
     * One-shot group errors (create/edit/delete/toggle/notice): the
     * sheets close on submit, so a failed write has nowhere to show
     * inline — the NavHost toasts these. Replay 0, buffer 1: an error
     * emitted while nothing collects is dropped, never queued stale.
     */
    private val _groupTaskError = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val groupTaskError: SharedFlow<String> = _groupTaskError

    /** The activity history of one group, newest first (live — the listener feeds it). */
    fun observeActivity(groupId: String): Flow<List<GroupActivityEntry>> =
        groupRepository.observeActivity(groupId)

    /** One-shot activity refresh for the detail (warms the flow before the listener fires). */
    suspend fun refreshActivity(groupId: String): List<GroupActivityEntry> =
        groupRepository.refreshActivity(groupId)

    /**
     * Sprint 5-5 — creator-only assign (feature 36, Q3=(b)). The repo
     * enforces the creator check and the member check; a member
     * calling this gets the Err on [groupTaskError] (the UI never
     * shows them the button). A null assignee unassigns.
     */
    fun assignGroupTask(groupId: String, itemId: String, assigneeUid: String?) {
        viewModelScope.launch {
            val result = groupRepository.assignGroupTask(groupId, userId, itemId, assigneeUid)
            if (result is GroupResult.Err) _groupTaskError.emit(result.message)
        }
    }

    /** The notices of one group, newest first (live — the listener feeds it). */
    fun observeNotices(groupId: String): Flow<List<GroupNotice>> =
        groupRepository.observeNotices(groupId)

    /** One-shot notices refresh for the detail (warms the flow before the listener fires). */
    suspend fun refreshNotices(groupId: String): List<GroupNotice> =
        groupRepository.refreshNotices(groupId)

    /**
     * Sprint 5-3 — creator-only text-only notice (feature 32, Q2=(a)).
     * The repo enforces the creator check and the 2000-char limit; a
     * member calling this gets the Err on [groupTaskError] (the UI
     * never shows them the button).
     */
    fun sendNotice(groupId: String, text: String) {
        viewModelScope.launch {
            val result = groupRepository.createGroupNotice(
                groupId, userId, currentUser.displayName, text
            )
            if (result is GroupResult.Err) _groupTaskError.emit(result.message)
        }
    }

    /**
     * Sprint 5-4 — creator delete cascade (feature 33, spec §7). The
     * repo enforces the creator check and purges locally; a member
     * calling this gets the Err on [groupTaskError] (the UI never shows
     * them the button).
     */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            val result = groupRepository.deleteGroup(userId, groupId)
            if (result is GroupResult.Err) _groupTaskError.emit(result.message)
        }
    }

    /**
     * Sprint 5-4 — member leave (feature 33, spec §7). The repo
     * sweeps the leaver out of the shared counts (Q6=(a)) and purges
     * locally; rejoin with the same code stays possible. The creator
     * calling this gets the Err on [groupTaskError] — they delete
     * instead of leaving.
     */
    fun leaveGroup(groupId: String) {
        viewModelScope.launch {
            val result = groupRepository.leaveGroup(userId, groupId)
            if (result is GroupResult.Err) _groupTaskError.emit(result.message)
        }
    }

    /** The shared completion map (raw — the screen filters it to current members). */
    fun observeCompletions(): Flow<Map<String, GroupCompletion>> =
        groupRepository.observeCompletions()

    /**
     * Sprint 5-2 — creator-only group task create (Q3=(b)). The repo
     * enforces the creator check; a member calling this gets the Err
     * on [groupTaskError] (the UI never shows them the button).
     */
    fun createGroupTask(
        groupId: String,
        title: String,
        description: String?,
        dueEpochMillis: Long,
        priority: Priority,
        category: TaskCategory,
        reminderSet: Boolean
    ) {
        viewModelScope.launch {
            val result = groupRepository.createGroupTask(
                groupId, userId, title, description,
                dueEpochMillis, priority, category, reminderSet
            )
            if (result is GroupResult.Err) _groupTaskError.emit(result.message)
        }
    }

    /** Sprint 5-2 — creator-only group schedule create (Q3=(b); no count — Q4=(a)). */
    fun createGroupSchedule(
        groupId: String,
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        locationOrLink: String?,
        eventType: String
    ) {
        viewModelScope.launch {
            val result = groupRepository.createGroupSchedule(
                groupId, userId, title, startEpochMillis, endEpochMillis,
                locationOrLink, eventType
            )
            if (result is GroupResult.Err) _groupTaskError.emit(result.message)
        }
    }

    /** Sprint 5-2 — creator-only group task edit (Q3=(b)). */
    fun updateGroupTask(groupId: String, task: TaskEntity) {
        viewModelScope.launch {
            val result = groupRepository.updateGroupTask(groupId, userId, task)
            if (result is GroupResult.Err) _groupTaskError.emit(result.message)
        }
    }

    /** Sprint 5-2 — creator-only group item delete (Q3=(b)). */
    fun deleteGroupItem(groupId: String, itemId: String, isTask: Boolean) {
        viewModelScope.launch {
            val result = groupRepository.deleteGroupItem(groupId, userId, itemId, isTask)
            if (result is GroupResult.Err) _groupTaskError.emit(result.message)
        }
    }

    /**
     * Sprint 5-2 — any member toggles their own completion on a group
     * task (feature 31). The repo writes the shared counter AND the
     * local flag in one call; on failure the local flag never moved,
     * so the checkbox cannot disagree with the shared count.
     */
    fun toggleGroupTask(task: TaskEntity) {
        val groupId = task.groupId ?: return
        viewModelScope.launch {
            val result = groupRepository.setGroupTaskCompleted(
                groupId, task.id, userId, !task.isCompleted
            )
            if (result is GroupResult.Err) _groupTaskError.emit(result.message)
        }
    }

    val uiState: StateFlow<GroupsUiState> = combine(
        groupRepository.observeGroups(),
        userFlow.flatMapLatest { user -> taskRepository.observeAllTasks(user.uid) },
        userFlow.flatMapLatest { user -> calendarRepository.observeAllEvents(user.uid) },
        prefillJoinCodeFlow,
        userFlow
    ) { groups, tasks, events, prefill, user ->
        GroupsUiState(
            user = user,
            groups = groups,
            allTasks = tasks,
            allEvents = events,
            prefillJoinCode = prefill
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsUiState(user = fallbackUser))

    init {
        // Sprint 5 fix round — follow the LIVE user. The auth flow
        // replays its current value at once, so a signed-in user
        // starts group sync here (the old once-only init guard); a
        // guest→signed-in flip after the Groups "Sign in" button
        // starts it then, with no restart. startGroupSync stops the
        // old listeners first, so a second call is safe.
        //
        // Sprint 5 fix round 3 — restore FIRST (device test on
        // ci-798: after reinstall the groups were gone because
        // restoreGroupsFromMirror was never called anywhere, and
        // startGroupSync only attaches listeners for LOCAL group
        // ids). Restore reads the membership mirror, so a fresh
        // install / new device gets its groups back before the
        // listeners attach below. It is idempotent — existing local
        // rows are never touched — so a second call is harmless.
        viewModelScope.launch {
            groupAuth.userState.collect { authUser ->
                val newUser = if (authUser == null || authUser.isGuest) fallbackUser else authUser
                if (newUser == userFlow.value) return@collect
                userFlow.value = newUser
                if (!newUser.isGuest) {
                    groupRepository.restoreGroupsFromMirror(newUser.uid)
                    groupRepository.startGroupSync(newUser.uid)
                }
            }
        }
    }

    /**
     * Sprint 5 fix round — the Groups "Sign in" button (device test
     * on ci-792: the button did nothing because the NavHost never
     * passed onSignInClick and nothing reachable called
     * AuthManager.signInWithGoogle). Errors surface on
     * [groupTaskError] — the NavHost toasts them — never silently.
     */
    fun signIn(activity: Activity, scope: CoroutineScope) {
        groupAuth.signIn(
            activity, scope,
            onSuccess = {},
            onError = { message ->
                viewModelScope.launch { _groupTaskError.emit(message) }
            }
        )
    }

    /** Creates a group (name validation + guest guard live in the repo). */
    suspend fun createGroup(name: String): GroupResult<GroupEntity> =
        groupRepository.createGroup(userId, currentUser.displayName, name)

    /** Joins by code (wrong code → Err with the message the sheet shows). */
    suspend fun joinGroup(code: String): GroupResult<GroupEntity> =
        groupRepository.joinGroup(userId, currentUser.displayName, code)

    /** Member list with the Room cache (instant) — blank names read as "Member". */
    fun observeMembers(groupId: String): Flow<List<GroupMember>> =
        groupRepository.observeGroupMembers(groupId).map { members ->
            members.map { it.withDisplayName() }
        }

    /** Remote refresh of the member list (cache kept on failure, repo rule). */
    suspend fun refreshMembers(groupId: String): List<GroupMember> =
        groupRepository.refreshGroupMembers(groupId).map { it.withDisplayName() }

    /** Stores a one-shot deep-link join code (signed-in users only). */
    fun setPrefillJoinCode(rawCode: String) {
        if (currentUser.isGuest) return
        val code = rawCode.trim().uppercase().filter { it.isLetterOrDigit() }
        if (code.isNotEmpty()) prefillJoinCodeFlow.value = code
    }

    /** Clears the pending prefill (the screen calls this once it opens the sheet). */
    fun consumePrefillJoinCode() {
        prefillJoinCodeFlow.value = null
    }

    private fun GroupMember.withDisplayName(): GroupMember =
        if (displayName.isBlank()) copy(displayName = "Member") else this
}
