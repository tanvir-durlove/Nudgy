package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.data.repository.HabitStreak
import com.nextgentool.nudgy.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Sprint 4-2 — the Habits section state (spec §5, Q10=(a)).
 *
 * The section lives inside Today; the ViewModel owns the list (with
 * streaks), the add field, and the inline rename / delete-confirm
 * state. The streak numbers come from
 * [FocusHabitRepository.getHabitsWithStreaks] — this class never
 * computes a streak itself, so the "at risk, not broken" rule
 * (HabitStreakUseCase) cannot drift between the list and the math.
 *
 * Every mutation refreshes the list afterwards: the habits Flow only
 * re-emits on habit-table changes, so check-ins (a different table)
 * never refresh the list on their own.
 */
data class HabitUiState(
    val habits: List<HabitStreak> = emptyList(),
    val isLoading: Boolean = true,
    val newTitle: String = "",
    val editingHabitId: String? = null,
    val editTitle: String = "",
    val confirmingDeleteHabitId: String? = null
)

/**
 * Sprint 4-2 — Habit tracker UI wiring (spec §5, Q5/Q6 all (a)).
 *
 * - Habits are a separate list from tasks; a habit is title-only
 *   (no daily target, no recurring-task machinery).
 * - Check-in is once per local day: the repository's primary key
 *   makes a second check-in for the same day a no-op (returns false),
 *   so [checkIn] is idempotent by construction. [undoCheckIn] clears
 *   the same day's check-in.
 * - Delete removes the habit AND its check-ins (CASCADE, spec §5).
 * - Habits are personal-only, never group items (S3 rule) — there is
 *   no groupId anywhere in this flow.
 * - [now] is a parameter on the clock-sensitive calls (defaulting to
 *   the real instant) so tests can pin a local day instead of
 *   depending on the real today. All day math stays device-local
 *   (HabitStreakUseCase) — no UTC anywhere here.
 */
@HiltViewModel
class HabitViewModel @Inject constructor(
    private val focusRepository: FocusHabitRepository,
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val userId: String = taskRepository.currentUserId()

    private val _uiState = MutableStateFlow(HabitUiState())
    val uiState: StateFlow<HabitUiState> = _uiState.asStateFlow()

    /** Serialises refreshes: the habits-Flow collection and the
     * mutation follow-ups must never interleave a stale read over a
     * fresh one. */
    private val refreshMutex = Mutex()

    init {
        viewModelScope.launch {
            focusRepository.getHabitsFlow(userId).collect {
                refresh()
            }
        }
    }

    /** Re-reads every habit with its streak state for [now]. */
    suspend fun refresh(now: Long = System.currentTimeMillis()) {
        refreshMutex.withLock {
            val streaks = focusRepository.getHabitsWithStreaks(userId, now)
            _uiState.update { it.copy(habits = streaks, isLoading = false) }
        }
    }

    // ---- Create ----

    fun setNewTitle(title: String) {
        _uiState.update { it.copy(newTitle = title) }
    }

    /** Blank titles are a no-op (same guard as the task quick-add). */
    fun createHabit(now: Long = System.currentTimeMillis()) {
        val title = _uiState.value.newTitle.trim()
        if (title.isBlank()) return
        viewModelScope.launch {
            focusRepository.createHabit(userId, title, now)
            _uiState.update { it.copy(newTitle = "") }
            refresh(now)
        }
    }

    // ---- Check-in / undo ----

    /**
     * Checks in for the local day of [now]. A second check-in for the
     * same day is a no-op (the repository returns false) — never a
     * second row (spec §5).
     */
    fun checkIn(habitId: String, now: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            focusRepository.checkIn(habitId, now)
            refresh(now)
        }
    }

    /** Undoes the check-in for the local day of [now] (spec §5). */
    fun undoCheckIn(habitId: String, now: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            focusRepository.undoCheckIn(habitId, now)
            refresh(now)
        }
    }

    // ---- Rename ----

    fun startRename(habit: HabitStreak) {
        _uiState.update {
            it.copy(editingHabitId = habit.habit.id, editTitle = habit.habit.title)
        }
    }

    fun setEditTitle(title: String) {
        _uiState.update { it.copy(editTitle = title) }
    }

    /** Blank renames are a no-op — the edit stays open. */
    fun confirmRename(now: Long = System.currentTimeMillis()) {
        val state = _uiState.value
        val habitId = state.editingHabitId ?: return
        val title = state.editTitle.trim()
        if (title.isBlank()) return
        viewModelScope.launch {
            focusRepository.renameHabit(habitId, title)
            _uiState.update { it.copy(editingHabitId = null, editTitle = "") }
            refresh(now)
        }
    }

    fun cancelRename() {
        _uiState.update { it.copy(editingHabitId = null, editTitle = "") }
    }

    // ---- Delete ----

    fun requestDelete(habitId: String) {
        _uiState.update { it.copy(confirmingDeleteHabitId = habitId) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(confirmingDeleteHabitId = null) }
    }

    /** The habit and its check-ins go together (CASCADE, spec §5). */
    fun confirmDelete(now: Long = System.currentTimeMillis()) {
        val habitId = _uiState.value.confirmingDeleteHabitId ?: return
        viewModelScope.launch {
            focusRepository.deleteHabit(habitId)
            _uiState.update { it.copy(confirmingDeleteHabitId = null) }
            refresh(now)
        }
    }
}
