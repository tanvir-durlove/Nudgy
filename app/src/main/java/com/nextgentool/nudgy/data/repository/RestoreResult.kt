package com.nextgentool.nudgy.data.repository

/**
 * Sprint 6-6 (step 4c) — one table's id-merge result: how many rows
 * the restore inserted vs skipped (already present by id).
 */
data class RestoreCounts(val inserted: Int, val skipped: Int)

/** All three tables' id-merge results for one restore. */
data class RestoreSummary(
    val tasks: RestoreCounts,
    val schedules: RestoreCounts,
    val memories: RestoreCounts
) {
    val totalInserted: Int get() = tasks.inserted + schedules.inserted + memories.inserted
    val totalSkipped: Int get() = tasks.skipped + schedules.skipped + memories.skipped
}
