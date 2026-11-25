package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Sprint 6-4 (step 2b) — global search ViewModel (approved Sprint 6
 * spec, Q5 + §7: a separate search screen, grouped results, tap
 * navigates).
 *
 * The query is a plain StateFlow set ([setQuery] launches nothing, so
 * tests may call it directly — the S6-3 rule: never call a
 * viewModelScope.launch method from a plain-JUnit/Robolectric test).
 * [taskResults] follows the query + the live user (TasksViewModel
 * pattern): blank query = no search, empty list, and the screen shows
 * "Type to search".
 *
 * Scope of this step: tasks only (title / description / tag name, one
 * grouped result — "notes" means task notes/descriptions, spec Q5).
 * Schedules, Notes-as-own-section and Groups land in step 2c.
 *
 * Live user (Sprint 5 fix round 4 — TasksViewModel pattern): the
 * fallback is the TaskRepository-derived user; the auth flow overrides
 * it the moment it emits a signed-in user. groupAuth is nullable with
 * a default on purpose (Hilt always injects the real seam in
 * production; call sites that don't pass one keep the previous
 * repository-derived behaviour).
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val groupAuth: GroupAuth? = null
) : ViewModel() {

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

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Plain StateFlow set — no coroutine, safe to call from tests. */
    fun setQuery(value: String) {
        _query.value = value
    }

    /** Tasks matching the query; empty while the query is blank. */
    val taskResults: StateFlow<List<TaskEntity>> =
        combine(_query, userFlow) { query, user -> query to user.uid }
            .flatMapLatest { (query, uid) ->
                if (query.isBlank()) flowOf(emptyList())
                else repository.searchTasks(uid, query)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        groupAuth?.let { auth ->
            viewModelScope.launch {
                auth.userState.collect { authUser ->
                    val newUser =
                        if (authUser == null || authUser.isGuest) fallbackUser else authUser
                    if (newUser == userFlow.value) return@collect
                    userFlow.value = newUser
                }
            }
        }
    }
}
