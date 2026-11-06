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
        repoScope.launch {
            try {
                val dbId = context.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                firestore.collection("users").document(userId).collection("tasks").document(taskId).delete().await()
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
            repoScope.launch {
                try {
                    val dbId = context.getString(R.string.firestore_database_id)
                    val firestore = FirebaseFirestore.getInstance(dbId)
                    val tasksColl = firestore.collection("users").document(userId).collection("tasks")
                    for (task in completed) {
                        tasksColl.document(task.id).delete().await()
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
                    if (task.completedAtEpochMillis != null) payload["completedAtEpochMillis"] = task.completedAtEpochMillis
                    if (task.reminderEpochMillis != null) payload["reminderEpochMillis"] = task.reminderEpochMillis
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
