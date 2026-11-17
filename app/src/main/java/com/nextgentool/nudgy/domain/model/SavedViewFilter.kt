package com.nextgentool.nudgy.domain.model

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

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
 *
 * JSON is written and read by hand with Moshi's streaming API
 * ([JsonWriter]/[JsonReader]) — deliberately NOT with a Moshi adapter.
 * A reflective adapter (`Moshi.Builder().build().adapter(...)`) breaks
 * under R8, and so does a `@JsonClass(generateAdapter = true)` adapter:
 * Moshi finds the generated adapter by reflection (Class.forName), R8
 * sees no static reference and strips it as dead code (the generated
 * SavedViewFilterJsonAdapter was absent from the ci-345 mapping.txt),
 * Moshi then falls back to the reflective ClassJsonAdapter, and its
 * EnumJsonAdapter does Class.getField("LOW") on the obfuscated enum —
 * which crashed the app at launch on ci-345 (SavedViewFilter.<clinit>).
 * The streaming API uses no reflection, so R8 can never break it, and
 * the stored format is byte-identical to what the Moshi adapter wrote
 * (same keys, same order, null fields omitted, enum names).
 * (Moshi's default is to omit nulls — JsonWriter.serializeNulls is
 * false unless opted in — and the old adapter did the same, so the
 * bytes match; fromJson accepts both omitted keys and explicit nulls.)
 */
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

    /** Manual JSON — same format the Moshi adapter wrote (see class KDoc). */
    fun toJson(): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginObject()
        writer.name("projectId")
        if (projectId == null) writer.nullValue() else writer.value(projectId)
        writer.name("tagIds")
        writer.beginArray()
        tagIds.forEach { writer.value(it) }
        writer.endArray()
        writer.name("priority")
        if (priority == null) writer.nullValue() else writer.value(priority.name)
        writer.name("status")
        if (status == null) writer.nullValue() else writer.value(status.name)
        writer.name("groupOnly")
        writer.value(groupOnly)
        writer.name("text")
        writer.value(text)
        writer.endObject()
        writer.close()
        return buffer.readUtf8()
    }

    companion object {
        fun fromJson(json: String): SavedViewFilter = try {
            val reader = JsonReader.of(Buffer().writeUtf8(json))
            reader.beginObject()
            var projectId: String? = null
            var tagIds: List<String> = emptyList()
            var priority: Priority? = null
            var status: TaskStatus? = null
            var groupOnly: Boolean = false
            var text: String = ""
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "projectId" -> projectId = reader.nextNullableString()
                    "tagIds" -> tagIds = reader.nextStringList()
                    "priority" -> priority = reader.nextNullableEnum { Priority.valueOf(it) }
                    "status" -> status = reader.nextNullableEnum { TaskStatus.valueOf(it) }
                    "groupOnly" -> groupOnly = reader.nextNullableBoolean() ?: false
                    "text" -> text = reader.nextNullableString() ?: ""
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            SavedViewFilter(projectId, tagIds, priority, status, groupOnly, text)
        } catch (e: Exception) {
            SavedViewFilter()
        }

        fun empty(): SavedViewFilter = SavedViewFilter()
    }
}

/** Null JSON value -> null; anything else must be a string. */
private fun JsonReader.nextNullableString(): String? =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        null
    } else {
        nextString()
    }

/** Null JSON value -> null; anything else must be a boolean. */
private fun JsonReader.nextNullableBoolean(): Boolean? =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        null
    } else {
        nextBoolean()
    }

/** Null JSON value -> empty list; otherwise an array of strings. */
private fun JsonReader.nextStringList(): List<String> =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        emptyList()
    } else {
        val list = mutableListOf<String>()
        beginArray()
        while (hasNext()) list += nextString()
        endArray()
        list
    }

/** Unknown enum name degrades to null for that field only. */
private fun <E : Enum<E>> JsonReader.nextNullableEnum(valueOf: (String) -> E): E? =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        null
    } else {
        runCatching { valueOf(nextString()) }.getOrNull()
    }
