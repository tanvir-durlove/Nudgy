package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.local.NudgyDatabase
import com.example.data.local.entity.NudgyMemoryEntity
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class MemoryRepository(
    private val context: Context,
    private val database: NudgyDatabase = NudgyDatabase.getInstance(context)
) {
    private val memoryDao = database.memoryDao()
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var snapshotListener: ListenerRegistration? = null

    fun observeMemories(userId: String): Flow<List<NudgyMemoryEntity>> =
        memoryDao.getMemoriesFlow(userId)

    suspend fun addMemory(userId: String, content: String) = withContext(Dispatchers.IO) {
        val memory = NudgyMemoryEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            content = content
        )
        memoryDao.insertMemory(memory)
        syncMemoryToFirestore(userId, memory)
    }

    suspend fun deleteMemory(userId: String, memoryId: String) = withContext(Dispatchers.IO) {
        memoryDao.deleteMemoryByIdForUser(userId, memoryId)
        deleteMemoryFromFirestore(userId, memoryId)
    }

    suspend fun deleteMemory(memoryId: String) = withContext(Dispatchers.IO) {
        memoryDao.deleteMemoryById(memoryId)
    }

    suspend fun getMemories(userId: String): List<NudgyMemoryEntity> = withContext(Dispatchers.IO) {
        memoryDao.getMemoriesFlow(userId).first()
    }

    suspend fun checkAndSeedInitialMemory(userId: String) = withContext(Dispatchers.IO) {
        // Production: Memories are authored exclusively by user interactions.
    }

    fun startCloudSync(userId: String) {
        if (userId == com.example.auth.AuthManager.GUEST_USER_ID) return
        snapshotListener?.remove()
        try {
            val dbId = context.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(dbId)
            snapshotListener = firestore.collection("users")
                .document(userId)
                .collection("memories")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Memories cloud sync listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        repoScope.launch {
                            for (dc in snapshot.documentChanges) {
                                when (dc.type) {
                                    DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                        val content = dc.document.getString("content") ?: continue
                                        val memory = NudgyMemoryEntity(
                                            id = dc.document.id,
                                            userId = userId,
                                            content = content
                                        )
                                        memoryDao.insertMemory(memory)
                                    }
                                    DocumentChange.Type.REMOVED -> {
                                        memoryDao.deleteMemoryByIdForUser(userId, dc.document.id)
                                    }
                                }
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start memories sync: ${e.message}")
        }
    }

    fun stopCloudSync() {
        snapshotListener?.remove()
        snapshotListener = null
    }

    private fun syncMemoryToFirestore(userId: String, memory: NudgyMemoryEntity) {
        if (userId == com.example.auth.AuthManager.GUEST_USER_ID) return
        repoScope.launch {
            try {
                val dbId = context.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                val payload = hashMapOf<String, Any>(
                    "id" to memory.id,
                    "userId" to memory.userId,
                    "content" to memory.content,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore.collection("users")
                    .document(userId)
                    .collection("memories")
                    .document(memory.id)
                    .set(payload, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Sync memory to Firestore failed: ${e.message}")
            }
        }
    }

    private fun deleteMemoryFromFirestore(userId: String, memoryId: String) {
        if (userId == com.example.auth.AuthManager.GUEST_USER_ID) return
        repoScope.launch {
            try {
                val dbId = context.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                firestore.collection("users")
                    .document(userId)
                    .collection("memories")
                    .document(memoryId)
                    .delete()
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Delete memory from Firestore failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "MemoryRepository"
    }
}
