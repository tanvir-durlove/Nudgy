package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.todayDateString
import com.nextgentool.nudgy.domain.usecase.CarryOverUseCase
import com.nextgentool.nudgy.domain.usecase.PlanDayUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the Plan list: the task + whether it is carried over. */
data class PlanRow(
    val task: TaskEntity,
    val isCarryOver: Boolean
)

data class PlanUiState(
    val isLoading: Boolean = true,
    /** Picked rows, in plan order (this order becomes the day's order). */
    val pickedRows: List<PlanRow> = emptyList(),
    /** Candidates not picked, in draft order. */
    val unpickedRows: List<PlanRow> = emptyList(),
    /** Sum of estimates of the picked rows (the 8h budget is 480). */
    val totalEstimatedMinutes: Int = 0,
    /** Planned start per picked task id (what "Start my day" will stamp). */
    val startTimes: Map<String, Long> = emptyMap(),
    /** One-shot: "Start my day" applied — the screen navigates back. */
    val planApplied: Boolean = false
)

/**
 * Sprint 1-2 — Plan My Day (Feature 14, spec §6).
 *
 * The draft is seeded from the repository (auto draft: carry-over
 * first, then today-due, 8h estimate cap — Q1) and re-seeded from
 * every emission until the user touches the plan — after that a later
 * database emission never clobbers an in-progress plan. Doing Plan a
 * second time shows the previous picks: they are seeded as picked
 * (plannedDate = today).
 *
 * Reorder is via up/down controls on each picked row. Long-press drag
 * is NOT implemented here: stock Compose has no built-in reorder API
 * (the `detectReorderAfterLongPress` API belongs to a third-party
 * library that is not in this build), and a hand-rolled drag gesture
 * cannot be verified on a device from this VM. The order — which is
 * what "Start my day" stamps as start times — is identical either way.
 */
@HiltViewModel
class PlanViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val planDayUseCase: PlanDayUseCase,
    private val carryOverUseCase: CarryOverUseCase,
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

    private val todayStart: Long = startOfTodayMillis()
    private val todayString: String = todayDateString()

    /** Full candidate order (picked first, then unpicked); null = unseeded. */
    private val orderFlow = MutableStateFlow<List<String>?>(null)

    /** Picked task ids; null = unseeded. */
    private val pickedFlow = MutableStateFlow<Set<String>?>(null)

    private val appliedFlow = MutableStateFlow(false)

    /**
     * Set the moment the user picks, unpicks, reorders or starts the
     * day: from then on the plan is theirs and later database emissions
     * never rewrite it. Before that the draft is re-seeded from every
     * emission, so it always reflects the current task list — seeding
     * once from the very first emission could freeze a partial snapshot
     * (the first query can land before a just-created task is visible)
     * and leave tasks out of the draft entirely.
     */
    private var userInteracted = false

    /** The uid the draft was seeded for — a flip re-seeds from scratch. */
    private var seededUid: String? = null

    init {
        // Sprint 5 fix round 4 — the seed collector follows the LIVE
        // user: a flip restarts the tasks flow for the new uid, and a
        // new uid resets the draft (the old user's order and picks
        // belong to the old user) before re-seeding from the new
        // user's tasks. Reopening Plan without a flip keeps the
        // previous picks (userInteracted stays true).
        viewModelScope.launch {
            userFlow.flatMapLatest { user ->
                repository.observeAllTasks(user.uid).map { tasks -> user.uid to tasks }
            }.collect { (uid, tasks) ->
                if (uid != seededUid) {
                    userInteracted = false
                    orderFlow.value = null
                    pickedFlow.value = null
                    seededUid = uid
                }
                if (!userInteracted) {
                    val (order, picked) = seed(tasks)
                    orderFlow.value = order
                    pickedFlow.value = picked
                }
            }
        }
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

    val uiState: StateFlow<PlanUiState> = userFlow.flatMapLatest { user ->
        combine(
            repository.observeAllTasks(user.uid),
            orderFlow,
            pickedFlow,
            appliedFlow
        ) { allTasks, order, picked, applied ->
        if (order == null || picked == null) {
            return@combine PlanUiState(isLoading = true, planApplied = applied)
        }
        val topLevel = allTasks.filter { it.parentTaskId == null }
        val carryOverIds = carryOverUseCase
            .findCarryOver(allTasks, todayStart).map { it.id }.toSet()
        val byId = topLevel.associateBy { it.id }
        val pickedRows = order
            .filter { it in picked }
            .mapNotNull { byId[it] }
            .map { PlanRow(it, it.id in carryOverIds) }
        val unpickedRows = order
            .filter { it !in picked }
            .mapNotNull { byId[it] }
            .map { PlanRow(it, it.id in carryOverIds) }
        PlanUiState(
            isLoading = false,
            pickedRows = pickedRows,
            unpickedRows = unpickedRows,
            totalEstimatedMinutes = pickedRows.sumOf { it.task.estimatedMinutes ?: 0 },
            startTimes = planDayUseCase.assignStartTimes(
                pickedOrdered = pickedRows.map { it.task },
                startOfToday = todayStart,
                now = System.currentTimeMillis()
            ),
            planApplied = applied
        )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlanUiState()
    )

    /**
     * The seed: candidates are unfinished top-level tasks that are
     * carried over, due today, or already picked for today. The draft
     * (Q1) picks carry-over first, then today-due, within the 8h
     * estimate budget; previous picks stay picked.
     */
    private fun seed(allTasks: List<TaskEntity>): Pair<List<String>, Set<String>> {
        val carryOverIds = carryOverUseCase
            .findCarryOver(allTasks, todayStart).map { it.id }.toSet()
        val previouslyPickedIds = allTasks
            .filter { it.plannedDate == todayString }
            .map { it.id }.toSet()
        val candidates = allTasks.filter { task ->
            task.parentTaskId == null &&
                !task.isCompleted &&
                (task.id in carryOverIds ||
                    task.dueDateEpochMillis in todayStart until startOfTomorrowMillis() ||
                    task.id in previouslyPickedIds)
        }
        val draft = planDayUseCase.buildDraft(candidates, carryOverIds, previouslyPickedIds)
        val order = (draft.picked + draft.unpicked).map { it.id }
        return order to draft.picked.map { it.id }.toSet()
    }

    /** Pick / unpick a candidate. Order is untouched — the row just moves sections. */
    fun togglePick(taskId: String) {
        val picked = pickedFlow.value ?: return
        userInteracted = true
        pickedFlow.value = if (taskId in picked) picked - taskId else picked + taskId
    }

    /** Move a picked row one step up (-1) or down (+1) in the plan order. */
    fun movePicked(taskId: String, delta: Int) {
        val order = orderFlow.value ?: return
        val picked = pickedFlow.value ?: return
        userInteracted = true
        val pickedOrdered = order.filter { it in picked }
        val index = pickedOrdered.indexOf(taskId)
        val target = index + delta
        if (index < 0 || target !in pickedOrdered.indices) return
        val swapped = pickedOrdered.toMutableList().apply { add(target, removeAt(index)) }
        val unpickedOrdered = order.filter { it !in picked }
        orderFlow.value = swapped + unpickedOrdered
    }

    /**
     * "Start my day": stamps the plan into Room, then the screen goes
     * back to Today. Picked tasks get plannedDate = today +
     * plannedStartEpochMillis (their due time, or the next sequential
     * slot). A picked carry-over task ALSO moves: its due becomes today
     * (same time-of-day) and carriedOverFromDate records the old date.
     * A task picked before but unpicked now loses its plan
     * (plannedDate / plannedStart cleared, due untouched).
     */
    fun startMyDay() {
        userInteracted = true
        viewModelScope.launch {
            val state = uiState.value
            if (state.isLoading || state.pickedRows.isEmpty()) return@launch
            val now = System.currentTimeMillis()
            val starts = planDayUseCase.assignStartTimes(
                pickedOrdered = state.pickedRows.map { it.task },
                startOfToday = todayStart,
                now = now
            )
            val pickedIds = state.pickedRows.map { it.task.id }.toSet()
            val allTasks = repository.getAllTasksOnce(currentUserId)
            for (task in allTasks) {
                if (task.plannedDate == todayString && task.id !in pickedIds) {
                    repository.updateTask(
                        task.copy(plannedDate = null, plannedStartEpochMillis = null)
                    )
                }
            }
            for (row in state.pickedRows) {
                val task = row.task
                val newDue = if (row.isCarryOver) {
                    carryOverUseCase.moveDueToToday(task.dueDateEpochMillis, todayStart)
                } else {
                    task.dueDateEpochMillis
                }
                val fromDate = if (row.isCarryOver) {
                    carryOverUseCase.carriedOverFromDate(task.dueDateEpochMillis)
                } else {
                    task.carriedOverFromDate
                }
                repository.updateTask(
                    task.copy(
                        plannedDate = todayString,
                        plannedStartEpochMillis = starts[task.id],
                        dueDateEpochMillis = newDue,
                        carriedOverFromDate = fromDate
                    )
                )
            }
            appliedFlow.value = true
        }
    }
}
