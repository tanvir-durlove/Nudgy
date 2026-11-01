package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.auth.AuthManager
import com.example.data.local.NudgyDatabase
import com.example.data.local.entity.CalendarEventEntity
import com.example.data.local.entity.GroupEntity
import com.example.data.local.entity.TaskEntity
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import com.example.reminders.ReminderManager
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Timestamp
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID

/**
 * Group v1 repository (Phase 1 of the group feature).
 *
 * Firestore layout (shared docs, NOT per-member copies):
 *   groups/{gid}                         — name, code, creatorUid, creatorName, timestamps
 *   groups/{gid}/members/{uid}           — membership + display name
 *   groups/{gid}/items/{itemId}          — kind TASK | SCHEDULE + item payload
 *   groupCodes/{CODE}                    — doc id IS the code (atomic uniqueness via existence)
 *   users/{uid}/groupMemberships/{gid}   — per-user mirror for fast group listing
 *
 * Locally, group items live in the SAME Room tables as personal items, distinguished
 * by TaskEntity.groupId / CalendarEventEntity.groupId (non-null = group row).
 * Completion stays Room-local: a member's isCompleted is never written to Firestore,
 * and the listener preserves it on every upsert.
 *
 * Group arrivals are the deliberate exception to the personal-path M4 gap: when a
 * group task arrives (listener or poll), its exact alarm is scheduled immediately.
 */
sealed class GroupResult<out T> {
    data class Ok<T>(val value: T) : GroupResult<T>()
    data class Err(val message: String) : GroupResult<Nothing>()
}

class GroupRepository(
    context: Context,
    database: NudgyDatabase = NudgyDatabase.getInstance(context)
) {
    private val groupDao = database.groupDao()
    private val taskDao = database.taskDao()
    private val calendarDao = database.calendarDao()
    private val reminderManager = ReminderManager(context)
    private val dbId = context.getString(R.string.firestore_database_id)
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val groupListeners = mutableMapOf<String, ListenerRegistration>()

    companion object {
        private const val TAG = "GroupRepository"
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val CODE_LENGTH = 6
        private const val MAX_CODE_ATTEMPTS = 5
        /** Poll overlap window (plan §3.5): batch writes share timestamps. */
        private const val POLL_OVERLAP_MS = 60_000L
        private const val POLL_QUERY_LIMIT = 50L
        private const val RECONCILE_LIMIT = 500L
    }

    private fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance(dbId)

    fun observeGroups(): Flow<List<GroupEntity>> = groupDao.getGroupsFlow()

    suspend fun getGroupById(groupId: String): GroupEntity? =
        withContext(Dispatchers.IO) { groupDao.getGroupById(groupId) }

    suspend fun isCreator(groupId: String, uid: String): Boolean =
        getGroupById(groupId)?.creatorUid == uid

    // ---------------------------------------------------------------- create / join

    private fun generateCode(): String {
        val random = SecureRandom()
        return (1..CODE_LENGTH)
            .map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
            .joinToString("")
    }

    /**
     * Creates a group. Writes are sequential (group doc FIRST, then code doc, then
     * members + mirror in one batch) rather than a single transaction: Firestore
     * security rules evaluate get() against committed state, so the code doc's
     * creator check only passes once the group doc exists. Code uniqueness is
     * enforced by the pre-check + retry loop (1-in-1B code space).
     */
    suspend fun createGroup(userId: String, displayName: String, name: String): GroupResult<GroupEntity> =
        withContext(Dispatchers.IO) {
            if (userId == AuthManager.GUEST_USER_ID) {
                return@withContext GroupResult.Err("Sign in to create a group.")
            }
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                return@withContext GroupResult.Err("Enter a group name.")
            }
            val fs = firestore()
            var lastError: Exception? = null
            repeat(MAX_CODE_ATTEMPTS) {
                val code = generateCode()
                try {
                    if (fs.collection("groupCodes").document(code).get().await().exists()) {
                        return@repeat // collision: regenerate
                    }
                    val groupId = UUID.randomUUID().toString()
                    fs.collection("groups").document(groupId).set(
                        mapOf(
                            "name" to trimmed,
                            "code" to code,
                            "creatorUid" to userId,
                            "creatorName" to displayName,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "lastUpdatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    fs.collection("groupCodes").document(code).set(
                        mapOf(
                            "groupId" to groupId,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    val batch = fs.batch()
                    batch.set(
                        fs.collection("groups").document(groupId)
                            .collection("members").document(userId),
                        mapOf(
                            "uid" to userId,
                            "displayName" to displayName,
                            "joinedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    batch.set(
                        fs.collection("users").document(userId)
                            .collection("groupMemberships").document(groupId),
                        mapOf(
                            "groupId" to groupId,
                            "groupName" to trimmed,
                            "code" to code,
                            "joinedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    batch.commit().await()
                    val entity = GroupEntity(
                        groupId = groupId,
                        name = trimmed,
                        code = code,
                        creatorUid = userId,
                        joinedAtEpochMillis = System.currentTimeMillis()
                    )
                    groupDao.insertGroup(entity)
                    startGroupSync(userId)
                    return@withContext GroupResult.Ok(entity)
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "createGroup attempt failed: ${e.message}")
                }
            }
            GroupResult.Err(
                lastError?.message
                    ?: "Could not create the group. Check your connection and try again."
            )
        }

    suspend fun joinGroup(userId: String, displayName: String, rawCode: String): GroupResult<GroupEntity> =
        withContext(Dispatchers.IO) {
            if (userId == AuthManager.GUEST_USER_ID) {
                return@withContext GroupResult.Err("Sign in to join a group.")
            }
            val code = rawCode.trim().uppercase()
            if (code.isEmpty()) {
                return@withContext GroupResult.Err("Enter a join code.")
            }
            try {
                val fs = firestore()
                val codeDoc = fs.collection("groupCodes").document(code).get().await()
                val groupId = codeDoc.getString("groupId")
                    ?: return@withContext GroupResult.Err(
                        "That code doesn't match any group. Check the code and try again."
                    )
                val existing = groupDao.getGroupById(groupId)
                if (existing != null) {
                    return@withContext GroupResult.Ok(existing) // re-join = no-op success
                }
                val groupDoc = fs.collection("groups").document(groupId).get().await()
                val name = groupDoc.getString("name")
                    ?: return@withContext GroupResult.Err("That group is no longer available.")
                val creatorUid = groupDoc.getString("creatorUid") ?: ""
                val batch = fs.batch()
                batch.set(
                    fs.collection("groups").document(groupId)
                        .collection("members").document(userId),
                    mapOf(
                        "uid" to userId,
                        "displayName" to displayName,
                        "joinedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    fs.collection("users").document(userId)
                        .collection("groupMemberships").document(groupId),
                    mapOf(
                        "groupId" to groupId,
                        "groupName" to name,
                        "code" to code,
                        "joinedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.commit().await()
                val entity = GroupEntity(
                    groupId = groupId,
                    name = name,
                    code = code,
                    creatorUid = creatorUid,
                    joinedAtEpochMillis = System.currentTimeMillis()
                )
                groupDao.insertGroup(entity)
                startGroupSync(userId)
                GroupResult.Ok(entity)
            } catch (e: Exception) {
                Log.w(TAG, "joinGroup failed: ${e.message}")
                GroupResult.Err("Could not join the group. Check the code and your connection.")
            }
        }

    // ---------------------------------------------------------------- creator writes

    private suspend fun writeGroupItem(groupId: String, itemId: String, payload: Map<String, Any>) {
        val fs = firestore()
        val batch = fs.batch()
        batch.set(
            fs.collection("groups").document(groupId).collection("items").document(itemId),
            payload
        )
        batch.update(
            fs.collection("groups").document(groupId),
            "lastUpdatedAt", FieldValue.serverTimestamp()
        )
        batch.commit().await()
    }

    private suspend fun updateGroupItem(groupId: String, itemId: String, payload: Map<String, Any>) {
        val fs = firestore()
        val batch = fs.batch()
        batch.set(
            fs.collection("groups").document(groupId).collection("items").document(itemId),
            payload, SetOptions.merge()
        )
        batch.update(
            fs.collection("groups").document(groupId),
            "lastUpdatedAt", FieldValue.serverTimestamp()
        )
        batch.commit().await()
    }

    suspend fun createGroupTask(
        groupId: String,
        creatorUid: String,
        title: String,
        description: String?,
        dueDateEpochMillis: Long,
        priority: Priority,
        category: TaskCategory,
        reminderSet: Boolean
    ): TaskEntity = withContext(Dispatchers.IO) {
        val itemId = UUID.randomUUID().toString()
        val payload = mutableMapOf<String, Any>(
            "kind" to "TASK",
            "title" to title,
            "dueDateEpochMillis" to dueDateEpochMillis,
            "priority" to priority.name,
            "category" to category.name,
            "reminderSet" to reminderSet,
            "createdBy" to creatorUid,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!description.isNullOrBlank()) payload["description"] = description
        if (reminderSet) payload["reminderEpochMillis"] = dueDateEpochMillis
        writeGroupItem(groupId, itemId, payload)
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            id = itemId,
            userId = creatorUid,
            title = title,
            description = description,
            dueDateEpochMillis = dueDateEpochMillis,
            priority = priority,
            category = category,
            isCompleted = false,
            reminderSet = reminderSet,
            reminderEpochMillis = if (reminderSet) dueDateEpochMillis else null,
            isSyncedToCloud = true, // group rows must never enter the personal sync
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            groupId = groupId
        )
        taskDao.insertTask(task)
        scheduleGroupTaskAlarm(task)
        task
    }

    suspend fun createGroupSchedule(
        groupId: String,
        creatorUid: String,
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        locationOrLink: String?,
        eventType: String
    ): CalendarEventEntity = withContext(Dispatchers.IO) {
        val itemId = UUID.randomUUID().toString()
        val payload = mutableMapOf<String, Any>(
            "kind" to "SCHEDULE",
            "title" to title,
            "startEpochMillis" to startEpochMillis,
            "endEpochMillis" to endEpochMillis,
            "eventType" to eventType,
            "createdBy" to creatorUid,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!locationOrLink.isNullOrBlank()) payload["locationOrLink"] = locationOrLink
        writeGroupItem(groupId, itemId, payload)
        val event = CalendarEventEntity(
            id = itemId,
            userId = creatorUid,
            title = title,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            locationOrLink = locationOrLink,
            eventType = eventType,
            isAllDay = false,
            googleCalendarEventId = null, // group schedules never touch Google Calendar
            isFromGoogleCalendar = false,
            groupId = groupId
        )
        calendarDao.insertEvent(event)
        event
    }

    /** Creator edits a group task. The member-local completion flag is preserved. */
    suspend fun updateGroupTask(groupId: String, task: TaskEntity) {
        withContext(Dispatchers.IO) {
            if (task.groupId != groupId) return@withContext
            val payload = mutableMapOf<String, Any>(
                "kind" to "TASK",
                "title" to task.title,
                "dueDateEpochMillis" to task.dueDateEpochMillis,
                "priority" to task.priority.name,
                "category" to task.category.name,
                "reminderSet" to task.reminderSet,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!task.description.isNullOrBlank()) payload["description"] = task.description!!
            if (task.reminderSet) payload["reminderEpochMillis"] = task.reminderEpochMillis
                ?: task.dueDateEpochMillis
            updateGroupItem(groupId, task.id, payload)
            val existing = taskDao.getTaskById(task.id)
            val merged = task.copy(
                isCompleted = existing?.isCompleted ?: task.isCompleted,
                completedAtEpochMillis = existing?.completedAtEpochMillis,
                isSyncedToCloud = true,
                updatedAtEpochMillis = System.currentTimeMillis()
            )
            taskDao.insertTask(merged)
            scheduleGroupTaskAlarm(merged)
        }
    }

    suspend fun deleteGroupItem(groupId: String, userId: String, itemId: String, isTask: Boolean) {
        withContext(Dispatchers.IO) {
            firestore().collection("groups").document(groupId)
                .collection("items").document(itemId).delete().await()
            if (isTask) {
                taskDao.deleteTaskByIdForUser(userId, itemId)
                reminderManager.cancelReminder(itemId)
            } else {
                calendarDao.deleteEventByIdForUser(userId, itemId)
            }
        }
    }

    // ---------------------------------------------------------------- listeners

    fun startGroupSync(userId: String) {
        if (userId == AuthManager.GUEST_USER_ID) return
        stopGroupSync()
        repoScope.launch {
            val groupIds = groupDao.getGroupIdsOnce()
            for (groupId in groupIds) attachGroupListener(userId, groupId)
        }
    }

    fun stopGroupSync() {
        groupListeners.values.forEach { it.remove() }
        groupListeners.clear()
    }

    private fun attachGroupListener(userId: String, groupId: String) {
        try {
            val registration = firestore().collection("groups").document(groupId)
                .collection("items")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Group listener error for $groupId: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener
                    repoScope.launch {
                        for (change in snapshot.documentChanges) {
                            when (change.type) {
                                com.google.firebase.firestore.DocumentChange.Type.ADDED,
                                com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                    when (change.document.getString("kind")) {
                                        "TASK" -> applyGroupTaskDoc(userId, groupId, change.document)
                                        "SCHEDULE" -> applyGroupScheduleDoc(userId, groupId, change.document)
                                    }
                                }
                                com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                                    val id = change.document.id
                                    if (taskDao.getTaskById(id) != null) {
                                        taskDao.deleteTaskById(id)
                                        reminderManager.cancelReminder(id)
                                    } else if (calendarDao.getEventById(id) != null) {
                                        calendarDao.deleteEventById(id)
                                    }
                                }
                            }
                        }
                    }
                }
            groupListeners[groupId] = registration
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach listener for $groupId: ${e.message}")
        }
    }

    private suspend fun applyGroupTaskDoc(userId: String, groupId: String, doc: DocumentSnapshot) {
        val title = doc.getString("title") ?: return
        val due = doc.getLong("dueDateEpochMillis") ?: System.currentTimeMillis()
        val priority = try {
            Priority.valueOf(doc.getString("priority") ?: "MEDIUM")
        } catch (e: Exception) {
            Priority.MEDIUM
        }
        val category = try {
            TaskCategory.valueOf(doc.getString("category") ?: "WORK")
        } catch (e: Exception) {
            TaskCategory.WORK
        }
        val existing = taskDao.getTaskById(doc.id)
        val task = TaskEntity(
            id = doc.id,
            userId = userId,
            title = title,
            description = doc.getString("description"),
            dueDateEpochMillis = due,
            priority = priority,
            category = category,
            isCompleted = existing?.isCompleted ?: false,
            completedAtEpochMillis = existing?.completedAtEpochMillis,
            reminderSet = doc.getBoolean("reminderSet") ?: false,
            reminderEpochMillis = doc.getLong("reminderEpochMillis"),
            recurrenceRule = null,
            isSyncedToCloud = true,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: System.currentTimeMillis(),
            updatedAtEpochMillis = System.currentTimeMillis(),
            groupId = groupId
        )
        taskDao.insertTask(task)
        scheduleGroupTaskAlarm(task)
    }

    private suspend fun applyGroupScheduleDoc(userId: String, groupId: String, doc: DocumentSnapshot) {
        val title = doc.getString("title") ?: return
        val event = CalendarEventEntity(
            id = doc.id,
            userId = userId,
            title = title,
            startEpochMillis = doc.getLong("startEpochMillis") ?: System.currentTimeMillis(),
            endEpochMillis = doc.getLong("endEpochMillis")
                ?: (System.currentTimeMillis() + 3_600_000L),
            locationOrLink = doc.getString("locationOrLink"),
            eventType = doc.getString("eventType") ?: "EVENT",
            isAllDay = false,
            googleCalendarEventId = null,
            isFromGoogleCalendar = false,
            groupId = groupId
        )
        calendarDao.insertEvent(event)
    }

    /** Group-arrival alarm rule: exact alarm on arrival, never the M4 gap. */
    private fun scheduleGroupTaskAlarm(task: TaskEntity) {
        if (!task.reminderSet) return
        if (task.isCompleted) {
            reminderManager.cancelReminder(task.id)
            return
        }
        val trigger = task.reminderEpochMillis ?: task.dueDateEpochMillis
        if (trigger > System.currentTimeMillis()) {
            reminderManager.scheduleReminder(task.id, task.title, trigger)
        } else {
            reminderManager.cancelReminder(task.id)
        }
    }

    // ---------------------------------------------------------------- polling (GroupPollWorker)

    suspend fun getJoinedGroupIds(): List<String> =
        withContext(Dispatchers.IO) { groupDao.getGroupIdsOnce() }

    /**
     * Items changed since [lastSeenMs], with the 60s overlap window
     * applied here (never in the caller) so every poll tick shares it.
     * Single-field range on updatedAt — automatic index, no composite
     * index needed.
     */
    suspend fun queryGroupItemsSince(groupId: String, lastSeenMs: Long): List<DocumentSnapshot> =
        withContext(Dispatchers.IO) {
            firestore().collection("groups").document(groupId).collection("items")
                .whereGreaterThan(
                    "updatedAt",
                    Timestamp(Date(lastSeenMs - POLL_OVERLAP_MS))
                )
                .limit(POLL_QUERY_LIMIT)
                .get().await().documents
        }

    /** All item ids of a group, bounded (reconciliation input). */
    suspend fun queryGroupItemIds(groupId: String): List<String> =
        withContext(Dispatchers.IO) {
            firestore().collection("groups").document(groupId).collection("items")
                .limit(RECONCILE_LIMIT)
                .get().await().documents.map { it.id }
        }

    /**
     * Applies polled docs through the same upsert path as the listeners
     * (completion preserved, alarms scheduled on arrival). Returns the
     * count of genuinely NEW item ids — ids already in Room are updates,
     * not new arrivals, so the overlap window can never re-notify.
     */
    suspend fun applyPolledDocs(
        userId: String,
        groupId: String,
        docs: List<DocumentSnapshot>
    ): Int = withContext(Dispatchers.IO) {
        var newCount = 0
        for (doc in docs) {
            when (doc.getString("kind")) {
                "TASK" -> {
                    if (taskDao.getTaskById(doc.id) == null) newCount++
                    applyGroupTaskDoc(userId, groupId, doc)
                }
                "SCHEDULE" -> {
                    if (calendarDao.getEventById(doc.id) == null) newCount++
                    applyGroupScheduleDoc(userId, groupId, doc)
                }
            }
        }
        newCount
    }

    /**
     * Deletes local group rows (and cancels their alarms) for ids no
     * longer present remotely. Only ever called with a fresh remote id
     * set from queryGroupItemIds — never with a partial or stale set.
     */
    suspend fun reconcileGroupItems(
        userId: String,
        groupId: String,
        remoteIds: Set<String>
    ) {
        withContext(Dispatchers.IO) {
            for (id in taskDao.getTaskIdsByGroupId(userId, groupId)) {
                if (id !in remoteIds) {
                    taskDao.deleteTaskById(id)
                    reminderManager.cancelReminder(id)
                }
            }
            for (id in calendarDao.getEventIdsByGroupId(userId, groupId)) {
                if (id !in remoteIds) {
                    calendarDao.deleteEventById(id)
                }
            }
        }
    }
}
