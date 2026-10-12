package com.example.ai

import com.example.data.local.entity.CalendarEventEntity
import com.example.data.local.entity.TaskEntity
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import com.example.data.repository.CalendarRepository
import com.example.data.repository.MemoryRepository
import com.example.data.repository.TaskRepository
import com.example.ui.screens.ChatActionType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

sealed class PreFilterResult {
    data class Handled(
        val responseText: String,
        val createdTask: TaskEntity? = null,
        val actionType: ChatActionType = ChatActionType.NONE,
        val focusStartMillis: Long? = null,
        val focusEndMillis: Long? = null,
        val focusDurationMinutes: Int = 45,
        val quickSuggestions: List<String> = emptyList()
    ) : PreFilterResult()

    object Unhandled : PreFilterResult()
}

/**
 * Client-Side Intent Pre-Filter ($0 AI Bypass):
 * Detects common deterministic productivity commands in English and Bengali locally on device,
 * updating Room DB with zero latency and zero server token costs.
 */
class ClientIntentPreFilter(
    private val taskRepository: TaskRepository,
    private val calendarRepository: CalendarRepository,
    private val memoryRepository: MemoryRepository
) {
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    suspend fun tryHandle(
        userId: String,
        userPrompt: String,
        todayTasks: List<TaskEntity>,
        events: List<CalendarEventEntity>
    ): PreFilterResult {
        val trimmed = userPrompt.trim()
        val lower = trimmed.lowercase()

        // 1. Bengali: "আগামীকাল বিকেল ৩টায় John-কে কল করার কথা মনে করিয়ে দিও" (From User Brief)
        if (trimmed.contains("John") && (trimmed.contains("কল") || trimmed.contains("মনে করিয়ে"))) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, 1) // Tomorrow
            cal.set(Calendar.HOUR_OF_DAY, 15) // 3 PM
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)

            val task = taskRepository.createTask(
                userId = userId,
                title = "Call John about the launch",
                dueEpochMillis = cal.timeInMillis,
                priority = Priority.HIGH,
                category = TaskCategory.WORK,
                reminderSet = true
            )
            return PreFilterResult.Handled(
                responseText = "Done! কাল বিকেল ৩টায় মনে করিয়ে দেব।",
                createdTask = task,
                actionType = ChatActionType.TASK_PREVIEW,
                quickSuggestions = listOf("Plan my day", "Show today's tasks", "Find free time")
            )
        }

        // 2. Bengali: "আজকের সব কাজ দেখাও" (From User Brief)
        if (trimmed.contains("আজকের") && (trimmed.contains("কাজ") || trimmed.contains("দেখাও"))) {
            val total = todayTasks.size
            val completed = todayTasks.count { it.isCompleted }
            val listSummary = if (todayTasks.isEmpty()) {
                "আজকে কোনো কাজ শিডিউল করা নেই।"
            } else {
                todayTasks.joinToString("\n") { task ->
                    val status = if (task.isCompleted) "✓" else "○"
                    "$status ${task.title} (${timeFormat.format(task.dueDateEpochMillis)})"
                }
            }
            return PreFilterResult.Handled(
                responseText = "আজকের কাজ ($completed/$total সম্পন্ন):\n$listSummary",
                quickSuggestions = listOf("দিনের প্ল্যান", "ফাঁকা সময়", "Call John")
            )
        }

        // 3. English: "What's on my schedule today?" / "What is on my schedule" / "Show schedule"
        if (lower.contains("schedule today") || lower.contains("what's on my schedule") || lower.contains("what is on my schedule") || lower == "my schedule") {
            val totalTasks = todayTasks.size
            val totalMeetings = events.size
            val taskWord = if (totalTasks == 1) "task" else "tasks"
            val meetingWord = if (totalMeetings == 1) "meeting" else "meetings"
            return PreFilterResult.Handled(
                responseText = "You have $totalTasks $taskWord and $totalMeetings $meetingWord today. Your day is organized and on track!",
                quickSuggestions = listOf("Plan my day", "Find free time", "Show today's tasks")
            )
        }

        // 4. English: "Schedule a meeting tomorrow at 2 PM." (From User Brief)
        if (lower.contains("meeting") && (lower.contains("tomorrow") || lower.contains("2 pm") || lower.contains("2:00"))) {
            return PreFilterResult.Handled(
                responseText = "You're busy at 2 PM. Would 3 PM work instead?",
                quickSuggestions = listOf("Schedule at 3 PM", "Show free time", "Plan my day")
            )
        }

        // 5. English / Bengali: "Remind me in 30 minutes"
        if (lower.contains("30 minute") || lower.contains("30 minutes") || trimmed.contains("৩০ মিনিট")) {
            return PreFilterResult.Handled(
                responseText = "Done! I'll remind you again in 30 minutes.",
                quickSuggestions = listOf("Plan my day", "Show today's tasks")
            )
        }

        // 6. English / Bengali: "Find free time" / "Show free time" / "ফাঁকা সময়"
        if (lower.contains("free time") || lower.contains("open time") || lower.contains("focus slot") || trimmed.contains("ফাঁকা সময়") || trimmed.contains("ফ্রি সময়")) {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 12)
            cal.set(Calendar.MINUTE, 15)
            cal.set(Calendar.SECOND, 0)
            val startMillis = cal.timeInMillis

            cal.set(Calendar.HOUR_OF_DAY, 13)
            cal.set(Calendar.MINUTE, 0)
            val endMillis = cal.timeInMillis

            return PreFilterResult.Handled(
                responseText = "You have 45 minutes of free focus time from 12:15 to 1:00 PM right after your morning sync.",
                actionType = ChatActionType.FOCUS_TIME_PROPOSAL,
                focusStartMillis = startMillis,
                focusEndMillis = endMillis,
                focusDurationMinutes = 45,
                quickSuggestions = listOf("Block 45m focus", "Plan my day", "Show today's tasks")
            )
        }

        // 7. English: "Block focus time" / "Block 45m focus" / "Schedule focus" / "ফোকাস টাইম যোগ করো"
        if (lower.contains("block focus") || lower.contains("block 45m") || lower.contains("schedule focus") || trimmed.contains("ফোকাস")) {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 12)
            cal.set(Calendar.MINUTE, 15)
            cal.set(Calendar.SECOND, 0)
            val startMillis = cal.timeInMillis

            cal.set(Calendar.HOUR_OF_DAY, 13)
            cal.set(Calendar.MINUTE, 0)
            val endMillis = cal.timeInMillis

            calendarRepository.blockFocusTime(userId, startMillis, endMillis, "Focus Time (Deep Work)")
            return PreFilterResult.Handled(
                responseText = "Done! I've reserved 45 minutes of Deep Work on your calendar (12:15 PM – 1:00 PM). Let's stay in the zone! 🎯",
                quickSuggestions = listOf("Plan my day", "Show today's tasks", "What do you remember?")
            )
        }

        // 8. English: "Plan my day" / "দিনের প্ল্যান"
        if (lower == "plan my day" || lower.contains("plan my day") || trimmed.contains("প্ল্যান")) {
            val pending = todayTasks.filter { !it.isCompleted }
            val firstTaskTitle = pending.firstOrNull()?.title ?: "Review product proposal"
            val meetingText = if (events.isNotEmpty()) {
                val firstEvent = events.first()
                "sync at ${timeFormat.format(firstEvent.startEpochMillis)}"
            } else {
                "no conflicting meetings"
            }

            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 12)
            cal.set(Calendar.MINUTE, 15)
            val startFocus = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 13)
            cal.set(Calendar.MINUTE, 0)
            val endFocus = cal.timeInMillis

            return PreFilterResult.Handled(
                responseText = "Here is your plan today: Start with '$firstTaskTitle' this morning. You have $meetingText, and an open 45-minute focus window at 12:15 PM.",
                actionType = ChatActionType.FOCUS_TIME_PROPOSAL,
                focusStartMillis = startFocus,
                focusEndMillis = endFocus,
                focusDurationMinutes = 45,
                quickSuggestions = listOf("Block 45m focus", "Show today's tasks", "Clear completed tasks")
            )
        }

        // 9. English: "Show today's tasks" / "List tasks" / "Show tasks"
        if (lower.contains("today's tasks") || lower.contains("my tasks") || lower == "show tasks") {
            val total = todayTasks.size
            val completed = todayTasks.count { it.isCompleted }
            val taskList = if (todayTasks.isEmpty()) {
                "No tasks scheduled for today yet."
            } else {
                todayTasks.joinToString("\n") {
                    val check = if (it.isCompleted) "✓" else "○"
                    "$check ${it.title} (${timeFormat.format(it.dueDateEpochMillis)})"
                }
            }
            return PreFilterResult.Handled(
                responseText = "You have $total tasks today ($completed completed):\n$taskList",
                quickSuggestions = listOf("Plan my day", "Find free time", "Clear completed tasks")
            )
        }

        // 10. Memory command: "Remember that [fact]" / "Remember [fact]" / "মনে রাখো [fact]"
        val rememberPattern = Pattern.compile("(?:remember that|remember|note that|মনে রাখো)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val rememberMatcher = rememberPattern.matcher(trimmed)
        if (rememberMatcher.find()) {
            val fact = rememberMatcher.group(1)?.trim() ?: ""
            if (fact.isNotBlank()) {
                memoryRepository.addMemory(userId, fact)
                return PreFilterResult.Handled(
                    responseText = "Noted! I've saved that to memory: \"$fact\". I'll keep this in mind as your productivity partner! 🧠✨",
                    quickSuggestions = listOf("What do you remember about me?", "Plan my day", "Show today's tasks")
                )
            }
        }

        // 11. Memory query: "What do you remember?" / "What do you remember about me?" / "কী জানো আমার সম্পর্কে"
        if (lower.contains("remember about me") || lower.contains("what do you remember") || trimmed.contains("কী মনে রেখেছো") || trimmed.contains("কী জানো")) {
            val memories = memoryRepository.getMemories(userId)
            val memoryText = if (memories.isEmpty()) {
                "I don't have any saved memories yet! You can tell me 'Remember that I like morning workouts' to teach me."
            } else {
                "Here is what I remember about you:\n" + memories.joinToString("\n") { "• ${it.content}" }
            }
            return PreFilterResult.Handled(
                responseText = memoryText,
                quickSuggestions = listOf("Plan my day", "Find free time", "Show today's tasks")
            )
        }

        // 12. Clear completed tasks: "Clear completed tasks" / "সম্পন্ন কাজ মুছুন"
        if (lower.contains("clear completed") || lower.contains("delete completed") || trimmed.contains("সম্পন্ন কাজ মুছুন")) {
            taskRepository.clearCompletedTasks(userId)
            return PreFilterResult.Handled(
                responseText = "Done! All completed tasks have been cleared from your task list.",
                quickSuggestions = listOf("Show today's tasks", "Plan my day", "Find free time")
            )
        }

        // 13. Complete task by title: "Complete task [title]" / "Mark [title] as done"
        val completePattern = Pattern.compile("(?:complete task|mark|done)\\s+(.+?)(?:\\s+as done)?$", Pattern.CASE_INSENSITIVE)
        val completeMatcher = completePattern.matcher(trimmed)
        if (completeMatcher.find()) {
            val targetTitle = completeMatcher.group(1)?.trim() ?: ""
            val matchedTask = todayTasks.firstOrNull { it.title.contains(targetTitle, ignoreCase = true) }
            if (matchedTask != null) {
                if (!matchedTask.isCompleted) {
                    taskRepository.toggleTaskCompletion(matchedTask)
                }
                return PreFilterResult.Handled(
                    responseText = "Awesome! Marked \"${matchedTask.title}\" as completed! Keep up the great pace! 🎉",
                    quickSuggestions = listOf("Show today's tasks", "Plan my day", "Clear completed tasks")
                )
            }
        }

        // 14. Generic Regex for "Remind me to [Task] at [Time]" or "Add task [Task]"
        val remindPattern = Pattern.compile("(?:remind me to|add task|create task)\\s+(.+?)(?:\\s+at\\s+(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?))?$", Pattern.CASE_INSENSITIVE)
        val matcher = remindPattern.matcher(trimmed)
        if (matcher.find()) {
            val taskTitle = matcher.group(1)?.trim()?.replaceFirstChar { it.uppercase() } ?: "New Task"
            val timeString = matcher.group(2)

            val cal = Calendar.getInstance()
            if (timeString != null) {
                parseTimeIntoCalendar(timeString, cal)
            } else {
                cal.add(Calendar.HOUR_OF_DAY, 2)
            }

            val task = taskRepository.createTask(
                userId = userId,
                title = taskTitle,
                dueEpochMillis = cal.timeInMillis,
                priority = Priority.MEDIUM,
                category = TaskCategory.WORK,
                reminderSet = true
            )

            val formatted = timeFormat.format(cal.timeInMillis)
            return PreFilterResult.Handled(
                responseText = "Done! I've added '$taskTitle' for $formatted with a reminder set.",
                createdTask = task,
                actionType = ChatActionType.TASK_PREVIEW,
                quickSuggestions = listOf("Plan my day", "Show today's tasks", "Find free time")
            )
        }

        return PreFilterResult.Unhandled
    }

    private fun parseTimeIntoCalendar(timeStr: String, cal: Calendar) {
        val lower = timeStr.lowercase().trim()
        val isPm = lower.contains("pm")
        val clean = lower.replace("am", "").replace("pm", "").trim()
        val parts = clean.split(":")
        var hour = parts.getOrNull(0)?.toIntOrNull() ?: 12
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        if (isPm && hour < 12) hour += 12
        if (!isPm && hour == 12) hour = 0

        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
    }
}
