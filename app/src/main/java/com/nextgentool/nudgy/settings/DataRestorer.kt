package com.nextgentool.nudgy.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.RestoreSummary
import com.nextgentool.nudgy.data.repository.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sprint 6-6 (step 4c) — JSON restore (spec §2 Q7=(a), §8): read a
 * [NudgyDataExport] document the user picks via the Storage Access
 * Framework open-document picker, and id-merge it into Room.
 *
 * The id-merge rule (spec Q7: "id-merge, no duplicates"): a record
 * whose id already exists in the database is SKIPPED — the local
 * row is never overwritten or deleted — and only missing ids are
 * inserted. Restoring the same file twice therefore inserts nothing
 * the second time. The counts come back in [RestoreSummary], so the
 * caller can tell the user what happened.
 *
 * The userId decision (the repository layer was read first, not
 * guessed): every restored record is re-stamped with the CALLER's
 * userId — the account restoring right now — not the userId stored
 * in the file. The export's records carry the exporting account's
 * id ([DataExporter.collectExport] reads one user's rows); keeping
 * it would strand a guest export restored after sign-in under the
 * guest id, where no userId-scoped read ever looks. The established
 * model is the guest→user flip (reassignUser on all three DAOs):
 * local data follows the current account. The existence check is by
 * id alone (the primary key), so a record already present under ANY
 * user is still skipped, never duplicated or overwritten. Step 2-1:
 * tags ARE restored now (schema v2) — the tag rows first, then the
 * task↔tag links, each link only when its task AND its tag both
 * exist after the merge, so a dangling link is skipped, never a
 * crash.
 *
 * Two honest limits, both by design:
 * - The picker itself ([createOpenDocumentIntent]) and the Uri read
 *   ([restoreFromUri]) are device-gated — no file picker exists on
 *   the JVM — so they are verified by CI compile + the batch-end
 *   device test. Everything below them ([restore], [restoreFromJson]
 *   and the repositories' restore methods) is unit-tested.
 * - This step is restore ONLY. The Settings rows that launch the
 *   picker are step 4d. Nothing here touches the Settings screen.
 */
object DataRestorer {

    private const val TAG = "DataRestorer"

    /** One restore's outcome: what happened, with the counts. */
    sealed interface RestoreOutcome {
        /** The file parsed and merged; [summary] carries the counts. */
        data class Restored(val summary: RestoreSummary) : RestoreOutcome

        /** The file is not a Nudgy export (malformed, or a version
         * this build cannot read) — nothing was written. */
        data object NotAnExportFile : RestoreOutcome

        /** The document could not be opened or read — nothing was written. */
        data object ReadFailed : RestoreOutcome
    }

    /** The SAF open-document intent for picking an export file. */
    fun createOpenDocumentIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }

    /**
     * Merges one parsed export document into the database for
     * [userId]: every record is re-stamped with [userId] (see the
     * class KDoc), then id-merged by the repositories. Order matters:
     * tasks → schedules → memories → tags → task↔tag links — the
     * links go last, because both their ends must exist first.
     */
    suspend fun restore(
        export: NudgyDataExport,
        userId: String,
        taskRepository: TaskRepository,
        calendarRepository: CalendarRepository,
        memoryRepository: MemoryRepository
    ): RestoreSummary = withContext(Dispatchers.IO) {
        val tasks = taskRepository.restoreTasks(
            export.tasks.map { it.copy(userId = userId) }
        )
        val schedules = calendarRepository.restoreEvents(
            export.schedules.map { it.copy(userId = userId) }
        )
        val memories = memoryRepository.restoreMemories(
            export.memories.map { it.copy(userId = userId) }
        )
        val tags = taskRepository.restoreTags(
            export.tags.map { it.copy(userId = userId) }
        )
        val taskTags = taskRepository.restoreTaskTags(export.taskTags)
        RestoreSummary(
            tasks = tasks,
            schedules = schedules,
            memories = memories,
            tags = tags,
            taskTags = taskTags
        )
    }

    /**
     * Parses [json] and restores it, or [RestoreOutcome.NotAnExportFile]
     * when it is not a readable Nudgy export — nothing is written
     * in that case.
     */
    suspend fun restoreFromJson(
        json: String,
        userId: String,
        taskRepository: TaskRepository,
        calendarRepository: CalendarRepository,
        memoryRepository: MemoryRepository
    ): RestoreOutcome {
        val export = NudgyDataExport.fromJson(json)
            ?: return RestoreOutcome.NotAnExportFile
        return RestoreOutcome.Restored(
            restore(export, userId, taskRepository, calendarRepository, memoryRepository)
        )
    }

    /**
     * Reads a SAF document Uri and restores it. Returns
     * [RestoreOutcome.ReadFailed] (and logs) when the document
     * cannot be opened or read — the caller shows the failure, it
     * never crashes.
     */
    suspend fun restoreFromUri(
        context: Context,
        uri: Uri,
        userId: String,
        taskRepository: TaskRepository,
        calendarRepository: CalendarRepository,
        memoryRepository: MemoryRepository
    ): RestoreOutcome = withContext(Dispatchers.IO) {
        val json = try {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return@withContext RestoreOutcome.ReadFailed
        } catch (e: Exception) {
            Log.w(TAG, "Restore read failed: ${e.message}")
            return@withContext RestoreOutcome.ReadFailed
        }
        restoreFromJson(json, userId, taskRepository, calendarRepository, memoryRepository)
    }
}
