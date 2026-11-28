package com.nextgentool.nudgy.data.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Date
import kotlinx.coroutines.tasks.await

/**
 * Sprint 5-0 — the Firestore access seam for Groups (spec §3, §8 S5-0).
 *
 * [GroupRepository] talks to Firestore ONLY through this interface.
 * Production gets [FirestoreGroupRemoteGateway] (thin forwarding — the
 * same calls, in the same order, the repository made before); tests
 * inject a fake, so no FirebaseApp — and no live project — is ever
 * touched by a unit test. This is the same seam pattern as
 * [CalendarProviderGateway] (Sprint 2-1).
 *
 * Boundary rules:
 * - Everything crossing the seam is plain Kotlin types (maps, strings,
 *   longs, [GroupItemDoc]). Firestore types ([Timestamp],
 *   [FieldValue] sentinels) are created and normalized INSIDE the real
 *   implementation only — the repository and the pure mapping in
 *   [GroupModels] never see them.
 * - Listeners are part of the seam too: the real implementation
 *   converts [DocumentChange]s to [GroupItemChange]s; the fake records
 *   registrations and lets tests emit changes by hand.
 * - The poll query constants live here (they are Firestore-query
 *   mechanics, not repository policy).
 */
interface GroupRemoteGateway {

    // ---- codes + groups ----

    /** The groupId a join code maps to, or null when the code matches nothing. */
    suspend fun getCodeGroupId(code: String): String?

    /** A group doc's fields, or null when the group does not exist. */
    suspend fun getGroupFields(groupId: String): Map<String, Any?>?

    /**
     * The create-group writes, in order: group doc FIRST, then the code
     * doc, then the creator's member doc + membership mirror in one
     * batch. The order is load-bearing: the code doc's creator check
     * reads the group doc, so it only passes once the group doc exists.
     */
    suspend fun createGroupDocs(
        groupId: String,
        groupFields: Map<String, Any>,
        code: String,
        codeFields: Map<String, Any>,
        memberFields: Map<String, Any>,
        mirrorFields: Map<String, Any>
    )

    /** The join writes: the member doc + the membership mirror in one batch. */
    suspend fun joinMemberDocs(
        groupId: String,
        userId: String,
        memberFields: Map<String, Any>,
        mirrorFields: Map<String, Any>
    )

    /** Every member doc of a group as (doc id, fields) pairs. */
    suspend fun getMemberFields(groupId: String): List<Pair<String, Map<String, Any?>>>

    /**
     * One member doc's fields, or null when the user is not a member.
     * A single-doc get of the caller's OWN doc is rules-safe even for a
     * non-member (allow read when uid == auth.uid) — unlike
     * [getMemberFields], whose collection get a non-member cannot run.
     * Used by joinGroup to tell a first join from a rejoin.
     */
    suspend fun getMemberDoc(groupId: String, userId: String): Map<String, Any?>?

    /**
     * Writes only the membership-mirror doc (users/{uid}/
     * groupMemberships/{gid}). The mirror is owner-writable, so a
     * rejoin-restore can rewrite it without touching the member doc.
     */
    suspend fun writeMembershipMirror(
        userId: String,
        groupId: String,
        mirrorFields: Map<String, Any>
    )

    /** The leave writes: the member doc + the membership mirror in one batch. */
    suspend fun deleteMemberDocs(groupId: String, userId: String)

    /** Whether the group still exists, and its lastDeleteAt signal (epoch ms, or -1 when the field is absent or the read failed — callers must never purge on a failed read). */
    suspend fun getGroupDeleteSignal(groupId: String): Pair<Boolean, Long>

    /** Every membership-mirror doc of a user (raw fields; the repository maps them). */
    suspend fun getMembershipMirrors(userId: String): List<Map<String, Any?>>

    /** Deletes one membership-mirror doc. */
    suspend fun deleteMembershipMirror(userId: String, groupId: String)

    /**
     * Step 2-2 — renames one member doc's displayName (Profile Edit
     * fan-out). Only the displayName key changes; `update()` (not
     * set-merge) so a missing member doc fails instead of creating
     * a phantom membership. The live rules allow exactly this: own
     * doc, displayName key only (firestore.rules, members block).
     */
    suspend fun updateMemberDisplayName(groupId: String, userId: String, displayName: String)

    // ---- items ----

    /** Writes one item doc; also bumps the group's lastUpdatedAt in the same batch. [merge] selects set-with-merge (update path) vs plain set (create path). */
    suspend fun writeGroupItem(
        groupId: String,
        itemId: String,
        fields: Map<String, Any>,
        merge: Boolean
    )

    /** Deletes one item doc; also bumps the group's lastDeleteAt in the same batch (the poll worker's reconcile gate). */
    suspend fun deleteGroupItem(groupId: String, itemId: String)

    /** All item ids of a group, bounded (reconciliation input). */
    suspend fun getGroupItemIds(groupId: String, limit: Long): List<String>

    /**
     * The creator delete cascade, in order: items, then notices, then
     * activity, then members, then mirrors, then the code doc, then the
     * group doc LAST (the code-doc and mirror delete rules read the
     * group doc). Chunked at 400 writes per batch (Firestore caps a
     * batch at 500). Sprint 5-4: notices are deleted too — the v1
     * cascade left the notices subcollection orphaned (subcollections
     * survive their parent doc's deletion); spec §10 item 9 requires
     * them gone. Sprint 5-5: same for the activity history.
     */
    suspend fun deleteGroupDocs(groupId: String, code: String?)

    /** Items changed since [lastSeenMs], with the 60s overlap window applied here (never in the caller). */
    suspend fun queryItemsSince(groupId: String, lastSeenMs: Long): List<GroupItemDoc>

    /**
     * Toggles one member's completion on an item doc (S5-2, Q7=(a):
     * offline-capable). The write is arrayUnion/arrayRemove +
     * increment — both resolve against the local cache while offline
     * and sync when the network returns, where the transaction version
     * failed outright offline. The caller's local isCompleted is the
     * idempotency guard (the UI only ever sends a genuine state
     * change); the rules are the backstop — they reject any write
     * whose count does not equal the array size. The write also bumps
     * updatedAt (a server timestamp) so the poll query sees the
     * change; isCompletionOnlyUpdate allows exactly that third key.
     * Returns the completion read back after the write, or null when
     * the item doc does not exist.
     */
    suspend fun runCompletionToggle(
        groupId: String,
        itemId: String,
        uid: String,
        completed: Boolean
    ): GroupCompletion?

    // ---- notices (text-only, spec §6) ----

    /** Adds a notice doc; returns its id. */
    suspend fun createNotice(groupId: String, fields: Map<String, Any>): String

    /** Every notice of a group as (doc id, fields) pairs, newest first. */
    suspend fun getNotices(groupId: String): List<Pair<String, Map<String, Any?>>>

    /**
     * Sprint 5-3 — notices changed since [lastSeenMs] (the poll
     * query), with the 60s overlap window applied here (never in the
     * caller) so every poll tick shares it. Single-field range on
     * createdAt — automatic index, no composite index needed.
     */
    suspend fun queryNoticesSince(groupId: String, lastSeenMs: Long): List<GroupNotice>

    // ---- activity history (Sprint 5-5, feature 36, Q10=(a)) ----

    /** Adds an activity doc; returns its id. */
    suspend fun addActivity(groupId: String, fields: Map<String, Any>): String

    /** Every activity entry of a group as (doc id, fields) pairs, newest first. */
    suspend fun getActivity(groupId: String): List<Pair<String, Map<String, Any?>>>

    // ---- shared lists (micro-task 2) ----

    /**
     * Writes one shared-list doc. [merge] selects set-with-merge
     * (the rename path — the name only; the rules reject any other
     * changed key) vs plain set (the create path, where the gateway
     * stamps createdAt). No group-doc bump: nothing reads it, and
     * members may only touch lastUpdatedAt there.
     */
    suspend fun writeSharedList(
        groupId: String,
        listId: String,
        fields: Map<String, Any>,
        merge: Boolean
    )

    /**
     * Deletes a whole list remotely: every comment, then every item,
     * then the list doc — the rules do not cascade (same pattern as
     * [deleteGroupDocs]). Chunked at 400 writes per batch.
     */
    suspend fun deleteSharedListDocs(groupId: String, listId: String)

    /**
     * Every shared list of a group, oldest first. A FULL fetch —
     * list docs carry no updatedAt, so there is no since-query.
     */
    suspend fun getSharedLists(groupId: String): List<SharedListDoc>

    /**
     * Writes one shared-list item doc; [merge] selects set-with-merge
     * (done/assign path — the changed keys must stay inside the
     * rules' done/assignee set + updatedAt) vs plain set (create
     * path). The caller passes the server timestamps in [fields]
     * (same pattern as [writeGroupItem]).
     */
    suspend fun writeSharedListItem(
        groupId: String,
        listId: String,
        itemId: String,
        fields: Map<String, Any>,
        merge: Boolean
    )

    /** Deletes one shared-list item remotely: its comments first, then the item doc. */
    suspend fun deleteSharedListItemDocs(groupId: String, listId: String, itemId: String)

    /** All item ids of one list, bounded (reconciliation input). */
    suspend fun getSharedListItemIds(groupId: String, listId: String, limit: Long): List<String>

    /** Items of one list changed since [lastSeenMs], with the 60s overlap window applied here (never in the caller). */
    suspend fun querySharedListItemsSince(
        groupId: String,
        listId: String,
        lastSeenMs: Long
    ): List<SharedListItemDoc>

    /** Writes one comment doc (create only — comments are never edited; the gateway stamps createdAt). */
    suspend fun writeSharedListComment(
        groupId: String,
        listId: String,
        itemId: String,
        commentId: String,
        fields: Map<String, Any>
    )

    /** Deletes one comment doc. */
    suspend fun deleteSharedListComment(
        groupId: String,
        listId: String,
        itemId: String,
        commentId: String
    )

    /** All comment ids of one item, bounded (reconciliation input). */
    suspend fun getSharedListCommentIds(
        groupId: String,
        listId: String,
        itemId: String,
        limit: Long
    ): List<String>

    /** Comments of one item created since [lastSeenMs], with the 60s overlap window applied here (never in the caller). */
    suspend fun querySharedListCommentsSince(
        groupId: String,
        listId: String,
        itemId: String,
        lastSeenMs: Long
    ): List<SharedListCommentDoc>

    // ---- listeners ----

    /** Watches a group's items; returns a remover. The callback receives only non-empty change lists. */
    fun addItemsListener(
        groupId: String,
        onChanges: (List<GroupItemChange>) -> Unit
    ): () -> Unit

    /**
     * Sprint 5-3 — watches a group's notices; returns a remover. The
     * callback receives the FULL list, newest first, on every snapshot
     * (including the initial one) — the repository keeps it in a flow,
     * so the detail list updates live while the app is open.
     */
    fun addNoticesListener(
        groupId: String,
        onNotices: (List<GroupNotice>) -> Unit
    ): () -> Unit

    /**
     * Sprint 5-5 — watches a group's activity history; returns a
     * remover. Same full-list contract as the notices listener.
     */
    fun addActivityListener(
        groupId: String,
        onActivity: (List<GroupActivityEntry>) -> Unit
    ): () -> Unit

    /** Watches a group doc; fires when the doc vanishes (creator deleted the group). Returns a remover. */
    fun addGroupDocListener(groupId: String, onDeleted: () -> Unit): () -> Unit
}

/**
 * The production gateway: thin Firestore forwarding. Every method below
 * is the exact call sequence [GroupRepository] made before the seam
 * existed — same collections, same batching, same ordering. No behaviour
 * changed here, only the location of the calls.
 */
class FirestoreGroupRemoteGateway(
    private val databaseId: String
) : GroupRemoteGateway {

    private fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance(databaseId)

    private fun timestampFields(vararg keys: String): Map<String, Any> =
        keys.associateWith { FieldValue.serverTimestamp() }

    /** Firestore values normalized to plain types: Timestamps become epoch millis (recursively, one level into lists). */
    private fun normalizeFields(fields: Map<String, Any?>): Map<String, Any?> =
        fields.mapValues { (_, value) ->
            when (value) {
                is Timestamp -> value.toDate().time
                is List<*> -> value.map { if (it is Timestamp) it.toDate().time else it }
                else -> value
            }
        }

    // ---- codes + groups ----

    override suspend fun getCodeGroupId(code: String): String? {
        val doc = firestore().collection("groupCodes").document(code).get().await()
        return codeDocGroupId(doc.data ?: return null)
    }

    override suspend fun getGroupFields(groupId: String): Map<String, Any?>? {
        val doc = firestore().collection("groups").document(groupId).get().await()
        if (!doc.exists()) return null
        return normalizeFields(doc.data ?: return null)
    }

    override suspend fun createGroupDocs(
        groupId: String,
        groupFields: Map<String, Any>,
        code: String,
        codeFields: Map<String, Any>,
        memberFields: Map<String, Any>,
        mirrorFields: Map<String, Any>
    ) {
        val fs = firestore()
        // Group doc FIRST (the code doc's creator check reads it).
        fs.collection("groups").document(groupId).set(
            groupFields + timestampFields("createdAt", "lastUpdatedAt", "lastDeleteAt")
        ).await()
        fs.collection("groupCodes").document(code).set(
            codeFields + timestampFields("createdAt")
        ).await()
        val batch = fs.batch()
        batch.set(
            fs.collection("groups").document(groupId)
                .collection("members").document(memberFields["uid"] as String),
            memberFields + timestampFields("joinedAt")
        )
        batch.set(
            fs.collection("users").document(memberFields["uid"] as String)
                .collection("groupMemberships").document(groupId),
            mirrorFields + timestampFields("joinedAt")
        )
        batch.commit().await()
    }

    override suspend fun joinMemberDocs(
        groupId: String,
        userId: String,
        memberFields: Map<String, Any>,
        mirrorFields: Map<String, Any>
    ) {
        val fs = firestore()
        val batch = fs.batch()
        batch.set(
            fs.collection("groups").document(groupId)
                .collection("members").document(userId),
            memberFields + timestampFields("joinedAt")
        )
        batch.set(
            fs.collection("users").document(userId)
                .collection("groupMemberships").document(groupId),
            mirrorFields + timestampFields("joinedAt")
        )
        batch.commit().await()
    }

    override suspend fun getMemberFields(groupId: String): List<Pair<String, Map<String, Any?>>> =
        firestore().collection("groups").document(groupId)
            .collection("members")
            .get().await().documents.map { it.id to normalizeFields(it.data ?: emptyMap()) }

    override suspend fun getMemberDoc(groupId: String, userId: String): Map<String, Any?>? {
        val doc = firestore().collection("groups").document(groupId)
            .collection("members").document(userId)
            .get().await()
        return if (doc.exists()) normalizeFields(doc.data ?: emptyMap()) else null
    }

    override suspend fun writeMembershipMirror(
        userId: String,
        groupId: String,
        mirrorFields: Map<String, Any>
    ) {
        firestore().collection("users").document(userId)
            .collection("groupMemberships").document(groupId)
            .set(mirrorFields + timestampFields("joinedAt")).await()
    }

    override suspend fun deleteMemberDocs(groupId: String, userId: String) {
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
    }

    override suspend fun getGroupDeleteSignal(groupId: String): Pair<Boolean, Long> {
        return try {
            val doc = firestore().collection("groups").document(groupId).get().await()
            if (!doc.exists()) return false to -1L
            val signal = doc.getTimestamp("lastDeleteAt")?.toDate()?.time ?: -1L
            true to signal
        } catch (e: Exception) {
            Log.w(TAG, "getGroupDeleteSignal failed: ${e.message}")
            true to -1L // never purge on a transient read failure
        }
    }

    override suspend fun getMembershipMirrors(userId: String): List<Map<String, Any?>> =
        firestore().collection("users").document(userId)
            .collection("groupMemberships").get().await().documents
            .map { normalizeFields(it.data ?: emptyMap()) }

    override suspend fun deleteMembershipMirror(userId: String, groupId: String) {
        firestore().collection("users").document(userId)
            .collection("groupMemberships").document(groupId)
            .delete().await()
    }

    override suspend fun updateMemberDisplayName(
        groupId: String,
        userId: String,
        displayName: String
    ) {
        firestore().collection("groups").document(groupId)
            .collection("members").document(userId)
            .update("displayName", displayName).await()
    }

    // ---- items ----

    override suspend fun writeGroupItem(
        groupId: String,
        itemId: String,
        fields: Map<String, Any>,
        merge: Boolean
    ) {
        val fs = firestore()
        val batch = fs.batch()
        val itemRef = fs.collection("groups").document(groupId)
            .collection("items").document(itemId)
        if (merge) batch.set(itemRef, fields, SetOptions.merge()) else batch.set(itemRef, fields)
        batch.update(
            fs.collection("groups").document(groupId),
            "lastUpdatedAt", FieldValue.serverTimestamp()
        )
        batch.commit().await()
    }

    override suspend fun deleteGroupItem(groupId: String, itemId: String) {
        val fs = firestore()
        val groupRef = fs.collection("groups").document(groupId)
        // The item delete and the lastDeleteAt bump MUST share one batch
        // (atomic). The poll worker gates its full reconcile on this
        // bump; a separate write could lose the signal and leave
        // orphaned local rows behind.
        val batch = fs.batch()
        batch.delete(groupRef.collection("items").document(itemId))
        batch.update(groupRef, "lastDeleteAt", FieldValue.serverTimestamp())
        batch.commit().await()
    }

    override suspend fun getGroupItemIds(groupId: String, limit: Long): List<String> =
        firestore().collection("groups").document(groupId).collection("items")
            .limit(limit)
            .get().await().documents.map { it.id }

    override suspend fun deleteGroupDocs(groupId: String, code: String?) {
        val fs = firestore()
        val groupRef = fs.collection("groups").document(groupId)
        val memberIds = groupRef.collection("members")
            .limit(500).get().await().documents.map { it.id }
        val itemIds = groupRef.collection("items")
            .limit(500).get().await().documents.map { it.id }
        val noticeIds = groupRef.collection("notices")
            .limit(500).get().await().documents.map { it.id }
        // Sprint 5-5: the activity history lives under the group doc,
        // so the cascade deletes it too — spec §7 item 9 (delete
        // removes everything) covers history as well.
        val activityIds = groupRef.collection("activity")
            .limit(500).get().await().documents.map { it.id }
        val itemRefs = itemIds.map { groupRef.collection("items").document(it) }
        val noticeRefs = noticeIds.map { groupRef.collection("notices").document(it) }
        val activityRefs = activityIds.map { groupRef.collection("activity").document(it) }
        val memberRefs = memberIds.map { groupRef.collection("members").document(it) }
        val mirrorRefs = memberIds.map { uid ->
            fs.collection("users").document(uid)
                .collection("groupMemberships").document(groupId)
        }
        for (refs in listOf(itemRefs, noticeRefs, activityRefs, memberRefs, mirrorRefs)) {
            for (chunk in refs.chunked(400)) {
                val batch = fs.batch()
                for (ref in chunk) batch.delete(ref)
                batch.commit().await()
            }
        }
        if (code != null) {
            fs.collection("groupCodes").document(code).delete().await()
        }
        // Group doc LAST: the code-doc and mirror delete rules read it.
        groupRef.delete().await()
    }

    override suspend fun queryItemsSince(groupId: String, lastSeenMs: Long): List<GroupItemDoc> =
        firestore().collection("groups").document(groupId).collection("items")
            .whereGreaterThan(
                "updatedAt",
                Timestamp(Date(lastSeenMs - POLL_OVERLAP_MS))
            )
            .limit(POLL_QUERY_LIMIT)
            .get().await().documents.mapNotNull { doc ->
                groupItemDocFromFields(doc.id, normalizeFields(doc.data ?: emptyMap()))
            }

    override suspend fun runCompletionToggle(
        groupId: String,
        itemId: String,
        uid: String,
        completed: Boolean
    ): GroupCompletion? {
        val fs = firestore()
        val itemRef = fs.collection("groups").document(groupId)
            .collection("items").document(itemId)
        // Array ops (not a transaction — S5-2, Q7=(a)): arrayUnion /
        // arrayRemove are idempotent and resolve against the local
        // cache while offline; increment moves the count with the
        // array. The read-back returns the post-write state (the
        // cache already holds it, offline or not).
        itemRef.update(
            mapOf<String, Any>(
                "completedBy" to if (completed) FieldValue.arrayUnion(uid)
                else FieldValue.arrayRemove(uid),
                "completedCount" to FieldValue.increment(if (completed) 1L else -1L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        val snap = itemRef.get().await()
        if (!snap.exists()) return null
        return completionFromFields(normalizeFields(snap.data ?: emptyMap()))
    }

    // ---- notices ----

    override suspend fun createNotice(groupId: String, fields: Map<String, Any>): String =
        firestore().collection("groups").document(groupId)
            .collection("notices")
            .add(fields + timestampFields("createdAt"))
            .await().id

    override suspend fun getNotices(groupId: String): List<Pair<String, Map<String, Any?>>> =
        firestore().collection("groups").document(groupId)
            .collection("notices")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(NOTICE_QUERY_LIMIT)
            .get().await().documents.map { it.id to normalizeFields(it.data ?: emptyMap()) }

    override suspend fun queryNoticesSince(groupId: String, lastSeenMs: Long): List<GroupNotice> =
        firestore().collection("groups").document(groupId).collection("notices")
            .whereGreaterThan(
                "createdAt",
                Timestamp(Date(lastSeenMs - POLL_OVERLAP_MS))
            )
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .limit(POLL_QUERY_LIMIT)
            .get().await().documents.mapNotNull { doc ->
                noticeFromDoc(doc.id, groupId, normalizeFields(doc.data ?: emptyMap()))
            }

    // ---- activity history ----

    override suspend fun addActivity(groupId: String, fields: Map<String, Any>): String =
        firestore().collection("groups").document(groupId)
            .collection("activity")
            .add(fields + timestampFields("createdAt"))
            .await().id

    override suspend fun getActivity(groupId: String): List<Pair<String, Map<String, Any?>>> =
        firestore().collection("groups").document(groupId)
            .collection("activity")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(ACTIVITY_QUERY_LIMIT)
            .get().await().documents.map { it.id to normalizeFields(it.data ?: emptyMap()) }

    // ---- shared lists (micro-task 2) ----

    private fun sharedListsRef(groupId: String) =
        firestore().collection("groups").document(groupId).collection("sharedLists")

    private fun sharedListItemsRef(groupId: String, listId: String) =
        sharedListsRef(groupId).document(listId).collection("items")

    private fun sharedListCommentsRef(groupId: String, listId: String, itemId: String) =
        sharedListItemsRef(groupId, listId).document(itemId).collection("comments")

    override suspend fun writeSharedList(
        groupId: String,
        listId: String,
        fields: Map<String, Any>,
        merge: Boolean
    ) {
        val ref = sharedListsRef(groupId).document(listId)
        if (merge) {
            // Rename only: the payload is the name alone (the rules
            // reject any other changed key) — never stamp createdAt
            // here, or the update itself would violate the rule.
            ref.set(fields, SetOptions.merge()).await()
        } else {
            ref.set(fields + timestampFields("createdAt")).await()
        }
    }

    override suspend fun deleteSharedListDocs(groupId: String, listId: String) {
        val fs = firestore()
        val listRef = sharedListsRef(groupId).document(listId)
        val itemIds = listRef.collection("items")
            .limit(500).get().await().documents.map { it.id }
        val deleteRefs = mutableListOf<DocumentReference>()
        for (itemId in itemIds) {
            val commentIds = listRef.collection("items").document(itemId)
                .collection("comments")
                .limit(500).get().await().documents.map { it.id }
            deleteRefs += commentIds.map {
                listRef.collection("items").document(itemId)
                    .collection("comments").document(it)
            }
        }
        deleteRefs += itemIds.map { listRef.collection("items").document(it) }
        for (chunk in deleteRefs.chunked(400)) {
            val batch = fs.batch()
            for (ref in chunk) batch.delete(ref)
            batch.commit().await()
        }
        // List doc LAST: its items are already gone, so a mid-flight
        // failure leaves an empty list, never orphaned items.
        listRef.delete().await()
    }

    override suspend fun getSharedLists(groupId: String): List<SharedListDoc> =
        sharedListsRef(groupId)
            .get().await().documents.mapNotNull { doc ->
                sharedListDocFromFields(doc.id, groupId, normalizeFields(doc.data ?: emptyMap()))
            }.sortedBy { it.createdAtEpochMillis }

    override suspend fun writeSharedListItem(
        groupId: String,
        listId: String,
        itemId: String,
        fields: Map<String, Any>,
        merge: Boolean
    ) {
        val ref = sharedListItemsRef(groupId, listId).document(itemId)
        if (merge) ref.set(fields, SetOptions.merge()).await()
        else ref.set(fields).await()
    }

    override suspend fun deleteSharedListItemDocs(
        groupId: String,
        listId: String,
        itemId: String
    ) {
        val fs = firestore()
        val itemRef = sharedListItemsRef(groupId, listId).document(itemId)
        val commentIds = itemRef.collection("comments")
            .limit(500).get().await().documents.map { it.id }
        val batch = fs.batch()
        for (commentId in commentIds) {
            batch.delete(itemRef.collection("comments").document(commentId))
        }
        batch.delete(itemRef)
        batch.commit().await()
    }

    override suspend fun getSharedListItemIds(
        groupId: String,
        listId: String,
        limit: Long
    ): List<String> =
        sharedListItemsRef(groupId, listId)
            .limit(limit)
            .get().await().documents.map { it.id }

    override suspend fun querySharedListItemsSince(
        groupId: String,
        listId: String,
        lastSeenMs: Long
    ): List<SharedListItemDoc> =
        sharedListItemsRef(groupId, listId)
            .whereGreaterThan(
                "updatedAt",
                Timestamp(Date(lastSeenMs - POLL_OVERLAP_MS))
            )
            .limit(POLL_QUERY_LIMIT)
            .get().await().documents.mapNotNull { doc ->
                sharedListItemDocFromFields(
                    doc.id, listId, groupId, normalizeFields(doc.data ?: emptyMap())
                )
            }

    override suspend fun writeSharedListComment(
        groupId: String,
        listId: String,
        itemId: String,
        commentId: String,
        fields: Map<String, Any>
    ) {
        sharedListCommentsRef(groupId, listId, itemId).document(commentId)
            .set(fields + timestampFields("createdAt")).await()
    }

    override suspend fun deleteSharedListComment(
        groupId: String,
        listId: String,
        itemId: String,
        commentId: String
    ) {
        sharedListCommentsRef(groupId, listId, itemId).document(commentId).delete().await()
    }

    override suspend fun getSharedListCommentIds(
        groupId: String,
        listId: String,
        itemId: String,
        limit: Long
    ): List<String> =
        sharedListCommentsRef(groupId, listId, itemId)
            .limit(limit)
            .get().await().documents.map { it.id }

    override suspend fun querySharedListCommentsSince(
        groupId: String,
        listId: String,
        itemId: String,
        lastSeenMs: Long
    ): List<SharedListCommentDoc> =
        sharedListCommentsRef(groupId, listId, itemId)
            .whereGreaterThan(
                "createdAt",
                Timestamp(Date(lastSeenMs - POLL_OVERLAP_MS))
            )
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .limit(POLL_QUERY_LIMIT)
            .get().await().documents.mapNotNull { doc ->
                sharedListCommentDocFromFields(
                    doc.id, itemId, listId, groupId, normalizeFields(doc.data ?: emptyMap())
                )
            }

    // ---- listeners ----

    override fun addActivityListener(
        groupId: String,
        onActivity: (List<GroupActivityEntry>) -> Unit
    ): () -> Unit {
        val registration = firestore().collection("groups").document(groupId)
            .collection("activity")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Group activity listener error for $groupId: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                onActivity(
                    snapshot.documents.mapNotNull { doc ->
                        activityFromDoc(doc.id, groupId, normalizeFields(doc.data ?: emptyMap()))
                    }
                )
            }
        return { registration.remove() }
    }

    override fun addNoticesListener(
        groupId: String,
        onNotices: (List<GroupNotice>) -> Unit
    ): () -> Unit {
        val registration = firestore().collection("groups").document(groupId)
            .collection("notices")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Group notices listener error for $groupId: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                onNotices(
                    snapshot.documents.mapNotNull { doc ->
                        noticeFromDoc(doc.id, groupId, normalizeFields(doc.data ?: emptyMap()))
                    }
                )
            }
        return { registration.remove() }
    }

    override fun addItemsListener(
        groupId: String,
        onChanges: (List<GroupItemChange>) -> Unit
    ): () -> Unit {
        val registration = firestore().collection("groups").document(groupId)
            .collection("items")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Group listener error for $groupId: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val changes = snapshot.documentChanges.mapNotNull { change ->
                    when (change.type) {
                        DocumentChange.Type.ADDED,
                        DocumentChange.Type.MODIFIED -> {
                            val doc = groupItemDocFromFields(
                                change.document.id,
                                normalizeFields(change.document.data)
                            )
                            doc?.let { GroupItemChange.Upsert(it) }
                        }
                        DocumentChange.Type.REMOVED ->
                            GroupItemChange.Removed(change.document.id)
                    }
                }
                if (changes.isNotEmpty()) onChanges(changes)
            }
        return { registration.remove() }
    }

    override fun addGroupDocListener(groupId: String, onDeleted: () -> Unit): () -> Unit {
        val registration = firestore().collection("groups").document(groupId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                if (!snapshot.exists()) onDeleted()
            }
        return { registration.remove() }
    }

    private companion object {
        const val TAG = "FirestoreGroupRemoteGateway"
        /** Poll overlap window (plan §3.5): batch writes share timestamps. */
        const val POLL_OVERLAP_MS = 60_000L
        const val POLL_QUERY_LIMIT = 50L
        const val NOTICE_QUERY_LIMIT = 100L
        /** Activity history page: newest first, one screenful and a half. */
        const val ACTIVITY_QUERY_LIMIT = 50L
    }
}
