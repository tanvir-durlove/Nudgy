package com.nextgentool.nudgy.data.repository

import com.nextgentool.nudgy.data.local.entity.GroupEntity

/**
 * Sprint 5-0 — Groups foundation: the shared group data model.
 *
 * Pure mapping only — this file NEVER touches Firestore types
 * (no DocumentSnapshot, no FieldValue, no Timestamp). The gateway
 * ([GroupRemoteGateway]) normalizes Firestore values to plain Kotlin
 * types at the boundary (Timestamps become epoch millis), so everything
 * here is unit-testable on plain JVM.
 *
 * Firestore layout (spec §3, v1-compatible — the 1.0.5 production app
 * shares project nudgy-8bd76, so field names below must match what v1
 * already writes):
 *   groups/{gid}                         — name, code, creatorUid, creatorName
 *   groups/{gid}/members/{uid}           — uid, displayName, code, joinedAt
 *   groups/{gid}/items/{itemId}          — kind TASK | SCHEDULE + payload
 *                                          + completedBy [uid] + completedCount
 *   groups/{gid}/notices/{noticeId}      — authorUid, authorName, text, createdAt
 *   groupCodes/{CODE}                    — doc id IS the code, groupId field
 *   users/{uid}/groupMemberships/{gid}   — groupId, groupName, code, joinedAt
 *
 * S5-0 adds two things to the v1 model: the completion fields on item
 * docs (feature 31) and the notices subcollection (feature 32, text-only).
 * Everything else here documents — and reuses — the v1 field names.
 */

/** Max notice text length (spec §6: text-only notices; enforced in the repo AND the rules). */
const val MAX_NOTICE_TEXT_LENGTH = 2000

/**
 * Who completed a group task, shared across members (feature 31,
 * PRD draft §4: counter + completedBy UID array on the item doc).
 * [completedCount] ALWAYS equals [completedBy].size — the rules enforce
 * it, and [toggleCompletion] maintains it.
 */
data class GroupCompletion(
    val completedBy: List<String> = emptyList(),
    val completedCount: Int = 0
)

/**
 * Pure: toggles one member's completion. Idempotent — completing twice
 * (or un-completing when absent) changes nothing, so a double tap can
 * never inflate the count. The count is always the array size.
 */
fun toggleCompletion(
    current: GroupCompletion,
    uid: String,
    completed: Boolean
): GroupCompletion {
    val next = if (completed) {
        if (uid in current.completedBy) current.completedBy else current.completedBy + uid
    } else {
        current.completedBy - uid
    }
    return GroupCompletion(completedBy = next, completedCount = next.size)
}

/**
 * Pure (S5-2, Q6=(a)): the completion as the group sees it — only
 * CURRENT members count. A member who left is dropped from the array
 * (and the count) even if their UID is still stored on the item doc
 * (the leave sweep in GroupRepository.leaveGroup removes it, but a
 * leave from another device or a partial sweep must never show a stale
 * count — so the display filters too). Rejoin is fresh only because
 * the sweep removed the UID; without the sweep this filter would count
 * a rejoined member's old completion again.
 */
fun effectiveCompletion(
    completion: GroupCompletion,
    memberUids: Set<String>
): GroupCompletion {
    val current = completion.completedBy.filter { it in memberUids }.distinct()
    return GroupCompletion(completedBy = current, completedCount = current.size)
}

/** Bengali digits, for the completion count the spec writes as "৩/৫ করেছে". */
private val BENGALI_DIGITS = listOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

/** Pure: a non-negative count in Bengali digits ("3" → "৩", "12" → "১২"). */
fun Int.toBengaliDigits(): String {
    require(this >= 0) { "completion counts are never negative" }
    return toString().map { BENGALI_DIGITS[it - '0'] }.joinToString("")
}

/**
 * Pure (S5-2, feature 31): the per-task shared completion label —
 * "৩/৫ করেছে" (denominator = current member count, Q6=(a)). Shown
 * only on group tasks, never on personal tasks (PRD draft §4).
 */
fun formatCompletionCount(completed: Int, total: Int): String =
    "${completed.toBengaliDigits()}/${total.toBengaliDigits()} করেছে"

/** Pure: reads the completion fields off an item doc's field map. Tolerant: a doc written before S5-0 has neither field, which reads as empty; a missing or wrong completedCount is derived from the array. */
fun completionFromFields(fields: Map<String, Any?>): GroupCompletion {
    val by = (fields["completedBy"] as? List<*>)
        ?.mapNotNull { it as? String }
        ?.distinct()
        ?: emptyList()
    return GroupCompletion(completedBy = by, completedCount = by.size)
}

/** Pure: the completion fields to write on an item doc. */
fun completionToFields(completion: GroupCompletion): Map<String, Any> = mapOf(
    "completedBy" to completion.completedBy,
    "completedCount" to completion.completedCount
)

/** A text-only admin notice (feature 32, spec §6). */
data class GroupNotice(
    val id: String,
    val groupId: String,
    val authorUid: String,
    val authorName: String,
    val text: String,
    val createdAtEpochMillis: Long
)

/** Pure: the notice fields to write (no id, no createdAt — the gateway stamps those). */
fun noticeToFields(authorUid: String, authorName: String, text: String): Map<String, Any> = mapOf(
    "authorUid" to authorUid,
    "authorName" to authorName,
    "text" to text
)

/** Pure: reads a notice doc's field map. Null when the text or author is missing — a notice without text is not a notice. */
fun noticeFromDoc(id: String, groupId: String, fields: Map<String, Any?>): GroupNotice? {
    val text = fields["text"] as? String ?: return null
    if (text.isBlank()) return null
    val authorUid = fields["authorUid"] as? String ?: return null
    return GroupNotice(
        id = id,
        groupId = groupId,
        authorUid = authorUid,
        authorName = fields["authorName"] as? String ?: "",
        text = text,
        createdAtEpochMillis = (fields["createdAt"] as? Number)?.toLong() ?: 0L
    )
}

/**
 * Pure (S5-3, feature 34): which polled notices are genuinely new.
 * A notice is new when its createdAt is past the watermark, or when it
 * sits inside the overlap window ([overlapMs], which MUST match the
 * gateway query window) and was never notified — the window exists
 * because batch writes share server timestamps, so a watermark landing
 * on a shared timestamp could otherwise skip a notice. A doc at or
 * before the window is stale (a clock-skewed re-read), never new,
 * even when its id was never seen.
 */
fun filterNewNotices(
    notices: List<GroupNotice>,
    lastSeenMs: Long,
    seenIds: Set<String>,
    overlapMs: Long = 60_000L
): List<GroupNotice> = notices.filter { notice ->
    notice.createdAtEpochMillis > lastSeenMs ||
        (notice.createdAtEpochMillis > lastSeenMs - overlapMs && notice.id !in seenIds)
}

/**
 * Pure (S5-3, feature 34): one summary line for a poll tick's new
 * arrivals — "1 new item", "2 new notices", "1 new item · 2 new
 * notices". Both zero returns "" (the worker never posts then).
 */
fun groupPollSummaryText(newItems: Int, newNotices: Int): String {
    val parts = mutableListOf<String>()
    if (newItems > 0) parts += if (newItems == 1) "1 new item" else "$newItems new items"
    if (newNotices > 0) parts += if (newNotices == 1) "1 new notice" else "$newNotices new notices"
    return parts.joinToString(" · ")
}

/**
 * One group item doc, as the app sees it — the plain representation the
 * gateway hands the repository (listeners and poll both). The Firestore
 * [DocumentSnapshot] never crosses into the repository anymore.
 */
data class GroupItemDoc(
    val id: String,
    val kind: String,
    val title: String,
    val description: String? = null,
    val dueDateEpochMillis: Long? = null,
    val priority: String? = null,
    val category: String? = null,
    val reminderSet: Boolean = false,
    val reminderEpochMillis: Long? = null,
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
    val locationOrLink: String? = null,
    val eventType: String? = null,
    val completedBy: List<String> = emptyList(),
    val completedCount: Int = 0,
    val updatedAtEpochMillis: Long? = null
)

/** Pure: reads an item doc's field map. Null when there is no usable title — mirrors the old listener's early return. */
fun groupItemDocFromFields(id: String, fields: Map<String, Any?>): GroupItemDoc? {
    val title = fields["title"] as? String ?: return null
    if (title.isBlank()) return null
    val completion = completionFromFields(fields)
    return GroupItemDoc(
        id = id,
        kind = fields["kind"] as? String ?: "TASK",
        title = title,
        description = fields["description"] as? String,
        dueDateEpochMillis = (fields["dueDateEpochMillis"] as? Number)?.toLong(),
        priority = fields["priority"] as? String,
        category = fields["category"] as? String,
        reminderSet = fields["reminderSet"] as? Boolean ?: false,
        reminderEpochMillis = (fields["reminderEpochMillis"] as? Number)?.toLong(),
        startEpochMillis = (fields["startEpochMillis"] as? Number)?.toLong(),
        endEpochMillis = (fields["endEpochMillis"] as? Number)?.toLong(),
        locationOrLink = fields["locationOrLink"] as? String,
        eventType = fields["eventType"] as? String,
        completedBy = completion.completedBy,
        completedCount = completion.completedCount,
        updatedAtEpochMillis = (fields["updatedAt"] as? Number)?.toLong()
    )
}

/** A listener change, in plain terms: an upserted item doc, or a removed item id. */
sealed class GroupItemChange {
    data class Upsert(val doc: GroupItemDoc) : GroupItemChange()
    data class Removed(val id: String) : GroupItemChange()
}

// ---------------------------------------------------------------- group / member / code docs

/** Pure: the group doc fields to write on create (v1 names). The gateway adds the server timestamps (createdAt, lastUpdatedAt, lastDeleteAt). */
fun groupCreateFields(
    name: String,
    code: String,
    creatorUid: String,
    creatorName: String
): Map<String, Any> = mapOf(
    "name" to name,
    "code" to code,
    "creatorUid" to creatorUid,
    "creatorName" to creatorName
)

/** Pure: reads a group doc's field map. Null when the name or creator is missing — the doc is unusable without them. */
fun groupEntityFromDoc(groupId: String, fields: Map<String, Any?>): GroupEntity? {
    val name = fields["name"] as? String ?: return null
    val creatorUid = fields["creatorUid"] as? String ?: return null
    return GroupEntity(
        groupId = groupId,
        name = name,
        code = fields["code"] as? String ?: "",
        creatorUid = creatorUid,
        joinedAtEpochMillis = System.currentTimeMillis()
    )
}

/** Pure: the member doc fields to write on join/create (v1 names + the B3 code proof). The gateway adds the joinedAt server timestamp. */
fun memberCreateFields(uid: String, displayName: String, code: String): Map<String, Any> = mapOf(
    "uid" to uid,
    "displayName" to displayName,
    "code" to code
)

/** Pure: reads a member doc's field map. A doc without a display name still maps (as "Member") — the old code did the same. */
fun memberFromDoc(uid: String, fields: Map<String, Any?>): GroupMember =
    GroupMember(uid = uid, displayName = fields["displayName"] as? String ?: "Member")

/** Pure: the groupCodes doc fields (the doc id IS the code). The gateway adds createdAt. */
fun codeDocFields(groupId: String): Map<String, Any> = mapOf("groupId" to groupId)

/** Pure: reads a groupCodes doc's field map. */
fun codeDocGroupId(fields: Map<String, Any?>): String? = fields["groupId"] as? String

/** Pure: the per-user membership mirror fields (v1 names). The gateway adds joinedAt. */
fun mirrorFields(groupId: String, groupName: String, code: String): Map<String, Any> = mapOf(
    "groupId" to groupId,
    "groupName" to groupName,
    "code" to code
)
