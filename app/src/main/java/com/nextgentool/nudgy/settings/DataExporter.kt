package com.nextgentool.nudgy.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sprint 6-6 (step 4b) — JSON export (spec §2 Q7=(a), §8): the user's
 * tasks + schedules + memories as one versioned JSON document
 * ([NudgyDataExport]), written to a document the user picks via the
 * Storage Access Framework create-document picker. Step 2-1 added
 * the user's tags + the task↔tag links (schema v2).
 *
 * Two honest limits, both by design:
 * - The picker itself ([createExportDocumentIntent]) and the Uri write
 *   ([exportToUri]) are device-gated — no file picker exists on the
 *   JVM — so they are verified by CI compile + the batch-end device
 *   test. Everything below them ([collectExport], [exportToStream],
 *   [NudgyDataExport] itself) is unit-tested.
 * - This step is export ONLY. Restore (open-document + id-merge) is
 *   step 4c, and the Settings rows that launch the picker are 4d.
 *   Nothing here touches the Settings screen.
 *
 * A guest can export too: [collectExport] takes the caller's userId
 * as-is, guest id included — the export is local data, no sign-in
 * required.
 */
object DataExporter {

    private const val TAG = "DataExporter"

    /** The file name offered in the create-document picker. */
    fun defaultFileName(nowEpochMillis: Long = System.currentTimeMillis()): String {
        // No java.time in main code (minSdk 24, no desugaring):
        // SimpleDateFormat + the device default timezone. Locale.US
        // keeps the digits Latin even in a Bengali locale.
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowEpochMillis))
        return "nudgy-export-$day.json"
    }

    /** The SAF create-document intent for the export file. */
    fun createExportDocumentIntent(fileName: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }

    /**
     * Reads one user's tasks, schedules, memories, tags and task↔tag
     * links from Room and builds the export document. The reads are
     * the repositories' own one-shot paths
     * ([TaskRepository.getAllTasksOnce],
     * [CalendarRepository.observeAllEvents] first emission,
     * [MemoryRepository.getMemories], [TaskRepository.getTagsOnce]).
     * Only the exported tasks' links are included — no other user's
     * links, and no links to tasks outside this export.
     */
    suspend fun collectExport(
        userId: String,
        taskRepository: TaskRepository,
        calendarRepository: CalendarRepository,
        memoryRepository: MemoryRepository
    ): NudgyDataExport = withContext(Dispatchers.IO) {
        val tasks = taskRepository.getAllTasksOnce(userId)
        NudgyDataExport(
            schemaVersion = NudgyDataExport.CURRENT_SCHEMA_VERSION,
            exportedAtEpochMillis = System.currentTimeMillis(),
            tasks = tasks,
            schedules = calendarRepository.observeAllEvents(userId).first(),
            memories = memoryRepository.getMemories(userId),
            tags = taskRepository.getTagsOnce(userId),
            taskTags = taskRepository.getCrossRefsForTasks(tasks.map { it.id })
        )
    }

    /** Writes the export document's bytes to any stream. */
    suspend fun exportToStream(
        export: NudgyDataExport,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        outputStream.write(export.toJson().toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    /**
     * Writes the export document to a SAF document Uri. Returns false
     * (and logs) when the Uri cannot be opened or the write fails —
     * the caller shows the failure, it never crashes. The stream is
     * closed here; the caller owns nothing after this call.
     */
    suspend fun exportToUri(
        context: Context,
        uri: Uri,
        export: NudgyDataExport
    ): Boolean = try {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(export.toJson().toByteArray(Charsets.UTF_8))
                stream.flush()
                true
            } ?: false
        }
    } catch (e: Exception) {
        Log.w(TAG, "Export write failed: ${e.message}")
        false
    }
}
