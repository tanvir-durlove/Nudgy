package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.GroupCompletion
import com.nextgentool.nudgy.data.repository.GroupMember
import com.nextgentool.nudgy.data.repository.GroupNotice
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.GroupResult
import com.nextgentool.nudgy.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    private val userId: String = taskRepository.currentUserId()

    /** The screen's `user` param (ChatViewModel precedent). */
    val user: NudgyUser = NudgyUser(
        uid = userId,
        displayName = "",
        email = "",
        isGuest = userId == AuthManager.GUEST_USER_ID
    )

    private val prefillJoinCodeFlow = MutableStateFlow<String?>(null)

    /**
     * One-shot group errors (create/edit/delete/toggle/notice): the
     * sheets close on submit, so a failed write has nowhere to show
     * inline — the NavHost toasts these. Replay 0, buffer 1: an error
     * emitted while nothing collects is dropped, never queued stale.
     */
    private val _groupTaskError = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val groupTaskError: SharedFlow<String> = _groupTaskError

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
                groupId, userId, user.displayName, text
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
        taskRepository.observeAllTasks(userId),
        calendarRepository.observeAllEvents(userId),
        prefillJoinCodeFlow
    ) { groups, tasks, events, prefill ->
        GroupsUiState(
            user = user,
            groups = groups,
            allTasks = tasks,
            allEvents = events,
            prefillJoinCode = prefill
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsUiState(user = user))

    init {
        // Attach the group listeners the first time the tab is used
        // (nothing else starts group sync in the rebuild yet — the
        // repository only starts it from create/join). Guests sync
        // nothing.
        if (!user.isGuest) {
            groupRepository.startGroupSync(userId)
        }
    }

    /** Creates a group (name validation + guest guard live in the repo). */
    suspend fun createGroup(name: String): GroupResult<GroupEntity> =
        groupRepository.createGroup(userId, user.displayName, name)

    /** Joins by code (wrong code → Err with the message the sheet shows). */
    suspend fun joinGroup(code: String): GroupResult<GroupEntity> =
        groupRepository.joinGroup(userId, user.displayName, code)

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
        if (user.isGuest) return
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
