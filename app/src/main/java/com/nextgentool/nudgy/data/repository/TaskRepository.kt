package com.nextgentool.nudgy.data.repository

import android.content.Context
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.ProjectEntity
import com.nextgentool.nudgy.data.local.entity.SavedViewEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.reminders.ReminderManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    private val reminderManager = ReminderManager(context)

    companion object {
        /** dueDateEpochMillis value meaning "no due date" (Feature 1). */
        const val NO_DUE_DATE: Long = 0L
    }

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
        estimatedMinutes: Int? = null
    ): TaskEntity = withContext(Dispatchers.IO) {
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
            estimatedMinutes = estimatedMinutes
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
        val resolved = task.copy(
            reminderEpochMillis = resolvedReminder,
            isCompleted = resolvedIsCompleted,
            completedAtEpochMillis = resolvedCompletedAt,
            updatedAtEpochMillis = System.currentTimeMillis(),
            isSyncedToCloud = if (task.groupId != null) task.isSyncedToCloud else false
        )
        taskDao.updateTask(resolved)
        if (resolvedReminder != null) {
            if (resolvedReminder > System.currentTimeMillis()) {
                reminderManager.scheduleReminder(task.id, task.title, resolvedReminder)
            } else {
                reminderManager.cancelReminder(task.id)
            }
        } else {
            reminderManager.cancelReminder(task.id)
        }
    }

    /** Flip completion — and the status with it (DONE ⇔ isCompleted). */
    suspend fun toggleTaskCompletion(task: TaskEntity) = withContext(Dispatchers.IO) {
        val newCompleted = !task.isCompleted
        val newStatus = if (newCompleted) TaskStatus.DONE else TaskStatus.TODO
        val completedAt = if (newCompleted) System.currentTimeMillis() else null
        val now = System.currentTimeMillis()
        // Group v1: member completion is Room-local ONLY.
        if (task.groupId != null) {
            taskDao.setTaskStatusAndCompletionLocal(
                userId = task.userId,
                id = task.id,
                status = newStatus.name,
                isCompleted = newCompleted,
                completedAt = completedAt,
                now = now
            )
        } else {
            taskDao.setTaskStatusAndCompletionForUser(
                userId = task.userId,
                id = task.id,
                status = newStatus.name,
                isCompleted = newCompleted,
                completedAt = completedAt,
                now = now
            )
        }
        if (newCompleted) {
            reminderManager.cancelReminder(task.id)
        } else if (task.reminderSet) {
            val trigger = ReminderManager.dueReminderTriggerMillis(task.reminderEpochMillis, task.dueDateEpochMillis)
            if (trigger > System.currentTimeMillis()) {
                reminderManager.scheduleReminder(task.id, task.title, trigger)
            }
        }
    }

    /** Set the Board/status column directly (S1-4 drag); completion follows. */
    suspend fun setTaskStatus(task: TaskEntity, status: TaskStatus) = withContext(Dispatchers.IO) {
        val completed = status == TaskStatus.DONE
        val completedAt = if (completed) System.currentTimeMillis() else null
        val now = System.currentTimeMillis()
        if (task.groupId != null) {
            taskDao.setTaskStatusAndCompletionLocal(
                userId = task.userId,
                id = task.id,
                status = status.name,
                isCompleted = completed,
                completedAt = completedAt,
                now = now
            )
        } else {
            taskDao.setTaskStatusAndCompletionForUser(
                userId = task.userId,
                id = task.id,
                status = status.name,
                isCompleted = completed,
                completedAt = completedAt,
                now = now
            )
        }
        if (completed) reminderManager.cancelReminder(task.id)
    }

    suspend fun deleteTask(userId: String, taskId: String) = withContext(Dispatchers.IO) {
        reminderManager.cancelReminder(taskId)
        // Group v1: group rows are deleted from the shared group item by the
        // creator (GroupRepository). A member-side delete is local-only.
        // Either way the row goes; task_tags / attachments rows follow via
        // the FK cascades.
        taskDao.deleteTaskByIdForUser(userId, taskId)
    }

    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        reminderManager.cancelReminder(taskId)
        val task = taskDao.getTaskById(taskId)
        if (task != null) {
            taskDao.deleteTaskByIdForUser(task.userId, taskId)
        } else {
            taskDao.deleteTaskById(taskId)
        }
    }

    suspend fun snoozeTask(taskId: String, snoozeEpochMillis: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Group v1: snoozing a group task is a local-only change (same rule
        // as completion) — never dirty-mark a group row.
        val existing = taskDao.getTaskById(taskId)
        if (existing?.groupId != null) {
            taskDao.snoozeTaskLocal(
                id = taskId,
                dueDate = snoozeEpochMillis,
                reminderDate = snoozeEpochMillis,
                now = now
            )
        } else {
            taskDao.snoozeTask(
                id = taskId,
                dueDate = snoozeEpochMillis,
                reminderDate = snoozeEpochMillis,
                now = now
            )
        }
        val task = taskDao.getTaskById(taskId)
        if (task != null) {
            reminderManager.scheduleReminder(task.id, task.title, snoozeEpochMillis)
        }
    }

    suspend fun clearCompletedTasks(userId: String) = withContext(Dispatchers.IO) {
        // Group v1: completed group rows are NOT cleared here — the shared
        // group item still exists, so a listener/poll tick would resurrect
        // them. Only personal completed tasks are purged.
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
