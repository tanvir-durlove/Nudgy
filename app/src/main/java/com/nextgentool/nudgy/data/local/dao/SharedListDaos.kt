package com.nextgentool.nudgy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nextgentool.nudgy.data.local.entity.SharedListCommentEntity
import com.nextgentool.nudgy.data.local.entity.SharedListEntity
import com.nextgentool.nudgy.data.local.entity.SharedListItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Shared list (spec s7-shared-list-scope-spec, Phase 1) — local mirror
 * of groups/{gid}/sharedLists/{listId}. Insert-with-REPLACE is the
 * upsert everywhere (the group DAO convention): the sync pass writes
 * remote docs over local rows, and a local create writes the same row
 * before the remote write lands.
 */
@Dao
interface SharedListDao {

    @Query("SELECT * FROM shared_lists WHERE groupId = :groupId ORDER BY createdAtEpochMillis ASC")
    fun getListsFlow(groupId: String): Flow<List<SharedListEntity>>

    @Query("SELECT * FROM shared_lists WHERE groupId = :groupId ORDER BY createdAtEpochMillis ASC")
    suspend fun getListsOnce(groupId: String): List<SharedListEntity>

    @Query("SELECT * FROM shared_lists WHERE id = :listId LIMIT 1")
    suspend fun getListById(listId: String): SharedListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertList(list: SharedListEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLists(lists: List<SharedListEntity>)

    @Query("DELETE FROM shared_lists WHERE id = :listId")
    suspend fun deleteListById(listId: String)

    @Query("DELETE FROM shared_lists WHERE groupId = :groupId")
    suspend fun deleteListsByGroupId(groupId: String)

    // C6 (same as GroupDao.clearAllGroups): account deletion must clear
    // the local mirror — the next account on a shared device must not
    // see the deleted user's group lists. Wired by the repository task;
    // this DAO only provides the query.
    @Query("DELETE FROM shared_lists")
    suspend fun clearAllLists()
}

@Dao
interface SharedListItemDao {

    @Query("SELECT * FROM shared_list_items WHERE listId = :listId ORDER BY createdAtEpochMillis ASC")
    fun getItemsFlow(listId: String): Flow<List<SharedListItemEntity>>

    @Query("SELECT * FROM shared_list_items WHERE listId = :listId ORDER BY createdAtEpochMillis ASC")
    suspend fun getItemsOnce(listId: String): List<SharedListItemEntity>

    @Query("SELECT * FROM shared_list_items WHERE id = :itemId LIMIT 1")
    suspend fun getItemById(itemId: String): SharedListItemEntity?

    @Query("SELECT COUNT(*) FROM shared_list_items WHERE listId = :listId")
    suspend fun getItemCount(listId: String): Int

    @Query("SELECT COUNT(*) FROM shared_list_items WHERE listId = :listId AND done = 1")
    suspend fun getDoneCount(listId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: SharedListItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<SharedListItemEntity>)

    @Query("DELETE FROM shared_list_items WHERE id = :itemId")
    suspend fun deleteItemById(itemId: String)

    @Query("DELETE FROM shared_list_items WHERE listId = :listId")
    suspend fun deleteItemsByListId(listId: String)

    @Query("DELETE FROM shared_list_items WHERE groupId = :groupId")
    suspend fun deleteItemsByGroupId(groupId: String)

    // C6: companion to SharedListDao.clearAllLists (see above).
    @Query("DELETE FROM shared_list_items")
    suspend fun clearAllItems()
}

@Dao
interface SharedListCommentDao {

    @Query("SELECT * FROM shared_list_comments WHERE itemId = :itemId ORDER BY createdAtEpochMillis ASC")
    fun getCommentsFlow(itemId: String): Flow<List<SharedListCommentEntity>>

    @Query("SELECT * FROM shared_list_comments WHERE itemId = :itemId ORDER BY createdAtEpochMillis ASC")
    suspend fun getCommentsOnce(itemId: String): List<SharedListCommentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: SharedListCommentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComments(comments: List<SharedListCommentEntity>)

    @Query("DELETE FROM shared_list_comments WHERE id = :commentId")
    suspend fun deleteCommentById(commentId: String)

    @Query("DELETE FROM shared_list_comments WHERE itemId = :itemId")
    suspend fun deleteCommentsByItemId(itemId: String)

    @Query("DELETE FROM shared_list_comments WHERE listId = :listId")
    suspend fun deleteCommentsByListId(listId: String)

    // C6: companion to SharedListDao.clearAllLists (see above).
    @Query("DELETE FROM shared_list_comments")
    suspend fun clearAllComments()
}
