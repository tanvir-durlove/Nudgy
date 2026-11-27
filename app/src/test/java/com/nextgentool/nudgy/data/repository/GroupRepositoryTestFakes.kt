package com.nextgentool.nudgy.data.repository

import java.util.UUID

/**
 * Sprint 5-0 — in-memory fake for [GroupRemoteGateway] (same placement
 * convention as CalendarSyncTestFakes.kt: test sources, same package as
 * the repository).
 *
 * Stores docs in maps keyed exactly like Firestore collections, so
 * repository tests exercise the real orchestration (validation, code
 * retry, Room writes, error mapping) with no FirebaseApp and no live
 * project. Timestamps the real gateway stamps with
 * FieldValue.serverTimestamp() are stored here as epoch millis.
 *
 * Failure injection: set [failNextWith] to make the next gateway call
 * throw (the repository must map it to Err / cached data, never crash).
 * Code-collision injection: set [collideFirstCodeCheck] to make the
 * first getCodeGroupId call report a collision, forcing the repository's
 * retry loop to run once.
 */
class FakeGroupRemoteGateway : GroupRemoteGateway {

    val groups = mutableMapOf<String, MutableMap<String, Any?>>()
    val codes = mutableMapOf<String, MutableMap<String, Any?>>()
    val members = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()
    val mirrors = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()
    val items = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()
    val notices = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()
    val activity = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()

    private val itemListeners =
        mutableMapOf<String, MutableList<(List<GroupItemChange>) -> Unit>>()
    private val groupDocListeners = mutableMapOf<String, MutableList<() -> Unit>>()
    private val noticeListeners =
        mutableMapOf<String, MutableList<(List<GroupNotice>) -> Unit>>()
    private val activityListeners =
        mutableMapOf<String, MutableList<(List<GroupActivityEntry>) -> Unit>>()

    var failNextWith: Exception? = null
    var collideFirstCodeCheck = false
    var createGroupDocsCalls = 0
    var joinMemberDocsCalls = 0
    var writeMembershipMirrorCalls = 0

    private var codeCheckCount = 0

    fun itemListenerCount(groupId: String): Int = itemListeners[groupId]?.size ?: 0
    fun groupDocListenerCount(groupId: String): Int = groupDocListeners[groupId]?.size ?: 0
    fun noticeListenerCount(groupId: String): Int = noticeListeners[groupId]?.size ?: 0
    fun activityListenerCount(groupId: String): Int = activityListeners[groupId]?.size ?: 0

    /** Delivers listener changes to every registered items listener for the group. */
    fun emitItemChanges(groupId: String, changes: List<GroupItemChange>) {
        itemListeners[groupId]?.toList()?.forEach { it(changes) }
    }

    /** Delivers a group-doc deletion to every registered group-doc listener. */
    fun emitGroupDeleted(groupId: String) {
        groupDocListeners[groupId]?.toList()?.forEach { it() }
    }

    /** Delivers a notices snapshot to every registered notices listener for the group. */
    fun emitNotices(groupId: String, notices: List<GroupNotice>) {
        noticeListeners[groupId]?.toList()?.forEach { it(notices) }
    }

    /** Delivers an activity snapshot to every registered activity listener for the group. */
    fun emitActivity(groupId: String, entries: List<GroupActivityEntry>) {
        activityListeners[groupId]?.toList()?.forEach { it(entries) }
    }

    /** Seeds one notice doc (as the real gateway would have stored it). */
    fun seedNotice(groupId: String, noticeId: String, fields: Map<String, Any?>) {
        notices.getOrPut(groupId) { mutableMapOf() }[noticeId] = fields.toMutableMap()
    }

    /** Seeds one item doc (as the real gateway would have stored it). */
    fun seedItem(groupId: String, itemId: String, fields: Map<String, Any?>) {
        items.getOrPut(groupId) { mutableMapOf() }[itemId] = fields.toMutableMap()
    }

    /** Seeds one group doc (as the real gateway would have stored it). */
    fun seedGroup(groupId: String, fields: Map<String, Any?>) {
        groups[groupId] = fields.toMutableMap()
    }

    private fun maybeThrow() {
        failNextWith?.let {
            failNextWith = null
            throw it
        }
    }

    override suspend fun getCodeGroupId(code: String): String? {
        maybeThrow()
        if (collideFirstCodeCheck && codeCheckCount == 0) {
            codeCheckCount++
            return "g-collision"
        }
        codeCheckCount++
        return codes[code]?.get("groupId") as? String
    }

    override suspend fun getGroupFields(groupId: String): Map<String, Any?>? {
        maybeThrow()
        return groups[groupId]?.toMap()
    }

    override suspend fun createGroupDocs(
        groupId: String,
        groupFields: Map<String, Any>,
        code: String,
        codeFields: Map<String, Any>,
        memberFields: Map<String, Any>,
        mirrorFields: Map<String, Any>
    ) {
        maybeThrow()
        createGroupDocsCalls++
        val now = System.currentTimeMillis()
        groups[groupId] = (groupFields + mapOf(
            "createdAt" to now, "lastUpdatedAt" to now, "lastDeleteAt" to now
        )).toMutableMap()
        codes[code] = (codeFields + mapOf("createdAt" to now)).toMutableMap()
        val uid = memberFields["uid"] as String
        members.getOrPut(groupId) { mutableMapOf() }[uid] =
            (memberFields + mapOf("joinedAt" to now)).toMutableMap()
        mirrors.getOrPut(uid) { mutableMapOf() }[groupId] =
            (mirrorFields + mapOf("joinedAt" to now)).toMutableMap()
    }

    override suspend fun joinMemberDocs(
        groupId: String,
        userId: String,
        memberFields: Map<String, Any>,
        mirrorFields: Map<String, Any>
    ) {
        maybeThrow()
        // Sprint 5 fix round 3 — models the live Firestore rules: the
        // members block has `allow create` + `allow delete` but NO
        // `allow update`, and a batch.set() on an EXISTING member doc
        // is evaluated as an update. So a rejoin that reaches this
        // write is denied (the whole batch, mirror included, fails).
        // The repository must check membership first and never call
        // this for an existing member.
        if (members[groupId]?.containsKey(userId) == true) {
            throw IllegalStateException(
                "PERMISSION_DENIED: member doc already exists (no allow update)"
            )
        }
        joinMemberDocsCalls++
        val now = System.currentTimeMillis()
        members.getOrPut(groupId) { mutableMapOf() }[userId] =
            (memberFields + mapOf("joinedAt" to now)).toMutableMap()
        mirrors.getOrPut(userId) { mutableMapOf() }[groupId] =
            (mirrorFields + mapOf("joinedAt" to now)).toMutableMap()
    }

    override suspend fun getMemberFields(groupId: String): List<Pair<String, Map<String, Any?>>> {
        maybeThrow()
        return members[groupId]?.map { (uid, fields) -> uid to fields.toMap() } ?: emptyList()
    }

    override suspend fun getMemberDoc(groupId: String, userId: String): Map<String, Any?>? {
        maybeThrow()
        return members[groupId]?.get(userId)?.toMap()
    }

    override suspend fun writeMembershipMirror(
        userId: String,
        groupId: String,
        mirrorFields: Map<String, Any>
    ) {
        maybeThrow()
        writeMembershipMirrorCalls++
        mirrors.getOrPut(userId) { mutableMapOf() }[groupId] =
            (mirrorFields + mapOf("joinedAt" to System.currentTimeMillis())).toMutableMap()
    }

    override suspend fun deleteMemberDocs(groupId: String, userId: String) {
        maybeThrow()
        members[groupId]?.remove(userId)
        mirrors[userId]?.remove(groupId)
    }

    override suspend fun getGroupDeleteSignal(groupId: String): Pair<Boolean, Long> {
        maybeThrow()
        val fields = groups[groupId] ?: return false to -1L
        return true to ((fields["lastDeleteAt"] as? Number)?.toLong() ?: -1L)
    }

    override suspend fun getMembershipMirrors(userId: String): List<Map<String, Any?>> {
        maybeThrow()
        return mirrors[userId]?.values?.map { it.toMap() } ?: emptyList()
    }

    override suspend fun deleteMembershipMirror(userId: String, groupId: String) {
        maybeThrow()
        mirrors[userId]?.remove(groupId)
    }

    // Step 2-2 — Profile Edit fan-out: renames the member doc in
    // one group. Throws when the doc is missing (the real gateway's
    // Firestore update() does the same), so the repository's
    // per-group catch — skip one stale group, rename the rest —
    // is exercised instead of hidden.
    override suspend fun updateMemberDisplayName(
        groupId: String,
        userId: String,
        displayName: String
    ) {
        maybeThrow()
        members[groupId]?.get(userId)?.set("displayName", displayName)
            ?: throw IllegalStateException("no member doc for $userId in $groupId")
    }

    override suspend fun writeGroupItem(
        groupId: String,
        itemId: String,
        fields: Map<String, Any>,
        merge: Boolean
    ) {
        maybeThrow()
        val groupItems = items.getOrPut(groupId) { mutableMapOf() }
        val existing = groupItems[itemId]
        groupItems[itemId] = if (merge && existing != null) {
            (existing + fields).toMutableMap()
        } else {
            fields.toMutableMap()
        }
    }

    override suspend fun deleteGroupItem(groupId: String, itemId: String) {
        maybeThrow()
        items[groupId]?.remove(itemId)
        groups[groupId]?.set("lastDeleteAt", System.currentTimeMillis())
    }

    override suspend fun getGroupItemIds(groupId: String, limit: Long): List<String> {
        maybeThrow()
        return items[groupId]?.keys?.take(limit.toInt()) ?: emptyList()
    }

    override suspend fun deleteGroupDocs(groupId: String, code: String?) {
        maybeThrow()
        items.remove(groupId)
        // Sprint 5-4: the real cascade deletes notices too (the v1
        // cascade left them orphaned); mirror it here.
        notices.remove(groupId)
        // Sprint 5-5: same for the activity history.
        activity.remove(groupId)
        val memberIds = members.remove(groupId)?.keys?.toList() ?: emptyList()
        for (uid in memberIds) mirrors[uid]?.remove(groupId)
        if (code != null) codes.remove(code)
        groups.remove(groupId)
    }

    override suspend fun queryItemsSince(groupId: String, lastSeenMs: Long): List<GroupItemDoc> {
        maybeThrow()
        val cutoff = lastSeenMs - 60_000L
        return items[groupId]?.mapNotNull { (itemId, fields) ->
            val updatedAt = (fields["updatedAt"] as? Number)?.toLong()
                ?: System.currentTimeMillis()
            if (updatedAt <= cutoff) return@mapNotNull null
            groupItemDocFromFields(itemId, fields)
        } ?: emptyList()
    }

    override suspend fun runCompletionToggle(
        groupId: String,
        itemId: String,
        uid: String,
        completed: Boolean
    ): GroupCompletion? {
        maybeThrow()
        val fields = items[groupId]?.get(itemId) ?: return null
        val updated = toggleCompletion(completionFromFields(fields), uid, completed)
        fields.putAll(completionToFields(updated))
        return updated
    }

    override suspend fun createNotice(groupId: String, fields: Map<String, Any>): String {
        maybeThrow()
        val id = UUID.randomUUID().toString()
        notices.getOrPut(groupId) { mutableMapOf() }[id] =
            (fields + mapOf("createdAt" to System.currentTimeMillis())).toMutableMap()
        return id
    }

    override suspend fun getNotices(groupId: String): List<Pair<String, Map<String, Any?>>> {
        maybeThrow()
        return notices[groupId]?.map { (id, fields) -> id to fields.toMap() }
            ?.sortedByDescending { (_, fields) ->
                (fields["createdAt"] as? Number)?.toLong() ?: 0L
            } ?: emptyList()
    }

    override suspend fun queryNoticesSince(groupId: String, lastSeenMs: Long): List<GroupNotice> {
        maybeThrow()
        val cutoff = lastSeenMs - 60_000L
        return notices[groupId]?.mapNotNull { (noticeId, fields) ->
            val createdAt = (fields["createdAt"] as? Number)?.toLong() ?: 0L
            if (createdAt <= cutoff) return@mapNotNull null
            noticeFromDoc(noticeId, groupId, fields)
        }?.sortedBy { it.createdAtEpochMillis } ?: emptyList()
    }

    override suspend fun addActivity(groupId: String, fields: Map<String, Any>): String {
        maybeThrow()
        val id = UUID.randomUUID().toString()
        activity.getOrPut(groupId) { mutableMapOf() }[id] =
            (fields + mapOf("createdAt" to System.currentTimeMillis())).toMutableMap()
        return id
    }

    override suspend fun getActivity(groupId: String): List<Pair<String, Map<String, Any?>>> {
        maybeThrow()
        return activity[groupId]?.map { (id, fields) -> id to fields.toMap() }
            ?.sortedByDescending { (_, fields) ->
                (fields["createdAt"] as? Number)?.toLong() ?: 0L
            } ?: emptyList()
    }

    override fun addItemsListener(
        groupId: String,
        onChanges: (List<GroupItemChange>) -> Unit
    ): () -> Unit {
        val list = itemListeners.getOrPut(groupId) { mutableListOf() }
        list.add(onChanges)
        return { list.remove(onChanges) }
    }

    override fun addGroupDocListener(groupId: String, onDeleted: () -> Unit): () -> Unit {
        val list = groupDocListeners.getOrPut(groupId) { mutableListOf() }
        list.add(onDeleted)
        return { list.remove(onDeleted) }
    }

    override fun addActivityListener(
        groupId: String,
        onActivity: (List<GroupActivityEntry>) -> Unit
    ): () -> Unit {
        val list = activityListeners.getOrPut(groupId) { mutableListOf() }
        list.add(onActivity)
        // The real listener fires its initial snapshot on attach.
        onActivity(
            activity[groupId]?.mapNotNull { (id, fields) -> activityFromDoc(id, groupId, fields) }
                ?.sortedByDescending { it.createdAtEpochMillis } ?: emptyList()
        )
        return { list.remove(onActivity) }
    }

    override fun addNoticesListener(
        groupId: String,
        onNotices: (List<GroupNotice>) -> Unit
    ): () -> Unit {
        val list = noticeListeners.getOrPut(groupId) { mutableListOf() }
        list.add(onNotices)
        // The real listener fires its initial snapshot on attach.
        onNotices(
            notices[groupId]?.mapNotNull { (id, fields) -> noticeFromDoc(id, groupId, fields) }
                ?.sortedByDescending { it.createdAtEpochMillis } ?: emptyList()
        )
        return { list.remove(onNotices) }
    }
}
