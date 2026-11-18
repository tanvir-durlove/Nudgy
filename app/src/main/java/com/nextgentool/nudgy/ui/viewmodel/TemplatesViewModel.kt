package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.model.TaskTemplate
import com.nextgentool.nudgy.domain.model.TemplateDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val repository: TaskRepository
) : ViewModel() {

    private val userId: String = repository.currentUserId()

    private val _templates = MutableStateFlow<List<TaskTemplate>>(emptyList())
    val templates: StateFlow<List<TaskTemplate>> = _templates.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedBuiltInTemplates(userId)
            repository.observeTemplates(userId).collect { _templates.value = it }
        }
    }

    fun createTemplate(name: String, drafts: List<TemplateDraft>) {
        viewModelScope.launch {
            repository.createTemplate(userId, name, drafts)
        }
    }

    fun updateTemplate(template: TaskTemplate) {
        viewModelScope.launch {
            if (template.isBuiltIn) return@launch
            repository.updateTemplate(userId, template)
        }
    }

    fun deleteTemplate(template: TaskTemplate) {
        viewModelScope.launch {
            if (template.isBuiltIn) return@launch
            // Deleting a template never deletes the tasks it created.
            repository.deleteTemplate(userId, template.id)
        }
    }

    fun createTasksFromTemplate(template: TaskTemplate, dueEpochMillis: Long) {
        viewModelScope.launch {
            repository.createTasksFromTemplate(userId, template, dueEpochMillis)
        }
    }

    /** 9:00 local on the given date — the same default Plan My Day uses. */
    fun dueEpochMillisFor(date: java.time.LocalDate): Long =
        repository.planMyDayDefaultDueEpochMillis(date)
}
