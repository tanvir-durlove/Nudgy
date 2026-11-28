package com.nextgentool.nudgy.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Shared list (spec s7-shared-list-scope-spec, Phase 1) — a list that
 * belongs to a whole group: shopping, supplies, anything. One group
 * holds as many lists as its members create (Q5: no limit).
 *
 * - [id] is the Firestore document id (groups/{gid}/sharedLists/{id}),
 *   so the local row and the remote doc are the same thing.
 * - [createdByUid]/[createdByName]: who created the list. Only this
 *   member or the group creator may rename or delete it (rules +
 *   Q1=(a)); every member may add items to it.
 * - The list has no done flag of its own — the group-detail row shows
 *   "N items · M done", computed from its items.
 */
@Entity(tableName = "shared_lists")
data class SharedListEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val name: String,
    val createdByUid: String,
    val createdByName: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

/**
 * One line on a shared list — "Milk 2 L", "Rice 10 kg".
 *
 * - [done] is a single flag, not a per-member array: unlike a group
 *   task (which tracks who completed it), a shopping item is done
 *   once anyone buys it. [doneByUid] records who checked it off.
 * - [assigneeUid]/[assigneeName] are the member the item is assigned
 *   to (null = "anyone can take it", prototype sheet-listitem).
 *   Denormalized name, same as the group-task assignee (MIGRATION_11_12):
 *   the row alone shows the name, no member lookup needed.
 * - Deleting a list deletes its items (CASCADE, spec §5); the
 *   repository deletes the remote docs too (the rules do not cascade).
 */
@Entity(
    tableName = "shared_list_items",
    foreignKeys = [
        ForeignKey(
            entity = SharedListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SharedListItemEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val groupId: String,
    val text: String,
    val addedByUid: String,
    val addedByName: String,
    val done: Boolean = false,
    val doneByUid: String? = null,
    val assigneeUid: String? = null,
    val assigneeName: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
)

/**
 * One text comment on a shared-list item (prototype sheet-listitem
 * "Comments"). Text only — attachments are parked to Phase 2 (spec
 * Q2: they need Storage + Blaze).
 *
 * - Comments are never edited (rules: allow update is false), only
 *   deleted — by the author or the group creator.
 * - [listId]/[groupId] are denormalized so a whole group's comments
 *   can be cleared without joining through items (account deletion,
 *   group leave).
 * - Deleting an item deletes its comments (CASCADE).
 */
@Entity(
    tableName = "shared_list_comments",
    foreignKeys = [
        ForeignKey(
            entity = SharedListItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SharedListCommentEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val listId: String,
    val groupId: String,
    val authorUid: String,
    val authorName: String,
    val text: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
