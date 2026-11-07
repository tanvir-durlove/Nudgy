package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.local.NudgyDatabase
import com.example.data.local.entity.TaskEntity
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import com.example.reminders.ReminderManager
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.UUID

data class DailyTaskStats(
    val total: Int = 0,
    val completed: Int = 0,
    val remaining: Int = 0,
    val percentage: Float = 0f
)

class TaskRepository(
    private val context: Context,
    private val database: NudgyDatabase = NudgyDatabase.getInstance(context)
) {
    private val taskDao = database.taskDao()
    private val reminderManager = com.example.reminders.ReminderManager(context)
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var snapshotListener: ListenerRegistration? = null

    companion object {
        private const val KEY_PENDING_TASK_DELETES = "pending_task_deletes"
        /** Per-item bound for a pending-delete retry (see drainPendingTaskDeletes). */
        private const val PENDING_DELETE_TIMEOUT_MS = 10_000L
    }

    // ---------------------------------------------------------------- C13 pending deletes
    //
    // A local delete records the id here in the SAME step; the entry is
    // removed only when the Firestore delete actually succeeds.
    // startCloudSync drains this store BEFORE attaching the listener, and
    // the listener skips ids still pending, so an offline/failed cloud
    // delete can never resurrect the task on the next sync. SharedPreferences
    // (no Room schema change): ids are UUIDs, stored as "userId/taskId".
    private fun pendingDeletesPrefs() =
        context.getSharedPreferences("nudgy_pending_deletes", Context.MODE_PRIVATE)

    private fun recordPendingTaskDelete(userId: String, taskId: String) {
        val prefs = pendingDeletesPrefs()
        val updated = prefs.getStringSet(KEY_PENDING_TASK_DELETES, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        // commit() (synchronous): the record must be durable before the
        // fire-and-forget cloud attempt that follows it.
        if (updated.add("$userId/$taskId")) {
            prefs.edit().putStringSet(KEY_PENDING_TASK_DELETES, updated).commit()
        }
    }

    private fun removePendingTaskDelete(userId: String, taskId: String) {
        val prefs = pendingDeletesPrefs()
        val updated = prefs.getStringSet(KEY_PENDING_TASK_DELETES, emptySet())
            ?.toMutableSet() ?: return
        if (updated.remove("$userId/$taskId")) {
            prefs.edit().putStringSet(KEY_PENDING_TASK_DELETES, updated).apply()
        }
    }

    private fun pendingTaskDeleteIds(userId: String): Set<String> {
        val prefix = "$userId/"
        return pendingDeletesPrefs().getStringSet(KEY_PENDING_TASK_DELETES, emptySet())
            .orEmpty().filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }.toSet()
    }

    /** Retry every pending delete for this user. Bounded per item: an offline
     *  delete().await() would otherwise suspend until connectivity returns
     *  and stall the sync that follows. A timed-out/failed entry stays
     *  pending for the next drain; the listener guard keeps the task from
     *  resurrecting in the meantime. Deleting a doc that is already gone
     *  succeeds, so a retry after a half-finished drain is harmless. */
    private suspend fun drainPendingTaskDeletes(userId: String) {
        if (userId == com.example.auth.AuthManager.GUEST_USER_ID) return
        val dbId = context.getString(R.string.firestore_database_id)
        val firestore = FirebaseFirestore.getInstance(dbId)
        for (taskId in pendingTaskDeleteIds(userId)) {
            val deleted = withTimeoutOrNull(PENDING_DELETE_TIMEOUT_MS) {
                try {
                    firestore.collection("users").document(userId).collection("tasks")
                        .document(taskId).delete().await()
                    true
                } catch (e: Exception) {
                    Log.w("TaskRepository", "Pending task delete failed for $taskId: ${e.message}")
                    false
                }
            } ?: false
            if (deleted) {
                removePendingTaskDelete(userId, taskId)
            } else {
                Log.w("TaskRepository", "Pending task delete still outstanding for $taskId; will retry on next sync")
            }
        }
    }

    fun observeAllTasks(userId: String): Flow<List<TaskEntity>> =
        taskDao.getAllTasksFlow(userId)

    fun observeTodayTasks(userId: String): Flow<List<TaskEntity>> {
        val (startOfDay, endOfDay) = getTodayBounds()
        return taskDao.getTasksForDayFlow(userId, startOfDay, endOfDay)
    }

    fun observeUpcomingTasks(userId: String): Flow<List<TaskEntity>> {
        val (_, endOfDay) = getTodayBounds()
        return taskDao.getUpcomingTasksFlow(userId, endOfDay)
    }

    fun observeCompletedTasks(userId: String): Flow<List<TaskEntity>> =
        taskDao.getCompletedTasksFlow(userId)

    fun observeTodayStats(userId: String): Flow<DailyTaskStats> {
        val (startOfDay, endOfDay) = getTodayBounds()
        return taskDao.getTasksForDayFlow(userId, startOfDay, endOfDay).map { tasks ->
            val total = tasks.size
            val completed = tasks.count { it.isCompleted }
            val remaining = (total - completed).coerceAtLeast(0)
            val percentage = if (total > 0) completed.toFloat() / total.toFloat() else 0f
            DailyTaskStats(
                total = total,
                completed = completed,
                remaining = remaining,
                percentage = percentage
            )
        }
    }

    suspend fun createTask(
        userId: String,
        title: String,
        description: String? = null,
        dueEpochMillis: Long = System.currentTimeMillis(),
        priority: Priority = Priority.MEDIUM,
        category: TaskCategory = TaskCategory.WORK,
        reminderSet: Boolean = false,
        recurrenceRule: String? = null,
        // An explicit reminder time ("remind me at 3 PM"). Null = the
        // 2h-before-due lead (ReminderManager.dueReminderTriggerMillis).
        reminderEpochMillis: Long? = null
    ): TaskEntity = withContext(Dispatchers.IO) {
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            title = title,
            description = description,
            dueDateEpochMillis = dueEpochMillis,
            priority = priority,
            category = category,
            isCompleted = false,
            reminderSet = reminderSet,
            reminderEpochMillis = if (reminderSet) {
                reminderEpochMillis ?: ReminderManager.dueReminderTriggerMillis(null, dueEpochMillis)
            } else {
                null
            },
            recurrenceRule = recurrenceRule,
            isSyncedToCloud = false,
            createdAtEpochMillis = System.currentTimeMillis(),
            updatedAtEpochMillis = System.currentTimeMillis()
        )
        taskDao.insertTask(task)
        if (task.reminderSet) {
            val trigger = task.reminderEpochMillis ?: task.dueDateEpochMillis
            if (trigger > System.currentTimeMillis()) {
                reminderManager.scheduleReminder(task.id, task.title, trigger)
            }
        }
        syncDirtyTasksToCloud(userId)
        task
    }

    suspend fun toggleTaskCompletion(task: TaskEntity) = withContext(Dispatchers.IO) {
        val newStatus = !task.isCompleted
        val completedAt = if (newStatus) System.currentTimeMillis() else null
        // Group v1: member completion is Room-local ONLY. Group rows must never
        // be dirty-marked or synced — otherwise a member's completion becomes
        // a personal cloud copy of a shared item.
        if (task.groupId != null) {
            taskDao.setTaskCompletionLocal(
                userId = task.userId,
                id = task.id,
                isCompleted = newStatus,
                completedAt = completedAt,
                now = System.currentTimeMillis()
            )
        } else {
            taskDao.setTaskCompletionForUser(
                userId = task.userId,
                id = task.id,
                isCompleted = newStatus,
                completedAt = completedAt,
                now = System.currentTimeMillis()
            )
        }
        if (newStatus) {
            reminderManager.cancelReminder(task.id)
        } else if (task.reminderSet) {
            val trigger = ReminderManager.dueReminderTriggerMillis(task.reminderEpochMillis, task.dueDateEpochMillis)
            if (trigger > System.currentTimeMillis()) {
                reminderManager.scheduleReminder(task.id, task.title, trigger)
            }
        }
        if (task.groupId == null) {
            syncDirtyTasksToCloud(task.userId)
        }
    }

    suspend fun getTaskById(taskId: String): TaskEntity? = withContext(Dispatchers.IO) {
        taskDao.getTaskById(taskId)
    }

    suspend fun updateTask(task: TaskEntity) = withContext(Dispatchers.IO) {
        // Group v1: group rows are edited through GroupRepository (cloud write
        // to the shared group item). This path is a defensive local-only update
        // that must NOT dirty-mark the row — a dirty group row would be
        // uploaded as a personal cloud copy by syncDirtyTasksToCloud.
        //
        // Keep the stored reminder time in sync with the (possibly new) due
        // time. An explicitly passed reminder time is respected; otherwise
        // the 2h-before-due lead is recomputed from the due time. A stored
        // reminder equal to the OLD due time means "remind me at due" (an
        // explicit reminder request), so it moves with the due time.
        val existing = taskDao.getTaskById(task.id)
        val passedReminderChanged = task.reminderEpochMillis != existing?.reminderEpochMillis
        val explicitAtDue = existing != null &&
            existing.reminderEpochMillis != null &&
            existing.reminderEpochMillis == existing.dueDateEpochMillis
        val resolvedReminder: Long? = when {
            !task.reminderSet || task.isCompleted -> null
            passedReminderChanged -> task.reminderEpochMillis
            explicitAtDue -> task.dueDateEpochMillis
            // C9: a stored reminder that is neither "at due" nor the 2h
            // lead is an explicit custom time — keep it verbatim instead
            // of silently replacing it with the lead. (EditTaskSheet only
            // passes such a value through when the due time is unchanged,
            // or when the due time changed and the stored time is custom.)
            existing?.reminderEpochMillis != null &&
                existing.reminderEpochMillis != existing.dueDateEpochMillis - ReminderManager.REMINDER_LEAD_MILLIS -> existing.reminderEpochMillis
            else -> ReminderManager.dueReminderTriggerMillis(null, task.dueDateEpochMillis)
        }
        val resolved = task.copy(reminderEpochMillis = resolvedReminder)
        if (task.groupId != null) {
            taskDao.updateTask(
                resolved.copy(updatedAtEpochMillis = System.currentTimeMillis())
            )
        } else {
            taskDao.updateTask(
                resolved.copy(
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    isSyncedToCloud = false
                )
            )
        }
        if (resolvedReminder != null) {
            if (resolvedReminder > System.currentTimeMillis()) {
                reminderManager.scheduleReminder(task.id, task.title, resolvedReminder)
            } else {
                reminderManager.cancelReminder(task.id)
            }
        } else {
            reminderManager.cancelReminder(task.id)
        }
        if (task.groupId == null) {
            syncDirtyTasksToCloud(task.userId)
        }
    }

    suspend fun deleteTask(userId: String, taskId: String) = withContext(Dispatchers.IO) {
        reminderManager.cancelReminder(taskId)
        // Group v1: group rows are deleted from the shared group item by the
        // creator (GroupRepository). A member-side delete is local-only.
        val task = taskDao.getTaskById(taskId)
        taskDao.deleteTaskByIdForUser(userId, taskId)
        if (task?.groupId == null) {
            deleteTaskFromFirestore(userId, taskId)
        }
    }

    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        reminderManager.cancelReminder(taskId)
        val task = taskDao.getTaskById(taskId)
        if (task != null) {
            taskDao.deleteTaskByIdForUser(task.userId, taskId)
            if (task.groupId == null) {
                deleteTaskFromFirestore(task.userId, taskId)
            }
        } else {
            taskDao.deleteTaskById(taskId)
        }
    }

    suspend fun snoozeTask(taskId: String, snoozeEpochMillis: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Group v1: snoozing a group task is a local-only change (same rule as
        // completion) — never dirty-mark a group row.
        val existing = taskDao.getTaskById(taskId)
        if (existing?.groupId != null) {
            taskDao.snoozeTaskLocal(
                id = taskId,
                dueDate = snoozeEpochMillis,
                reminderDate = snoozeEpochMillis,
                now = now
            )
        } else {
            taskDao.snoozeTask(
                id = taskId,
                dueDate = snoozeEpochMillis,
                reminderDate = snoozeEpochMillis,
                now = now
            )
        }
        val task = taskDao.getTaskById(taskId)
        if (task != null) {
            reminderManager.scheduleReminder(task.id, task.title, snoozeEpochMillis)
            if (task.groupId == null) {
                syncDirtyTasksToCloud(task.userId)
            }
        }
    }

    private fun deleteTaskFromFirestore(userId: String, taskId: String) {
        // Guests have no Firebase Auth identity — cloud calls would only
        // fail with permission-denied; local persistence is unaffected.
        if (userId == com.example.auth.AuthManager.GUEST_USER_ID) return
        // C13: record the pending delete in the same step as the local
        // delete (this runs synchronously on the caller's IO thread,
        // before the fire-and-forget attempt below). Removed only when
        // the cloud delete actually succeeds.
        recordPendingTaskDelete(userId, taskId)
        repoScope.launch {
            try {
                val dbId = context.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                firestore.collection("users").document(userId).collection("tasks").document(taskId).delete().await()
                removePendingTaskDelete(userId, taskId)
            } catch (e: Exception) {
                Log.w("TaskRepository", "Failed to delete task from cloud: ${e.message}")
            }
        }
    }

    suspend fun clearCompletedTasks(userId: String) = withContext(Dispatchers.IO) {
        // Group v1: completed group rows are NOT cleared here — the shared
        // group item still exists, so a listener/poll tick would resurrect
        // them. Only personal completed tasks are purged (local + cloud).
        val completed = taskDao.getCompletedTasksOnce(userId)
            .filter { it.groupId == null }
        if (completed.isNotEmpty() && userId != com.example.auth.AuthManager.GUEST_USER_ID) {
            // C13: record every id BEFORE the local purge, so a failed
            // cloud purge cannot resurrect them on the next sync. Entries
            // are removed per item as their cloud delete succeeds; any
            // id left behind is retried by the drain in startCloudSync.
            completed.forEach { recordPendingTaskDelete(userId, it.id) }
            repoScope.launch {
                try {
                    val dbId = context.getString(R.string.firestore_database_id)
                    val firestore = FirebaseFirestore.getInstance(dbId)
                    val tasksColl = firestore.collection("users").document(userId).collection("tasks")
                    for (task in completed) {
                        tasksColl.document(task.id).delete().await()
                        removePendingTaskDelete(userId, task.id)
                    }
                } catch (e: Exception) {
                    Log.w("TaskRepository", "Cloud completed tasks purge warning: ${e.message}")
                }
            }
        }
        taskDao.deleteCompletedPersonalTasks(userId)
    }

    fun startCloudSync(userId: String) {
        if (userId == com.example.auth.AuthManager.GUEST_USER_ID) return
        snapshotListener?.remove()
        // C13: drain pending deletes BEFORE the listener attaches, so its
        // initial snapshot cannot resurrect a task whose cloud delete
        // failed. The drain is time-bounded; the listener additionally
        // skips any id still pending (see below), so a timed-out drain
        // still cannot resurrect.
        repoScope.launch {
            drainPendingTaskDeletes(userId)
            attachTasksListener(userId)
        }
    }

    private fun attachTasksListener(userId: String) {
        try {
            val dbId = context.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(dbId)
            snapshotListener = firestore.collection("users")
                .document(userId)
                .collection("tasks")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("TaskRepository", "Tasks cloud sync listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        repoScope.launch {
                            for (dc in snapshot.documentChanges) {
                                when (dc.type) {
                                    DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                        val doc = dc.document
                                        // C13: a task whose cloud delete is still
                                        // pending must never be re-inserted by
                                        // the initial snapshot (or any later one).
                                        if (doc.id in pendingTaskDeleteIds(userId)) continue
                                        val localTask = taskDao.getTaskById(doc.id)
                                        if (localTask != null && !localTask.isSyncedToCloud) {
                                            // Local changes take precedence until uploaded
                                            continue
                                        }
                                        val title = doc.getString("title") ?: continue
                                        val dueDate = doc.getLong("dueDateEpochMillis") ?: System.currentTimeMillis()
                                        val priorityStr = doc.getString("priority") ?: Priority.MEDIUM.name
                                        val categoryStr = doc.getString("category") ?: TaskCategory.WORK.name
                                        val isCompleted = doc.getBoolean("isCompleted") ?: false
                                        val completedAt = doc.getLong("completedAtEpochMillis")
                                        val reminderSet = doc.getBoolean("reminderSet") ?: false
                                        val reminderEpoch = doc.getLong("reminderEpochMillis")
                                        val description = doc.getString("description")
                                        val recurrence = doc.getString("recurrenceRule")

                                        val priority = try { Priority.valueOf(priorityStr) } catch (e: Exception) { Priority.MEDIUM }
                                        val category = try { TaskCategory.valueOf(categoryStr) } catch (e: Exception) { TaskCategory.WORK }

                                        val entity = TaskEntity(
                                            id = doc.id,
                                            userId = userId,
                                            title = title,
                                            description = description,
                                            dueDateEpochMillis = dueDate,
                                            priority = priority,
                                            category = category,
                                            isCompleted = isCompleted,
                                            completedAtEpochMillis = completedAt,
                                            reminderSet = reminderSet,
                                            reminderEpochMillis = reminderEpoch,
                                            recurrenceRule = recurrence,
                                            isSyncedToCloud = true,
                                            // Group v1: never let a personal-collection
                                            // upsert strip a group row's origin.
                                            groupId = localTask?.groupId
                                        )
                                        taskDao.insertTask(entity)
                                    }
                                    DocumentChange.Type.REMOVED -> {
                                        // Group v1: a personal-doc delete must never
                                        // remove a group row that happens to share
                                        // the id (defensive; ids are UUIDs).
                                        val localRow = taskDao.getTaskById(dc.document.id)
                                        if (localRow?.groupId == null) {
                                            taskDao.deleteTaskByIdForUser(userId, dc.document.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w("TaskRepository", "Could not start tasks cloud sync: ${e.message}")
        }
    }

    fun stopCloudSync() {
        snapshotListener?.remove()
        snapshotListener = null
    }

    /**
     * Seeds initial tasks matching Figma designs on first launch only if both
     * local Room and remote Firestore are empty.
     */
    suspend fun checkAndSeedInitialData(userId: String) = withContext(Dispatchers.IO) {
        // Production: New users start with a clean slate; no fake tasks or unwanted alarms.
    }

    /**
     * Debounced Cloud Sync to Firestore (Minimizes server writes)
     */
    fun syncDirtyTasksToCloud(userId: String, scope: CoroutineScope = repoScope) {
        if (userId == com.example.auth.AuthManager.GUEST_USER_ID) return
        scope.launch(Dispatchers.IO) {
            try {
                // Group v1 (belt-and-braces): group rows are stored with
                // isSyncedToCloud = true, but never upload one even if a
                // future code path dirty-marks it by mistake.
                val unsynced = taskDao.getUnsyncedTasks(userId)
                    .filter { it.groupId == null }
                if (unsynced.isEmpty()) return@launch

                val dbId = context.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                val tasksCollection = firestore.collection("users").document(userId).collection("tasks")

                for (task in unsynced) {
                    val payload = hashMapOf<String, Any>(
                        "id" to task.id,
                        "userId" to task.userId,
                        "title" to task.title,
                        "dueDateEpochMillis" to task.dueDateEpochMillis,
                        "priority" to task.priority.name,
                        "category" to task.category.name,
                        "isCompleted" to task.isCompleted,
                        "reminderSet" to task.reminderSet,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (task.description != null) payload["description"] = task.description
                    // A null completedAt/reminder MUST delete the field: set()
                    // with merge() keeps a field an omitted key never touches,
                    // so an un-complete (completedAt = null) left the OLD
                    // timestamp in the cloud doc and the listener restored it
                    // locally — the task behaved "as if completed once
                    // before". The listener already reads a missing field as
                    // null, so deleting ends the resurrection. Deleting a
                    // field that was never there is a no-op.
                    if (task.completedAtEpochMillis != null) payload["completedAtEpochMillis"] = task.completedAtEpochMillis
                    else payload["completedAtEpochMillis"] = FieldValue.delete()
                    if (task.reminderEpochMillis != null) payload["reminderEpochMillis"] = task.reminderEpochMillis
                    else payload["reminderEpochMillis"] = FieldValue.delete()
                    if (task.recurrenceRule != null) payload["recurrenceRule"] = task.recurrenceRule

                    tasksCollection.document(task.id).set(payload, SetOptions.merge()).await()
                    taskDao.markTaskSynced(task.id)
                }
            } catch (e: Exception) {
                Log.w("TaskRepository", "Background cloud sync deferred: ${e.message}")
            }
        }
    }

    private fun getTodayBounds(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endOfDay = calendar.timeInMillis

        return Pair(startOfDay, endOfDay)
    }
}
