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
import com.google.firebase.firestore.FieldValue
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Group repository (Phase 1 group core + Phase 3 delete/leave/member list + Sprint 5-0 foundation).
 *
 * Firestore layout (shared docs, NOT per-member copies):
 *   groups/{gid}                         — name, code, creatorUid, creatorName, timestamps
 *   groups/{gid}/members/{uid}           — membership + display name
 *   groups/{gid}/items/{itemId}          — kind TASK | SCHEDULE + item payload
 *                                          + completedBy [uid] + completedCount (S5-0)
 *   groups/{gid}/notices/{noticeId}      — authorUid, authorName, text, createdAt (S5-0, text-only)
 *   groupCodes/{CODE}                    — doc id IS the code (atomic uniqueness via existence)
 *   users/{uid}/groupMemberships/{gid}   — per-user mirror for fast group listing
 *
 * Locally, group items live in the SAME Room tables as personal items, distinguished
 * by TaskEntity.groupId / CalendarEventEntity.groupId (non-null = group row).
 *
 * Completion is SHARED since Sprint 5-0 (feature 31): a member's completion
 * is written to the item doc as completedBy + completedCount (see
 * [setGroupTaskCompleted] — one member counted once, toggle removes).
 * The LOCAL isCompleted flag is still the member's own state and is
 * preserved on every listener/poll upsert.
 *
 * Group arrivals are the deliberate exception to the personal-path M4 gap: when a
 * group task arrives (listener or poll), its exact alarm is scheduled immediately.
 *
 * Firestore access goes ONLY through [GroupRemoteGateway] (Sprint 5-0 seam):
 * production uses [FirestoreGroupRemoteGateway]; tests inject a fake, so no
 * FirebaseApp is ever touched by a unit test. The listener and poll paths
 * go through the seam too (the gateway converts snapshots to [GroupItemDoc]).
 */
sealed class GroupResult<out T> {
    data class Ok<T>(val value: T) : GroupResult<T>()
    data class Err(val message: String) : GroupResult<Nothing>()
}

/** A group member row, as stored in groups/{gid}/members/{uid}. */
data class GroupMember(val uid: String, val displayName: String)

class GroupRepository(
    private val context: Context,
    database: NudgyDatabase = NudgyDatabase.getInstance(context),
    remoteGateway: GroupRemoteGateway? = null
) {
    private val groupDao = database.groupDao()
    private val groupMemberDao = database.groupMemberDao()
    private val taskDao = database.taskDao()
    private val calendarDao = database.calendarDao()
    private val reminderManager = ReminderManager(context)
    private val dbId = context.getString(R.string.firestore_database_id)
    private val remote: GroupRemoteGateway =
        remoteGateway ?: FirestoreGroupRemoteGateway(dbId)
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val groupListeners = mutableMapOf<String, () -> Unit>()
    private val groupDocListeners = mutableMapOf<String, () -> Unit>()
    /**
     * Sprint 5-2 (feature 31): the shared completion per item doc,
     * as the listeners and the poll last reported it (keyed by item
     * id). In-memory only — no new Room table: the listeners re-fire
     * the full snapshot on every attach, so the counts repopulate on
     * restart, and the poll upsert path feeds the same map. The UI
     * filters it through [effectiveCompletion] (current members only).
     * [MutableStateFlow] is thread-safe: writers are the listener
     * scope, the poll path and [setGroupTaskCompleted].
     */
    private val completionFlow =
        MutableStateFlow<Map<String, GroupCompletion>>(emptyMap())

    /** The shared completion map (raw — the UI filters it to current members). */
    fun observeCompletions(): Flow<Map<String, GroupCompletion>> = completionFlow

    /**
     * Sprint 5-3 (feature 32): the notices per group, newest first,
     * as the listener last reported them. In-memory only — no new Room
     * table: the listener re-fires the full snapshot on every attach,
     * so the list repopulates on restart, and [refreshNotices] + the
     * optimistic prepend in [createGroupNotice] feed the same map.
     * [MutableStateFlow] is thread-safe: writers are the listener
     * scope, the refresh path and [createGroupNotice].
     */
    private val noticesFlow =
        MutableStateFlow<Map<String, List<GroupNotice>>>(emptyMap())

    /** The notices of one group, newest first (empty until the first snapshot). */
    fun observeNotices(groupId: String): Flow<List<GroupNotice>> =
        noticesFlow.map { it[groupId] ?: emptyList() }

    private val noticeListeners = mutableMapOf<String, () -> Unit>()

    companion object {
        private const val TAG = "GroupRepository"
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val CODE_LENGTH = 6
        private const val MAX_CODE_ATTEMPTS = 5
        private const val RECONCILE_LIMIT = 500L
    }

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
            var lastError: Exception? = null
            repeat(MAX_CODE_ATTEMPTS) {
                val code = generateCode()
                try {
                    if (remote.getCodeGroupId(code) != null) {
                        return@repeat // collision: regenerate
                    }
                    val groupId = UUID.randomUUID().toString()
                    remote.createGroupDocs(
                        groupId = groupId,
                        groupFields = groupCreateFields(trimmed, code, userId, displayName),
                        code = code,
                        codeFields = codeDocFields(groupId),
                        memberFields = memberCreateFields(userId, displayName, code),
                        mirrorFields = mirrorFields(groupId, trimmed, code)
                    )
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
                val groupId = remote.getCodeGroupId(code)
                    ?: return@withContext GroupResult.Err(
                        "That code doesn't match any group. Check the code and try again."
                    )
                val existing = groupDao.getGroupById(groupId)
                if (existing != null) {
                    return@withContext GroupResult.Ok(existing) // re-join = no-op success
                }
                val groupFields = remote.getGroupFields(groupId)
                val name = groupFields?.get("name") as? String
                    ?: return@withContext GroupResult.Err("That group is no longer available.")
                val creatorUid = groupFields["creatorUid"] as? String ?: ""
                remote.joinMemberDocs(
                    groupId = groupId,
                    userId = userId,
                    memberFields = memberCreateFields(userId, displayName, code),
                    mirrorFields = mirrorFields(groupId, name, code)
                )
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

    /**
     * Sprint 5-2 (Q3=(b)): only the group's creator may write items.
     * Members can neither create nor edit — they only complete (the
     * completion-only update rule enforces it remotely too). The local
     * group row is the check; a missing row means "not a member here".
     */
    private suspend fun requireCreator(groupId: String, uid: String): GroupResult<Unit> {
        if (uid == AuthManager.GUEST_USER_ID) {
            return GroupResult.Err("Sign in to change group tasks.")
        }
        val local = groupDao.getGroupById(groupId)
            ?: return GroupResult.Err("Group not found.")
        if (local.creatorUid != uid) {
            return GroupResult.Err("Only the creator can change group tasks.")
        }
        return GroupResult.Ok(Unit)
    }

    private suspend fun writeGroupItem(groupId: String, itemId: String, payload: Map<String, Any>) {
        remote.writeGroupItem(groupId, itemId, payload, merge = false)
    }

    private suspend fun updateGroupItem(groupId: String, itemId: String, payload: Map<String, Any>) {
        remote.writeGroupItem(groupId, itemId, payload, merge = true)
    }

    /**
     * Creator-only (Q3=(b)): creates a group task — the shared item doc
     * plus the creator's own local row. Members see it arrive through
     * the listener/poll path, with a group badge, in their own list.
     */
    suspend fun createGroupTask(
        groupId: String,
        creatorUid: String,
        title: String,
        description: String?,
        dueDateEpochMillis: Long,
        priority: Priority,
        category: TaskCategory,
        reminderSet: Boolean
    ): GroupResult<TaskEntity> = withContext(Dispatchers.IO) {
        val creatorCheck = requireCreator(groupId, creatorUid)
        if (creatorCheck is GroupResult.Err) return@withContext creatorCheck
        if (title.trim().isEmpty()) {
            return@withContext GroupResult.Err("Enter a task title.")
        }
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
        try {
            writeGroupItem(groupId, itemId, payload)
        } catch (e: Exception) {
            Log.w(TAG, "createGroupTask failed: ${e.message}")
            return@withContext GroupResult.Err(
                "Could not post the task. Check your connection and try again."
            )
        }
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
        GroupResult.Ok(task)
    }

    /** Creator-only (Q3=(b)): creates a group schedule item (no completion count — Q4=(a)). */
    suspend fun createGroupSchedule(
        groupId: String,
        creatorUid: String,
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        locationOrLink: String?,
        eventType: String
    ): GroupResult<CalendarEventEntity> = withContext(Dispatchers.IO) {
        val creatorCheck = requireCreator(groupId, creatorUid)
        if (creatorCheck is GroupResult.Err) return@withContext creatorCheck
        if (title.trim().isEmpty()) {
            return@withContext GroupResult.Err("Enter a schedule title.")
        }
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
        try {
            writeGroupItem(groupId, itemId, payload)
        } catch (e: Exception) {
            Log.w(TAG, "createGroupSchedule failed: ${e.message}")
            return@withContext GroupResult.Err(
                "Could not post the schedule. Check your connection and try again."
            )
        }
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
        GroupResult.Ok(event)
    }

    /**
     * Creator-only (Q3=(b)): edits a group task. The member-local
     * completion flag is preserved. [actorUid] is the signed-in user
     * making the edit (the local row's userId is the row owner, not
     * necessarily the actor — never use it for the creator check).
     */
    suspend fun updateGroupTask(
        groupId: String,
        actorUid: String,
        task: TaskEntity
    ): GroupResult<Unit> {
        return withContext(Dispatchers.IO) {
            if (task.groupId != groupId) {
                return@withContext GroupResult.Err("That task doesn't belong to this group.")
            }
            val creatorCheck = requireCreator(groupId, actorUid)
            if (creatorCheck is GroupResult.Err) return@withContext creatorCheck
            if (task.title.trim().isEmpty()) {
                return@withContext GroupResult.Err("Enter a task title.")
            }
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
            try {
                updateGroupItem(groupId, task.id, payload)
            } catch (e: Exception) {
                Log.w(TAG, "updateGroupTask failed: ${e.message}")
                return@withContext GroupResult.Err(
                    "Could not save the task. Check your connection and try again."
                )
            }
            val existing = taskDao.getTaskById(task.id)
            val merged = task.copy(
                isCompleted = existing?.isCompleted ?: task.isCompleted,
                completedAtEpochMillis = existing?.completedAtEpochMillis,
                // DONE ⇔ isCompleted: the status twin of the preserved
                // flag must follow it, or the row lands completed in
                // the TODO column (a stale edit passing status TODO
                // while the local row is DONE). When the local row is
                // not completed, the editor's status stands.
                status = if (existing?.isCompleted == true) TaskStatus.DONE
                else task.status,
                isSyncedToCloud = true,
                updatedAtEpochMillis = System.currentTimeMillis()
            )
            taskDao.insertTask(merged)
            scheduleGroupTaskAlarm(merged)
            GroupResult.Ok(Unit)
        }
    }

    /** Creator-only (Q3=(b)): deletes one group item, locally and remotely. */
    suspend fun deleteGroupItem(
        groupId: String,
        userId: String,
        itemId: String,
        isTask: Boolean
    ): GroupResult<Unit> {
        return withContext(Dispatchers.IO) {
            val creatorCheck = requireCreator(groupId, userId)
            if (creatorCheck is GroupResult.Err) return@withContext creatorCheck
            try {
                remote.deleteGroupItem(groupId, itemId)
            } catch (e: Exception) {
                Log.w(TAG, "deleteGroupItem failed: ${e.message}")
                return@withContext GroupResult.Err(
                    "Could not delete the item. Check your connection and try again."
                )
            }
            if (isTask) {
                taskDao.deleteTaskByIdForUser(userId, itemId)
                reminderManager.cancelReminder(itemId)
            } else {
                calendarDao.deleteEventByIdForUser(userId, itemId)
            }
            GroupResult.Ok(Unit)
        }
    }

    // ---------------------------------------------------------------- Sprint 5-0: shared completion + notices

    /**
     * Toggles one member's completion on a group task (feature 31, spec §5).
     *
     * The shared state lives on the item doc (completedBy + completedCount,
     * updated atomically in a transaction — one member counted once, a
     * second tap changes nothing). The member's LOCAL isCompleted flag is
     * updated in the same call, so the local list and the shared count
     * never disagree on this device.
     */
    suspend fun setGroupTaskCompleted(
        groupId: String,
        itemId: String,
        userId: String,
        completed: Boolean
    ): GroupResult<GroupCompletion> =
        withContext(Dispatchers.IO) {
            if (userId == AuthManager.GUEST_USER_ID) {
                return@withContext GroupResult.Err("Sign in to update a group task.")
            }
            try {
                val updated = remote.runCompletionToggle(groupId, itemId, userId, completed)
                    ?: return@withContext GroupResult.Err("That task is no longer available.")
                // S5-2: the shared count updates here too, so the
                // detail shows the new count without waiting for the
                // listener round-trip (the listener re-fires the same
                // values, so this is idempotent).
                completionFlow.update { it + (itemId to updated) }
                val now = System.currentTimeMillis()
                val local = taskDao.getTaskById(itemId)
                if (local != null && local.groupId == groupId) {
                    val merged = local.copy(
                        isCompleted = completed,
                        completedAtEpochMillis = if (completed) now else null,
                        // DONE ⇔ isCompleted; an IN_PROGRESS row that is
                        // un-completed keeps IN_PROGRESS, it never drops
                        // to TODO through a completion toggle.
                        status = if (completed) TaskStatus.DONE
                        else if (local.status == TaskStatus.DONE) TaskStatus.TODO
                        else local.status,
                        updatedAtEpochMillis = now
                    )
                    taskDao.insertTask(merged)
                    scheduleGroupTaskAlarm(merged)
                }
                GroupResult.Ok(updated)
            } catch (e: Exception) {
                Log.w(TAG, "setGroupTaskCompleted failed: ${e.message}")
                GroupResult.Err("Could not update the task. Check your connection and try again.")
            }
        }

    /**
     * Sends a text-only admin notice (feature 32, spec §6). Creator-only:
     * the local group row is the check (the rules enforce it remotely too).
     */
    suspend fun createGroupNotice(
        groupId: String,
        authorUid: String,
        authorName: String,
        rawText: String
    ): GroupResult<GroupNotice> =
        withContext(Dispatchers.IO) {
            if (authorUid == AuthManager.GUEST_USER_ID) {
                return@withContext GroupResult.Err("Sign in to send a notice.")
            }
            val text = rawText.trim()
            if (text.isEmpty()) {
                return@withContext GroupResult.Err("Write the notice first.")
            }
            if (text.length > MAX_NOTICE_TEXT_LENGTH) {
                return@withContext GroupResult.Err("The notice is too long.")
            }
            val local = groupDao.getGroupById(groupId)
                ?: return@withContext GroupResult.Err("Group not found.")
            if (local.creatorUid != authorUid) {
                return@withContext GroupResult.Err("Only the creator can send a notice.")
            }
            try {
                val id = remote.createNotice(
                    groupId,
                    noticeToFields(authorUid, authorName, text)
                )
                val notice = GroupNotice(
                    id = id,
                    groupId = groupId,
                    authorUid = authorUid,
                    authorName = authorName,
                    text = text,
                    createdAtEpochMillis = System.currentTimeMillis()
                )
                // Sprint 5-3: the detail shows the new notice without
                // waiting for the listener round-trip (the listener
                // re-fires the same list, so this is idempotent — the
                // snapshot replaces the whole list, never appends).
                noticesFlow.update { current ->
                    val existing = current[groupId] ?: emptyList()
                    current + (groupId to listOf(notice) + existing.filter { it.id != id })
                }
                GroupResult.Ok(notice)
            } catch (e: Exception) {
                Log.w(TAG, "createGroupNotice failed: ${e.message}")
                GroupResult.Err("Could not send the notice. Check your connection and try again.")
            }
        }

    /** Notices of a group, newest first. A failed read returns empty — never throws. */
    suspend fun getGroupNotices(groupId: String): List<GroupNotice> =
        withContext(Dispatchers.IO) {
            try {
                remote.getNotices(groupId).mapNotNull { (id, fields) ->
                    noticeFromDoc(id, groupId, fields)
                }
            } catch (e: Exception) {
                Log.w(TAG, "getGroupNotices failed: ${e.message}")
                emptyList()
            }
        }

    /**
     * Sprint 5-3 — one-shot notices refresh for the detail: reads the
     * list and feeds the flow, so the detail shows notices even before
     * the listener's first snapshot. Returns the fresh list (empty on
     * failure — the previous list stays in the flow, never wiped).
     */
    suspend fun refreshNotices(groupId: String): List<GroupNotice> =
        withContext(Dispatchers.IO) {
            val fresh = getGroupNotices(groupId)
            if (fresh.isNotEmpty()) {
                noticesFlow.update { it + (groupId to fresh) }
            }
            fresh
        }

    /**
     * Sprint 5-3 — notices changed since [lastSeenMs] (the poll
     * query). The worker decides what is genuinely new (see
     * [filterNewNotices]) — notices are not stored locally, so the
     * dedup lives in the worker's seen set, not here.
     */
    suspend fun queryGroupNoticesSince(groupId: String, lastSeenMs: Long): List<GroupNotice> =
        withContext(Dispatchers.IO) { remote.queryNoticesSince(groupId, lastSeenMs) }

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
                val fresh = remote.getMemberFields(groupId).map { (docId, fields) ->
                    memberFromDoc(fields["uid"] as? String ?: docId, fields)
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
        withContext(Dispatchers.IO) { remote.getGroupDeleteSignal(groupId) }

    /**
     * Local purge for one group: cancel its alarms, delete its local
     * tasks/events and its group row, drop its listeners, and clear its
     * poll watermarks (so a later rejoin gets a fresh baseline tick
     * instead of a notification flood). Remote state is untouched —
     * callers delete remotely first where required.
     */
    suspend fun purgeLocalGroup(userId: String, groupId: String) {
        withContext(Dispatchers.IO) {
            // S5-2: drop this group's shared completions from the flow
            // before the rows go (a later rejoin re-fires the listener
            // snapshot and repopulates them — never a stale count).
            val taskIds = taskDao.getTaskIdsByGroupId(userId, groupId)
            completionFlow.update { current -> current - taskIds.toSet() }
            // S5-3: same for notices — a later rejoin re-fires the
            // notices snapshot and repopulates them.
            noticesFlow.update { current -> current - groupId }
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
                remote.deleteMemberDocs(groupId, userId)
                // S5-2 (Q6=(a)): leaving removes the member from the
                // shared counts — best-effort sweep, never fails the
                // leave itself (the display filters to current members
                // too, so a partial sweep can never show a stale count;
                // a rejoin is fresh because the UID is gone from the
                // arrays). Bounded by the poll query limit (50 docs).
                try {
                    sweepLeaverCompletions(groupId, userId)
                } catch (e: Exception) {
                    Log.w(TAG, "leaveGroup completion sweep failed: ${e.message}")
                }
                purgeLocalGroup(userId, groupId)
                GroupResult.Ok(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "leaveGroup failed: ${e.message}")
                GroupResult.Err("Could not leave the group. Check your connection and try again.")
            }
        }

    /**
     * Removes [uid] from the completedBy array of every item doc they
     * had completed. Reads the current docs first (only completed
     * items need a toggle — the array ops in runCompletionToggle are
     * idempotent, but a blind toggle would also move the count).
     * queryItemsSince(0) returns every item doc (60s overlap window
     * is irrelevant at epoch 0), capped at the poll query limit.
     */
    private suspend fun sweepLeaverCompletions(groupId: String, uid: String) {
        val docs = remote.queryItemsSince(groupId, 0L)
        for (doc in docs) {
            if (uid in doc.completedBy) {
                remote.runCompletionToggle(groupId, doc.id, uid, false)
            }
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
                val code = remote.getGroupFields(groupId)?.get("code") as? String
                remote.deleteGroupDocs(groupId, code)
                purgeLocalGroup(userId, groupId)
                GroupResult.Ok(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "deleteGroup failed: ${e.message}")
                GroupResult.Err("Could not delete the group. Check your connection and try again.")
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
                val mirrors = remote.getMembershipMirrors(userId)
                // C12: the mirror is deleted atomically with membership —
                // leaveGroup and deleteGroup remove the member doc and the
                // mirror in one batch — so a local group with no mirror
                // entry means "left on another device". Purge it here
                // instead of letting it linger with frozen items. This runs
                // on every sign-in (MainActivity) and costs zero extra
                // reads (the mirror list is already fetched). A failed
                // mirror read throws before this point, so a transient
                // failure can never purge a healthy group.
                val mirrorIds = mirrors.map { it["groupId"] as? String ?: "" }.toSet()
                for (localId in groupDao.getGroupIdsOnce()) {
                    if (localId !in mirrorIds) {
                        Log.w(TAG, "restoreGroupsFromMirror: purging stale local group $localId (no mirror entry — left on another device)")
                        purgeLocalGroup(userId, localId)
                    }
                }
                for (mirror in mirrors) {
                    val groupId = mirror["groupId"] as? String ?: continue
                    if (groupDao.getGroupById(groupId) != null) continue
                    val groupFields = remote.getGroupFields(groupId)
                    if (groupFields == null) {
                        // Stale mirror: the group is gone, so drop the mirror too.
                        remote.deleteMembershipMirror(userId, groupId)
                        continue
                    }
                    val name = mirror["groupName"] as? String
                        ?: groupFields["name"] as? String ?: continue
                    val code = mirror["code"] as? String
                        ?: groupFields["code"] as? String ?: ""
                    groupDao.insertGroup(
                        GroupEntity(
                            groupId = groupId,
                            name = name,
                            code = code,
                            creatorUid = groupFields["creatorUid"] as? String ?: "",
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
                attachNoticesListener(groupId)
            }
        }
    }

    fun stopGroupSync() {
        groupListeners.values.forEach { it() }
        groupListeners.clear()
        groupDocListeners.values.forEach { it() }
        groupDocListeners.clear()
        noticeListeners.values.forEach { it() }
        noticeListeners.clear()
    }

    /** Drops all three listeners for one group (leave/delete/purge path). */
    private fun stopGroupListener(groupId: String) {
        groupListeners.remove(groupId)?.invoke()
        groupDocListeners.remove(groupId)?.invoke()
        noticeListeners.remove(groupId)?.invoke()
    }

    /**
     * Sprint 5-3 — watches a group's notices: every snapshot replaces
     * the flow entry (newest first), so the detail list updates live
     * while the app is open (spec §6) and the poll worker's baseline
     * never needs a local copy.
     */
    private fun attachNoticesListener(groupId: String) {
        try {
            val remove = remote.addNoticesListener(groupId) { notices ->
                noticesFlow.update { it + (groupId to notices) }
            }
            noticeListeners[groupId] = remove
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach notices listener for $groupId: ${e.message}")
        }
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
            val remove = remote.addGroupDocListener(groupId) {
                repoScope.launch { purgeLocalGroup(userId, groupId) }
            }
            groupDocListeners[groupId] = remove
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach group-doc listener for $groupId: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- listeners (items)

    private fun attachGroupListener(userId: String, groupId: String) {
        try {
            val remove = remote.addItemsListener(groupId) { changes ->
                repoScope.launch {
                    for (change in changes) {
                        when (change) {
                            is GroupItemChange.Upsert -> when (change.doc.kind) {
                                "TASK" -> applyGroupTaskDoc(userId, groupId, change.doc)
                                "SCHEDULE" -> applyGroupScheduleDoc(userId, groupId, change.doc)
                            }
                            is GroupItemChange.Removed -> {
                                val id = change.id
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
            groupListeners[groupId] = remove
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach listener for $groupId: ${e.message}")
        }
    }

    private suspend fun applyGroupTaskDoc(userId: String, groupId: String, doc: GroupItemDoc) {
        val priority = try {
            Priority.valueOf(doc.priority ?: "MEDIUM")
        } catch (e: Exception) {
            Priority.MEDIUM
        }
        val category = try {
            TaskCategory.valueOf(doc.category ?: "WORK")
        } catch (e: Exception) {
            TaskCategory.WORK
        }
        // S5-2: the shared completion rides on the doc — feed the flow
        // so the detail count updates on every listener/poll upsert.
        completionFlow.update {
            it + (doc.id to GroupCompletion(doc.completedBy, doc.completedCount))
        }
        val existing = taskDao.getTaskById(doc.id)
        val task = TaskEntity(
            id = doc.id,
            userId = userId,
            title = doc.title,
            description = doc.description,
            dueDateEpochMillis = doc.dueDateEpochMillis ?: System.currentTimeMillis(),
            priority = priority,
            category = category,
            isCompleted = existing?.isCompleted ?: false,
            completedAtEpochMillis = existing?.completedAtEpochMillis,
            reminderSet = doc.reminderSet,
            reminderEpochMillis = doc.reminderEpochMillis,
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

    private suspend fun applyGroupScheduleDoc(userId: String, groupId: String, doc: GroupItemDoc) {
        val event = CalendarEventEntity(
            id = doc.id,
            userId = userId,
            title = doc.title,
            startEpochMillis = doc.startEpochMillis ?: System.currentTimeMillis(),
            endEpochMillis = doc.endEpochMillis
                ?: (System.currentTimeMillis() + 3_600_000L),
            locationOrLink = doc.locationOrLink,
            eventType = doc.eventType ?: "EVENT",
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
     * applied in the gateway (never in the caller) so every poll tick shares it.
     * Single-field range on updatedAt — automatic index, no composite
     * index needed.
     */
    suspend fun queryGroupItemsSince(groupId: String, lastSeenMs: Long): List<GroupItemDoc> =
        withContext(Dispatchers.IO) { remote.queryItemsSince(groupId, lastSeenMs) }

    /** All item ids of a group, bounded (reconciliation input). */
    suspend fun queryGroupItemIds(groupId: String): List<String> =
        withContext(Dispatchers.IO) { remote.getGroupItemIds(groupId, RECONCILE_LIMIT) }

    /**
     * Applies polled docs through the same upsert path as the listeners
     * (completion preserved, alarms scheduled on arrival). Returns the
     * count of genuinely NEW item ids — ids already in Room are updates,
     * not new arrivals, so the overlap window can never re-notify.
     */
    suspend fun applyPolledDocs(
        userId: String,
        groupId: String,
        docs: List<GroupItemDoc>
    ): Int = withContext(Dispatchers.IO) {
        var newCount = 0
        for (doc in docs) {
            when (doc.kind) {
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
