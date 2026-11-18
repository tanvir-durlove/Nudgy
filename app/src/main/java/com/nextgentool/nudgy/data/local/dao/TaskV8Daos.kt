package com.nextgentool.nudgy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.nextgentool.nudgy.data.local.entity.ProjectEntity
import com.nextgentool.nudgy.data.local.entity.SavedViewEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.local.entity.TaskAttachmentEntity
import com.nextgentool.nudgy.data.local.entity.TaskTagCrossRef
import com.nextgentool.nudgy.data.local.entity.TaskTemplateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Sprint 1-1 — DAOs for the six MIGRATION_7_8 tables (spec §4).
 * Every query is scoped by userId: tasks, tags, projects and views are
 * per-user data, and a shared device must never leak one user's rows
 * into another's list.
 */
@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE userId = :userId ORDER BY name COLLATE NOCASE ASC")
    fun getTagsFlow(userId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE userId = :userId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getTagsOnce(userId: String): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getTagById(userId: String, id: String): TagEntity?

    @Query("SELECT t.* FROM tags t INNER JOIN task_tags tt ON t.id = tt.tagId WHERE tt.taskId = :taskId ORDER BY t.name COLLATE NOCASE ASC")
    fun getTagsForTaskFlow(taskId: String): Flow<List<TagEntity>>

    @Query("SELECT t.* FROM tags t INNER JOIN task_tags tt ON t.id = tt.tagId WHERE tt.taskId = :taskId ORDER BY t.name COLLATE NOCASE ASC")
    suspend fun getTagsForTaskOnce(taskId: String): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity)

    @Update
    suspend fun updateTag(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id AND userId = :userId")
    suspend fun deleteTagById(userId: String, id: String)

    @Query("SELECT * FROM task_tags")
    fun getAllCrossRefsFlow(): Flow<List<TaskTagCrossRef>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: TaskTagCrossRef)

    @Query("DELETE FROM task_tags WHERE taskId = :taskId")
    suspend fun deleteCrossRefsForTask(taskId: String)

    @Transaction
    suspend fun setTagsForTask(taskId: String, tagIds: List<String>) {
        deleteCrossRefsForTask(taskId)
        tagIds.forEach { insertCrossRef(TaskTagCrossRef(taskId, it)) }
    }
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE userId = :userId ORDER BY name COLLATE NOCASE ASC")
    fun getProjectsFlow(userId: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE userId = :userId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getProjectsOnce(userId: String): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getProjectById(userId: String, id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id AND userId = :userId")
    suspend fun deleteProjectById(userId: String, id: String)
}

@Dao
interface SavedViewDao {
    @Query("SELECT * FROM saved_views WHERE userId = :userId ORDER BY createdAtEpochMillis ASC")
    fun getSavedViewsFlow(userId: String): Flow<List<SavedViewEntity>>

    @Query("SELECT * FROM saved_views WHERE userId = :userId ORDER BY createdAtEpochMillis ASC")
    suspend fun getSavedViewsOnce(userId: String): List<SavedViewEntity>

    @Query("SELECT * FROM saved_views WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getSavedViewById(userId: String, id: String): SavedViewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedView(view: SavedViewEntity)

    @Query("DELETE FROM saved_views WHERE id = :id AND userId = :userId")
    suspend fun deleteSavedViewById(userId: String, id: String)
}

@Dao
interface TaskAttachmentDao {
    @Query("SELECT * FROM task_attachments WHERE taskId = :taskId ORDER BY createdAtEpochMillis ASC")
    fun getAttachmentsForTaskFlow(taskId: String): Flow<List<TaskAttachmentEntity>>

    @Query("SELECT * FROM task_attachments WHERE taskId = :taskId ORDER BY createdAtEpochMillis ASC")
    suspend fun getAttachmentsForTaskOnce(taskId: String): List<TaskAttachmentEntity>

    @Query("SELECT * FROM task_attachments WHERE id = :id LIMIT 1")
    suspend fun getAttachmentById(id: String): TaskAttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: TaskAttachmentEntity)

    @Query("DELETE FROM task_attachments WHERE id = :id")
    suspend fun deleteAttachmentById(id: String)

    @Query("DELETE FROM task_attachments WHERE taskId = :taskId")
    suspend fun deleteAttachmentsForTask(taskId: String)
}

@Dao
interface TaskTemplateDao {
    @Query("SELECT * FROM task_templates WHERE userId = :userId ORDER BY name COLLATE NOCASE ASC")
    fun getTemplatesFlow(userId: String): Flow<List<TaskTemplateEntity>>

    @Query("SELECT * FROM task_templates WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getTemplateById(userId: String, id: String): TaskTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: TaskTemplateEntity)

    @Query("DELETE FROM task_templates WHERE id = :id AND userId = :userId")
    suspend fun deleteTemplateById(userId: String, id: String)
}
