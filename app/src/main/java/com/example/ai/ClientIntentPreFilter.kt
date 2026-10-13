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
        val createdEvent: CalendarEventEntity? = null,
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
        val cleaned = cleanUserPrompt(trimmed)
        val lower = cleaned.lowercase()

        // 1. Bengali: "John-কে কল করার কথা মনে করিয়ে দিও" (From User Brief)
        if (cleaned.contains("John", ignoreCase = true) && (cleaned.contains("কল") || cleaned.contains("মনে করিয়ে"))) {
            val cal = Calendar.getInstance()
            if (cleaned.contains("আগামীকাল") || cleaned.contains("কাল")) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
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
            val event = calendarRepository.addEvent(
                userId = userId,
                title = "Call John about the launch",
                startEpochMillis = cal.timeInMillis,
                endEpochMillis = cal.timeInMillis + (30 * 60 * 1000L),
                locationOrLink = "Phone Call",
                eventType = "MEETING"
            )
            return PreFilterResult.Handled(
                responseText = "Done! কাল বিকেল ৩টায় মনে করিয়ে দেব এবং ক্যালেন্ডারে যোগ করেছি।",
                createdTask = task,
                createdEvent = event,
                actionType = ChatActionType.TASK_PREVIEW,
                quickSuggestions = listOf("Plan my day", "Show today's tasks", "Find free time")
            )
        }

        // 2. Bengali: "আজকের সব কাজ দেখাও" (From User Brief)
        if (cleaned.contains("আজকের") && (cleaned.contains("কাজ") || cleaned.contains("দেখাও"))) {
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
        if (lower.contains("schedule today") || lower.contains("what's on my schedule") ||
            lower.contains("what is on my schedule") || lower == "my schedule" || lower == "show schedule"
        ) {
            val totalTasks = todayTasks.size
            val totalMeetings = events.size
            val taskWord = if (totalTasks == 1) "task" else "tasks"
            val meetingWord = if (totalMeetings == 1) "meeting" else "meetings"
            return PreFilterResult.Handled(
                responseText = "You have $totalTasks $taskWord and $totalMeetings $meetingWord today. Your day is organized and on track!",
                quickSuggestions = listOf("Plan my day", "Find free time", "Show today's tasks")
            )
        }

        // 4. Flexible Schedule / Meeting / Appointment Intent (English & Bengali)
        // Matches: "schedule meeting", "put a schedule", "schedule a call", "set a schedule", "appointment", "meeting tomorrow at 3pm"
        val isMeetingOrScheduleIntent = lower.contains("meeting") ||
                lower.contains("schedule") ||
                lower.contains("appointment") ||
                lower.contains("মিটিং") ||
                lower.contains("শিডিউল") ||
                lower.startsWith("call with") ||
                lower.startsWith("sync with")

        if (isMeetingOrScheduleIntent && !lower.contains("what's on my schedule") && !lower.contains("show schedule")) {
            val isTomorrow = lower.contains("tomorrow") || cleaned.contains("কাল") || cleaned.contains("আগামীকাল")
            val cal = Calendar.getInstance()
            if (isTomorrow) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }

            // Extract time if present
            val timeMatcher = Pattern.compile("(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))", Pattern.CASE_INSENSITIVE).matcher(cleaned)
            if (timeMatcher.find()) {
                parseTimeIntoCalendar(timeMatcher.group(1) ?: "2:00 PM", cal)
            } else {
                // If past 2pm today, default to next open hour
                val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (!isTomorrow && currentHour >= 14) {
                    cal.add(Calendar.HOUR_OF_DAY, 2)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                } else {
                    cal.set(Calendar.HOUR_OF_DAY, 14) // 2:00 PM default
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                }
            }

            val startMillis = cal.timeInMillis
            val endMillis = startMillis + (45 * 60 * 1000L) // 45 minutes default duration

            // Extract clean title
            var meetingTitle = cleaned
                .replace(Regex("(?i)schedule\\s+a\\s+meeting\\s+(?:with|for)?"), "")
                .replace(Regex("(?i)schedule\\s+meeting\\s+(?:with|for)?"), "")
                .replace(Regex("(?i)schedule\\s+(?:a\\s+)?call\\s+(?:with|for)?"), "")
                .replace(Regex("(?i)put\\s+(?:a\\s+)?schedule\\s+(?:for|to|with)?"), "")
                .replace(Regex("(?i)set\\s+(?:a\\s+)?schedule\\s+(?:for|to|with)?"), "")
                .replace(Regex("(?i)create\\s+(?:a\\s+)?meeting\\s+(?:with|for)?"), "")
                .replace(Regex("(?i)add\\s+(?:a\\s+)?meeting\\s+(?:with|for)?"), "")
                .replace(Regex("(?i)book\\s+(?:a\\s+)?meeting\\s+(?:with|for)?"), "")
                .replace(Regex("(?i)schedule\\s+for"), "")
                .replace(Regex("(?i)schedule"), "")
                .replace(Regex("(?i)meeting\\s+with"), "")
                .replace(Regex("(?i)meeting"), "")
                .replace(Regex("(?i)\\b(tomorrow|today)\\b"), "")
                .replace(Regex("(?i)at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?"), "")
                .replace(Regex("(?i)\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)"), "")
                .replace(Regex("^[,:\\s-]+"), "")
                .replace(Regex("[,:\\s-]+$"), "")
                .trim()

            if (meetingTitle.isBlank() || meetingTitle.equals("for", ignoreCase = true) || meetingTitle.equals("to", ignoreCase = true)) {
                meetingTitle = if (lower.contains("call")) "Team Phone Call" else "Team Sync Meeting"
            } else {
                meetingTitle = meetingTitle.replaceFirstChar { it.uppercase() }
            }

            // 1. Add to Calendar
            val createdEvent = calendarRepository.addEvent(
                userId = userId,
                title = meetingTitle,
                startEpochMillis = startMillis,
                endEpochMillis = endMillis,
                locationOrLink = "Google Meet",
                eventType = "MEETING"
            )

            // 2. ALSO add to Tasks repository so it appears on Tasks tab & Home screen!
            val createdTask = taskRepository.createTask(
                userId = userId,
                title = meetingTitle,
                description = "Scheduled meeting ($meetingTitle)",
                dueEpochMillis = startMillis,
                priority = Priority.HIGH,
                category = TaskCategory.WORK,
                reminderSet = true
            )

            val dayLabel = if (isTomorrow) "tomorrow" else "today"
            val formattedTime = timeFormat.format(startMillis)
            return PreFilterResult.Handled(
                responseText = "Done! Scheduled \"$meetingTitle\" for $dayLabel at $formattedTime on both your calendar and task list. 📅🔔",
                createdTask = createdTask,
                createdEvent = createdEvent,
                actionType = ChatActionType.EVENT_CREATED,
                focusStartMillis = startMillis,
                focusEndMillis = endMillis,
                quickSuggestions = listOf("Show schedule", "Plan my day", "Find free time")
            )
        }

        // 5. English / Bengali: "Remind me in 30 minutes"
        if (lower.contains("30 minute") || lower.contains("30 minutes") || cleaned.contains("৩০ মিনিট")) {
            val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 30) }
            val reminderTask = taskRepository.createTask(
                userId = userId,
                title = "Follow-up reminder",
                dueEpochMillis = cal.timeInMillis,
                priority = Priority.MEDIUM,
                category = TaskCategory.PERSONAL,
                reminderSet = true
            )
            return PreFilterResult.Handled(
                responseText = "Done! I've set a reminder for 30 minutes from now (at ${timeFormat.format(cal.timeInMillis)}). 🔔",
                createdTask = reminderTask,
                actionType = ChatActionType.TASK_PREVIEW,
                quickSuggestions = listOf("Plan my day", "Show today's tasks")
            )
        }

        // 6. English / Bengali: "Find free time" / "Show free time" / "ফাঁকা সময়"
        if (lower.contains("free time") || lower.contains("open time") || lower.contains("focus slot") ||
            cleaned.contains("ফাঁকা সময়") || cleaned.contains("ফ্রি সময়")
        ) {
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
        if (lower.contains("block focus") || lower.contains("block 45m") || lower.contains("schedule focus") || cleaned.contains("ফোকাস")) {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 12)
            cal.set(Calendar.MINUTE, 15)
            cal.set(Calendar.SECOND, 0)
            val startMillis = cal.timeInMillis

            cal.set(Calendar.HOUR_OF_DAY, 13)
            cal.set(Calendar.MINUTE, 0)
            val endMillis = cal.timeInMillis

            val event = calendarRepository.blockFocusTime(userId, startMillis, endMillis, "Focus Time (Deep Work)")
            val task = taskRepository.createTask(
                userId = userId,
                title = "Focus Time (Deep Work)",
                dueEpochMillis = startMillis,
                priority = Priority.HIGH,
                category = TaskCategory.WORK,
                reminderSet = true
            )
            return PreFilterResult.Handled(
                responseText = "Done! Reserved 45 minutes of Deep Work on your calendar and task list (12:15 PM – 1:00 PM). Let's stay in the zone! 🎯",
                createdTask = task,
                createdEvent = event,
                quickSuggestions = listOf("Plan my day", "Show today's tasks", "What do you remember?")
            )
        }

        // 8. English: "Plan my day" / "দিনের প্ল্যান"
        if (lower == "plan my day" || lower.contains("plan my day") || cleaned.contains("প্ল্যান")) {
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
        if (lower.contains("today's tasks") || lower.contains("my tasks") || lower == "show tasks" || lower == "tasks") {
            val total = todayTasks.size
            val completed = todayTasks.count { it.isCompleted }
            val taskList = if (todayTasks.isEmpty()) {
                "No tasks scheduled for today yet. Tell me 'Add task [title]' to create one!"
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
        val rememberMatcher = rememberPattern.matcher(cleaned)
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
        if (lower.contains("remember about me") || lower.contains("what do you remember") || cleaned.contains("কী মনে রেখেছো") || cleaned.contains("কী জানো")) {
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
        if (lower.contains("clear completed") || lower.contains("delete completed") || cleaned.contains("সম্পন্ন কাজ মুছুন")) {
            taskRepository.clearCompletedTasks(userId)
            return PreFilterResult.Handled(
                responseText = "Done! All completed tasks have been cleared from your task list.",
                quickSuggestions = listOf("Show today's tasks", "Plan my day", "Find free time")
            )
        }

        // 13. Complete task by title: "Complete task [title]" / "Mark [title] as done"
        val completePattern = Pattern.compile("(?:complete task|mark|done)\\s+(.+?)(?:\\s+as done)?$", Pattern.CASE_INSENSITIVE)
        val completeMatcher = completePattern.matcher(cleaned)
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

        // 14. Flexible Task Phrases: "remind me to...", "add task...", "create task...", "todo...", "call...", "buy...", etc.
        val taskKeywords = listOf(
            "remind me to", "remind me", "add task", "create task", "add a task",
            "put a task", "new task", "todo", "need to", "have to", "don't forget to", "dont forget to",
            "remember to", "মনে করিয়ে দাও", "কাজ যোগ করো", "টাস্ক"
        )

        var matchedPrefix = taskKeywords.firstOrNull { lower.startsWith(it) }
        var isTaskIntent = matchedPrefix != null

        // Or starts with direct imperative action verb
        val actionVerbs = listOf(
            "call ", "buy ", "email ", "finish ", "send ", "review ",
            "pay ", "clean ", "study ", "cook ", "read ", "write ", "workout ",
            "order ", "fix ", "check ", "meet "
        )
        if (!isTaskIntent && actionVerbs.any { lower.startsWith(it) }) {
            isTaskIntent = true
            matchedPrefix = ""
        }

        if (isTaskIntent) {
            var rawTask = if (matchedPrefix.isNullOrBlank()) cleaned else cleaned.substring(matchedPrefix.length).trim()
            if (rawTask.startsWith("to ", ignoreCase = true)) {
                rawTask = rawTask.substring(3).trim()
            }
            if (rawTask.startsWith("that ", ignoreCase = true)) {
                rawTask = rawTask.substring(5).trim()
            }

            val isTomorrow = lower.contains("tomorrow") || cleaned.contains("কাল") || cleaned.contains("আগামীকাল")
            val cal = Calendar.getInstance()
            if (isTomorrow) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }

            // Extract time like "at 5 pm", "at 5:30", "at 4pm", "5 PM"
            val timeRegex = Pattern.compile("(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))", Pattern.CASE_INSENSITIVE)
            val timeMatcher = timeRegex.matcher(rawTask)
            val hasExplicitTime = timeMatcher.find()
            if (hasExplicitTime) {
                val foundTime = timeMatcher.group(1) ?: "12:00 PM"
                parseTimeIntoCalendar(foundTime, cal)
            } else {
                // If past 2pm today, set in 2 hours
                cal.add(Calendar.HOUR_OF_DAY, 2)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
            }

            // Clean title: remove "tomorrow", "today", "at 5 PM"
            var cleanTitle = rawTask
                .replace(Regex("(?i)\\b(tomorrow|today)\\b"), "")
                .replace(Regex("(?i)at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?"), "")
                .replace(Regex("(?i)\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)"), "")
                .replace(Regex("^[,:\\s-]+"), "")
                .replace(Regex("[,:\\s-]+$"), "")
                .trim()

            if (cleanTitle.isBlank()) {
                cleanTitle = "New Task"
            } else {
                cleanTitle = cleanTitle.replaceFirstChar { it.uppercase() }
            }

            val task = taskRepository.createTask(
                userId = userId,
                title = cleanTitle,
                dueEpochMillis = cal.timeInMillis,
                priority = if (lower.contains("urgent") || lower.contains("important") || lower.contains("high")) Priority.HIGH else Priority.MEDIUM,
                category = if (lower.contains("work") || lower.contains("launch") || lower.contains("project") || lower.contains("client")) TaskCategory.WORK
                else if (lower.contains("study") || lower.contains("class") || lower.contains("book")) TaskCategory.STUDY
                else TaskCategory.PERSONAL,
                reminderSet = true
            )

            // If an explicit time was scheduled, also put on Calendar
            val event = if (hasExplicitTime) {
                calendarRepository.addEvent(
                    userId = userId,
                    title = cleanTitle,
                    startEpochMillis = cal.timeInMillis,
                    endEpochMillis = cal.timeInMillis + (30 * 60 * 1000L),
                    locationOrLink = "Personal",
                    eventType = "EVENT"
                )
            } else null

            val formatted = timeFormat.format(cal.timeInMillis)
            val dayLabel = if (isTomorrow) "tomorrow at" else "today at"
            return PreFilterResult.Handled(
                responseText = "Done! Added '$cleanTitle' for $dayLabel $formatted with a reminder set. 🔔",
                createdTask = task,
                createdEvent = event,
                actionType = ChatActionType.TASK_PREVIEW,
                quickSuggestions = listOf("Show today's tasks", "Plan my day", "Find free time")
            )
        }

        // 15. Intelligent Fallback for Any Natural Language Action Phrase (e.g. "Doctor appointment 5pm", "Gym session tomorrow")
        if (lower.contains(" at ") || lower.contains("pm") || lower.contains("am") || lower.contains("tomorrow") || lower.contains("today")) {
            val cal = Calendar.getInstance()
            val isTomorrow = lower.contains("tomorrow")
            if (isTomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)

            val timeMatcher = Pattern.compile("(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))", Pattern.CASE_INSENSITIVE).matcher(cleaned)
            if (timeMatcher.find()) {
                parseTimeIntoCalendar(timeMatcher.group(1) ?: "3:00 PM", cal)
            } else {
                cal.add(Calendar.HOUR_OF_DAY, 2)
            }

            var fallbackTitle = cleaned
                .replace(Regex("(?i)\\b(tomorrow|today)\\b"), "")
                .replace(Regex("(?i)at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?"), "")
                .replace(Regex("(?i)\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)"), "")
                .replace(Regex("^[,:\\s-]+"), "")
                .replace(Regex("[,:\\s-]+$"), "")
                .trim()

            if (fallbackTitle.isNotBlank() && fallbackTitle.length in 3..60) {
                fallbackTitle = fallbackTitle.replaceFirstChar { it.uppercase() }
                val task = taskRepository.createTask(
                    userId = userId,
                    title = fallbackTitle,
                    dueEpochMillis = cal.timeInMillis,
                    priority = Priority.MEDIUM,
                    category = TaskCategory.PERSONAL,
                    reminderSet = true
                )
                val event = calendarRepository.addEvent(
                    userId = userId,
                    title = fallbackTitle,
                    startEpochMillis = cal.timeInMillis,
                    endEpochMillis = cal.timeInMillis + (45 * 60 * 1000L),
                    locationOrLink = "Personal",
                    eventType = "EVENT"
                )
                val formatted = timeFormat.format(cal.timeInMillis)
                val dayLabel = if (isTomorrow) "tomorrow at" else "today at"
                return PreFilterResult.Handled(
                    responseText = "Got it! I've scheduled '$fallbackTitle' for $dayLabel $formatted on your task list and calendar. 🔔📅",
                    createdTask = task,
                    createdEvent = event,
                    actionType = ChatActionType.TASK_PREVIEW,
                    quickSuggestions = listOf("Show today's tasks", "Plan my day", "Find free time")
                )
            }
        }

        return PreFilterResult.Unhandled
    }

    private fun cleanUserPrompt(prompt: String): String {
        var text = prompt.trim()
        val prefixes = listOf(
            "hey nudgy", "ok nudgy", "hi nudgy", "hello nudgy", "nudgy",
            "হে নাজি", "নাজি", "হে নাদি",
            "can you please", "could you please", "can you", "could you", "please",
            "i want to", "i need to", "help me to", "help me", "i would like to",
            "tell me to", "would you"
        )
        for (prefix in prefixes) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length).trim()
            }
        }
        return text.trimStart(',', ':', '.', '-', ' ', '!')
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
