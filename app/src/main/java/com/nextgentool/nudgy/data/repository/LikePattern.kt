package com.nextgentool.nudgy.data.repository

/**
 * Sprint 6-4 (global search) — the LIKE pattern for a raw search
 * string, shared by the task search (step 2b) and the calendar-event
 * search (step 2c): escape '\', '%' and '_', then wrap in '%', so a
 * typed '%' or '_' matches literally instead of matching everything.
 *
 * Lives here (not as a private member of one repository) so both
 * search paths use the same escaping — never duplicate it.
 */
internal fun likePattern(query: String): String {
    val escaped = query
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return "%$escaped%"
}
