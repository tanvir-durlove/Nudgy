package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.FocusSessionKind
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.recentDayLabel
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.focus.PomodoroAlarm
import com.nextgentool.nudgy.focus.PomodoroCycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The timer's phase on the Focus screen (spec §4). */
enum class PomodoroPhase {
    IDLE,
    RUNNING,
    PAUSED
}

/**
 * Sprint 4-1 — the Focus screen state (spec §4).
 *
 * [remainingMillis] counts down from [totalMillis] while RUNNING; it
 * freezes while PAUSED. [justFinishedKind] is set when a session runs
 * to its end so the screen can offer the next step ("take a break"
 * after focus, "ready to focus" after a break) — it clears on the
 * next start. [nextBreakMinutes] is the break that follows the next
 * completed focus session (15 after every 4th, else 5).
 */
data class FocusUiState(
    val phase: PomodoroPhase = PomodoroPhase.IDLE,
    val kind: FocusSessionKind = FocusSessionKind.FOCUS,
    val remainingMillis: Long = PomodoroCycle.FOCUS_MINUTES * 60_000L,
    val totalMillis: Long = PomodoroCycle.FOCUS_MINUTES * 60_000L,
    val sessionId: String? = null,
    val selectedTaskId: String? = null,
    val selectedTaskTitle: String? = null,
    val todaysFocusMinutes: Int = 0,
    val completedFocusToday: Int = 0,
    // Sprint 7 (Focus data) — the prototype's other 3 statboxes +
    // the Recent sessions card. [weekCompletionPercent] is null when
    // nothing ended this week yet (the screen shows "–").
    val weekFocusMinutes: Int = 0,
    val weekCompletionPercent: Int? = null,
    val focusStreakDays: Int = 0,
    val recentSessions: List<RecentFocusSession> = emptyList(),
    val nextBreakMinutes: Int = PomodoroCycle.SHORT_BREAK_MINUTES,
    val justFinishedKind: FocusSessionKind? = null,
    val availableTasks: List<TaskEntity> = emptyList()
)

/**
 * Sprint 7 (Focus data) — one row of the Recent sessions card:
 * the day label (Today / Yesterday / date), the task title
 * ("Standalone focus" when the session had no task), the minutes
 * the session actually ran (Done → the planned minutes; Stopped
 * early → the elapsed minutes, rounded down), and whether the
 * timer ran to its end.
 */
data class RecentFocusSession(
    val dayLabel: String,
    val title: String,
    val durationMinutes: Int,
    val completed: Boolean
)

/**
 * Sprint 4-1 — the Pomodoro timer (spec §4, Q1–Q4 all (a)).
 *
 * The ViewModel owns the countdown; Room owns the truth. Starting a
 * session writes it (planned end persisted, so the timer survives
 * process death) and arms the exact-alarm end signal; the ticker only
 * updates the displayed countdown and finishes the session when the
 * wall-clock end passes.
 *
 * - Pause freezes the displayed countdown AND the persisted end: on
 *   resume the planned end moves to now + the frozen remaining time,
 *   so the pause never eats into the session. The alarm is cancelled
 *   on pause and re-armed on resume.
 * - Cancel ends the session NOT completed (minutes never count) and
 *   cancels the alarm — no end notification can fire for it.
 * - Finish (ticker or end alarm) completes the session, plays the
 *   short reply sound, and refreshes today's minutes. The end alarm
 *   still fires at the planned end and posts the notification — sound
 *   + notification is exactly the Q4=(a) behaviour. The finish is
 *   idempotent: whichever of ticker / receiver gets there first wins,
 *   the other is a no-op (the receiver already completed it).
 * - Process death: [restoreRunningSession] runs in init — a running
 *   session whose planned end has passed is completed; one still
 *   inside its window resumes counting down from the persisted end
 *   and its alarm is re-armed (in case the system dropped it).
 * - One session at a time: starting a new one ends the running one
 *   first (the repository does this) and cancels its alarm.
 *
 * Break minutes never count toward focus stats: the repository's
 * minute queries only sum completed FOCUS sessions — this class
 * never filters by kind itself.
 */
@HiltViewModel
class FocusViewModel @Inject constructor(
    private val focusRepository: FocusHabitRepository,
    private val taskRepository: TaskRepository,
    private val pomodoroAlarm: PomodoroAlarm,
    private val soundPlayer: ReplySoundPlayer,
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
        val uid = taskRepository.currentUserId()
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

    private val _uiState = MutableStateFlow(FocusUiState())
    val uiState: StateFlow<FocusUiState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null

    init {
        viewModelScope.launch {
            // S5-5 fix (FocusViewModelTest "planned end passed" flake —
            // a real init race, diagnosed 2026-11-22, AGENTS.md):
            // restoreRunningSession() completes a past-end session and
            // refreshes stats; a concurrent refreshStats() could finish
            // LAST after reading the DB before the completion and
            // overwrite the correct stats with stale ones (0 minutes /
            // 0 completed). Sequenced: restore first, then refresh.
            restoreRunningSession()
            refreshStats()
        }
        viewModelScope.launch {
            userFlow.flatMapLatest { user ->
                taskRepository.observeAllTasks(user.uid)
            }.collect { tasks ->
                _uiState.update { state ->
                    state.copy(availableTasks = tasks.filter { !it.isCompleted })
                }
            }
        }
        // Sprint 5 fix round 4 — follow the LIVE user (same bug as
        // TodayViewModel on ci-803). On a flip, the new user's
        // running session is restored and stats refreshed, sequenced
        // exactly like init above (S5-5 race fix: restore first,
        // then refresh).
        groupAuth?.let { auth ->
            viewModelScope.launch {
                auth.userState.collect { authUser ->
                    val newUser =
                        if (authUser == null || authUser.isGuest) fallbackUser else authUser
                    if (newUser == userFlow.value) return@collect
                    userFlow.value = newUser
                    restoreRunningSession()
                    refreshStats()
                }
            }
        }
    }

    // ---- Start ----

    /**
     * Starts a 25-minute focus session, on [taskId] when one is
     * picked (Q3=(a)) or standalone (null). A running session is
     * ended first (not completed) and its alarm cancelled.
     */
    fun startFocus(taskId: String? = _uiState.value.selectedTaskId) {
        startSession(
            taskId = taskId,
            minutes = PomodoroCycle.FOCUS_MINUTES,
            kind = FocusSessionKind.FOCUS
        )
    }

    /** Starts the break that follows a completed focus session (5 or 15 minutes). */
    fun startBreak() {
        startSession(
            taskId = null,
            minutes = _uiState.value.nextBreakMinutes,
            kind = FocusSessionKind.BREAK
        )
    }

    private fun startSession(taskId: String?, minutes: Int, kind: FocusSessionKind) {
        viewModelScope.launch {
            _uiState.value.sessionId?.let { pomodoroAlarm.cancel(it) }
            tickerJob?.cancel()
            val now = System.currentTimeMillis()
            val session = focusRepository.startSession(currentUserId, taskId, minutes, kind, now)
            val title = taskTitle(taskId)
            pomodoroAlarm.scheduleEnd(session.id, kind, title, session.plannedEndEpochMillis)
            _uiState.update {
                it.copy(
                    phase = PomodoroPhase.RUNNING,
                    kind = kind,
                    remainingMillis = minutes * 60_000L,
                    totalMillis = minutes * 60_000L,
                    sessionId = session.id,
                    selectedTaskId = taskId,
                    selectedTaskTitle = title,
                    justFinishedKind = null
                )
            }
            startTicker(session.plannedEndEpochMillis)
        }
    }

    // ---- Pause / resume / cancel ----

    /** Freezes the countdown and cancels the end alarm (it is re-armed on resume). */
    fun pause() {
        if (_uiState.value.phase != PomodoroPhase.RUNNING) return
        tickerJob?.cancel()
        _uiState.value.sessionId?.let { pomodoroAlarm.cancel(it) }
        _uiState.update { it.copy(phase = PomodoroPhase.PAUSED) }
    }

    /**
     * Resumes from the frozen remaining time: the persisted planned
     * end moves to now + remaining, and the end alarm is re-armed
     * for the new end.
     */
    fun resume() {
        val state = _uiState.value
        if (state.phase != PomodoroPhase.PAUSED) return
        val sessionId = state.sessionId ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val newPlannedEnd = now + state.remainingMillis
            focusRepository.updateSessionPlannedEnd(sessionId, newPlannedEnd)
            pomodoroAlarm.scheduleEnd(
                sessionId, state.kind, state.selectedTaskTitle, newPlannedEnd
            )
            _uiState.update { it.copy(phase = PomodoroPhase.RUNNING) }
            startTicker(newPlannedEnd)
        }
    }

    /**
     * Ends the session NOT completed — its minutes never count — and
     * cancels the end alarm so no "session complete" notification can
     * fire for it. A session the ticker/receiver already completed is
     * left alone (its alarm still delivers the Q4 notification).
     */
    fun cancelCurrent() {
        val state = _uiState.value
        if (state.phase == PomodoroPhase.IDLE) return
        val sessionId = state.sessionId ?: return
        viewModelScope.launch {
            tickerJob?.cancel()
            val session = focusRepository.getSessionById(sessionId)
            if (session != null && !session.completed) {
                pomodoroAlarm.cancel(sessionId)
                focusRepository.cancelSession(sessionId, System.currentTimeMillis())
            }
            _uiState.update {
                it.copy(
                    phase = PomodoroPhase.IDLE,
                    sessionId = null,
                    remainingMillis = PomodoroCycle.FOCUS_MINUTES * 60_000L,
                    totalMillis = PomodoroCycle.FOCUS_MINUTES * 60_000L,
                    justFinishedKind = null
                )
            }
        }
    }

    // ---- Finish + process-death resume ----

    /**
     * The timer ran to its end (called by the ticker, and by tests).
     * Idempotent: when the end alarm's receiver already completed
     * the session, this only refreshes the stats and the state — no
     * second completion, no second sound.
     */
    fun finishCurrentSession(now: Long = System.currentTimeMillis()) {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        viewModelScope.launch {
            tickerJob?.cancel()
            // A cancel that landed first clears the phase — never
            // complete a session the user just cancelled (millisecond
            // race at the planned end).
            val current = _uiState.value
            if (current.phase != PomodoroPhase.RUNNING || current.sessionId != sessionId) return@launch
            val session = focusRepository.getSessionById(sessionId)
            if (session != null && !session.completed) {
                focusRepository.completeSession(sessionId, now)
                soundPlayer.playReplySound()
            }
            refreshStats(now)
            _uiState.update {
                it.copy(
                    phase = PomodoroPhase.IDLE,
                    sessionId = null,
                    remainingMillis = PomodoroCycle.FOCUS_MINUTES * 60_000L,
                    totalMillis = PomodoroCycle.FOCUS_MINUTES * 60_000L,
                    justFinishedKind = state.kind
                )
            }
        }
    }

    /**
     * Process-death resume (runs in init, spec §3): a running session
     * whose planned end has passed is completed silently (the end
     * alarm's notification already fired, or fires on its own); one
     * still inside its window resumes counting down from the
     * persisted end, and its alarm is re-armed.
     */
    suspend fun restoreRunningSession(now: Long = System.currentTimeMillis()) {
        val running = focusRepository.getRunningSession(currentUserId) ?: return
        if (PomodoroCycle.isFinished(running.plannedEndEpochMillis, now)) {
            focusRepository.completeSession(running.id, now)
            refreshStats(now)
            _uiState.update { it.copy(justFinishedKind = running.kind) }
            return
        }
        val title = taskTitle(running.taskId)
        pomodoroAlarm.scheduleEnd(
            running.id, running.kind, title, running.plannedEndEpochMillis
        )
        _uiState.update {
            it.copy(
                phase = PomodoroPhase.RUNNING,
                kind = running.kind,
                remainingMillis = PomodoroCycle.remainingMillis(running.plannedEndEpochMillis, now),
                totalMillis = running.durationMinutes * 60_000L,
                sessionId = running.id,
                selectedTaskId = running.taskId,
                selectedTaskTitle = title,
                justFinishedKind = null
            )
        }
        startTicker(running.plannedEndEpochMillis)
    }

    // ---- Task picker + stats ----

    /** Picks the task the next focus session counts toward (Q3=(a)); null = standalone. */
    fun selectTask(taskId: String?) {
        val title = _uiState.value.availableTasks.firstOrNull { it.id == taskId }?.title
        _uiState.update { it.copy(selectedTaskId = taskId, selectedTaskTitle = title) }
    }

    /** Dismisses the "session complete" prompt (the next start clears it anyway). */
    fun clearJustFinished() {
        _uiState.update { it.copy(justFinishedKind = null) }
    }

    private suspend fun refreshStats(now: Long = System.currentTimeMillis()) {
        val minutes = focusRepository.getFocusMinutesForDay(currentUserId, now)
        val count = focusRepository.getCompletedFocusCountSince(currentUserId, startOfDayMillis(now))
        // Sprint 7 (Focus data) — the week stats load HERE, inside
        // this same function (the single coroutine sequenced after
        // restoreRunningSession in init): never a second concurrent
        // refresh — the S5-5 init race is the reason restore-then-
        // refresh is one coroutine. finishCurrentSession and
        // restoreRunningSession call this same function, so the new
        // fields refresh at the same points, for free.
        val weekMinutes = focusRepository.getFocusMinutesForWeek(currentUserId, now)
        val weekCompletion = focusRepository.getFocusWeekCompletionPercent(currentUserId, now)
        val streak = focusRepository.getFocusStreakDays(currentUserId, now)
        val recent = focusRepository.getRecentEndedFocusSessions(currentUserId, 3).map { session ->
            RecentFocusSession(
                dayLabel = recentDayLabel(session.startedAtEpochMillis, now),
                title = session.taskId?.let { taskTitle(it) } ?: "Standalone focus",
                durationMinutes = if (session.completed) {
                    session.durationMinutes
                } else {
                    // Stopped early: the minutes actually elapsed
                    // (started → ended, rounded down, never below 0).
                    val endedAt = session.endedAtEpochMillis ?: session.startedAtEpochMillis
                    ((endedAt - session.startedAtEpochMillis) / 60_000L)
                        .coerceAtLeast(0L).toInt()
                },
                completed = session.completed
            )
        }
        _uiState.update {
            it.copy(
                todaysFocusMinutes = minutes,
                completedFocusToday = count,
                weekFocusMinutes = weekMinutes,
                weekCompletionPercent = weekCompletion,
                focusStreakDays = streak,
                recentSessions = recent,
                nextBreakMinutes = PomodoroCycle.breakMinutesAfter(count)
            )
        }
    }

    private suspend fun taskTitle(taskId: String?): String? {
        if (taskId == null) return null
        return taskRepository.getTaskById(taskId)?.title
    }

    private fun startTicker(plannedEndEpochMillis: Long) {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val now = System.currentTimeMillis()
                if (PomodoroCycle.isFinished(plannedEndEpochMillis, now)) {
                    finishCurrentSession(now)
                    return@launch
                }
                _uiState.update {
                    it.copy(remainingMillis = PomodoroCycle.remainingMillis(plannedEndEpochMillis, now))
                }
            }
        }
    }
}
