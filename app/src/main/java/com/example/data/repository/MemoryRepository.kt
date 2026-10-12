package com.example.data.repository

import android.content.Context
import com.example.data.local.NudgyDatabase
import com.example.data.local.entity.NudgyMemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

class MemoryRepository(
    private val context: Context,
    private val database: NudgyDatabase = NudgyDatabase.getInstance(context)
) {
    private val memoryDao = database.memoryDao()

    fun observeMemories(userId: String): Flow<List<NudgyMemoryEntity>> =
        memoryDao.getMemoriesFlow(userId)

    suspend fun addMemory(userId: String, content: String) = withContext(Dispatchers.IO) {
        val memory = NudgyMemoryEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            content = content
        )
        memoryDao.insertMemory(memory)
    }

    suspend fun deleteMemory(memoryId: String) = withContext(Dispatchers.IO) {
        memoryDao.deleteMemoryById(memoryId)
    }

    suspend fun getMemories(userId: String): List<NudgyMemoryEntity> = withContext(Dispatchers.IO) {
        memoryDao.getMemoriesFlow(userId).first()
    }

    suspend fun checkAndSeedInitialMemory(userId: String) = withContext(Dispatchers.IO) {
        val current = memoryDao.getMemoriesFlow(userId).first()
        if (current.isEmpty()) {
            val seed = NudgyMemoryEntity(
                id = "seed_memory_1",
                userId = userId,
                content = "You prefer focus time in the morning and studying at 7 PM."
            )
            memoryDao.insertMemory(seed)
        }
    }
}
