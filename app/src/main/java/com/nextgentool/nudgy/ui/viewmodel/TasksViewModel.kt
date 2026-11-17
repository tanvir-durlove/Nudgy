package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.data.local.entity.ProjectEntity
import com.nextgentool.nudgy.data.local.entity.TaskTagCrossRef
import com.nextgentool.nudgy.data.local.entity.SavedViewEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.model.SavedViewFilter
import com.nextgentool.nudgy.domain.usecase.AddSubtaskUseCase
import com.nextgentool.nudgy.domain.usecase.CreateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.DeleteTaskUseCase
import com.nextgentool.nudgy.domain.usecase.SaveViewUseCase
import com.nextgentool.nudgy.domain.usecase.ToggleTaskCompletionUseCase
import com.nextgentool.nudgy.domain.usecase.UpdateTaskUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row in the Tasks list: the task plus everything its row shows. */
data class TaskListItem(
    val task: TaskEntity,
    val tags: List<TagEntity> = emptyList(),
    val project: ProjectEntity? = null,
    val subtaskDone: Int = 0,
    val subtaskTotal: Int = 0
)

/** The open detail sheet's data, derived from the same flows as the list. */
data class TaskDetailState(
    val task: TaskEntity,
    val subtasks: List<TaskEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList()
)

data class TasksUiState(
    val isLoading: Boolean = true,
    /** Filtered top-level tasks in display order (active first, then done). */
    val items: List<TaskListItem> = emptyList(),
    val totalCount: Int = 0,
    val tags: List<TagEntity> = emptyList(),
    val projects: List<ProjectEntity> = emptyList(),
    val savedViews: List<SavedViewEntity> = emptyList(),
    val filter: SavedViewFilter = SavedViewFilter.empty(),
    val activeViewId: String? = null,
    val selectedDetail: TaskDetailState? = null,
    /** The task just created — its inline due/priority row shows under QuickAdd. */
    val lastCreatedTaskId: String? = null,
    /** Double-tap guard: a create is in flight. */
    val isCreating: Boolean = false
)

/**
 * Sprint 1-1 — Tasks screen ViewModel (Features 1–2).
 *
 * One combined flow turns the repository's five streams into the whole
 * screen state. Filtering and search run in memory over the loaded list
 * (title / description / tag name): the list is small, the work is
 * trivial, and it keeps filter + search in exactly one place — the
 * [SavedViewFilter] — so a saved view captures both.
 *
 * Display order: active tasks by due date (no-date last), then completed
 * tasks by completion time. Group rows (groupId != null) appear with a
 * badge and are read-only here — no edit/delete; toggling completion is
 * local-only, as in v1.
 */
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val createTaskUseCase: CreateTaskUseCase,
    private val toggleCompletion: ToggleTaskCompletionUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val deleteTask: DeleteTaskUseCase,
    private val addSubtask: AddSubtaskUseCase,
    private val saveView: SaveViewUseCase
) : ViewModel() {

    private val userId: String = repository.currentUserId()

    private val filterFlow = MutableStateFlow(SavedViewFilter.empty())
    private val activeViewIdFlow = MutableStateFlow<String?>(null)
    private val selectedTaskIdFlow = MutableStateFlow<String?>(null)
    private val lastCreatedTaskIdFlow = MutableStateFlow<String?>(null)
    private val isCreatingFlow = MutableStateFlow(false)

    /**
     * The 10 streams arrive in two 5-flow combines: a single 10-flow
     * combine does not resolve under this Kotlin version (the compiler
     * picks the vararg overload and the lambda parameters lose their
     * types — CI compileReleaseKotlin, 2026-11-16). Two 5-flow combines
     * plus one 2-flow combine resolve cleanly.
     */
    private data class TasksData(
        val allTasks: List<TaskEntity>,
        val tags: List<TagEntity>,
        val crossRefs: List<TaskTagCrossRef>,
        val projects: List<ProjectEntity>,
        val savedViews: List<SavedViewEntity>
    )

    private data class TasksUi(
        val filter: SavedViewFilter,
        val activeViewId: String?,
        val selectedTaskId: String?,
        val lastCreatedTaskId: String?,
        val isCreating: Boolean
    )

    val uiState: StateFlow<TasksUiState> = combine(
        combine(
            repository.observeAllTasks(userId),
            repository.observeTags(userId),
            repository.observeAllTaskTags(),
            repository.observeProjects(userId),
            repository.observeSavedViews(userId)
        ) { allTasks, tags, crossRefs, projects, savedViews ->
            TasksData(allTasks, tags, crossRefs, projects, savedViews)
        },
        combine(
            filterFlow,
            activeViewIdFlow,
            selectedTaskIdFlow,
            lastCreatedTaskIdFlow,
            isCreatingFlow
        ) { filter, activeViewId, selectedTaskId, lastCreatedTaskId, isCreating ->
            TasksUi(filter, activeViewId, selectedTaskId, lastCreatedTaskId, isCreating)
        }
    ) { data, ui ->
        val (allTasks, tags, crossRefs, projects, savedViews) = data
        val (filter, activeViewId, selectedTaskId, lastCreatedTaskId, isCreating) = ui
        val tagsById = tags.associateBy { it.id }
        val tagIdsByTask = crossRefs.groupBy({ it.taskId }, { it.tagId })
            .mapValues { it.value.toSet() }
        val projectsById = projects.associateBy { it.id }
        val topLevel = allTasks.filter { it.parentTaskId == null }
        val subtasksByParent = allTasks.filter { it.parentTaskId != null }
            .groupBy { it.parentTaskId!! }

        val query = filter.text.trim()
        val items = topLevel
            .filter { task ->
                val taskTagIds = tagIdsByTask[task.id].orEmpty()
                if (!filter.matches(task, taskTagIds)) return@filter false
                if (query.isEmpty()) return@filter true
                if (task.title.contains(query, ignoreCase = true)) return@filter true
                if (task.description?.contains(query, ignoreCase = true) == true) return@filter true
                taskTagIds.any { (tagsById[it]?.name ?: "").contains(query, ignoreCase = true) }
            }
            .map { task ->
                val subs = subtasksByParent[task.id].orEmpty()
                TaskListItem(
                    task = task,
                    tags = tagIdsByTask[task.id].orEmpty().mapNotNull { tagsById[it] },
                    project = task.projectId?.let { projectsById[it] },
                    subtaskDone = subs.count { it.isCompleted },
                    subtaskTotal = subs.size
                )
            }
            .sortedWith(
                compareBy<TaskListItem> { it.task.isCompleted }
                    .thenBy { if (it.task.isCompleted) 0L else dueSortKey(it.task) }
                    .thenByDescending { it.task.completedAtEpochMillis ?: 0L }
            )

        val selectedTask = selectedTaskId?.let { id -> allTasks.firstOrNull { it.id == id } }
        val selectedDetail = selectedTask?.let {
            TaskDetailState(
                task = it,
                subtasks = subtasksByParent[it.id].orEmpty()
                    .sortedBy { sub -> sub.createdAtEpochMillis },
                tags = tagIdsByTask[it.id].orEmpty().mapNotNull { tagId -> tagsById[tagId] }
            )
        }

        TasksUiState(
            isLoading = false,
            items = items,
            totalCount = topLevel.size,
            tags = tags,
            projects = projects,
            savedViews = savedViews,
            filter = filter,
            activeViewId = activeViewId,
            selectedDetail = selectedDetail,
            lastCreatedTaskId = lastCreatedTaskId,
            isCreating = isCreating
        )
        }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TasksUiState()
    )

    /** No-date tasks sort after every dated task. */
    private fun dueSortKey(task: TaskEntity): Long =
        if (task.dueDateEpochMillis == TaskRepository.NO_DUE_DATE) Long.MAX_VALUE
        else task.dueDateEpochMillis

    // ------------------------------------------------------------ quick add

    fun createTask(title: String) {
        if (isCreatingFlow.value) return // double-tap guard
        viewModelScope.launch {
            isCreatingFlow.value = true
            try {
                val created = createTaskUseCase(title)
                lastCreatedTaskIdFlow.value = created?.id
            } finally {
                isCreatingFlow.value = false
            }
        }
    }

    /** Inline due/priority for the just-created task (Feature 1, no sheet). */
    fun setLastCreatedDue(dueEpochMillis: Long) {
        val id = lastCreatedTaskIdFlow.value ?: return
        val task = uiState.value.items.firstOrNull { it.task.id == id }?.task ?: return
        viewModelScope.launch { updateTask(task.copy(dueDateEpochMillis = dueEpochMillis)) }
    }

    fun setLastCreatedPriority(priority: Priority) {
        val id = lastCreatedTaskIdFlow.value ?: return
        val task = uiState.value.items.firstOrNull { it.task.id == id }?.task ?: return
        viewModelScope.launch { updateTask(task.copy(priority = priority)) }
    }

    fun clearLastCreated() {
        lastCreatedTaskIdFlow.value = null
    }

    // ------------------------------------------------------------ task actions

    fun toggleTask(task: TaskEntity) {
        viewModelScope.launch { toggleCompletion(task) }
    }

    fun deleteTask(task: TaskEntity) {
        if (task.groupId != null) return // group rows are read-only here (Sprint 5)
        viewModelScope.launch {
            deleteTask(task)
            if (selectedTaskIdFlow.value == task.id) selectedTaskIdFlow.value = null
        }
    }

    fun selectTask(taskId: String) {
        selectedTaskIdFlow.value = taskId
    }

    fun dismissDetail() {
        selectedTaskIdFlow.value = null
    }

    fun updateSelectedTask(task: TaskEntity) {
        if (task.groupId != null) return // group rows are read-only here
        viewModelScope.launch { updateTask(task) }
    }

    fun setTaskStatus(task: TaskEntity, status: TaskStatus) {
        viewModelScope.launch { repository.setTaskStatus(task, status) }
    }

    // ------------------------------------------------------------- subtasks

    fun addSubtask(parentId: String, title: String) {
        viewModelScope.launch { addSubtask(parentId, title) }
    }

    fun toggleSubtask(subtask: TaskEntity) {
        viewModelScope.launch { repository.toggleSubtask(subtask) }
    }

    fun deleteSubtask(subtaskId: String) {
        viewModelScope.launch { repository.deleteSubtask(subtaskId) }
    }

    // ------------------------------------------------------- tags / projects

    fun createTag(name: String, color: String) {
        viewModelScope.launch { repository.createTag(userId, name, color) }
    }

    fun renameTag(tag: TagEntity, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.updateTag(tag.copy(name = name.trim())) }
    }

    fun deleteTag(tagId: String) {
        viewModelScope.launch { repository.deleteTag(userId, tagId) }
    }

    fun setTaskTags(taskId: String, tagIds: List<String>) {
        viewModelScope.launch { repository.setTaskTags(taskId, tagIds) }
    }

    fun createProject(name: String, color: String) {
        viewModelScope.launch { repository.createProject(userId, name, color) }
    }

    fun renameProject(project: ProjectEntity, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.updateProject(project.copy(name = name.trim())) }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch { repository.deleteProject(userId, projectId) }
    }

    // ------------------------------------------------------- filter / search

    fun setFilter(filter: SavedViewFilter) {
        filterFlow.value = filter
        activeViewIdFlow.value = null
    }

    fun setSearchText(text: String) {
        filterFlow.value = filterFlow.value.copy(text = text)
        activeViewIdFlow.value = null
    }

    fun clearFilter() {
        filterFlow.value = SavedViewFilter.empty()
        activeViewIdFlow.value = null
    }

    fun applySavedView(view: SavedViewEntity) {
        filterFlow.value = SavedViewFilter.fromJson(view.filterJson)
        activeViewIdFlow.value = view.id
    }

    fun saveCurrentView(name: String) {
        viewModelScope.launch {
            val saved = saveView(name, filterFlow.value)
            if (saved != null) activeViewIdFlow.value = saved.id
        }
    }

    fun deleteSavedView(viewId: String) {
        viewModelScope.launch {
            repository.deleteSavedView(userId, viewId)
            if (activeViewIdFlow.value == viewId) {
                activeViewIdFlow.value = null
                filterFlow.value = SavedViewFilter.empty()
            }
        }
    }
}
