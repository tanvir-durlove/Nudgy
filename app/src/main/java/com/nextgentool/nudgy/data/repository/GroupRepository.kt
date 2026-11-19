package com.nextgentool.nudgy.data.repository

import android.content.Context
import android.util.Log
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.GroupMemberEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.reminders.ReminderManager
import com.nextgentool.nudgy.work.GroupPollWorker
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Timestamp
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID

/**
 * Group repository (Phase 1 group core + Phase 3 delete/leave/member list).
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

/** A group member row, as stored in groups/{gid}/members/{uid}. */
data class GroupMember(val uid: String, val displayName: String)

class GroupRepository(
    private val context: Context,
    database: NudgyDatabase = NudgyDatabase.getInstance(context)
) {
    private val groupDao = database.groupDao()
    private val groupMemberDao = database.groupMemberDao()
    private val taskDao = database.taskDao()
    private val calendarDao = database.calendarDao()
    private val reminderManager = ReminderManager(context)
    private val dbId = context.getString(R.string.firestore_database_id)
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val groupListeners = mutableMapOf<String, ListenerRegistration>()
    private val groupDocListeners = mutableMapOf<String, ListenerRegistration>()

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
                            "lastUpdatedAt" to FieldValue.serverTimestamp(),
                            // Minimization: reconcile gate signal. Set at
                            // creation so the field exists from birth; the
                            // poll worker treats a missing field as "unknown"
                            // and falls back to the old daily reconcile.
                            "lastDeleteAt" to FieldValue.serverTimestamp()
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
                            // B3: code proof for the member-create rule —
                            // the creator's first member write must carry
                            // the join code, like every join.
                            "code" to code,
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
                        // B3: code proof for the member-create rule — the
                        // write must carry the join code the user entered,
                        // verified against the groupCodes doc.
                        "code" to code,
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
        if (reminderSet) payload["reminderEpochMillis"] = ReminderManager.dueReminderTriggerMillis(null, dueDateEpochMillis)
        writeGroupItem(groupId, itemId, payload)
        val now = System.currentTimeMillis()
        // S1-4g: a new group row appends at the END of its column (TODO)
        // — a default boardOrder of 0 would collide with the column's
        // top card and break the dense 0..n-1 invariant.
        val boardOrder = (taskDao.getMaxBoardOrder(creatorUid, TaskStatus.TODO.name) ?: -1) + 1
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
            reminderEpochMillis = if (reminderSet) ReminderManager.dueReminderTriggerMillis(null, dueDateEpochMillis) else null,
            isSyncedToCloud = true, // group rows must never enter the personal sync
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            groupId = groupId,
            boardOrder = boardOrder
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
            else payload["description"] = FieldValue.delete()
            if (task.reminderSet) payload["reminderEpochMillis"] = ReminderManager.dueReminderTriggerMillis(task.reminderEpochMillis, task.dueDateEpochMillis)
            else payload["reminderEpochMillis"] = FieldValue.delete()
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
            val fs = firestore()
            val groupRef = fs.collection("groups").document(groupId)
            // Minimization: the item delete and the lastDeleteAt bump MUST
            // share one batch (atomic). The poll worker gates its full
            // reconcile on this bump; a separate write could lose the
            // signal and leave orphaned local rows behind.
            val batch = fs.batch()
            batch.delete(groupRef.collection("items").document(itemId))
            batch.update(groupRef, "lastDeleteAt", FieldValue.serverTimestamp())
            batch.commit().await()
            if (isTask) {
                taskDao.deleteTaskByIdForUser(userId, itemId)
                reminderManager.cancelReminder(itemId)
            } else {
                calendarDao.deleteEventByIdForUser(userId, itemId)
            }
        }
    }

    // ---------------------------------------------------------------- Phase 3: members / leave / delete

    /**
     * Member list with a Room cache (minimization): the detail view reads
     * the cache instantly and refreshes in the background, instead of a
     * full one-shot members query on every open.
     */
    fun observeGroupMembers(groupId: String): Flow<List<GroupMember>> =
        groupMemberDao.getMembersFlow(groupId).map { rows ->
            rows.map { GroupMember(uid = it.uid, displayName = it.displayName) }
        }

    suspend fun getCachedGroupMembers(groupId: String): List<GroupMember> =
        withContext(Dispatchers.IO) {
            groupMemberDao.getMembersOnce(groupId)
                .map { GroupMember(uid = it.uid, displayName = it.displayName) }
        }

    /**
     * Remote refresh: fetch the member list, replace the cache, return
     * the fresh list. On failure the cache is left untouched and the
     * cached list is returned, so a failed refresh never wipes the
     * detail view.
     */
    suspend fun refreshGroupMembers(groupId: String): List<GroupMember> =
        withContext(Dispatchers.IO) {
            try {
                val fresh = firestore().collection("groups").document(groupId)
                    .collection("members")
                    .get().await().documents.map { doc ->
                        GroupMember(
                            uid = doc.getString("uid") ?: doc.id,
                            displayName = doc.getString("displayName") ?: "Member"
                        )
                    }.sortedBy { it.displayName.lowercase() }
                groupMemberDao.replaceMembers(
                    groupId,
                    fresh.map {
                        GroupMemberEntity(
                            groupId = groupId,
                            uid = it.uid,
                            displayName = it.displayName,
                            cachedAtEpochMillis = System.currentTimeMillis()
                        )
                    }
                )
                fresh
            } catch (e: Exception) {
                Log.w(TAG, "refreshGroupMembers failed: ${e.message}")
                getCachedGroupMembers(groupId)
            }
        }

    /**
     * One doc get serving both the worker's existence check and the
     * reconcile gate: whether the group still exists, and its
     * lastDeleteAt signal (epoch ms, or -1 when the field is absent —
     * pre-gating group docs — or the read failed). Callers must never
     * purge on a failed read.
     */
    suspend fun getGroupDeleteSignal(groupId: String): Pair<Boolean, Long> =
        withContext(Dispatchers.IO) {
            try {
                val doc = firestore().collection("groups").document(groupId).get().await()
                if (!doc.exists()) return@withContext false to -1L
                val signal = doc.getTimestamp("lastDeleteAt")?.toDate()?.time ?: -1L
                true to signal
            } catch (e: Exception) {
                Log.w(TAG, "getGroupDeleteSignal failed: ${e.message}")
                true to -1L // never purge on a transient read failure
            }
        }

    /**
     * Local purge for one group: cancel its alarms, delete its local
     * tasks/events and its group row, drop its listeners, and clear its
     * poll watermarks (so a later rejoin gets a fresh baseline tick
     * instead of a notification flood). Remote state is untouched —
     * callers delete remotely first where required.
     */
    suspend fun purgeLocalGroup(userId: String, groupId: String) {
        withContext(Dispatchers.IO) {
            try {
                reconcileGroupItems(userId, groupId, emptySet())
            } catch (e: Exception) {
                Log.w(TAG, "purgeLocalGroup reconcile failed: ${e.message}")
            }
            groupDao.deleteGroupById(groupId)
            groupMemberDao.deleteMembersByGroupId(groupId)
            stopGroupListener(groupId)
            GroupPollWorker.clearGroupState(context, groupId)
        }
    }

    /** Member leave: remote membership + mirror first, local purge second. */
    suspend fun leaveGroup(userId: String, groupId: String): GroupResult<Unit> =
        withContext(Dispatchers.IO) {
            if (userId == AuthManager.GUEST_USER_ID) {
                return@withContext GroupResult.Err("Sign in to leave a group.")
            }
            val local = groupDao.getGroupById(groupId)
                ?: return@withContext GroupResult.Err("Group not found.")
            if (local.creatorUid == userId) {
                return@withContext GroupResult.Err(
                    "The creator can't leave — delete the group instead."
                )
            }
            try {
                val fs = firestore()
                val batch = fs.batch()
                batch.delete(
                    fs.collection("groups").document(groupId)
                        .collection("members").document(userId)
                )
                batch.delete(
                    fs.collection("users").document(userId)
                        .collection("groupMemberships").document(groupId)
                )
                batch.commit().await()
                purgeLocalGroup(userId, groupId)
                GroupResult.Ok(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "leaveGroup failed: ${e.message}")
                GroupResult.Err("Could not leave the group. Check your connection and try again.")
            }
        }

    /**
     * Creator delete cascade. Order matters: the code-doc and mirror
     * delete rules read the group doc, so the group doc is deleted
     * LAST. Local purge runs only after the full remote cascade
     * succeeds, so a partial failure leaves local state consistent.
     */
    suspend fun deleteGroup(userId: String, groupId: String): GroupResult<Unit> =
        withContext(Dispatchers.IO) {
            if (userId == AuthManager.GUEST_USER_ID) {
                return@withContext GroupResult.Err("Sign in to delete a group.")
            }
            val local = groupDao.getGroupById(groupId)
                ?: return@withContext GroupResult.Err("Group not found.")
            if (local.creatorUid != userId) {
                return@withContext GroupResult.Err("Only the creator can delete this group.")
            }
            try {
                val fs = firestore()
                val groupRef = fs.collection("groups").document(groupId)
                val groupDoc = groupRef.get().await()
                val code = groupDoc.getString("code")
                val memberIds = groupRef.collection("members")
                    .limit(500).get().await().documents.map { it.id }
                val itemIds = groupRef.collection("items")
                    .limit(500).get().await().documents.map { it.id }
                val itemRefs = itemIds.map { groupRef.collection("items").document(it) }
                val memberRefs = memberIds.map { groupRef.collection("members").document(it) }
                val mirrorRefs = memberIds.map { uid ->
                    fs.collection("users").document(uid)
                        .collection("groupMemberships").document(groupId)
                }
                deleteRefsInBatches(fs, itemRefs)
                deleteRefsInBatches(fs, memberRefs)
                deleteRefsInBatches(fs, mirrorRefs)
                if (code != null) {
                    fs.collection("groupCodes").document(code).delete().await()
                }
                groupRef.delete().await()
                purgeLocalGroup(userId, groupId)
                GroupResult.Ok(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "deleteGroup failed: ${e.message}")
                GroupResult.Err("Could not delete the group. Check your connection and try again.")
            }
        }

    /** Chunked batch deletes (Firestore caps a batch at 500 writes). */
    private suspend fun deleteRefsInBatches(fs: FirebaseFirestore, refs: List<DocumentReference>) {
        for (chunk in refs.chunked(400)) {
            val batch = fs.batch()
            for (ref in chunk) batch.delete(ref)
            batch.commit().await()
        }
    }

    /**
     * Restores groups from the membership mirror after a fresh install or a
     * new-device sign-in. Room is wiped on uninstall, and startGroupSync only
     * knows local group ids — without this, groups that still exist in
     * Firestore stay invisible. The mirror is the source of truth for
     * membership and already carries groupId + groupName + code, so no extra
     * reads are needed for the local rows (one group-doc get per group only
     * verifies the group still exists).
     *
     * Additive only and idempotent: existing local rows are never touched
     * (re-sign-in never duplicates). A mirror whose group doc is gone is
     * stale, so it is skipped locally and its mirror doc deleted, so it can
     * never resurrect the group. Empty mirror list = no-op. Guests = no-op.
     * Failures are logged, never thrown, so a restore failure can never
     * block the sync that follows.
     */
    suspend fun restoreGroupsFromMirror(userId: String) {
        if (userId == AuthManager.GUEST_USER_ID) return
        try {
            withContext(Dispatchers.IO) {
                val fs = firestore()
                val mirrors = fs.collection("users").document(userId)
                    .collection("groupMemberships").get().await().documents
                // C12: the mirror is deleted atomically with membership —
                // leaveGroup and deleteGroup remove the member doc and the
                // mirror in one batch — so a local group with no mirror
                // entry means "left on another device". Purge it here
                // instead of letting it linger with frozen items. This runs
                // on every sign-in (MainActivity) and costs zero extra
                // reads (the mirror list is already fetched). A failed
                // mirror read throws before this point, so a transient
                // failure can never purge a healthy group.
                val mirrorIds = mirrors.map { it.getString("groupId") ?: it.id }.toSet()
                for (localId in groupDao.getGroupIdsOnce()) {
                    if (localId !in mirrorIds) {
                        Log.w(TAG, "restoreGroupsFromMirror: purging stale local group $localId (no mirror entry — left on another device)")
                        purgeLocalGroup(userId, localId)
                    }
                }
                for (mirror in mirrors) {
                    val groupId = mirror.getString("groupId") ?: continue
                    if (groupDao.getGroupById(groupId) != null) continue
                    val groupDoc = fs.collection("groups").document(groupId).get().await()
                    if (!groupDoc.exists()) {
                        // Stale mirror: the group is gone, so drop the mirror too.
                        mirror.reference.delete().await()
                        continue
                    }
                    val name = mirror.getString("groupName")
                        ?: groupDoc.getString("name") ?: continue
                    val code = mirror.getString("code")
                        ?: groupDoc.getString("code") ?: ""
                    groupDao.insertGroup(
                        GroupEntity(
                            groupId = groupId,
                            name = name,
                            code = code,
                            creatorUid = groupDoc.getString("creatorUid") ?: "",
                            joinedAtEpochMillis = System.currentTimeMillis()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreGroupsFromMirror failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- listeners

    fun startGroupSync(userId: String) {
        if (userId == AuthManager.GUEST_USER_ID) return
        stopGroupSync()
        repoScope.launch {
            val groupIds = groupDao.getGroupIdsOnce()
            for (groupId in groupIds) {
                attachGroupListener(userId, groupId)
                attachGroupDocListener(userId, groupId)
            }
        }
    }

    fun stopGroupSync() {
        groupListeners.values.forEach { it.remove() }
        groupListeners.clear()
        groupDocListeners.values.forEach { it.remove() }
        groupDocListeners.clear()
    }

    /** Drops both listeners for one group (leave/delete/purge path). */
    private fun stopGroupListener(groupId: String) {
        groupListeners.remove(groupId)?.remove()
        groupDocListeners.remove(groupId)?.remove()
    }

    /**
     * Watches the group doc itself: when the creator deletes the group,
     * the doc vanishes (the items listener alone cannot see this, since
     * deleting a parent doc leaves its subcollections behind). Members
     * then purge locally; the creator's cascade already removed the
     * remote members/mirrors, so no remote cleanup is needed here.
     */
    private fun attachGroupDocListener(userId: String, groupId: String) {
        try {
            val registration = firestore().collection("groups").document(groupId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    if (!snapshot.exists()) {
                        repoScope.launch { purgeLocalGroup(userId, groupId) }
                    }
                }
            groupDocListeners[groupId] = registration
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach group-doc listener for $groupId: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- listeners (items)

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
            groupId = groupId,
            // S1-4g: the doc carries no status and no order — both are
            // LOCAL state. A member's local completion (status DONE) and
            // local Board order must survive reconciliation, or every
            // snapshot would flip completed group rows back to TODO and
            // reset their boardOrder to 0. A row never seen before
            // appends at the END of TODO.
            status = existing?.status ?: TaskStatus.TODO,
            boardOrder = existing?.boardOrder
                ?: ((taskDao.getMaxBoardOrder(userId, TaskStatus.TODO.name) ?: -1) + 1)
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
        val trigger = ReminderManager.dueReminderTriggerMillis(task.reminderEpochMillis, task.dueDateEpochMillis)
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
