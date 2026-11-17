package com.nextgentool.nudgy.domain.model

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * Sprint 1-1 — a saved Tasks filter + search setup (Feature 2).
 *
 * Stored as JSON in saved_views.filterJson. Every key is optional: a
 * missing key means "no constraint on this dimension". The schema is
 * versioned only by tolerance — [fromJson] returns an empty filter for
 * any unreadable document instead of crashing, so an old or hand-edited
 * row can never break the Tasks screen.
 *
 * Schema:
 * ```
 * {
 *   "projectId": "<uuid>" | null,
 *   "tagIds": ["<uuid>", ...],
 *   "priority": "HIGH" | "MEDIUM" | "LOW" | null,
 *   "status": "TODO" | "IN_PROGRESS" | "DONE" | null,
 *   "groupOnly": false,
 *   "text": "<search string>"
 * }
 * ```
 * Text matching (title / description / tag name) is applied by the
 * ViewModel — it owns the tag-name map this class does not have — while
 * [matches] covers the structured dimensions.
 */
@JsonClass(generateAdapter = true)
data class SavedViewFilter(
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
    val priority: Priority? = null,
    val status: TaskStatus? = null,
    val groupOnly: Boolean = false,
    val text: String = ""
) {
    /** True when the task passes every structured constraint. */
    fun matches(task: TaskEntity, taskTagIds: Set<String>): Boolean {
        if (projectId != null && task.projectId != projectId) return false
        if (tagIds.isNotEmpty() && !tagIds.all { it in taskTagIds }) return false
        if (priority != null && task.priority != priority) return false
        if (status != null && task.status != status) return false
        if (groupOnly && task.groupId == null) return false
        return true
    }

    /** True when no constraint is set at all (the "All tasks" state). */
    fun isEmpty(): Boolean =
        projectId == null && tagIds.isEmpty() && priority == null &&
            status == null && !groupOnly && text.isBlank()

    fun toJson(): String = ADAPTER.toJson(this)

    companion object {
        private val ADAPTER = Moshi.Builder().build().adapter(SavedViewFilter::class.java)

        fun fromJson(json: String): SavedViewFilter = try {
            ADAPTER.fromJson(json) ?: SavedViewFilter()
        } catch (e: Exception) {
            SavedViewFilter()
        }

        fun empty(): SavedViewFilter = SavedViewFilter()
    }
}
