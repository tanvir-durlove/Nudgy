package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.GroupMember
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.GroupResult
import com.nextgentool.nudgy.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
