package com.nextgentool.nudgy.domain.model

import com.nextgentool.nudgy.data.model.Priority
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

/**
 * Sprint 1-4 — task templates (Feature 13, spec §6).
 *
 * A template is a named set of task drafts (title + estimate + priority
 * + subtask titles). Picking a template creates its tasks together; the
 * user picks ONE due date for the whole set. The seed holds NO due
 * dates — due is chosen at creation time (spec Feature 13).
 *
 * Stored as JSON in task_templates.templateJson. The JSON is written and
 * read by hand with Moshi's streaming API ([JsonWriter]/[JsonReader]) —
 * deliberately NOT with a Moshi adapter: a reflective adapter breaks
 * under R8 (see SavedViewFilter's KDoc for the ci-345 crash), and the
 * generated adapter is found by reflection too. The streaming API uses
 * no reflection, so R8 can never break it.
 *
 * Schema:
 * ```
 * {"drafts":[{"title":"...","estimatedMinutes":30,"priority":"HIGH",
 *   "subtasks":["..."]}]}
 * ```
 * Parsing is tolerant: a malformed document yields an empty list, and a
 * malformed draft is skipped (never a crash, never a half-read list).
 */
data class TemplateDraft(
    val title: String,
    val estimatedMinutes: Int? = null,
    val priority: Priority = Priority.MEDIUM,
    val subtaskTitles: List<String> = emptyList()
)

data class TaskTemplate(
    val id: String,
    val name: String,
    val drafts: List<TemplateDraft>,
    val isBuiltIn: Boolean = false
) {
    /** Total estimated minutes across all drafts (null estimates count 0). */
    val totalEstimatedMinutes: Int get() = drafts.sumOf { it.estimatedMinutes ?: 0 }
}

/** Manual JSON — see the class KDoc for why this is hand-written. */
fun List<TemplateDraft>.toTemplateJson(): String {
    val buffer = Buffer()
    val writer = JsonWriter.of(buffer)
    writer.beginObject()
    writer.name("drafts")
    writer.beginArray()
    forEach { draft ->
        writer.beginObject()
        writer.name("title").value(draft.title)
        writer.name("estimatedMinutes")
        if (draft.estimatedMinutes == null) writer.nullValue() else writer.value(draft.estimatedMinutes)
        writer.name("priority").value(draft.priority.name)
        writer.name("subtasks")
        writer.beginArray()
        draft.subtaskTitles.forEach { writer.value(it) }
        writer.endArray()
        writer.endObject()
    }
    writer.endArray()
    writer.endObject()
    writer.close()
    return buffer.readUtf8()
}

/** Tolerant parse: any unreadable document yields an empty list. */
fun String.parseTemplateDrafts(): List<TemplateDraft> = try {
    val reader = JsonReader.of(Buffer().writeUtf8(this))
    var drafts: List<TemplateDraft> = emptyList()
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "drafts" -> drafts = reader.readDraftList()
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    drafts
} catch (e: Exception) {
    emptyList()
}

private fun JsonReader.readDraftList(): List<TemplateDraft> {
    val drafts = mutableListOf<TemplateDraft>()
    beginArray()
    while (hasNext()) {
        runCatching { readOneDraft() }.getOrNull()?.let { drafts += it }
    }
    endArray()
    return drafts
}

private fun JsonReader.readOneDraft(): TemplateDraft {
    var title: String? = null
    var estimatedMinutes: Int? = null
    var priority: Priority = Priority.MEDIUM
    var subtasks: List<String> = emptyList()
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "title" -> title = nextNullableString()
            "estimatedMinutes" -> estimatedMinutes = nextNullableInt()
            "priority" -> priority = nextNullableEnum { Priority.valueOf(it) } ?: Priority.MEDIUM
            "subtasks" -> subtasks = nextStringList()
            else -> skipValue()
        }
    }
    endObject()
    return TemplateDraft(
        title = title?.takeIf { it.isNotBlank() } ?: return throw IllegalArgumentException("draft without title"),
        estimatedMinutes = estimatedMinutes,
        priority = priority,
        subtaskTitles = subtasks
    )
}

private fun JsonReader.nextNullableString(): String? =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        null
    } else {
        nextString()
    }

private fun JsonReader.nextNullableInt(): Int? =
    if (peek() == JsonReader.Token.NULL) {
        skipValue()
        null
    } else {
        nextInt()
    }

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

/**
 * The 3 approved built-in templates (Q6 draft, approved 2026-11-16).
 * Names are Bengali as approved; task titles are English (app primary
 * language). Fixed IDs make seeding idempotent — a re-seed never
 * duplicates them. Totals: 1h50m / 2h40m / 3h, each far under the 8h
 * Plan cap (Q1), so one template never fills a day on its own.
 *
 * NO prayer template: parked by Tanvir's explicit order 2026-11-16
 * ("আপাতত বাদ রাখো") — neither as a 4th template nor inside
 * দৈনিক রুটিন. Revisit only when he says so.
 */
object BuiltInTemplates {
    const val DAILY_ROUTINE_ID = "builtin-daily-routine"
    const val STUDY_ID = "builtin-study"
    const val WORK_ID = "builtin-work"

    val all: List<TaskTemplate> = listOf(
        TaskTemplate(
            id = DAILY_ROUTINE_ID,
            name = "দৈনিক রুটিন",
            isBuiltIn = true,
            drafts = listOf(
                TemplateDraft(
                    title = "Morning routine",
                    estimatedMinutes = 30,
                    priority = Priority.MEDIUM,
                    subtaskTitles = listOf("Drink water", "Freshen up")
                ),
                TemplateDraft(
                    title = "Exercise / walk",
                    estimatedMinutes = 30,
                    priority = Priority.HIGH
                ),
                TemplateDraft(
                    title = "Plan the day",
                    estimatedMinutes = 10,
                    priority = Priority.HIGH,
                    subtaskTitles = listOf("Review Today list")
                ),
                TemplateDraft(
                    title = "Family time",
                    estimatedMinutes = 30,
                    priority = Priority.MEDIUM
                ),
                TemplateDraft(
                    title = "Prepare tomorrow's list",
                    estimatedMinutes = 10,
                    priority = Priority.LOW,
                    subtaskTitles = listOf("Pick top 3 for tomorrow")
                )
            )
        ),
        TaskTemplate(
            id = STUDY_ID,
            name = "পড়াশোনা",
            isBuiltIn = true,
            drafts = listOf(
                TemplateDraft(
                    title = "Revise yesterday's notes",
                    estimatedMinutes = 30,
                    priority = Priority.HIGH
                ),
                TemplateDraft(
                    title = "Focus study session",
                    estimatedMinutes = 60,
                    priority = Priority.HIGH,
                    subtaskTitles = listOf("Read the chapter", "Write a short summary")
                ),
                TemplateDraft(
                    title = "Practice problems",
                    estimatedMinutes = 45,
                    priority = Priority.MEDIUM
                ),
                TemplateDraft(
                    title = "Review flashcards",
                    estimatedMinutes = 15,
                    priority = Priority.LOW
                ),
                TemplateDraft(
                    title = "Plan tomorrow's study",
                    estimatedMinutes = 10,
                    priority = Priority.LOW,
                    subtaskTitles = listOf("Pick tomorrow's topic")
                )
            )
        ),
        TaskTemplate(
            id = WORK_ID,
            name = "কাজ",
            isBuiltIn = true,
            drafts = listOf(
                TemplateDraft(
                    title = "Check messages and plan the workday",
                    estimatedMinutes = 15,
                    priority = Priority.HIGH
                ),
                TemplateDraft(
                    title = "Deep work — main task",
                    estimatedMinutes = 90,
                    priority = Priority.HIGH,
                    subtaskTitles = listOf("Break it into 2–3 steps first")
                ),
                TemplateDraft(
                    title = "Reply to emails and messages",
                    estimatedMinutes = 30,
                    priority = Priority.MEDIUM
                ),
                TemplateDraft(
                    title = "Meetings and follow-ups",
                    estimatedMinutes = 30,
                    priority = Priority.MEDIUM
                ),
                TemplateDraft(
                    title = "End-of-day wrap-up",
                    estimatedMinutes = 15,
                    priority = Priority.LOW,
                    subtaskTitles = listOf("Note tomorrow's top 3")
                )
            )
        )
    )
}
