package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.CalendarEventEntity
import com.example.data.local.entity.DailyBriefEntity
import com.example.data.local.entity.NudgyMemoryEntity
import com.example.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE userId = :userId ORDER BY dueDateEpochMillis ASC")
    fun getAllTasksFlow(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND dueDateEpochMillis >= :startOfDay AND dueDateEpochMillis <= :endOfDay ORDER BY isCompleted ASC, dueDateEpochMillis ASC")
    fun getTasksForDayFlow(userId: String, startOfDay: Long, endOfDay: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND isCompleted = 0 ORDER BY dueDateEpochMillis ASC")
    fun getPendingTasksFlow(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND isCompleted = 1 ORDER BY completedAtEpochMillis DESC")
    fun getCompletedTasksFlow(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND dueDateEpochMillis > :cutoffEpochMillis AND isCompleted = 0 ORDER BY dueDateEpochMillis ASC")
    fun getUpcomingTasksFlow(userId: String, cutoffEpochMillis: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE userId = :userId AND isSyncedToCloud = 0")
    suspend fun getUnsyncedTasks(userId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TaskEntity>)

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("UPDATE tasks SET isCompleted = :isCompleted, completedAtEpochMillis = :completedAt, isSyncedToCloud = 0, updatedAtEpochMillis = :now WHERE id = :id")
    suspend fun setTaskCompletion(id: String, isCompleted: Boolean, completedAt: Long?, now: Long)

    @Query("UPDATE tasks SET isSyncedToCloud = 1 WHERE id = :id")
    suspend fun markTaskSynced(id: String)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: String)

    @Query("DELETE FROM tasks WHERE userId = :userId AND isCompleted = 1")
    suspend fun deleteCompletedTasks(userId: String)

    @Query("DELETE FROM tasks WHERE userId = :userId")
    suspend fun clearTasksForUser(userId: String)
}

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_events WHERE userId = :userId AND startEpochMillis >= :startOfDay AND startEpochMillis <= :endOfDay ORDER BY startEpochMillis ASC")
    fun getEventsForDayFlow(userId: String, startOfDay: Long, endOfDay: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE userId = :userId ORDER BY startEpochMillis ASC")
    fun getAllEventsFlow(userId: String): Flow<List<CalendarEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<CalendarEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: CalendarEventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteEventById(id: String)

    @Query("DELETE FROM calendar_events WHERE userId = :userId")
    suspend fun clearEventsForUser(userId: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM nudgy_memories WHERE userId = :userId ORDER BY createdAtEpochMillis DESC")
    fun getMemoriesFlow(userId: String): Flow<List<NudgyMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: NudgyMemoryEntity)

    @Query("DELETE FROM nudgy_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String)
}

@Dao
interface DailyBriefDao {
    @Query("SELECT * FROM daily_briefs WHERE userId = :userId AND dateString = :dateString LIMIT 1")
    suspend fun getBriefForDate(userId: String, dateString: String): DailyBriefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBrief(brief: DailyBriefEntity)
}
