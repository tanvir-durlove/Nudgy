package com.nextgentool.nudgy.ai

import com.nextgentool.nudgy.data.model.Priority
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Sprint 3-3 — the LLM structured-output contract (spec §8).
 *
 * The model is instructed (see [GeminiClient.structuredSystemInstructionText])
 * to answer with ONLY this JSON:
 * ```
 * {
 *   "action": "create_tasks" | "create_event" | "split_task" | "none",
 *   "tasks": [{"title": "...", "due": "ISO-8601 or null", "priority": "LOW|MEDIUM|HIGH"}],
 *   "events": [{"title": "...", "start": "ISO-8601", "end": "ISO-8601"}],
 *   "subtasks": [{"title": "..."}],
 *   "reply": "..."
 * }
 * ```
 *
 * Validation is strict and fail-safe, by design:
 * - A title must be non-blank. A date must parse as strict ISO-8601
 *   (offset datetime, local datetime, or date-only) — an unparseable
 *   date rejects the WHOLE JSON, never just the one item.
 * - An event's end must be after its start.
 * - An unknown action is treated as a plain-text reply (never a save).
 * - ANY validation failure returns [LlmStructuredResult.Invalid]: the
 *   caller shows the model's `reply` field as plain text (or the local
 *   fallback when it is empty) and saves NOTHING.
 *
 * S3-3 limits (documented, not hidden):
 * - Exactly ONE task (and no events) → [LlmStructuredResult.SingleTask];
 *   exactly ONE event (and no tasks) → [LlmStructuredResult.SingleEvent].
 *   Anything multi-item is [LlmStructuredResult.ReplyOnly] — the S3-2
 *   preview card holds ONE draft, so multi-task confirm waits for the
 *   S3-4 combined card (Q9). The system prompt asks the model for at
 *   most one item, so this path should stay rare.
 * - `split_task` is validated strictly (non-blank subtask titles) and
 *   returns [LlmStructuredResult.SplitTask]: the S3-3 handler maps it
 *   to a plain-text reply (no parent task in the S3-3 fallback context
 *   to attach subtasks to); the S3-5 split flow passes the parent and
 *   turns it into a [ChatDraft] instead.
 * - A task with no due (`"due": null`) is [LlmStructuredResult.ReplyOnly]:
 *   the S3-2 draft model needs a concrete start time, and inventing one
 *   would break the never-guess rule. The reply should ask for the time.
 *
 * This is pure parsing — no Android, no network, no DB. The LLM's text
 * NEVER reaches createTask/addEvent from here: only a validated
 * [LlmStructuredResult.SingleTask]/[SingleEvent]/[SplitTask] (the last
 * only with a known parent task) may become a [ChatDraft], and only
 * after the user confirms it on the preview card.
 */
data class LlmTaskItem(
    val title: String,
    val dueEpochMillis: Long?,
    val priority: Priority
)

data class LlmEventItem(
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long
)

data class LlmSubtaskItem(val title: String)

sealed class LlmStructuredResult {
    /** Plain-text reply only — no draft, no save. */
    data class ReplyOnly(val reply: String) : LlmStructuredResult()

    /** Exactly one validated task — the caller may offer it as a draft. */
    data class SingleTask(val task: LlmTaskItem, val reply: String) : LlmStructuredResult()

    /** Exactly one validated event — the caller may offer it as a draft. */
    data class SingleEvent(val event: LlmEventItem, val reply: String) : LlmStructuredResult()

    /**
     * Sprint 3-5 — a validated split: the model's subtask titles for
     * the task the caller asked to break down. The caller may offer it
     * as a draft ONLY when it knows the parent task; without one it
     * degrades to a plain-text reply (see [LlmFallbackHandler]).
     */
    data class SplitTask(val subtasks: List<LlmSubtaskItem>, val reply: String) : LlmStructuredResult()

    /** Validation failed — show [reply] as plain text, save NOTHING.
     *  [reply] is the model's `reply` field when usable, else "". */
    data class Invalid(val reply: String) : LlmStructuredResult()
}

object LlmStructuredOutput {

    /**
     * Parses the model's raw text into a validated result. Plain prose
     * (no JSON at all) is a valid plain-text reply, NOT an error — the
     * model is allowed to answer in words. A text that TRIES to be JSON
     * (starts with `{` or is fenced) but fails validation is Invalid.
     */
    fun parse(rawText: String): LlmStructuredResult {
        val text = stripFences(rawText).trim()
        if (text.isEmpty()) return LlmStructuredResult.Invalid("")
        // No JSON object at all: the model answered in prose.
        val jsonStart = text.indexOf('{')
        if (jsonStart < 0) return LlmStructuredResult.ReplyOnly(text)
        val jsonText = text.substring(jsonStart)
        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return LlmStructuredResult.Invalid("")
        }
        return parseJson(json)
    }

    private fun parseJson(json: JSONObject): LlmStructuredResult {
        val reply = json.optString("reply", "").trim()
        val action = json.optString("action", "none").trim()
        return when (action) {
            "none" -> replyOrInvalid(reply)
            "create_tasks" -> parseCreateTasks(json, reply)
            "create_event" -> parseCreateEvent(json, reply)
            "split_task" -> parseSplitTask(json, reply)
            // Unknown action: plain-text reply, never a save.
            else -> replyOrInvalid(reply)
        }
    }

    private fun parseCreateTasks(json: JSONObject, reply: String): LlmStructuredResult {
        val tasks = parseTasks(json.optJSONArray("tasks")) ?: return LlmStructuredResult.Invalid(reply)
        val events = parseEvents(json.optJSONArray("events")) ?: return LlmStructuredResult.Invalid(reply)
        if (tasks.isEmpty()) return LlmStructuredResult.Invalid(reply)
        if (tasks.size == 1 && events.isEmpty()) {
            val task = tasks.single()
            // A task with no due cannot become an S3-2 draft (the draft
            // model needs a concrete start time; inventing one would
            // guess). Ask for the time instead.
            if (task.dueEpochMillis == null) return LlmStructuredResult.ReplyOnly(reply.ifEmpty {
                "When should I do this?"
            })
            return LlmStructuredResult.SingleTask(task, reply)
        }
        // Multi-item: reply-only in S3-3 (combined card is S3-4).
        return LlmStructuredResult.ReplyOnly(reply)
    }

    private fun parseCreateEvent(json: JSONObject, reply: String): LlmStructuredResult {
        val tasks = parseTasks(json.optJSONArray("tasks")) ?: return LlmStructuredResult.Invalid(reply)
        val events = parseEvents(json.optJSONArray("events")) ?: return LlmStructuredResult.Invalid(reply)
        if (events.isEmpty()) return LlmStructuredResult.Invalid(reply)
        if (events.size == 1 && tasks.isEmpty()) {
            return LlmStructuredResult.SingleEvent(events.single(), reply)
        }
        return LlmStructuredResult.ReplyOnly(reply)
    }

    private fun parseSplitTask(json: JSONObject, reply: String): LlmStructuredResult {
        // Strict validation first (a bad subtask rejects the JSON).
        // The caller decides what a valid split becomes: a draft when
        // it knows the parent task (S3-5), a plain-text reply when it
        // does not (S3-3).
        val subtasks = parseSubtasks(json.optJSONArray("subtasks"))
            ?: return LlmStructuredResult.Invalid(reply)
        if (subtasks.isEmpty()) return LlmStructuredResult.Invalid(reply)
        return LlmStructuredResult.SplitTask(subtasks, reply)
    }

    /** Parses the tasks array. Null (absent) means "no tasks". A null
     *  return means the array itself failed validation. */
    private fun parseTasks(array: JSONArray?): List<LlmTaskItem>? {
        if (array == null) return emptyList()
        val out = mutableListOf<LlmTaskItem>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: return null
            val title = item.optString("title", "").trim()
            if (title.isEmpty()) return null
            val dueRaw = if (item.isNull("due")) null else item.optString("due", "").trim()
            val due = when {
                dueRaw.isNullOrEmpty() -> null
                else -> parseIsoEpochMillis(dueRaw) ?: return null
            }
            val priority = when (item.optString("priority", "MEDIUM").trim().uppercase()) {
                "LOW" -> Priority.LOW
                "HIGH" -> Priority.HIGH
                else -> Priority.MEDIUM
            }
            out.add(LlmTaskItem(title, due, priority))
        }
        return out
    }

    /** Parses the events array. Null (absent) means "no events". A null
     *  return means the array itself failed validation. */
    private fun parseEvents(array: JSONArray?): List<LlmEventItem>? {
        if (array == null) return emptyList()
        val out = mutableListOf<LlmEventItem>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: return null
            val title = item.optString("title", "").trim()
            if (title.isEmpty()) return null
            val startRaw = item.optString("start", "").trim()
            val endRaw = item.optString("end", "").trim()
            val start = parseIsoEpochMillis(startRaw) ?: return null
            val end = parseIsoEpochMillis(endRaw) ?: return null
            if (end <= start) return null
            out.add(LlmEventItem(title, start, end))
        }
        return out
    }

    private fun parseSubtasks(array: JSONArray?): List<LlmSubtaskItem>? {
        if (array == null) return emptyList()
        val out = mutableListOf<LlmSubtaskItem>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: return null
            val title = item.optString("title", "").trim()
            if (title.isEmpty()) return null
            out.add(LlmSubtaskItem(title))
        }
        return out
    }

    private fun replyOrInvalid(reply: String): LlmStructuredResult =
        if (reply.isNotEmpty()) LlmStructuredResult.ReplyOnly(reply)
        else LlmStructuredResult.Invalid("")

    /**
     * Strict ISO-8601 → epoch millis, device-local zone for naive
     * values. Accepts offset datetimes ("2026-11-22T09:00:00+05:30",
     * "...Z"), local datetimes ("2026-11-22T09:00:00") and dates
     * ("2026-11-22" → start of day). Anything else — "tomorrow 3pm",
     * "3 PM", "next Friday" — returns null (reject the JSON).
     */
    fun parseIsoEpochMillis(value: String): Long? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        try {
            return OffsetDateTime.parse(v).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            // Fall through to the naive forms.
        }
        try {
            return LocalDateTime.parse(v).atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            // Fall through to the date-only form.
        }
        return try {
            LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Strips a ```json / ``` fenced block when the model wraps its
     *  answer in one (the contract says bare JSON, but models fence). */
    private fun stripFences(rawText: String): String {
        val text = rawText.trim()
        if (!text.startsWith("```")) return text
        val firstNewline = text.indexOf('\n')
        if (firstNewline < 0) return text
        val afterFence = text.substring(firstNewline + 1)
        val closing = afterFence.lastIndexOf("```")
        return if (closing >= 0) afterFence.substring(0, closing) else afterFence
    }
}
