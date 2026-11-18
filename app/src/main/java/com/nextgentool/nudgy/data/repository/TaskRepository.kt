package com.nextgentool.nudgy.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
        if (completed) {
            reminderManager.cancelReminder(task.id)
            maybeCreateNextOccurrence(task)
        }
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
