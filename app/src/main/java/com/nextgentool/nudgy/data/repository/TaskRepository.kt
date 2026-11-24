package com.nextgentool.nudgy.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.ProjectEntity
import com.nextgentool.nudgy.data.local.entity.SavedViewEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.local.entity.TaskAttachmentEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.local.entity.TaskTemplateEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.domain.model.BuiltInTemplates
import com.nextgentool.nudgy.domain.model.TaskTemplate
import com.nextgentool.nudgy.domain.model.parseTemplateDrafts
import com.nextgentool.nudgy.domain.model.toTemplateJson
import com.nextgentool.nudgy.domain.usecase.Recurrence
import com.nextgentool.nudgy.reminders.ReminderManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class DailyTaskStats(
    val total: Int = 0,
    val completed: Int = 0,
    val remaining: Int = 0,
    val percentage: Float = 0f
)

/**
 * Sprint 1-1 — rewritten Tasks data layer (spec §3, §6 Features 1–2).
 *
 * Room is the single source of truth: the UI only ever sees Flows from
 * here (or a UseCase on top of it) and never touches a DAO directly.
 *
 * The single write path: every completion/status change goes through
 * [toggleTaskCompletion] / [setTaskStatus], which write `status` and
 * `isCompleted` together — DONE ⇔ isCompleted = true can never drift.
 * (The DAO's setTaskCompletion* SQLs carry the same rule for the
 * ReminderReceiver's direct calls.)
 *
 * "No date": [NO_DUE_DATE] (0L) means a task has no due date (Feature 1).
 * The v7 schema has no nullable due column, so a sentinel is used
 * instead of a table rebuild. Tasks with due = 0 never appear in Today
 * (S1-2 must exclude them from the day query) and group under "No date"
 * in Upcoming / the Tasks list. 0 is 1970-01-01 — never a real due date.
 *
 * Cloud sync: deliberately NOT in this rewrite. The old repository's
 * Firestore listener + upload + pending-delete machinery is not running
 * in the rebuild (nothing calls startCloudSync since the old MainActivity
 * went away in S1-0), and Sprint 1 is local-first. It will be re-attached
 * from git history (TaskRepository before this rewrite) when sync returns
 * — the local rules it depended on are preserved: group rows are
 * local-only (never dirty-marked, never uploaded) and their completion
 * is Room-local. Local data is unaffected by the removal.
 *
 * Constructor shape is frozen: ReminderReceiver builds
 * TaskRepository(context) directly, and ClientIntentPreFilterTest builds
 * TaskRepository(context, db). Both must keep compiling.
 */
@Singleton
class TaskRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: NudgyDatabase = NudgyDatabase.getInstance(context)
) {
    private val taskDao = database.taskDao()
    private val tagDao = database.tagDao()
    private val projectDao = database.projectDao()
    private val savedViewDao = database.savedViewDao()
    private val templateDao = database.taskTemplateDao()
    private val attachmentDao = database.taskAttachmentDao()
    private val reminderManager = ReminderManager(context)

    companion object {
        /** dueDateEpochMillis value meaning "no due date" (Feature 1). */
        const val NO_DUE_DATE: Long = 0L

        /** Sprint 1-4 — attachment limits (Feature 10, spec-fixed). */
        const val MAX_ATTACHMENT_BYTES: Long = 25L * 1024L * 1024L
        const val MAX_ATTACHMENTS_PER_TASK: Int = 10
    }

    /**
     * Sprint 1-4g — one status column's display order: boardOrder ASC,
     * ties by creation time (defensive — the migration backfills dense
     * unique values, and every move renumbers, so ties should not
     * survive; a tie must still order deterministically).
     */
    private val boardColumnOrder: Comparator<TaskEntity> =
        compareBy<TaskEntity> { it.boardOrder }.thenBy { it.createdAtEpochMillis }

    /** The user whose data this repository serves (guest when signed out). */
    fun currentUserId(): String =
        AuthManager.getActiveUserId(context).ifBlank { AuthManager.GUEST_USER_ID }

    // ------------------------------------------------------------------ reads

    fun observeAllTasks(userId: String): Flow<List<TaskEntity>> =
        taskDao.getAllTasksFlow(userId)

    /** Top-level tasks (subtasks excluded — they live in the detail sheet). */
    fun observeTopLevelTasks(userId: String): Flow<List<TaskEntity>> =
        taskDao.getTopLevelTasksFlow(userId)

    fun observeTask(taskId: String): Flow<TaskEntity?> =
        taskDao.getTaskByIdFlow(taskId)

    fun observeTodayTasks(userId: String): Flow<List<TaskEntity>> {
        val (startOfDay, endOfDay) = getTodayBounds()
        return taskDao.getTasksForDayFlow(userId, startOfDay, endOfDay)
    }

    fun observeUpcomingTasks(userId: String): Flow<List<TaskEntity>> {
        val (_, endOfDay) = getTodayBounds()
        return taskDao.getUpcomingTasksFlow(userId, endOfDay)
    }

    fun observeCompletedTasks(userId: String): Flow<List<TaskEntity>> =
        taskDao.getCompletedTasksFlow(userId)

    fun observeTodayStats(userId: String): Flow<DailyTaskStats> {
        val (startOfDay, endOfDay) = getTodayBounds()
        return taskDao.getTasksForDayFlow(userId, startOfDay, endOfDay).map { tasks ->
            val total = tasks.size
            val completed = tasks.count { it.isCompleted }
            val remaining = (total - completed).coerceAtLeast(0)
            val percentage = if (total > 0) completed.toFloat() / total.toFloat() else 0f
            DailyTaskStats(
                total = total,
                completed = completed,
                remaining = remaining,
                percentage = percentage
            )
        }
    }

    suspend fun getTaskById(taskId: String): TaskEntity? = withContext(Dispatchers.IO) {
        taskDao.getTaskById(taskId)
    }

    /** One-shot read of every task (Plan uses it to find previous picks). */
    suspend fun getAllTasksOnce(userId: String): List<TaskEntity> = withContext(Dispatchers.IO) {
        taskDao.getAllTasksOnce(userId)
    }

    // ------------------------------------------------------------- tasks CRUD

    suspend fun createTask(
        userId: String,
        title: String,
        description: String? = null,
        dueEpochMillis: Long = System.currentTimeMillis(),
        priority: Priority = Priority.MEDIUM,
        category: TaskCategory = TaskCategory.WORK,
        reminderSet: Boolean = false,
        recurrenceRule: String? = null,
        // An explicit reminder time ("remind me at 3 PM"). Null = the
        // 2h-before-due lead (ReminderManager.dueReminderTriggerMillis).
        reminderEpochMillis: Long? = null,
        parentTaskId: String? = null,
        projectId: String? = null,
        templateId: String? = null,
        estimatedMinutes: Int? = null,
        // Sprint 6-3 — an onboarding sample task (feature 40): the
        // row's "Sample" chip + one-tap delete read this flag.
        isSample: Boolean = false
    ): TaskEntity = withContext(Dispatchers.IO) {
        // S1-4g (d1): a new top-level task appends at the END of its
        // column (every create lands in TODO) — creating must never
        // shove other cards down. Subtasks keep 0 (no Board presence);
        // the Board only ever orders top-level tasks.
        val boardOrder = if (parentTaskId == null) {
            (taskDao.getMaxBoardOrder(userId, TaskStatus.TODO.name) ?: -1) + 1
        } else {
            0
        }
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            title = title,
            description = description,
            dueDateEpochMillis = dueEpochMillis,
            priority = priority,
            category = category,
            isCompleted = false,
            status = TaskStatus.TODO,
            reminderSet = reminderSet && dueEpochMillis != NO_DUE_DATE,
            reminderEpochMillis = if (reminderSet && dueEpochMillis != NO_DUE_DATE) {
                reminderEpochMillis ?: ReminderManager.dueReminderTriggerMillis(null, dueEpochMillis)
            } else {
                null
            },
            recurrenceRule = recurrenceRule,
            isSyncedToCloud = false,
            createdAtEpochMillis = System.currentTimeMillis(),
            updatedAtEpochMillis = System.currentTimeMillis(),
            parentTaskId = parentTaskId,
            projectId = projectId,
            templateId = templateId,
            estimatedMinutes = estimatedMinutes,
            isSample = isSample,
            boardOrder = boardOrder
        )
        taskDao.insertTask(task)
        if (task.reminderSet) {
            val trigger = task.reminderEpochMillis ?: task.dueDateEpochMillis
            if (trigger > System.currentTimeMillis()) {
                reminderManager.scheduleReminder(task.id, task.title, trigger)
            }
        }
        task
    }

    /**
     * A subtask is a plain task row with parentTaskId set. ONE level only:
     * creating a subtask under a subtask is rejected — the detail sheet
     * never offers it, and this is the backstop.
     */
    suspend fun createSubtask(parentId: String, title: String): TaskEntity =
        withContext(Dispatchers.IO) {
            val parent = taskDao.getTaskById(parentId)
                ?: throw IllegalArgumentException("Parent task $parentId not found")
            require(parent.parentTaskId == null) {
                "Subtasks have no subtasks (parent $parentId is itself a subtask)"
            }
            createTask(
                userId = parent.userId,
                title = title,
                dueEpochMillis = NO_DUE_DATE,
                parentTaskId = parentId
            )
        }

    suspend fun updateTask(task: TaskEntity) = withContext(Dispatchers.IO) {
        // Group v1: group rows are edited through GroupRepository (cloud
        // write to the shared group item). This path is a defensive
        // local-only update — it must NOT dirty-mark the row.
        //
        // Keep the stored reminder time in sync with the (possibly new) due
        // time. An explicitly passed reminder time is respected; otherwise
        // the 2h-before-due lead is recomputed from the due time. A stored
        // reminder equal to the OLD due time means "remind me at due" (an
        // explicit reminder request), so it moves with the due time.
        val existing = taskDao.getTaskById(task.id)
        val passedReminderChanged = task.reminderEpochMillis != existing?.reminderEpochMillis
        val explicitAtDue = existing != null &&
            existing.reminderEpochMillis != null &&
            existing.reminderEpochMillis == existing.dueDateEpochMillis
        val resolvedReminder: Long? = when {
            !task.reminderSet || task.isCompleted || task.dueDateEpochMillis == NO_DUE_DATE -> null
            passedReminderChanged -> task.reminderEpochMillis
            explicitAtDue -> task.dueDateEpochMillis
            existing?.reminderEpochMillis != null &&
                existing.reminderEpochMillis != existing.dueDateEpochMillis - ReminderManager.REMINDER_LEAD_MILLIS -> existing.reminderEpochMillis
            else -> ReminderManager.dueReminderTriggerMillis(null, task.dueDateEpochMillis)
        }
        // The invariant: status and isCompleted are derived from each
        // other, never written independently.
        val resolvedIsCompleted = task.status == TaskStatus.DONE
        val resolvedCompletedAt = when {
            resolvedIsCompleted && task.completedAtEpochMillis == null -> System.currentTimeMillis()
            resolvedIsCompleted -> task.completedAtEpochMillis
            else -> null
        }
        // S1-4g: a completion flipped through an edit is a Board move
        // too — (c1) completing lands on TOP of Done, un-completing
        // appends at the END of To do. The seed below only fixes the
        // moved row's sort key; renumberColumn (in the same transaction)
        // makes both columns dense again. No transition → the stored
        // boardOrder is untouched (Plan / carry-over edits must not
        // reshuffle the Board).
        val completing = resolvedIsCompleted && existing?.isCompleted == false
        val uncompleting = !resolvedIsCompleted && existing?.isCompleted == true
        val resolvedBoardOrder = when {
            completing -> 0
            // Un-completing appends at the END of its TARGET column
            // (usually TODO — but the passed status decides, not an
            // assumption: the detail sheet can move a DONE task to
            // IN_PROGRESS too).
            uncompleting -> (taskDao.getMaxBoardOrder(task.userId, task.status.name) ?: -1) + 1
            else -> task.boardOrder
        }
        val resolved = task.copy(
            reminderEpochMillis = resolvedReminder,
            isCompleted = resolvedIsCompleted,
            completedAtEpochMillis = resolvedCompletedAt,
            updatedAtEpochMillis = System.currentTimeMillis(),
            isSyncedToCloud = if (task.groupId != null) task.isSyncedToCloud else false,
            boardOrder = resolvedBoardOrder
        )
        database.withTransaction {
            taskDao.updateTask(resolved)
            if (completing) {
                renumberColumn(task.userId, TaskStatus.DONE, firstId = task.id)
                if (existing != null && existing.status != TaskStatus.DONE) {
                    renumberColumn(task.userId, existing.status)
                }
            } else if (uncompleting) {
                renumberColumn(task.userId, task.status, lastId = task.id)
                if (existing != null && existing.status != task.status) {
                    renumberColumn(task.userId, existing.status)
                }
            }
        }
        if (resolvedReminder != null) {
            if (resolvedReminder > System.currentTimeMillis()) {
                reminderManager.scheduleReminder(task.id, task.title, resolvedReminder)
            } else {
                reminderManager.cancelReminder(task.id)
            }
        } else {
            reminderManager.cancelReminder(task.id)
        }
        // Completing through an edit (not just the checkbox / Board drag)
        // also advances a recurrence — but only on a real transition.
        if (resolvedIsCompleted && existing?.isCompleted == false) {
            maybeCreateNextOccurrence(resolved)
        }
    }

    /**
     * Sprint 1-3a — recurrence (Feature 3). Completing a task with a
     * recurrence rule creates its next occurrence: same title, description,
     * priority, project and estimate; the reminder lead (due − reminder) is
     * preserved, falling back to the 2h default when the preserved time
     * would already be past. The old occurrence is NOT deleted — history
     * stays. Subtasks and tags are not carried over (v1 scope). Group
     * recurrence is Sprint 5; no-date tasks have no anchor to step from.
     * Completing consumes the current occurrence, so the next one is always
     * a later cycle — even when completed early.
     */
    private suspend fun maybeCreateNextOccurrence(completedTask: TaskEntity) {
        val rule = completedTask.recurrenceRule ?: return
        if (completedTask.parentTaskId != null) return
        if (completedTask.groupId != null) return
        if (completedTask.dueDateEpochMillis == NO_DUE_DATE) return
        val nextDue = Recurrence.nextOccurrence(rule, completedTask.dueDateEpochMillis) ?: return
        val lead = completedTask.reminderEpochMillis?.let { completedTask.dueDateEpochMillis - it }
            ?: ReminderManager.REMINDER_LEAD_MILLIS
        val nextReminder = (nextDue - lead).takeIf { it > System.currentTimeMillis() }
        createTask(
            userId = completedTask.userId,
            title = completedTask.title,
            description = completedTask.description,
            dueEpochMillis = nextDue,
            priority = completedTask.priority,
            category = completedTask.category,
            reminderSet = completedTask.reminderSet,
            reminderEpochMillis = if (completedTask.reminderSet) nextReminder else null,
            recurrenceRule = rule,
            projectId = completedTask.projectId,
            estimatedMinutes = completedTask.estimatedMinutes
        )
    }

    /** Whether the system allows exact alarms (UI: "missed" state + hint). */
    fun canScheduleExactAlarms(): Boolean = reminderManager.canScheduleExactAlarms()

    /** Flip completion — and the status with it (DONE ⇔ isCompleted). */
    suspend fun toggleTaskCompletion(task: TaskEntity) = withContext(Dispatchers.IO) {
        // The flip decision follows the PASSED entity, as before:
        // ReminderReceiver deliberately passes a copy with
        // isCompleted = false to force completion.
        val newCompleted = !task.isCompleted
        val newStatus = if (newCompleted) TaskStatus.DONE else TaskStatus.TODO
        val fresh = taskDao.getTaskById(task.id) ?: return@withContext
        if (fresh.parentTaskId != null) {
            // Subtasks toggle in place — they have no Board column.
            val completedAt = if (newCompleted) System.currentTimeMillis() else null
            val now = System.currentTimeMillis()
            if (fresh.groupId != null) {
                taskDao.setTaskStatusAndCompletionLocal(
                    userId = fresh.userId,
                    id = fresh.id,
                    status = newStatus.name,
                    isCompleted = newCompleted,
                    completedAt = completedAt,
                    now = now
                )
            } else {
                taskDao.setTaskStatusAndCompletionForUser(
                    userId = fresh.userId,
                    id = fresh.id,
                    status = newStatus.name,
                    isCompleted = newCompleted,
                    completedAt = completedAt,
                    now = now
                )
            }
        } else {
            // Top-level: a completion is a Board move — (c1) completing
            // lands on TOP of Done, un-completing appends at the END of
            // To do — and both columns stay dense (see applyBoardMove).
            val allTopLevel = taskDao.getAllTasksOnce(fresh.userId)
                .filter { it.parentTaskId == null }
            val targetColumn = allTopLevel.filter { it.status == newStatus }
                .sortedWith(boardColumnOrder)
            val fullIndex = if (newCompleted) 0 else targetColumn.size
            applyBoardMove(
                task = fresh,
                targetStatus = newStatus,
                fullIndex = fullIndex,
                newPlannedStart = fresh.plannedStartEpochMillis,
                topLevel = allTopLevel
            )
        }
        if (newCompleted) {
            reminderManager.cancelReminder(task.id)
            maybeCreateNextOccurrence(task)
        } else if (task.reminderSet) {
            val trigger = ReminderManager.dueReminderTriggerMillis(task.reminderEpochMillis, task.dueDateEpochMillis)
            if (trigger > System.currentTimeMillis()) {
                reminderManager.scheduleReminder(task.id, task.title, trigger)
            }
        }
    }

    /** Set the Board/status column directly (S1-4 drag); completion follows. */
    suspend fun setTaskStatus(task: TaskEntity, status: TaskStatus) = withContext(Dispatchers.IO) {
        val fresh = taskDao.getTaskById(task.id) ?: return@withContext
        if (fresh.parentTaskId != null) return@withContext // subtasks have no column
        if (fresh.status == status) return@withContext // already there — no move, no re-stamp
        val allTopLevel = taskDao.getAllTasksOnce(fresh.userId)
            .filter { it.parentTaskId == null }
        val targetColumn = allTopLevel.filter { it.status == status }
            .sortedWith(boardColumnOrder)
        // (c1): completing lands on TOP of Done; any other status change
        // appends at the END of the target column — a move nobody
        // positioned must not shove other cards down.
        val fullIndex = if (status == TaskStatus.DONE) 0 else targetColumn.size
        val moved = applyBoardMove(
            task = fresh,
            targetStatus = status,
            fullIndex = fullIndex,
            newPlannedStart = fresh.plannedStartEpochMillis,
            topLevel = allTopLevel
        )
        if (!moved) return@withContext
        if (status == TaskStatus.DONE) {
            reminderManager.cancelReminder(fresh.id)
            maybeCreateNextOccurrence(fresh)
        }
    }

    /**
     * Sprint 1-4g — move a card to a position on a Board (Feature 5 reorder).
     *
     * [targetIndex] counts VISIBLE cards in the target column — the Board
     * the user is looking at (a filtered Tasks Board, or the My Day subset
     * of planned tasks) — NOT the full column. [visibleItems] are that
     * column's visible cards in display order, EXCLUDING the dragged card
     * (the UI counts other cards only — see TaskBoard.boardDropIndex).
     * The visible index is mapped onto the full status column before
     * writing, so hidden tasks keep their relative order and one board's
     * reorder never scrambles the other's (spec §5, decision (e2) note).
     *
     * [updatePlannedStart] is true only for the My Day Board (e2). The
     * My Day display order IS plannedStart order, so honouring a drop
     * there means re-slotting the day: the dragged card takes a new
     * plannedStart consistent with its drop position — the midpoint of
     * its new neighbours (30 min before the first / after the last when
     * it lands at an end, clamped to that day; its own value when it has
     * no timed neighbour). Other cards' plannedStart values are never
     * touched, and no time slot is invented or dropped. The Tasks Board
     * never touches plannedStart. See resolvePlannedStartForDrop.
     *
     * Returns false when the drop is a no-op (same position) — no write.
     * One Room transaction; the Flow re-emits and the Board follows.
     */
    suspend fun moveTaskToPosition(
        task: TaskEntity,
        targetStatus: TaskStatus,
        targetIndex: Int,
        visibleItems: List<TaskEntity>,
        updatePlannedStart: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val fresh = taskDao.getTaskById(task.id) ?: return@withContext false
        if (fresh.parentTaskId != null) return@withContext false // subtasks have no column
        val allTopLevel = taskDao.getAllTasksOnce(fresh.userId)
            .filter { it.parentTaskId == null }
        val targetColumn = allTopLevel.filter { it.status == targetStatus }
            .sortedWith(boardColumnOrder)
        val targetWithout = targetColumn.filter { it.id != fresh.id }
        // The visible list the UI counted may be stale (a background
        // write landed between render and drop) — keep only rows the
        // column still has, in the UI's display order.
        val visibleOthers = visibleItems.filter { v -> targetWithout.any { it.id == v.id } }
        // Same column, and the dragged card is the ONLY visible card:
        // its visible position cannot change — no-op, whatever the
        // full column holds.
        if (targetStatus == fresh.status && visibleOthers.isEmpty()) return@withContext false
        // (c1): dropping into an empty VISIBLE Done column completes the
        // task with no position given, so it goes to the TOP of Done.
        // Any other empty visible column appends at the full column's end.
        val fullIndex = if (visibleOthers.isEmpty() && targetStatus == TaskStatus.DONE &&
            fresh.status != TaskStatus.DONE
        ) {
            0
        } else {
            mapVisibleIndexToFullIndex(targetWithout, visibleOthers, targetIndex)
        }
        val newPlannedStart = if (updatePlannedStart) {
            resolvePlannedStartForDrop(
                visiblePlannedStarts = visibleOthers.map { it.plannedStartEpochMillis },
                targetIndex = targetIndex,
                fallback = fresh.plannedStartEpochMillis
            )
        } else {
            fresh.plannedStartEpochMillis
        }
        val moved = applyBoardMove(
            task = fresh,
            targetStatus = targetStatus,
            fullIndex = fullIndex,
            newPlannedStart = newPlannedStart,
            topLevel = allTopLevel
        )
        if (!moved) return@withContext false
        if (targetStatus == TaskStatus.DONE && fresh.status != TaskStatus.DONE) {
            reminderManager.cancelReminder(fresh.id)
            maybeCreateNextOccurrence(fresh)
        }
        true
    }

    /**
     * The single Room write for a Board move. [fullIndex] is the position
     * in the FULL target column, dragged card already removed (see
     * mapVisibleIndexToFullIndex). Same-column drop at the same index is
     * a no-op (false, no write). Cross-column moves keep every
     * setTaskStatus side effect — the caller runs reminder cancel +
     * recurrence afterwards, exactly as setTaskStatus does today.
     *
     * All writes run in ONE transaction (withTransaction), so a move can
     * never leave two rows sharing a boardOrder. Completing an
     * already-completed task keeps its ORIGINAL completedAt (the
     * completion time is when it was first completed, not re-stamped on
     * every re-drop into Done).
     */
    private suspend fun applyBoardMove(
        task: TaskEntity,
        targetStatus: TaskStatus,
        fullIndex: Int,
        newPlannedStart: Long?,
        topLevel: List<TaskEntity>
    ): Boolean {
        val now = System.currentTimeMillis()
        val completed = targetStatus == TaskStatus.DONE
        val completedAt = if (completed) {
            if (task.isCompleted) task.completedAtEpochMillis else now
        } else {
            null
        }
        val sourceColumn = topLevel.filter { it.status == task.status }
            .sortedWith(boardColumnOrder)
        val targetColumn = if (targetStatus == task.status) sourceColumn
            else topLevel.filter { it.status == targetStatus }.sortedWith(boardColumnOrder)
        val sourceWithout = sourceColumn.filter { it.id != task.id }
        val targetWithout = targetColumn.filter { it.id != task.id }
        val clampedIndex = fullIndex.coerceIn(0, targetWithout.size)
        if (targetStatus == task.status) {
            val currentIndex = sourceColumn.indexOfFirst { it.id == task.id }
            if (currentIndex == clampedIndex) return false
        }
        val newTargetOrder = targetWithout.toMutableList().apply { add(clampedIndex, task) }
        database.withTransaction {
            // The moved row: status + completion + position (+ plannedStart).
            if (task.groupId != null) {
                taskDao.setStatusCompletionBoardOrderLocal(
                    userId = task.userId, id = task.id, status = targetStatus.name,
                    isCompleted = completed, completedAt = completedAt,
                    boardOrder = clampedIndex, plannedStart = newPlannedStart, now = now
                )
            } else {
                taskDao.setStatusCompletionBoardOrderForUser(
                    userId = task.userId, id = task.id, status = targetStatus.name,
                    isCompleted = completed, completedAt = completedAt,
                    boardOrder = clampedIndex, plannedStart = newPlannedStart, now = now
                )
            }
            // Renumber the target column dense 0..n-1 in the new order.
            newTargetOrder.forEachIndexed { index, row ->
                if (row.id != task.id) writeBoardOrder(row, index, now)
            }
            // Cross-column: close the gap in the source column too.
            if (targetStatus != task.status) {
                sourceWithout.forEachIndexed { index, row -> writeBoardOrder(row, index, now) }
            }
        }
        return true
    }

    /** One row's position write — group rows are never dirty-marked. */
    private suspend fun writeBoardOrder(row: TaskEntity, boardOrder: Int, now: Long) {
        if (row.groupId != null) {
            taskDao.setBoardOrderLocal(row.userId, row.id, boardOrder, now)
        } else {
            taskDao.setBoardOrderForUser(row.userId, row.id, boardOrder, now)
        }
    }

    /**
     * Rewrites one status column dense 0..n-1 from its stored boardOrder
     * (ties: [firstId] first, [lastId] last, then createdAt ASC). Used
     * when a completion flips OUTSIDE a Board drop (updateTask), where
     * the moved row's position is a seed, not a computed index. Call
     * inside a transaction with the triggering write.
     */
    private suspend fun renumberColumn(
        userId: String,
        status: TaskStatus,
        firstId: String? = null,
        lastId: String? = null
    ) {
        val column = taskDao.getAllTasksOnce(userId)
            .filter { it.parentTaskId == null && it.status == status }
            .sortedWith(
                compareBy<TaskEntity> { it.boardOrder }
                    .thenBy { if (it.id == firstId) 0 else 1 }
                    .thenBy { if (it.id == lastId) 1 else 0 }
                    .thenBy { it.createdAtEpochMillis }
            )
        val now = System.currentTimeMillis()
        column.forEachIndexed { index, row -> writeBoardOrder(row, index, now) }
    }

    suspend fun deleteTask(userId: String, taskId: String) = withContext(Dispatchers.IO) {
        reminderManager.cancelReminder(taskId)
        // Group v1: group rows are deleted from the shared group item by the
        // creator (GroupRepository). A member-side delete is local-only.
        // Either way the row goes; task_tags / attachments rows follow via
        // the FK cascades — but the FILES live on disk, so they are removed
        // here first (S1-4, Feature 10: deleting a task deletes its files).
        deleteAttachmentFilesForTask(taskId)
        taskDao.deleteTaskByIdForUser(userId, taskId)
    }

    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        reminderManager.cancelReminder(taskId)
        val task = taskDao.getTaskById(taskId)
        deleteAttachmentFilesForTask(taskId)
        if (task != null) {
            taskDao.deleteTaskByIdForUser(task.userId, taskId)
        } else {
            taskDao.deleteTaskById(taskId)
        }
    }

    /**
     * Sprint 1-3a — snooze (Feature 6). Only the REMINDER moves to the
     * snooze time; the due date is untouched (v1 moved both, which
     * corrupted the due date). The alarm itself is re-scheduled by
     * ReminderManager, so the snooze survives app kill — and a reboot
     * restores it via BootReceiver from the stored reminder time.
     */
    suspend fun snoozeTask(taskId: String, snoozeEpochMillis: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = taskDao.getTaskById(taskId) ?: return@withContext
        if (existing.isCompleted) return@withContext
        // Group v1: snoozing a group task is a local-only change (same rule
        // as completion) — never dirty-mark a group row.
        if (existing.groupId != null) {
            taskDao.setReminderTimeLocal(
                id = taskId,
                reminderEpochMillis = snoozeEpochMillis,
                now = now
            )
        } else {
            taskDao.setReminderTime(
                id = taskId,
                reminderEpochMillis = snoozeEpochMillis,
                now = now
            )
        }
        reminderManager.scheduleReminder(taskId, existing.title, snoozeEpochMillis)
    }

    suspend fun clearCompletedTasks(userId: String) = withContext(Dispatchers.IO) {
        // Group v1: completed group rows are NOT cleared here — the shared
        // group item still exists, so a listener/poll tick would resurrect
        // them. Only personal completed tasks are purged.
        // Their attachment files go first (S1-4, Feature 10), same as a
        // single delete — group rows keep their files (they still exist).
        taskDao.getCompletedTasksOnce(userId)
            .filter { it.groupId == null }
            .forEach { deleteAttachmentFilesForTask(it.id) }
        taskDao.deleteCompletedPersonalTasks(userId)
    }

    // ---------------------------------------------------------------- subtasks

    fun observeSubtasks(parentId: String): Flow<List<TaskEntity>> =
        taskDao.getSubtasksFlow(parentId)

    /** (completed, total) for the parent row's progress label, e.g. 2/5. */
    suspend fun subtaskProgress(parentId: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val total = taskDao.getSubtaskCount(parentId)
        val done = taskDao.getSubtaskCompletedCount(parentId)
        done to total
    }

    suspend fun toggleSubtask(subtask: TaskEntity) {
        // A subtask completes on its own — the parent never auto-completes
        // its subtasks, and completing the parent never completes them.
        toggleTaskCompletion(subtask)
    }

    suspend fun deleteSubtask(subtaskId: String) {
        deleteTask(subtaskId)
    }

    // -------------------------------------------------------------------- tags

    fun observeTags(userId: String): Flow<List<TagEntity>> =
        tagDao.getTagsFlow(userId)

    fun observeTagsForTask(taskId: String): Flow<List<TagEntity>> =
        tagDao.getTagsForTaskFlow(taskId)

    /** Every task↔tag link — the ViewModel joins tags to tasks in memory. */
    fun observeAllTaskTags(): Flow<List<com.nextgentool.nudgy.data.local.entity.TaskTagCrossRef>> =
        tagDao.getAllCrossRefsFlow()

    suspend fun createTag(userId: String, name: String, color: String): TagEntity =
        withContext(Dispatchers.IO) {
            val tag = TagEntity(userId = userId, name = name.trim(), color = color)
            tagDao.insertTag(tag)
            tag
        }

    suspend fun updateTag(tag: TagEntity) = withContext(Dispatchers.IO) {
        tagDao.updateTag(tag)
    }

    suspend fun deleteTag(userId: String, tagId: String) = withContext(Dispatchers.IO) {
        // The tag goes; its tasks stay (the task_tags links cascade).
        tagDao.deleteTagById(userId, tagId)
    }

    suspend fun setTaskTags(taskId: String, tagIds: List<String>) = withContext(Dispatchers.IO) {
        tagDao.setTagsForTask(taskId, tagIds)
    }

    // ---------------------------------------------------------------- projects

    fun observeProjects(userId: String): Flow<List<ProjectEntity>> =
        projectDao.getProjectsFlow(userId)

    suspend fun createProject(userId: String, name: String, color: String): ProjectEntity =
        withContext(Dispatchers.IO) {
            val project = ProjectEntity(userId = userId, name = name.trim(), color = color)
            projectDao.insertProject(project)
            project
        }

    suspend fun updateProject(project: ProjectEntity) = withContext(Dispatchers.IO) {
        projectDao.updateProject(project)
    }

    suspend fun deleteProject(userId: String, projectId: String) = withContext(Dispatchers.IO) {
        // The project goes; its tasks move to "no project" — never deleted.
        taskDao.clearProjectFromTasks(projectId, System.currentTimeMillis())
        projectDao.deleteProjectById(userId, projectId)
    }

    // -------------------------------------------------------------- saved views

    fun observeSavedViews(userId: String): Flow<List<SavedViewEntity>> =
        savedViewDao.getSavedViewsFlow(userId)

    suspend fun saveView(userId: String, name: String, filterJson: String): SavedViewEntity =
        withContext(Dispatchers.IO) {
            val view = SavedViewEntity(userId = userId, name = name.trim(), filterJson = filterJson)
            savedViewDao.insertSavedView(view)
            view
        }

    suspend fun deleteSavedView(userId: String, viewId: String) = withContext(Dispatchers.IO) {
        savedViewDao.deleteSavedViewById(userId, viewId)
    }

    // -------------------------------------------------------------- templates

    fun observeTemplates(userId: String): Flow<List<TaskTemplate>> =
        templateDao.getTemplatesFlow(userId).map { entities ->
            entities.map { entity ->
                TaskTemplate(
                    id = entity.id,
                    name = entity.name,
                    drafts = entity.templateJson.parseTemplateDrafts(),
                    isBuiltIn = entity.isBuiltIn
                )
            }
        }

    /**
     * Sprint 1-4 — seeds the 3 approved built-in templates (Feature 13,
     * Q6 draft) on first run. Fixed IDs + insert-if-absent make it
     * idempotent: a re-seed never duplicates them, and a built-in is
     * never overwritten once it exists.
     */
    suspend fun seedBuiltInTemplates(userId: String) = withContext(Dispatchers.IO) {
        BuiltInTemplates.all.forEach { template ->
            if (templateDao.getTemplateById(userId, template.id) == null) {
                templateDao.insertTemplate(
                    TaskTemplateEntity(
                        id = template.id,
                        userId = userId,
                        name = template.name,
                        templateJson = template.drafts.toTemplateJson(),
                        isBuiltIn = true
                    )
                )
            }
        }
    }

    suspend fun createTemplate(
        userId: String,
        name: String,
        drafts: List<com.nextgentool.nudgy.domain.model.TemplateDraft>
    ): TaskTemplate = withContext(Dispatchers.IO) {
        val template = TaskTemplate(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            drafts = drafts,
            isBuiltIn = false
        )
        templateDao.insertTemplate(
            TaskTemplateEntity(
                id = template.id,
                userId = userId,
                name = template.name,
                templateJson = template.drafts.toTemplateJson(),
                isBuiltIn = false
            )
        )
        template
    }

    suspend fun updateTemplate(userId: String, template: TaskTemplate) =
        withContext(Dispatchers.IO) {
            // Built-ins are read-only in the UI; this is the backstop.
            require(!template.isBuiltIn) { "Built-in templates cannot be edited" }
            templateDao.insertTemplate(
                TaskTemplateEntity(
                    id = template.id,
                    userId = userId,
                    name = template.name,
                    templateJson = template.drafts.toTemplateJson(),
                    isBuiltIn = false
                )
            )
        }

    suspend fun deleteTemplate(userId: String, templateId: String) =
        withContext(Dispatchers.IO) {
            // Deleting a template never deletes the tasks it created —
            // they are separate rows (templateId is just a label).
            templateDao.deleteTemplateById(userId, templateId)
        }

    /**
     * Sprint 1-4 — 9:00 local on the given date: the default due time for
     * tasks created from a template (Feature 13), the same default Plan
     * My Day and the detail sheet use.
     */
    fun planMyDayDefaultDueEpochMillis(date: java.time.LocalDate): Long =
        date.atTime(9, 0).atZone(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    /**
     * Creates a template's tasks together (Feature 13): every draft
     * becomes a task with the SAME due date (picked once by the user),
     * its estimate + priority carried over, its subtasks created under
     * it, and templateId set. No reminders (the user adds them later).
     */
    suspend fun createTasksFromTemplate(
        userId: String,
        template: TaskTemplate,
        dueEpochMillis: Long
    ): List<TaskEntity> = withContext(Dispatchers.IO) {
        val created = mutableListOf<TaskEntity>()
        template.drafts.forEach { draft ->
            val task = createTask(
                userId = userId,
                title = draft.title,
                dueEpochMillis = dueEpochMillis,
                priority = draft.priority,
                templateId = template.id,
                estimatedMinutes = draft.estimatedMinutes
            )
            draft.subtaskTitles.forEach { subtaskTitle ->
                createSubtask(task.id, subtaskTitle)
            }
            created += task
        }
        created
    }

    // ------------------------------------------------------------ attachments

    fun observeAttachments(taskId: String): Flow<List<TaskAttachmentEntity>> =
        attachmentDao.getAttachmentsForTaskFlow(taskId)

    /** Result of adding a file: the saved row, or a user-facing reason. */
    sealed interface AddAttachmentResult {
        data class Added(val attachment: TaskAttachmentEntity) : AddAttachmentResult
        data class Rejected(val reason: String) : AddAttachmentResult
    }

    /**
     * Sprint 1-4 — adds a file to a task (Feature 10).
     *
     * LOCAL-ONLY (locked decision 6): the file is copied into
     * app-private storage (`files/attachments/`); Room holds only its
     * path. Limits are spec-fixed: max 25 MB per file, max 10 files per
     * task — beyond either, the add is BLOCKED with a reason, never
     * silently dropped. A `sizeBytes` of null means "unknown": the file
     * is copied first, then measured, and removed again if too big.
     */
    suspend fun addAttachmentFromStream(
        taskId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long?,
        openStream: () -> InputStream?
    ): AddAttachmentResult = withContext(Dispatchers.IO) {
        val task = taskDao.getTaskById(taskId)
            ?: return@withContext AddAttachmentResult.Rejected("Task not found")
        if (task.groupId != null) {
            return@withContext AddAttachmentResult.Rejected(
                "Group tasks can't have attachments here"
            )
        }
        val existing = attachmentDao.getAttachmentsForTaskOnce(taskId)
        if (existing.size >= MAX_ATTACHMENTS_PER_TASK) {
            return@withContext AddAttachmentResult.Rejected(
                "This task already has $MAX_ATTACHMENTS_PER_TASK attachments"
            )
        }
        if (sizeBytes != null && sizeBytes > MAX_ATTACHMENT_BYTES) {
            return@withContext AddAttachmentResult.Rejected(
                "File is larger than 25 MB"
            )
        }
        val attachmentId = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val safeName = fileName
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeLast(100)
            .ifBlank { "file" }
        val dest = File(dir, "${attachmentId}_$safeName")
        val copiedBytes = try {
            val stream = openStream() ?: return@withContext AddAttachmentResult.Rejected(
                "Couldn't read the file"
            )
            stream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.length()
        } catch (e: Exception) {
            dest.delete()
            return@withContext AddAttachmentResult.Rejected("Couldn't save the file")
        }
        if (copiedBytes > MAX_ATTACHMENT_BYTES) {
            dest.delete()
            return@withContext AddAttachmentResult.Rejected("File is larger than 25 MB")
        }
        val attachment = TaskAttachmentEntity(
            id = attachmentId,
            taskId = taskId,
            userId = task.userId,
            fileName = fileName.substringAfterLast('/').ifBlank { "file" },
            mimeType = mimeType,
            localPath = dest.absolutePath,
            sizeBytes = copiedBytes
        )
        attachmentDao.insertAttachment(attachment)
        AddAttachmentResult.Added(attachment)
    }

    /** Adds a file picked from a content Uri (name/size/mime resolved here). */
    suspend fun addAttachment(taskId: String, uri: Uri): AddAttachmentResult {
        val resolver = context.contentResolver
        var displayName: String? = null
        var size: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return addAttachmentFromStream(
            taskId = taskId,
            fileName = displayName ?: "file",
            mimeType = mime,
            sizeBytes = size,
            openStream = { resolver.openInputStream(uri) }
        )
    }

    /** Removes one attachment: its file first, then its row. */
    suspend fun deleteAttachment(attachmentId: String) = withContext(Dispatchers.IO) {
        val attachment = attachmentDao.getAttachmentById(attachmentId) ?: return@withContext
        File(attachment.localPath).delete()
        attachmentDao.deleteAttachmentById(attachmentId)
    }

    /** Deletes every file (and row) attached to a task. Called before the task row goes. */
    private suspend fun deleteAttachmentFilesForTask(taskId: String) {
        attachmentDao.getAttachmentsForTaskOnce(taskId).forEach { attachment ->
            File(attachment.localPath).delete()
        }
        attachmentDao.deleteAttachmentsForTask(taskId)
    }

    /**
     * Seeds initial tasks matching Figma designs on first launch only if both
     * local Room and remote Firestore are empty.
     */
    suspend fun checkAndSeedInitialData(userId: String) = withContext(Dispatchers.IO) {
        // Production: New users start with a clean slate; no fake tasks or unwanted alarms.
    }

    private fun getTodayBounds(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endOfDay = calendar.timeInMillis

        return Pair(startOfDay, endOfDay)
    }
}

// ------------------------------------------------------------------ S1-4g pure helpers
//
// Board reorder, testable without Room: the visible→full index mapping
// and the My Day plannedStart rule (decision (e2), defined in the PR
// body before implementing).

/**
 * Maps a drop index counted among VISIBLE cards onto the FULL status
 * column (dragged card already removed from [fullColumn]).
 * [visibleOthers] are the target column's visible cards in display
 * order, dragged card excluded; [targetIndex] counts among them.
 *
 * - No visible cards → append at the full column's end (the user gave
 *   no position; the (c1) top-of-Done exception lives in the caller).
 * - targetIndex past the last visible card → just after that card in
 *   the full column (hidden cards after it stay after it).
 * - Otherwise → just before the visible card at targetIndex.
 * A visible card the full column no longer has (stale UI list) falls
 * back to appending — never a crash, never a wrong-column write.
 */
/**
 * Maps the Board's visible drop index onto the FULL status column.
 *
 * [columnWithoutDragged] is the target column in board order with the
 * dragged card already removed — the returned index is an insertion
 * position in THAT list (applyBoardMove inserts into the same list).
 * [visibleOthers] is the UI's visible subset of it, in display order;
 * [targetIndex] counts how many of those sit above the pointer.
 * A stale visible id falls back to append; an empty visible list
 * appends at the column's end.
 */
internal fun mapVisibleIndexToFullIndex(
    columnWithoutDragged: List<TaskEntity>,
    visibleOthers: List<TaskEntity>,
    targetIndex: Int
): Int {
    if (visibleOthers.isEmpty()) return columnWithoutDragged.size
    if (targetIndex >= visibleOthers.size) {
        val lastVisible = visibleOthers.last()
        val fullPos = columnWithoutDragged.indexOfFirst { it.id == lastVisible.id }
        return if (fullPos < 0) columnWithoutDragged.size else fullPos + 1
    }
    val anchor = visibleOthers[targetIndex.coerceAtLeast(0)]
    val fullPos = columnWithoutDragged.indexOfFirst { it.id == anchor.id }
    return if (fullPos < 0) columnWithoutDragged.size else fullPos
}

/** Local midnight starting the day that holds [millis]. */
internal fun startOfDayMillis(millis: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = millis
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/** Local 23:59:59.999 ending the day that holds [millis]. */
internal fun endOfDayMillis(millis: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = millis
    calendar.set(Calendar.HOUR_OF_DAY, 23)
    calendar.set(Calendar.MINUTE, 59)
    calendar.set(Calendar.SECOND, 59)
    calendar.set(Calendar.MILLISECOND, 999)
    return calendar.timeInMillis
}

/**
 * My Day (e2) — what a drop does to plannedStart. The My Day display
 * order IS plannedStart order, so honouring a drop position means
 * re-slotting the day: the dragged card takes a new plannedStart
 * consistent with its new position among the target column's visible
 * planned items ([visiblePlannedStarts], display order, dragged card
 * excluded; null = that card has no planned time).
 *
 * - Between two timed neighbours → their midpoint.
 * - Above the first timed card → 30 min before it, clamped to that
 *   day's start (never the previous day).
 * - Below the last timed card → 30 min after it, clamped to that
 *   day's end (never the next day).
 * - No timed neighbour at all → [fallback] (the card's own value —
 *   the drop cannot place it in time, so nothing changes).
 * Other cards' plannedStart values are never touched; no time slot is
 * invented or dropped. A dropped card keeps a valid time on its
 * planned day, so the Timeline never loses it.
 */
internal fun resolvePlannedStartForDrop(
    visiblePlannedStarts: List<Long?>,
    targetIndex: Int,
    fallback: Long?
): Long? {
    val clampedIndex = targetIndex.coerceIn(0, visiblePlannedStarts.size)
    val prev = visiblePlannedStarts.take(clampedIndex).lastOrNull { it != null }
    val next = visiblePlannedStarts.drop(clampedIndex).firstOrNull { it != null }
    return when {
        prev != null && next != null -> (prev + next) / 2
        next != null -> maxOf(next - 30 * 60 * 1000L, startOfDayMillis(next))
        prev != null -> minOf(prev + 30 * 60 * 1000L, endOfDayMillis(prev))
        else -> fallback
    }
}
