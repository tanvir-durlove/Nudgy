package com.nextgentool.nudgy.settings

import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.NudgyMemoryEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.local.entity.TaskTagCrossRef
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.model.TaskStatus
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer
import java.util.UUID

/**
 * Sprint 6-6 (step 4b) — one JSON export document: every task +
 * schedule (calendar event) + memory for one user, each record with
 * its real id so step 4c's restore can id-merge without duplicates.
 * Step 2-1 added the user's tags + the task↔tag links (schema v2):
 * a restore brings the tags back; a v1 file (no tags keys) still
 * reads, with empty tags.
 *
 * Schema (v2):
 * ```
 * {
 *   "schemaVersion": 2,
 *   "exportedAtEpochMillis": 1732...,
 *   "tasks": [ { <every TaskEntity field> }, ... ],
 *   "schedules": [ { <every CalendarEventEntity field> }, ... ],
 *   "memories": [ { <every NudgyMemoryEntity field> }, ... ],
 *   "tags": [ { <every TagEntity field> }, ... ],
 *   "taskTags": [ { "taskId": ..., "tagId": ... }, ... ]
 * }
 * ```
 *
 * JSON is written and read by hand with Moshi's streaming API
 * ([JsonWriter]/[JsonReader]) — deliberately NOT with a Moshi
 * adapter. A reflective adapter breaks under R8, and so does a
 * `@JsonClass(generateAdapter = true)` adapter (see SavedViewFilter's
 * KDoc for the ci-345 crash this caused). The streaming API uses no
 * reflection, so R8 can never break it. Every key is always written
 * (nulls as explicit nulls, same as SavedViewFilter); the reader
 * accepts omitted keys and explicit nulls, skips unknown keys, and
 * degrades an unknown enum name to that field's default — so a
 * hand-edited file can never crash the restore.
 *
 * [fromJson] returns null for anything that is not a readable Nudgy
 * export: malformed JSON, a missing schema version, or a version
 * newer than [CURRENT_SCHEMA_VERSION] (a newer app wrote it; this
 * build must not guess at its shape). Step 4c treats null as
 * "not an export file".
 *
 * Pure logic, plain-JUnit testable (no Android APIs, no Room): the
 * SAF file-picker path that writes these bytes to a user-chosen
 * document lives in [DataExporter] and is device-gated.
 */
data class NudgyDataExport(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtEpochMillis: Long = 0L,
    val tasks: List<TaskEntity> = emptyList(),
    val schedules: List<CalendarEventEntity> = emptyList(),
    val memories: List<NudgyMemoryEntity> = emptyList(),
    /** Step 2-1 — the user's tags (schema v2). Absent in v1 files. */
    val tags: List<TagEntity> = emptyList(),
    /** Step 2-1 — the task↔tag links of the exported tasks (schema v2). */
    val taskTags: List<TaskTagCrossRef> = emptyList()
) {
    /** Manual JSON — same streaming pattern as SavedViewFilter. */
    fun toJson(): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginObject()
        writer.nameInt("schemaVersion", schemaVersion)
        writer.nameLong("exportedAtEpochMillis", exportedAtEpochMillis)
        writer.name("tasks")
        writer.beginArray()
        tasks.forEach { it.writeTask(writer) }
        writer.endArray()
        writer.name("schedules")
        writer.beginArray()
        schedules.forEach { it.writeSchedule(writer) }
        writer.endArray()
        writer.name("memories")
        writer.beginArray()
        memories.forEach { it.writeMemory(writer) }
        writer.endArray()
        writer.name("tags")
        writer.beginArray()
        tags.forEach { it.writeTag(writer) }
        writer.endArray()
        writer.name("taskTags")
        writer.beginArray()
        taskTags.forEach { it.writeTaskTag(writer) }
        writer.endArray()
        writer.endObject()
        writer.close()
        return buffer.readUtf8()
    }

    companion object {
        /** The schema version this build writes; older versions still read (v1 files read with empty tags). */
        const val CURRENT_SCHEMA_VERSION: Int = 2

        fun fromJson(json: String): NudgyDataExport? = try {
            val reader = JsonReader.of(Buffer().writeUtf8(json))
            reader.beginObject()
            var schemaVersion: Int? = null
            var exportedAtEpochMillis: Long = 0L
            var tasks: List<TaskEntity> = emptyList()
            var schedules: List<CalendarEventEntity> = emptyList()
            var memories: List<NudgyMemoryEntity> = emptyList()
            var tags: List<TagEntity> = emptyList()
            var taskTags: List<TaskTagCrossRef> = emptyList()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "schemaVersion" -> schemaVersion = reader.nextNullableInt()
                    "exportedAtEpochMillis" ->
                        exportedAtEpochMillis = reader.nextNullableLong() ?: 0L
                    "tasks" -> tasks = reader.readTaskList()
                    "schedules" -> schedules = reader.readScheduleList()
                    "memories" -> memories = reader.readMemoryList()
                    // Step 2-1 (schema v2) — absent in v1 files, which
                    // then read as empty lists (see the defaults above).
                    "tags" -> tags = reader.readTagList()
                    "taskTags" -> taskTags = reader.readTaskTagList()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (schemaVersion == null || schemaVersion > CURRENT_SCHEMA_VERSION) {
                null
            } else {
                NudgyDataExport(
                    schemaVersion = schemaVersion,
                    exportedAtEpochMillis = exportedAtEpochMillis,
                    tasks = tasks,
                    schedules = schedules,
                    memories = memories,
                    tags = tags,
                    taskTags = taskTags
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Every TaskEntity field, in entity-declaration order. */
private fun TaskEntity.writeTask(writer: JsonWriter) {
    writer.beginObject()
    writer.nameString("id", id)
    writer.nameString("userId", userId)
    writer.nameString("title", title)
    writer.nameString("description", description)
    writer.nameLong("dueDateEpochMillis", dueDateEpochMillis)
    writer.nameEnum("priority", priority)
    writer.nameEnum("category", category)
    writer.nameBoolean("isCompleted", isCompleted)
    writer.nameLong("completedAtEpochMillis", completedAtEpochMillis)
    writer.nameBoolean("reminderSet", reminderSet)
    writer.nameLong("reminderEpochMillis", reminderEpochMillis)
    writer.nameString("recurrenceRule", recurrenceRule)
    writer.nameBoolean("isSyncedToCloud", isSyncedToCloud)
    writer.nameLong("createdAtEpochMillis", createdAtEpochMillis)
    writer.nameLong("updatedAtEpochMillis", updatedAtEpochMillis)
    writer.nameString("groupId", groupId)
    writer.nameEnum("status", status)
    writer.nameLong("plannedStartEpochMillis", plannedStartEpochMillis)
    writer.nameString("plannedDate", plannedDate)
    writer.nameInt("estimatedMinutes", estimatedMinutes)
    writer.nameInt("actualMinutes", actualMinutes)
    writer.nameString("parentTaskId", parentTaskId)
    writer.nameString("projectId", projectId)
    writer.nameString("carriedOverFromDate", carriedOverFromDate)
    writer.nameString("templateId", templateId)
    writer.nameInt("reminderRepeatMinutes", reminderRepeatMinutes)
    writer.nameInt("boardOrder", boardOrder)
    writer.nameString("assigneeUid", assigneeUid)
    writer.nameString("assigneeName", assigneeName)
    writer.nameBoolean("isSample", isSample)
    writer.endObject()
}

/** Every CalendarEventEntity field, in entity-declaration order. */
private fun CalendarEventEntity.writeSchedule(writer: JsonWriter) {
    writer.beginObject()
    writer.nameString("id", id)
    writer.nameString("userId", userId)
    writer.nameString("title", title)
    writer.nameLong("startEpochMillis", startEpochMillis)
    writer.nameLong("endEpochMillis", endEpochMillis)
    writer.nameString("locationOrLink", locationOrLink)
    writer.nameString("eventType", eventType)
    writer.nameBoolean("isAllDay", isAllDay)
    writer.nameLong("googleCalendarEventId", googleCalendarEventId)
    writer.nameBoolean("isFromGoogleCalendar", isFromGoogleCalendar)
    writer.nameString("groupId", groupId)
    writer.endObject()
}

/** Every NudgyMemoryEntity field, in entity-declaration order. */
private fun NudgyMemoryEntity.writeMemory(writer: JsonWriter) {
    writer.beginObject()
    writer.nameString("id", id)
    writer.nameString("userId", userId)
    writer.nameString("content", content)
    writer.nameLong("createdAtEpochMillis", createdAtEpochMillis)
    writer.endObject()
}

/** Step 2-1 (schema v2) — every TagEntity field, in entity-declaration order. */
private fun TagEntity.writeTag(writer: JsonWriter) {
    writer.beginObject()
    writer.nameString("id", id)
    writer.nameString("userId", userId)
    writer.nameString("name", name)
    writer.nameString("color", color)
    writer.endObject()
}

/** Step 2-1 (schema v2) — one task↔tag link. */
private fun TaskTagCrossRef.writeTaskTag(writer: JsonWriter) {
    writer.beginObject()
    writer.nameString("taskId", taskId)
    writer.nameString("tagId", tagId)
    writer.endObject()
}

private fun JsonReader.readTaskList(): List<TaskEntity> =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        emptyList()
    } else {
        val list = mutableListOf<TaskEntity>()
        beginArray()
        while (hasNext()) list += readTask()
        endArray()
        list
    }

private fun JsonReader.readTask(): TaskEntity {
    beginObject()
    var id: String? = null
    var userId: String? = null
    var title: String? = null
    var description: String? = null
    var dueDateEpochMillis: Long = 0L
    var priority: Priority = Priority.MEDIUM
    var category: TaskCategory = TaskCategory.WORK
    var isCompleted: Boolean = false
    var completedAtEpochMillis: Long? = null
    var reminderSet: Boolean = false
    var reminderEpochMillis: Long? = null
    var recurrenceRule: String? = null
    var isSyncedToCloud: Boolean = false
    var createdAtEpochMillis: Long = 0L
    var updatedAtEpochMillis: Long = 0L
    var groupId: String? = null
    var status: TaskStatus = TaskStatus.TODO
    var plannedStartEpochMillis: Long? = null
    var plannedDate: String? = null
    var estimatedMinutes: Int? = null
    var actualMinutes: Int? = null
    var parentTaskId: String? = null
    var projectId: String? = null
    var carriedOverFromDate: String? = null
    var templateId: String? = null
    var reminderRepeatMinutes: Int? = null
    var boardOrder: Int = 0
    var assigneeUid: String? = null
    var assigneeName: String? = null
    var isSample: Boolean = false
    while (hasNext()) {
        when (nextName()) {
            "id" -> id = nextNullableString()
            "userId" -> userId = nextNullableString()
            "title" -> title = nextNullableString()
            "description" -> description = nextNullableString()
            "dueDateEpochMillis" -> dueDateEpochMillis = nextNullableLong() ?: 0L
            "priority" -> priority = nextNullableEnum { Priority.valueOf(it) } ?: Priority.MEDIUM
            "category" -> category = nextNullableEnum { TaskCategory.valueOf(it) } ?: TaskCategory.WORK
            "isCompleted" -> isCompleted = nextNullableBoolean() ?: false
            "completedAtEpochMillis" -> completedAtEpochMillis = nextNullableLong()
            "reminderSet" -> reminderSet = nextNullableBoolean() ?: false
            "reminderEpochMillis" -> reminderEpochMillis = nextNullableLong()
            "recurrenceRule" -> recurrenceRule = nextNullableString()
            "isSyncedToCloud" -> isSyncedToCloud = nextNullableBoolean() ?: false
            "createdAtEpochMillis" -> createdAtEpochMillis = nextNullableLong() ?: 0L
            "updatedAtEpochMillis" -> updatedAtEpochMillis = nextNullableLong() ?: 0L
            "groupId" -> groupId = nextNullableString()
            "status" -> status = nextNullableEnum { TaskStatus.valueOf(it) } ?: TaskStatus.TODO
            "plannedStartEpochMillis" -> plannedStartEpochMillis = nextNullableLong()
            "plannedDate" -> plannedDate = nextNullableString()
            "estimatedMinutes" -> estimatedMinutes = nextNullableInt()
            "actualMinutes" -> actualMinutes = nextNullableInt()
            "parentTaskId" -> parentTaskId = nextNullableString()
            "projectId" -> projectId = nextNullableString()
            "carriedOverFromDate" -> carriedOverFromDate = nextNullableString()
            "templateId" -> templateId = nextNullableString()
            "reminderRepeatMinutes" -> reminderRepeatMinutes = nextNullableInt()
            "boardOrder" -> boardOrder = nextNullableInt() ?: 0
            "assigneeUid" -> assigneeUid = nextNullableString()
            "assigneeName" -> assigneeName = nextNullableString()
            "isSample" -> isSample = nextNullableBoolean() ?: false
            else -> skipValue()
        }
    }
    endObject()
    return TaskEntity(
        id = id ?: UUID.randomUUID().toString(),
        userId = userId ?: "",
        title = title ?: "",
        description = description,
        dueDateEpochMillis = dueDateEpochMillis,
        priority = priority,
        category = category,
        isCompleted = isCompleted,
        completedAtEpochMillis = completedAtEpochMillis,
        reminderSet = reminderSet,
        reminderEpochMillis = reminderEpochMillis,
        recurrenceRule = recurrenceRule,
        isSyncedToCloud = isSyncedToCloud,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        groupId = groupId,
        status = status,
        plannedStartEpochMillis = plannedStartEpochMillis,
        plannedDate = plannedDate,
        estimatedMinutes = estimatedMinutes,
        actualMinutes = actualMinutes,
        parentTaskId = parentTaskId,
        projectId = projectId,
        carriedOverFromDate = carriedOverFromDate,
        templateId = templateId,
        reminderRepeatMinutes = reminderRepeatMinutes,
        boardOrder = boardOrder,
        assigneeUid = assigneeUid,
        assigneeName = assigneeName,
        isSample = isSample
    )
}

private fun JsonReader.readScheduleList(): List<CalendarEventEntity> =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        emptyList()
    } else {
        val list = mutableListOf<CalendarEventEntity>()
        beginArray()
        while (hasNext()) list += readSchedule()
        endArray()
        list
    }

private fun JsonReader.readSchedule(): CalendarEventEntity {
    beginObject()
    var id: String? = null
    var userId: String? = null
    var title: String? = null
    var startEpochMillis: Long = 0L
    var endEpochMillis: Long = 0L
    var locationOrLink: String? = null
    var eventType: String = "MEETING"
    var isAllDay: Boolean = false
    var googleCalendarEventId: Long? = null
    var isFromGoogleCalendar: Boolean = false
    var groupId: String? = null
    while (hasNext()) {
        when (nextName()) {
            "id" -> id = nextNullableString()
            "userId" -> userId = nextNullableString()
            "title" -> title = nextNullableString()
            "startEpochMillis" -> startEpochMillis = nextNullableLong() ?: 0L
            "endEpochMillis" -> endEpochMillis = nextNullableLong() ?: 0L
            "locationOrLink" -> locationOrLink = nextNullableString()
            "eventType" -> eventType = nextNullableString() ?: "MEETING"
            "isAllDay" -> isAllDay = nextNullableBoolean() ?: false
            "googleCalendarEventId" -> googleCalendarEventId = nextNullableLong()
            "isFromGoogleCalendar" -> isFromGoogleCalendar = nextNullableBoolean() ?: false
            "groupId" -> groupId = nextNullableString()
            else -> skipValue()
        }
    }
    endObject()
    return CalendarEventEntity(
        id = id ?: UUID.randomUUID().toString(),
        userId = userId ?: "",
        title = title ?: "",
        startEpochMillis = startEpochMillis,
        endEpochMillis = endEpochMillis,
        locationOrLink = locationOrLink,
        eventType = eventType,
        isAllDay = isAllDay,
        googleCalendarEventId = googleCalendarEventId,
        isFromGoogleCalendar = isFromGoogleCalendar,
        groupId = groupId
    )
}

private fun JsonReader.readMemoryList(): List<NudgyMemoryEntity> =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        emptyList()
    } else {
        val list = mutableListOf<NudgyMemoryEntity>()
        beginArray()
        while (hasNext()) list += readMemory()
        endArray()
        list
    }

private fun JsonReader.readMemory(): NudgyMemoryEntity {
    beginObject()
    var id: String? = null
    var userId: String? = null
    var content: String? = null
    var createdAtEpochMillis: Long = 0L
    while (hasNext()) {
        when (nextName()) {
            "id" -> id = nextNullableString()
            "userId" -> userId = nextNullableString()
            "content" -> content = nextNullableString()
            "createdAtEpochMillis" -> createdAtEpochMillis = nextNullableLong() ?: 0L
            else -> skipValue()
        }
    }
    endObject()
    return NudgyMemoryEntity(
        id = id ?: UUID.randomUUID().toString(),
        userId = userId ?: "",
        content = content ?: "",
        createdAtEpochMillis = createdAtEpochMillis
    )
}

/** Step 2-1 (schema v2) — null array reads as an empty list (same as the other lists). */
private fun JsonReader.readTagList(): List<TagEntity> =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        emptyList()
    } else {
        val list = mutableListOf<TagEntity>()
        beginArray()
        while (hasNext()) list += readTag()
        endArray()
        list
    }

private fun JsonReader.readTag(): TagEntity {
    beginObject()
    var id: String? = null
    var userId: String? = null
    var name: String? = null
    var color: String? = null
    while (hasNext()) {
        when (nextName()) {
            "id" -> id = nextNullableString()
            "userId" -> userId = nextNullableString()
            "name" -> name = nextNullableString()
            "color" -> color = nextNullableString()
            else -> skipValue()
        }
    }
    endObject()
    return TagEntity(
        id = id ?: UUID.randomUUID().toString(),
        userId = userId ?: "",
        name = name ?: "",
        // A missing colour renders as the brand teal through
        // colorFromHex's fallback, so "" is a safe default here.
        color = color ?: ""
    )
}

/** Step 2-1 (schema v2) — null array reads as an empty list. */
private fun JsonReader.readTaskTagList(): List<TaskTagCrossRef> =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        emptyList()
    } else {
        val list = mutableListOf<TaskTagCrossRef>()
        beginArray()
        while (hasNext()) list += readTaskTag()
        endArray()
        list
    }

private fun JsonReader.readTaskTag(): TaskTagCrossRef {
    beginObject()
    var taskId: String? = null
    var tagId: String? = null
    while (hasNext()) {
        when (nextName()) {
            "taskId" -> taskId = nextNullableString()
            "tagId" -> tagId = nextNullableString()
            else -> skipValue()
        }
    }
    endObject()
    return TaskTagCrossRef(
        taskId = taskId ?: "",
        tagId = tagId ?: ""
    )
}

/** Write a named string key; null writes an explicit JSON null. */
private fun JsonWriter.nameString(name: String, value: String?) {
    name(name)
    if (value == null) nullValue() else value(value)
}

/** Write a named long key; null writes an explicit JSON null. */
private fun JsonWriter.nameLong(name: String, value: Long?) {
    name(name)
    if (value == null) nullValue() else value(value)
}

/** Write a named int key; null writes an explicit JSON null. */
private fun JsonWriter.nameInt(name: String, value: Int?) {
    name(name)
    if (value == null) nullValue() else value(value)
}

/** Write a named boolean key (never null). */
private fun JsonWriter.nameBoolean(name: String, value: Boolean) {
    name(name)
    value(value)
}

/** Write a named enum key by its name (never null). */
private fun JsonWriter.nameEnum(name: String, value: Enum<*>) {
    name(name)
    value(value.name)
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

/** Null JSON value -> null; anything else must be a long. */
private fun JsonReader.nextNullableLong(): Long? =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        null
    } else {
        nextLong()
    }

/** Null JSON value -> null; anything else must be an int. */
private fun JsonReader.nextNullableInt(): Int? =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        null
    } else {
        nextInt()
    }

/** Unknown enum name degrades to null for that field only. */
private fun <E : Enum<E>> JsonReader.nextNullableEnum(valueOf: (String) -> E): E? =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        null
    } else {
        runCatching { valueOf(nextString()) }.getOrNull()
    }
