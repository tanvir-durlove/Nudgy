package com.nextgentool.nudgy.ai

import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory

/**
 * Sprint 3-2 (Q2 = (a)) — what the chat is about to create, before
 * the user confirms it. A draft is NEVER a saved row: the prefilter
 * builds it ([ClientIntentPreFilter.buildDraftFromParsed] and the
 * step-4 / step-5 / 30-minute / focus branches), the preview card
 * shows it, and only [ClientIntentPreFilter.confirmDraft] writes it
 * to the DB.
 *
 * Fields mirror the repository calls confirmDraft makes, so the
 * draft is a faithful preview of the save — no re-derivation at
 * confirm time:
 * - [startEpochMillis]: due time for TASK/REMINDER, start time for
 *   SCHEDULE/FOCUS_EVENT.
 * - [endEpochMillis]: SCHEDULE/FOCUS_EVENT only (start + 45m for a
 *   schedule, the suggested slot for focus).
 * - [reminderEpochMillis]: the reminder time for TASK/REMINDER
 *   (due time when [reminderAtDue], else due − 2h — the same values
 *   the old immediate-save path passed to createTask).
 * - [conflictingTitle]: read-only conflict found at draft time
 *   (SCHEDULE only); confirmDraft re-checks it fresh.
 */
enum class DraftKind { TASK, SCHEDULE, REMINDER, FOCUS_EVENT }

data class ChatDraft(
    val kind: DraftKind,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long? = null,
    val priority: Priority = Priority.MEDIUM,
    val category: TaskCategory? = null,
    val reminderAtDue: Boolean = false,
    val reminderEpochMillis: Long? = null,
    val scheduleLocation: String? = null,
    val scheduleType: String = "MEETING",
    val isBengali: Boolean = false,
    val conflictingTitle: String? = null,
    val focusDurationMinutes: Int = 45
) {
    fun withTitle(newTitle: String): ChatDraft = copy(title = newTitle)

    /**
     * Moves the draft to a new start time, shifting the end and the
     * reminder by the same delta so durations and leads survive an
     * edit. A reminder-at-due follows the new start exactly.
     */
    fun withStartTime(newStartMillis: Long): ChatDraft {
        val delta = newStartMillis - startEpochMillis
        return copy(
            startEpochMillis = newStartMillis,
            endEpochMillis = endEpochMillis?.plus(delta),
            reminderEpochMillis = when {
                reminderAtDue -> newStartMillis
                reminderEpochMillis != null -> reminderEpochMillis + delta
                else -> null
            }
        )
    }
}
