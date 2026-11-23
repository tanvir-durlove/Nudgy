package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.model.TaskTemplate
import com.nextgentool.nudgy.domain.model.TemplateDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sprint 1-4 — templates screen state (Feature 13, spec §6).
 *
 * Own templates can be created / edited / deleted; built-ins are
 * read-only (create/update/delete of a built-in is refused here, and
 * the repository refuses it too). The built-ins are seeded once by
 * [TaskRepository.seedBuiltInTemplates] from the main ViewModel init.
 */
@HiltViewModel
class TemplatesViewModel @Inject constructor(
    private val repository: TaskRepository,
    // Sprint 5 fix round 4 — the live-user seam (device test on
    // ci-803: tab ViewModels froze the uid captured at construction).
    // Nullable with a default on purpose (TodayViewModel precedent):
    // Hilt always injects the real seam in production (AuthModule
    // provides it); call sites that don't pass one keep the previous
    // frozen-uid behaviour.
    private val groupAuth: GroupAuth? = null
) : ViewModel() {

    /**
     * Sprint 5 fix round 4 — the LIVE user (GroupsViewModel pattern,
     * PR #98). The fallback is the TaskRepository-derived user (blank
     * display name); the auth flow overrides it the moment it emits a
     * signed-in user. The equality guard cannot skip a real Google
     * sign-in: the fallback has a blank email while a real sign-in
     * always carries a non-blank one.
     */
    private val fallbackUser: NudgyUser = run {
        val uid = repository.currentUserId()
        NudgyUser(
            uid = uid,
            displayName = "",
            email = "",
            isGuest = uid == AuthManager.GUEST_USER_ID
        )
    }
    private val userFlow = MutableStateFlow(fallbackUser)

    /** Live uid — read at call time so a sign-in flip is followed. */
    private val currentUserId: String get() = userFlow.value.uid

    private val _templates = MutableStateFlow<List<TaskTemplate>>(emptyList())
    val templates: StateFlow<List<TaskTemplate>> = _templates.asStateFlow()

    init {
        // Sprint 1-4 — seeds the built-ins (Feature 13). Idempotent.
        // Seeded for the fallback uid here, and again for the new uid
        // on a flip (Sprint 5 fix round 4), so the new user's
        // templates exist; the collect below re-subscribes via
        // flatMapLatest on the same flip.
        viewModelScope.launch { repository.seedBuiltInTemplates(fallbackUser.uid) }
        groupAuth?.let { auth ->
            viewModelScope.launch {
                auth.userState.collect { authUser ->
                    val newUser =
                        if (authUser == null || authUser.isGuest) fallbackUser else authUser
                    if (newUser == userFlow.value) return@collect
                    userFlow.value = newUser
                    repository.seedBuiltInTemplates(newUser.uid)
                }
            }
        }
        viewModelScope.launch {
            userFlow.flatMapLatest { user ->
                repository.observeTemplates(user.uid)
            }.collect { _templates.value = it }
        }
    }

    fun createTemplate(name: String, drafts: List<TemplateDraft>) {
        viewModelScope.launch {
            repository.createTemplate(currentUserId, name, drafts)
        }
    }

    fun updateTemplate(template: TaskTemplate) {
        viewModelScope.launch {
            if (template.isBuiltIn) return@launch
            repository.updateTemplate(currentUserId, template)
        }
    }

    fun deleteTemplate(template: TaskTemplate) {
        viewModelScope.launch {
            if (template.isBuiltIn) return@launch
            // Deleting a template never deletes the tasks it created.
            repository.deleteTemplate(currentUserId, template.id)
        }
    }

    fun createTasksFromTemplate(template: TaskTemplate, dueEpochMillis: Long) {
        viewModelScope.launch {
            repository.createTasksFromTemplate(currentUserId, template, dueEpochMillis)
        }
    }

    /** 9:00 local on the given date — the same default Plan My Day uses. */
    fun dueEpochMillisFor(date: java.time.LocalDate): Long =
        repository.planMyDayDefaultDueEpochMillis(date)
}
