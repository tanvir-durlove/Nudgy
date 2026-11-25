package com.nextgentool.nudgy.data.local.dao

import androidx.room.Dao
import androidx.room.Transaction
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.ChatMessageEntity
import com.nextgentool.nudgy.data.local.entity.DailyBriefEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.GroupMemberEntity
import com.nextgentool.nudgy.data.local.entity.NudgyMemoryEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE userId = :userId ORDER BY dueDateEpochMillis ASC")
    fun getAllTasksFlow(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND ((dueDateEpochMillis >= :startOfDay AND dueDateEpochMillis <= :endOfDay) OR (dueDateEpochMillis < :startOfDay AND isCompleted = 0)) ORDER BY isCompleted ASC, dueDateEpochMillis ASC")
    fun getTasksForDayFlow(userId: String, startOfDay: Long, endOfDay: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND ((dueDateEpochMillis >= :startOfDay AND dueDateEpochMillis <= :endOfDay) OR (dueDateEpochMillis < :startOfDay AND isCompleted = 0)) ORDER BY isCompleted ASC, dueDateEpochMillis ASC")
    suspend fun getTasksForDayOnce(userId: String, startOfDay: Long, endOfDay: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND isCompleted = 0 ORDER BY dueDateEpochMillis ASC")
    fun getPendingTasksFlow(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND isCompleted = 1 ORDER BY completedAtEpochMillis DESC")
    fun getCompletedTasksFlow(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND dueDateEpochMillis > :cutoffEpochMillis AND isCompleted = 0 ORDER BY dueDateEpochMillis ASC")
    fun getUpcomingTasksFlow(userId: String, cutoffEpochMillis: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND isSyncedToCloud = 0")
    suspend fun getUnsyncedTasks(userId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE userId = :userId")
    suspend fun getAllTasksOnce(userId: String): List<TaskEntity>

    // B2: boot restore must select on the DUE time, not only on the stored
    // trigger. The stored trigger is usually the computed 2h lead (due - 2h);
    // after a reboot / app update inside the lead window it is already past
    // while the due time is still ahead, and the old predicate excluded the
    // task entirely. Keeping `reminderEpochMillis > :now` as well preserves
    // the old coverage (explicit reminder after the due time, if any).
    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND reminderSet = 1 AND (reminderEpochMillis > :now OR dueDateEpochMillis > :now)")
    suspend fun getPendingTasksWithReminders(now: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND isCompleted = 0 AND reminderSet = 1 AND (reminderEpochMillis > :now OR dueDateEpochMillis > :now)")
    suspend fun getPendingTasksWithRemindersForUser(userId: String, now: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getTaskByIdForUser(userId: String, id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TaskEntity>)

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("UPDATE tasks SET isCompleted = :isCompleted, status = CASE WHEN :isCompleted THEN 'DONE' ELSE 'TODO' END, completedAtEpochMillis = :completedAt, isSyncedToCloud = 0, updatedAtEpochMillis = :now WHERE id = :id")
    suspend fun setTaskCompletion(id: String, isCompleted: Boolean, completedAt: Long?, now: Long)

    @Query("UPDATE tasks SET isCompleted = :isCompleted, status = CASE WHEN :isCompleted THEN 'DONE' ELSE 'TODO' END, completedAtEpochMillis = :completedAt, isSyncedToCloud = 0, updatedAtEpochMillis = :now WHERE id = :id AND userId = :userId")
    suspend fun setTaskCompletionForUser(userId: String, id: String, isCompleted: Boolean, completedAt: Long?, now: Long)

    // Group rows only: completion is local-only, so it must NOT mark the row
    // dirty — otherwise the personal sync would upload a group task as a
    // personal cloud copy. Group rows are inserted with isSyncedToCloud = 1
    // and must stay that way.
    @Query("UPDATE tasks SET isCompleted = :isCompleted, status = CASE WHEN :isCompleted THEN 'DONE' ELSE 'TODO' END, completedAtEpochMillis = :completedAt, updatedAtEpochMillis = :now WHERE id = :id AND userId = :userId")
    suspend fun setTaskCompletionLocal(userId: String, id: String, isCompleted: Boolean, completedAt: Long?, now: Long)

    @Query("UPDATE tasks SET isSyncedToCloud = 1 WHERE id = :id")
    suspend fun markTaskSynced(id: String)

    @Query("DELETE FROM tasks WHERE id = :id AND userId = :userId")
    suspend fun deleteTaskByIdForUser(userId: String, id: String)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: String)

    // S1-3a: snooze only moves the REMINDER, never the due date. v1 moved
    // both, which corrupted the due date (a 5 PM task snoozed at 3 PM
    // became due at 3:10 PM). Snooze is runtime state — no new column.
    @Query("UPDATE tasks SET reminderEpochMillis = :reminderEpochMillis, reminderSet = 1, isSyncedToCloud = 0, updatedAtEpochMillis = :now WHERE id = :id")
    suspend fun setReminderTime(id: String, reminderEpochMillis: Long, now: Long)

    // Group rows only: snooze is local-only (same reason as
    // setTaskCompletionLocal — never mark a group row dirty).
    @Query("UPDATE tasks SET reminderEpochMillis = :reminderEpochMillis, reminderSet = 1, updatedAtEpochMillis = :now WHERE id = :id")
    suspend fun setReminderTimeLocal(id: String, reminderEpochMillis: Long, now: Long)

    // Group reconciliation helpers (polling worker, Phase 1): ids of local
    // group rows, and removal of a whole group's local rows.
    @Query("SELECT id FROM tasks WHERE userId = :userId AND groupId = :groupId")
    suspend fun getTaskIdsByGroupId(userId: String, groupId: String): List<String>

    @Query("DELETE FROM tasks WHERE userId = :userId AND groupId = :groupId")
    suspend fun deleteTasksByGroupId(userId: String, groupId: String)

    @Query("SELECT * FROM tasks WHERE userId = :userId AND isCompleted = 1")
    suspend fun getCompletedTasksOnce(userId: String): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE userId = :userId AND isCompleted = 1")
    suspend fun deleteCompletedTasks(userId: String)

    // Group v1: "clear completed" must NOT delete group rows — the shared group
    // item still exists in Firestore, so the next listener/poll tick would
    // resurrect the task. Group rows are cleared only when the group item
    // itself is deleted by its creator.
    @Query("DELETE FROM tasks WHERE userId = :userId AND isCompleted = 1 AND groupId IS NULL")
    suspend fun deleteCompletedPersonalTasks(userId: String)

    @Query("DELETE FROM tasks WHERE userId = :userId")
    suspend fun clearTasksForUser(userId: String)

    @Query("UPDATE tasks SET userId = :toUserId, isSyncedToCloud = 0 WHERE userId = :fromUserId")
    suspend fun reassignUser(fromUserId: String, toUserId: String)

    // ---- Sprint 1-1 (MIGRATION_7_8) ----

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    fun getTaskByIdFlow(id: String): Flow<TaskEntity?>

    /** Top-level tasks only — subtasks (parentTaskId != null) live inside the detail sheet. */
    @Query("SELECT * FROM tasks WHERE userId = :userId AND parentTaskId IS NULL ORDER BY dueDateEpochMillis ASC")
    fun getTopLevelTasksFlow(userId: String): Flow<List<TaskEntity>>

    /** Subtasks of one parent, creation order. ONE level only. */
    @Query("SELECT * FROM tasks WHERE parentTaskId = :parentId ORDER BY createdAtEpochMillis ASC")
    fun getSubtasksFlow(parentId: String): Flow<List<TaskEntity>>

    @Query("SELECT COUNT(*) FROM tasks WHERE parentTaskId = :parentId")
    suspend fun getSubtaskCount(parentId: String): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE parentTaskId = :parentId AND isCompleted = 1")
    suspend fun getSubtaskCompletedCount(parentId: String): Int

    /**
     * The single write path for completion + status (repository toggle and
     * Board drag, S1-4): sets both together so DONE ⇔ isCompleted = true
     * can never drift. Personal rows are dirty-marked; group rows use the
     * Local variant below (never dirty-marked, never synced).
     */
    @Query("UPDATE tasks SET status = :status, isCompleted = :isCompleted, completedAtEpochMillis = :completedAt, isSyncedToCloud = 0, updatedAtEpochMillis = :now WHERE id = :id AND userId = :userId")
    suspend fun setTaskStatusAndCompletionForUser(userId: String, id: String, status: String, isCompleted: Boolean, completedAt: Long?, now: Long)

    @Query("UPDATE tasks SET status = :status, isCompleted = :isCompleted, completedAtEpochMillis = :completedAt, updatedAtEpochMillis = :now WHERE id = :id AND userId = :userId")
    suspend fun setTaskStatusAndCompletionLocal(userId: String, id: String, status: String, isCompleted: Boolean, completedAt: Long?, now: Long)

    // ---- Sprint 1-4g (board reorder, MIGRATION_8_9) ----
    //
    // boardOrder is dense 0..n-1 per status column, over TOP-LEVEL tasks
    // only (subtasks keep 0 — they have no Board presence). Every write
    // below has a Local twin: group rows are never dirty-marked (same
    // rule as setTaskStatusAndCompletionLocal — never synced, never
    // uploaded as a personal cloud copy).

    /** Highest boardOrder in a top-level status column (null = empty). */
    @Query("SELECT MAX(boardOrder) FROM tasks WHERE userId = :userId AND parentTaskId IS NULL AND status = :status")
    suspend fun getMaxBoardOrder(userId: String, status: String): Int?

    /** Rewrite one row's position (personal rows are dirty-marked). */
    @Query("UPDATE tasks SET boardOrder = :boardOrder, isSyncedToCloud = 0, updatedAtEpochMillis = :now WHERE id = :id AND userId = :userId")
    suspend fun setBoardOrderForUser(userId: String, id: String, boardOrder: Int, now: Long)

    /** Rewrite one row's position (group rows stay clean). */
    @Query("UPDATE tasks SET boardOrder = :boardOrder, updatedAtEpochMillis = :now WHERE id = :id AND userId = :userId")
    suspend fun setBoardOrderLocal(userId: String, id: String, boardOrder: Int, now: Long)

    /**
     * The moved row's write in a Board drop: status + completion +
     * position (+ plannedStart — the My Day Board re-slots the day, see
     * TaskRepository.moveTaskToPosition) in one UPDATE. The caller passes
     * the resolved plannedStart (the stored value when it does not move).
     */
    @Query("UPDATE tasks SET status = :status, isCompleted = :isCompleted, completedAtEpochMillis = :completedAt, boardOrder = :boardOrder, plannedStartEpochMillis = :plannedStart, isSyncedToCloud = 0, updatedAtEpochMillis = :now WHERE id = :id AND userId = :userId")
    suspend fun setStatusCompletionBoardOrderForUser(userId: String, id: String, status: String, isCompleted: Boolean, completedAt: Long?, boardOrder: Int, plannedStart: Long?, now: Long)

    @Query("UPDATE tasks SET status = :status, isCompleted = :isCompleted, completedAtEpochMillis = :completedAt, boardOrder = :boardOrder, plannedStartEpochMillis = :plannedStart, updatedAtEpochMillis = :now WHERE id = :id AND userId = :userId")
    suspend fun setStatusCompletionBoardOrderLocal(userId: String, id: String, status: String, isCompleted: Boolean, completedAt: Long?, boardOrder: Int, plannedStart: Long?, now: Long)

    /** Deleting a project orphans its tasks (projectId -> NULL); tasks are never deleted. */
    @Query("UPDATE tasks SET projectId = NULL, updatedAtEpochMillis = :now WHERE projectId = :projectId")
    suspend fun clearProjectFromTasks(projectId: String, now: Long)

    // ---- Sprint 6-4 (step 2b, global search) ----
    //
    // One query for the Tasks section: title + description ("notes",
    // spec Q5) + tag name. Tags are not a TaskEntity column — the tag
    // match JOINs tags ⨝ task_tags. DISTINCT: a task with several
    // matching tags must appear once. The LIKE pattern comes from
    // the shared likePattern (wildcards escaped) — see
    // data/repository/LikePattern.kt. Query-only change: no schema
    // change, no migration.
    @Query(
        "SELECT DISTINCT t.* FROM tasks t " +
            "LEFT JOIN task_tags tt ON t.id = tt.taskId " +
            "LEFT JOIN tags g ON g.id = tt.tagId " +
            "WHERE t.userId = :userId " +
            "AND (t.title LIKE :pattern ESCAPE '\\' COLLATE NOCASE " +
            "OR t.description LIKE :pattern ESCAPE '\\' COLLATE NOCASE " +
            "OR g.name LIKE :pattern ESCAPE '\\' COLLATE NOCASE) " +
            "ORDER BY t.dueDateEpochMillis ASC"
    )
    fun searchTasksFlow(userId: String, pattern: String): Flow<List<TaskEntity>>
}

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_events WHERE userId = :userId AND ((startEpochMillis >= :startOfDay AND startEpochMillis <= :endOfDay) OR (startEpochMillis <= :endOfDay AND endEpochMillis >= :startOfDay)) ORDER BY startEpochMillis ASC")
    fun getEventsForDayFlow(userId: String, startOfDay: Long, endOfDay: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE userId = :userId AND ((startEpochMillis >= :startOfDay AND startEpochMillis <= :endOfDay) OR (startEpochMillis <= :endOfDay AND endEpochMillis >= :startOfDay)) ORDER BY startEpochMillis ASC")
    suspend fun getEventsForDayOnce(userId: String, startOfDay: Long, endOfDay: Long): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE userId = :userId ORDER BY startEpochMillis ASC")
    fun getAllEventsFlow(userId: String): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE userId = :userId ORDER BY startEpochMillis ASC")
    suspend fun getAllEventsOnce(userId: String): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE id = :id LIMIT 1")
    suspend fun getEventById(id: String): CalendarEventEntity?

    @Query("SELECT * FROM calendar_events WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getEventByIdForUser(userId: String, id: String): CalendarEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<CalendarEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: CalendarEventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :id AND userId = :userId")
    suspend fun deleteEventByIdForUser(userId: String, id: String)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteEventById(id: String)

    @Query("DELETE FROM calendar_events WHERE userId = :userId")
    suspend fun clearEventsForUser(userId: String)

    // Group reconciliation helpers (polling worker, Phase 1).
    @Query("SELECT id FROM calendar_events WHERE userId = :userId AND groupId = :groupId")
    suspend fun getEventIdsByGroupId(userId: String, groupId: String): List<String>

    @Query("DELETE FROM calendar_events WHERE userId = :userId AND groupId = :groupId")
    suspend fun deleteEventsByGroupId(userId: String, groupId: String)

    @Query("UPDATE calendar_events SET userId = :toUserId WHERE userId = :fromUserId")
    suspend fun reassignUser(fromUserId: String, toUserId: String)

    // ---- Sprint 6-4 (step 2c, global search) ----
    //
    // One query for the Schedules section: title + location/link.
    // The LIKE pattern comes from the shared likePattern (wildcards
    // escaped) — see data/repository/LikePattern.kt. Query-only
    // change: no schema change, no migration.
    @Query(
        "SELECT * FROM calendar_events WHERE userId = :userId " +
            "AND (title LIKE :pattern ESCAPE '\\' COLLATE NOCASE " +
            "OR locationOrLink LIKE :pattern ESCAPE '\\' COLLATE NOCASE) " +
            "ORDER BY startEpochMillis ASC"
    )
    fun searchEventsFlow(userId: String, pattern: String): Flow<List<CalendarEventEntity>>
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY joinedAtEpochMillis DESC")
    fun getGroupsFlow(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: String): GroupEntity?

    @Query("SELECT groupId FROM groups")
    suspend fun getGroupIdsOnce(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<GroupEntity>)

    @Query("DELETE FROM groups WHERE groupId = :groupId")
    suspend fun deleteGroupById(groupId: String)

    // C6: account deletion must clear the local groups mirror. The groups
    // table has no userId column and getGroupsFlow() is unfiltered, so
    // without this the next account on a shared device would see the
    // deleted user's groups (names, join codes) in the Groups tab.
    @Query("DELETE FROM groups")
    suspend fun clearAllGroups()
}

@Dao
interface GroupMemberDao {
    // Member-list cache (minimization): detail opens read Room instantly;
    // refreshGroupMembers replaces the cache in the background.
    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY displayName COLLATE NOCASE ASC")
    fun getMembersFlow(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getMembersOnce(groupId: String): List<GroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<GroupMemberEntity>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteMembersByGroupId(groupId: String)

    // C6: companion to GroupDao.clearAllGroups — the member cache holds
    // other members' display names from the deleted user's groups.
    @Query("DELETE FROM group_members")
    suspend fun clearAllMembers()

    @Transaction
    suspend fun replaceMembers(groupId: String, members: List<GroupMemberEntity>) {
        deleteMembersByGroupId(groupId)
        insertMembers(members)
    }
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM nudgy_memories WHERE userId = :userId ORDER BY createdAtEpochMillis DESC")
    fun getMemoriesFlow(userId: String): Flow<List<NudgyMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: NudgyMemoryEntity)

    @Query("DELETE FROM nudgy_memories WHERE id = :id AND userId = :userId")
    suspend fun deleteMemoryByIdForUser(userId: String, id: String)

    @Query("DELETE FROM nudgy_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String)

    @Query("DELETE FROM nudgy_memories WHERE userId = :userId")
    suspend fun clearMemoriesForUser(userId: String)

    @Query("UPDATE nudgy_memories SET userId = :toUserId WHERE userId = :fromUserId")
    suspend fun reassignUser(fromUserId: String, toUserId: String)
}

@Dao
interface DailyBriefDao {
    @Query("SELECT * FROM daily_briefs WHERE userId = :userId AND dateString = :dateString LIMIT 1")
    suspend fun getBriefForDate(userId: String, dateString: String): DailyBriefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBrief(brief: DailyBriefEntity)

    @Query("DELETE FROM daily_briefs WHERE userId = :userId")
    suspend fun clearBriefsForUser(userId: String)

    @Query("UPDATE daily_briefs SET userId = :toUserId WHERE userId = :fromUserId")
    suspend fun reassignUser(fromUserId: String, toUserId: String)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE userId = :userId ORDER BY timestampMillis ASC")
    fun getMessagesFlow(userId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("UPDATE chat_messages SET focusActionCompleted = 1 WHERE id = :messageId")
    suspend fun markFocusActionCompleted(messageId: String)

    @Query("DELETE FROM chat_messages WHERE userId = :userId")
    suspend fun clearMessages(userId: String)

    @Query("UPDATE chat_messages SET userId = :toUserId WHERE userId = :fromUserId")
    suspend fun reassignUser(fromUserId: String, toUserId: String)
}
