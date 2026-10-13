package com.example.data.repository

import android.content.Context
import com.example.data.local.NudgyDatabase
import com.example.data.local.entity.CalendarEventEntity
import com.example.data.local.entity.ChatMessageEntity
import com.example.data.local.entity.TaskEntity
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import com.example.ui.screens.ChatActionType
import com.example.ui.screens.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatRepository(
    private val context: Context,
    private val database: NudgyDatabase = NudgyDatabase.getInstance(context)
) {
    private val chatDao = database.chatDao()

    fun observeMessages(userId: String): Flow<List<ChatMessage>> {
        return chatDao.getMessagesFlow(userId).map { entities ->
            entities.map { entity ->
                val actionType = try {
                    ChatActionType.valueOf(entity.actionType)
                } catch (e: Exception) {
                    ChatActionType.NONE
                }

                val task = if (!entity.associatedTaskId.isNullOrBlank() && !entity.associatedTaskTitle.isNullOrBlank()) {
                    TaskEntity(
                        id = entity.associatedTaskId,
                        userId = userId,
                        title = entity.associatedTaskTitle,
                        dueDateEpochMillis = entity.associatedTaskDueMillis ?: entity.timestampMillis,
                        priority = Priority.MEDIUM,
                        category = TaskCategory.WORK
                    )
                } else null

                val event = if (!entity.associatedEventId.isNullOrBlank() && !entity.associatedEventTitle.isNullOrBlank()) {
                    CalendarEventEntity(
                        id = entity.associatedEventId,
                        userId = userId,
                        title = entity.associatedEventTitle,
                        startEpochMillis = entity.associatedEventStartMillis ?: entity.timestampMillis,
                        endEpochMillis = entity.associatedEventEndMillis ?: (entity.timestampMillis + 45 * 60 * 1000L),
                        locationOrLink = "Google Meet",
                        eventType = "MEETING"
                    )
                } else null

                val suggestions = if (entity.quickSuggestionsRaw.isNotBlank()) {
                    entity.quickSuggestionsRaw.split("||").filter { it.isNotBlank() }
                } else emptyList()

                ChatMessage(
                    id = entity.id,
                    senderIsUser = entity.senderIsUser,
                    text = entity.text,
                    timestampMillis = entity.timestampMillis,
                    actionType = actionType,
                    associatedTask = task,
                    associatedEvent = event,
                    focusStartMillis = entity.focusStartMillis,
                    focusEndMillis = entity.focusEndMillis,
                    focusDurationMinutes = entity.focusDurationMinutes,
                    focusActionCompleted = entity.focusActionCompleted,
                    quickSuggestions = suggestions
                )
            }
        }
    }

    suspend fun addMessage(userId: String, message: ChatMessage) = withContext(Dispatchers.IO) {
        val entity = ChatMessageEntity(
            id = message.id,
            userId = userId,
            senderIsUser = message.senderIsUser,
            text = message.text,
            timestampMillis = message.timestampMillis,
            actionType = message.actionType.name,
            associatedTaskId = message.associatedTask?.id,
            associatedTaskTitle = message.associatedTask?.title,
            associatedTaskDueMillis = message.associatedTask?.dueDateEpochMillis,
            associatedEventId = message.associatedEvent?.id,
            associatedEventTitle = message.associatedEvent?.title,
            associatedEventStartMillis = message.associatedEvent?.startEpochMillis,
            associatedEventEndMillis = message.associatedEvent?.endEpochMillis,
            focusStartMillis = message.focusStartMillis,
            focusEndMillis = message.focusEndMillis,
            focusDurationMinutes = message.focusDurationMinutes,
            focusActionCompleted = message.focusActionCompleted,
            quickSuggestionsRaw = message.quickSuggestions.joinToString("||")
        )
        chatDao.insertMessage(entity)
    }

    suspend fun markFocusActionCompleted(messageId: String) = withContext(Dispatchers.IO) {
        chatDao.markFocusActionCompleted(messageId)
    }

    suspend fun checkAndSeedWelcomeMessage(userId: String, displayName: String) = withContext(Dispatchers.IO) {
        val existing = chatDao.getMessagesFlow(userId).first()
        if (existing.isEmpty()) {
            val firstName = displayName.split(" ").firstOrNull() ?: "Arif"
            val welcome = ChatMessage(
                id = "welcome_msg_${UUID.randomUUID()}",
                senderIsUser = false,
                text = "Hi $firstName! What can I help you get done today?",
                quickSuggestions = listOf("Plan my day", "Show today's tasks", "Find free time", "What do you remember?")
            )
            addMessage(userId, welcome)
        }
    }

    suspend fun clearChat(userId: String) = withContext(Dispatchers.IO) {
        chatDao.clearMessages(userId)
    }
}
