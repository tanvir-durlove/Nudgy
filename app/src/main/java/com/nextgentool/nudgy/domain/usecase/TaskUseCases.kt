package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.model.SavedViewFilter
import javax.inject.Inject

/**
 * Sprint 1-1 — domain UseCases for Features 1–2.
 *
 * Thin on purpose: the Repository owns the write path (status ⇔
 * isCompleted, reminder scheduling, group-local rules). A UseCase only
 * validates its input and names the operation the UI means — so the
 * ViewModel reads as user intent, not as DAO calls.
 */
class CreateTaskUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    /**
     * Creates a task from a title. A blank title never saves (the UI
     * disables the button, and this is the backstop). No due date means
     * [TaskRepository.NO_DUE_DATE] — the task lands in "No date", never
     * in Today.
     */
    suspend operator fun invoke(
        title: String,
        dueEpochMillis: Long = TaskRepository.NO_DUE_DATE,
        priority: Priority = Priority.MEDIUM,
        description: String? = null,
        parentTaskId: String? = null,
        projectId: String? = null,
        templateId: String? = null,
        estimatedMinutes: Int? = null
    ): TaskEntity? {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return null
        return repository.createTask(
            userId = repository.currentUserId(),
            title = cleanTitle,
            description = description?.trim()?.ifBlank { null },
            dueEpochMillis = dueEpochMillis,
            priority = priority,
            category = TaskCategory.WORK,
            parentTaskId = parentTaskId,
            projectId = projectId,
            templateId = templateId,
            estimatedMinutes = estimatedMinutes
        )
    }
}

class ToggleTaskCompletionUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    suspend operator fun invoke(task: TaskEntity) {
        repository.toggleTaskCompletion(task)
    }
}

class UpdateTaskUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    suspend operator fun invoke(task: TaskEntity) {
        repository.updateTask(task)
    }
}

class DeleteTaskUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    suspend operator fun invoke(task: TaskEntity) {
        repository.deleteTask(task.userId, task.id)
    }
}

class SetTaskStatusUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    suspend operator fun invoke(task: TaskEntity, status: TaskStatus) {
        repository.setTaskStatus(task, status)
    }
}

class AddSubtaskUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    /** Returns null for a blank title; the repository rejects nesting. */
    suspend operator fun invoke(parentId: String, title: String): TaskEntity? {
        if (title.isBlank()) return null
        return repository.createSubtask(parentId, title.trim())
    }
}

class SaveViewUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    /** Returns null for a blank name; otherwise the saved view. */
    suspend operator fun invoke(name: String, filter: SavedViewFilter) =
        if (name.isBlank()) {
            null
        } else {
            repository.saveView(
                userId = repository.currentUserId(),
                name = name.trim(),
                filterJson = filter.toJson()
            )
        }
}
