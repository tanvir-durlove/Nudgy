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
    private fun formatTime(millis: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(millis))

    private fun formatTime(date: java.util.Date): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)

    suspend fun tryHandle(
        userId: String,
        userPrompt: String,
        todayTasks: List<TaskEntity>,
        events: List<CalendarEventEntity>
    ): PreFilterResult {
        val trimmed = userPrompt.trim()
        val cleaned = cleanUserPrompt(trimmed)
        val lower = cleaned.lowercase()
        // Digit-normalized copy used ONLY for time extraction, so Bengali
        // digits (৫টায়) parse the same as ASCII (5 PM). Titles keep the
        // user's original script.
        val cleanedNorm = normalizeBengaliDigits(cleaned)
        // Timed tasks count as busy time when suggesting focus slots.
        val taskBusyIntervals = todayTasks.filter { !it.isCompleted }
            .map { it.dueDateEpochMillis to (it.dueDateEpochMillis + 30 * 60 * 1000L) }

        // 1. Bengali: Dynamic Call / Reminder Intent (e.g. "রাহুলকে কল করার কথা মনে করিয়ে দিও")
        if (cleaned.contains("মনে করিয়ে") || (cleaned.contains("কল") && cleaned.contains("করার"))) {
            val isTomorrow = mentionsTomorrow(cleaned, lower)
            val cal = Calendar.getInstance()
            if (isTomorrow) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            // Check for explicit time like "৩টায়", "3 PM"
            val timeMatcher = Pattern.compile("(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\s*(?:টায়|টায়)?)", Pattern.CASE_INSENSITIVE).matcher(cleanedNorm)
            if (timeMatcher.find()) {
                val rawTime = timeMatcher.group(1)?.replace("টায়", "")?.replace("টায়", "")?.trim() ?: "3 PM"
                parseTimeIntoCalendar(rawTime, cal)
            } else {
                cal.set(Calendar.HOUR_OF_DAY, 15) // default 3 PM
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }

            var title = cleaned
                .replace("মনে করিয়ে দিও", "")
                .replace("মনে করিয়ে দাও", "")
                .replace("কথা", "")
                .replace("আগামীকাল", "")
                .replace(Regex("(^|\\s)কাল(কে)?(?=\\s|$)"), " ")
                .trim()
            if (title.isBlank()) title = "জরুরি কল ও ফলোআপ"

            val task = taskRepository.createTask(
                userId = userId,
                title = title,
                dueEpochMillis = cal.timeInMillis,
                priority = Priority.HIGH,
                category = TaskCategory.WORK,
                reminderSet = true
            )
            // Label from the ACTUAL date: parseTimeIntoCalendar may have
            // rolled a past time to tomorrow — never claim "today" then.
            val dayLabel = if (cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) "আগামীকাল" else "আজ"
            val formatted = formatTime(cal.timeInMillis)
            return PreFilterResult.Handled(
                responseText = "ঠিক আছে! $dayLabel $formatted সময়ে \"$title\" মনে করিয়ে দেব। 🔔",
                createdTask = task,
                actionType = ChatActionType.TASK_PREVIEW,
                quickSuggestions = listOf("দিনের প্ল্যান", "আজকের কাজ", "ফাঁকা সময়")
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
                    "$status ${task.title} (${formatTime(task.dueDateEpochMillis)})"
                }
            }
            return PreFilterResult.Handled(
                responseText = "আজকের কাজ ($completed/$total সম্পন্ন):\n$listSummary",
                quickSuggestions = listOf("দিনের প্ল্যান", "ফাঁকা সময়", "নতুন কাজ")
            )
        }

        // 3. English: "What's on my schedule today?" / "What is on my schedule" / "Show schedule" / "Scheduled"
        val isScheduleQuery = lower == "scheduled" || lower == "schedule" ||
                lower.contains("schedule today") || lower.contains("what's on my schedule") ||
                lower.contains("what is on my schedule") || lower == "my schedule" || lower == "show schedule" ||
                lower.contains("scheduled items") || lower.contains("scheduled tasks") ||
                lower.contains("what is scheduled") || lower.contains("what's scheduled") ||
                lower.contains("show scheduled") || lower.contains("view schedule") ||
                cleaned.contains("শিডিউল দেখাও") || cleaned.contains("আজকের শিডিউল")

        if (isScheduleQuery) {
            val totalTasks = todayTasks.size
            val totalMeetings = events.size
            val response = if (totalTasks == 0 && totalMeetings == 0) {
                "You have no items scheduled for today! Your schedule is completely open. Tap + to add a task or say 'Schedule meeting with team tomorrow at 2pm'."
            } else {
                val lines = mutableListOf<String>()
                lines.add("Here is your schedule for today ($totalMeetings ${if (totalMeetings == 1) "meeting" else "meetings"}, $totalTasks ${if (totalTasks == 1) "task" else "tasks"}):")
                if (events.isNotEmpty()) {
                    lines.add("\n📅 Meetings & Events:")
                    events.sortedBy { it.startEpochMillis }.forEach {
                        lines.add("• ${it.title} (${formatTime(it.startEpochMillis)} – ${formatTime(it.endEpochMillis)})")
                    }
                }
                if (todayTasks.isNotEmpty()) {
                    lines.add("\n✅ Tasks:")
                    todayTasks.sortedBy { it.dueDateEpochMillis }.forEach {
                        val status = if (it.isCompleted) "✓" else "○"
                        lines.add("$status ${it.title} (due ${formatTime(it.dueDateEpochMillis)})")
                    }
                }
                lines.joinToString("\n")
            }
            return PreFilterResult.Handled(
                responseText = response.trim(),
                quickSuggestions = listOf("Plan my day", "Find free time", "Show today's tasks")
            )
        }

        // 4. Flexible Schedule / Meeting / Appointment Intent (English & Bengali)
        // Guard against cancellation/deletion commands ("cancel appointment", "delete meeting")
        val isCancellation = lower.contains("cancel") || lower.contains("delete") ||
                lower.contains("remove") || cleaned.contains("বাতিল") || cleaned.contains("মুছে")
        val isMeetingOrScheduleIntent = !isCancellation && (
                lower.startsWith("schedule ") ||
                lower.startsWith("schedule a ") ||
                lower.contains("schedule a meeting") ||
                lower.contains("schedule meeting") ||
                lower.contains("book meeting") ||
                lower.contains("set a meeting") ||
                lower.contains("new appointment") ||
                lower.contains("book an appointment") ||
                lower.contains("schedule appointment") ||
                lower.contains("মিটিং শিডিউল") ||
                lower.startsWith("call with") ||
                lower.startsWith("sync with")
        )

        if (isMeetingOrScheduleIntent && !isScheduleQuery) {
            val isTomorrow = mentionsTomorrow(cleaned, lower)
            val cal = Calendar.getInstance()
            if (isTomorrow) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }

            // Extract time if present
            val timeMatcher = Pattern.compile("(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))", Pattern.CASE_INSENSITIVE).matcher(cleanedNorm)
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

            // Meetings live on the Calendar only — no shadow task is created.
            // Label from the ACTUAL date (the parser may roll a past time to
            // tomorrow) and warn when the slot overlaps an existing event.
            val dayLabel = if (cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) "tomorrow" else "today"
            val formattedTime = formatTime(startMillis)
            val conflict = calendarRepository.checkConflict(userId, startMillis, endMillis)
            val conflictNote = if (conflict.hasConflict && conflict.conflictingEvent?.id != createdEvent.id) {
                " ⚠ Heads up: this overlaps with '${conflict.conflictingEvent?.title}'."
            } else ""
            return PreFilterResult.Handled(
                responseText = "Done! Scheduled \"$meetingTitle\" for $dayLabel at $formattedTime on your calendar. 📅$conflictNote",
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
                responseText = "Done! I've set a reminder for 30 minutes from now (at ${formatTime(cal.timeInMillis)}). 🔔",
                createdTask = reminderTask,
                actionType = ChatActionType.TASK_PREVIEW,
                quickSuggestions = listOf("Plan my day", "Show today's tasks")
            )
        }

        // 6. English / Bengali: "Find free time" / "Show free time" / "ফাঁকা সময়"
        if (lower.contains("free time") || lower.contains("open time") || lower.contains("focus slot") ||
            cleaned.contains("ফাঁকা সময়") || cleaned.contains("ফ্রি সময়")
        ) {
            val suggestedSlot = calendarRepository.findSuggestedFocusSlot(userId, System.currentTimeMillis(), taskBusyIntervals)
            val startMillis = suggestedSlot?.startEpochMillis ?: run {
                val cal = Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, 1)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis
            }
            val durationMinutes = suggestedSlot?.durationMinutes ?: 45
            val endMillis = suggestedSlot?.endEpochMillis ?: (startMillis + (durationMinutes * 60 * 1000L))
            val timeRangeText = "${formatTime(java.util.Date(startMillis))} – ${formatTime(java.util.Date(endMillis))}"
            val reason = suggestedSlot?.reason ?: "$durationMinutes minutes free in your schedule"

            return PreFilterResult.Handled(
                responseText = "You have $durationMinutes minutes of free focus time from $timeRangeText ($reason).",
                actionType = ChatActionType.FOCUS_TIME_PROPOSAL,
                focusStartMillis = startMillis,
                focusEndMillis = endMillis,
                focusDurationMinutes = durationMinutes,
                quickSuggestions = listOf("Block ${durationMinutes}m focus", "Plan my day", "Show today's tasks")
            )
        }

        // 7. English: "Block focus time" / "Block 45m focus" / "Schedule focus" / "ফোকাস টাইম যোগ করো"
        if (lower.contains("block focus") || lower.contains("block 45m") || lower.contains("schedule focus") || cleaned.contains("ফোকাস")) {
            val suggestedSlot = calendarRepository.findSuggestedFocusSlot(userId, System.currentTimeMillis(), taskBusyIntervals)
            val startMillis = suggestedSlot?.startEpochMillis ?: run {
                val cal = Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, 1)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis
            }
            val durationMinutes = suggestedSlot?.durationMinutes ?: 45
            val endMillis = suggestedSlot?.endEpochMillis ?: (startMillis + (durationMinutes * 60 * 1000L))
            val timeRangeText = "${formatTime(java.util.Date(startMillis))} – ${formatTime(java.util.Date(endMillis))}"

            val event = calendarRepository.blockFocusTime(userId, startMillis, endMillis, "Focus Time (Deep Work)")
            return PreFilterResult.Handled(
                responseText = "Done! Reserved $durationMinutes minutes of Deep Work on your calendar ($timeRangeText). Let's stay in the zone! 🎯",
                createdEvent = event,
                quickSuggestions = listOf("Plan my day", "Show today's tasks", "What do you remember?")
            )
        }

        // 8. English: "Plan my day" / "দিনের প্ল্যান"
        if (lower == "plan my day" || lower.contains("plan my day") || cleaned.contains("প্ল্যান")) {
            val pending = todayTasks.filter { !it.isCompleted }
            val planLead = if (pending.isEmpty()) {
                "You have no tasks scheduled yet today"
            } else {
                "Start with '${pending.first().title}'"
            }
            val meetingText = if (events.isNotEmpty()) {
                val firstEvent = events.first()
                "meeting '${firstEvent.title}' at ${formatTime(firstEvent.startEpochMillis)}"
            } else {
                "no conflicting meetings"
            }

            val suggestedSlot = calendarRepository.findSuggestedFocusSlot(userId, System.currentTimeMillis(), taskBusyIntervals)
            val startFocus = suggestedSlot?.startEpochMillis ?: run {
                val cal = Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, 1)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis
            }
            val durationMinutes = suggestedSlot?.durationMinutes ?: 45
            val endFocus = suggestedSlot?.endEpochMillis ?: (startFocus + (durationMinutes * 60 * 1000L))
            val focusRangeText = "${formatTime(java.util.Date(startFocus))} – ${formatTime(java.util.Date(endFocus))}"

            return PreFilterResult.Handled(
                responseText = "Here is your plan today: $planLead. You have $meetingText, and an open $durationMinutes-minute focus window ($focusRangeText).",
                actionType = ChatActionType.FOCUS_TIME_PROPOSAL,
                focusStartMillis = startFocus,
                focusEndMillis = endFocus,
                focusDurationMinutes = durationMinutes,
                quickSuggestions = listOf("Block ${durationMinutes}m focus", "Show today's tasks", "Clear completed tasks")
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
                    "$check ${it.title} (${formatTime(it.dueDateEpochMillis)})"
                }
            }
            return PreFilterResult.Handled(
                responseText = "You have $total tasks today ($completed completed):\n$taskList",
                quickSuggestions = listOf("Plan my day", "Find free time", "Clear completed tasks")
            )
        }

        // 10. Memory command: "Remember that [fact]" / "Note that [fact]" / "মনে রাখো [fact]"
        // Explicitly guard against actionable tasks like "remember to buy milk" or "মনে করিয়ে দিও"
        val isActionableTask = lower.startsWith("remember to ") || lower.contains("মনে করিয়ে")
        if (!isActionableTask) {
            val rememberPattern = Pattern.compile("^(?:remember that|note that|keep in mind that|মনে রাখো|মনে রেখো)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
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
            "order ", "fix ", "check ", "meet ",
            "go ", "get ", "pick ", "book ", "visit ", "attend ", "submit ", "prepare "
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

            val isTomorrow = mentionsTomorrow(cleaned, lower)
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

            // Tasks stay in the Tasks list only — no calendar event is written
            // for a task, even when it has an explicit time.
            val formatted = formatTime(cal.timeInMillis)
            val isNowTomorrow = cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            val dayLabel = if (isNowTomorrow) "tomorrow at" else "today at"
            return PreFilterResult.Handled(
                responseText = "Done! Added '$cleanTitle' for $dayLabel $formatted with a reminder set. 🔔",
                createdTask = task,
                actionType = ChatActionType.TASK_PREVIEW,
                quickSuggestions = listOf("Show today's tasks", "Plan my day", "Find free time")
            )
        }

        // 15. Intelligent Fallback for Specific Scheduled Events (e.g. "Doctor appointment 5pm", "Gym session tomorrow at 6am")
        // Strictly require valid time regex and actionable nouns so casual phrases like "I am busy" or names like "Sam" are not hijacked!
        val isEventCancellation = lower.contains("cancel") || lower.contains("delete") ||
                lower.contains("remove") || cleaned.contains("বাতিল") || cleaned.contains("মুছে")
        val hasTimePattern = Pattern.compile("(?i)\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b").matcher(cleanedNorm).find()
        val hasActionContext = lower.contains("appointment") || lower.contains("session") ||
                lower.contains("doctor") || lower.contains("dentist") || lower.contains("workout") ||
                lower.contains("gym") || lower.contains("flight") || lower.contains("pickup") ||
                lower.contains("class") || lower.contains("practice")

        if (!isEventCancellation && hasTimePattern && hasActionContext) {
            val cal = Calendar.getInstance()
            val isTomorrow = lower.contains("tomorrow")
            if (isTomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)

            val timeMatcher = Pattern.compile("(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))", Pattern.CASE_INSENSITIVE).matcher(cleanedNorm)
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
                val conflict = calendarRepository.checkConflict(userId, cal.timeInMillis, cal.timeInMillis + (45 * 60 * 1000L))
                val conflictNote = if (conflict.hasConflict) {
                    " ⚠ Heads up: this overlaps with '${conflict.conflictingEvent?.title}'."
                } else ""
                // Scheduled appointments/sessions are events — Calendar only,
                // no shadow task (keeps Tasks and Calendar separate).
                val event = calendarRepository.addEvent(
                    userId = userId,
                    title = fallbackTitle,
                    startEpochMillis = cal.timeInMillis,
                    endEpochMillis = cal.timeInMillis + (45 * 60 * 1000L),
                    locationOrLink = "Personal",
                    eventType = "EVENT"
                )
                val formatted = formatTime(cal.timeInMillis)
                val isNowTomorrow = cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                val dayLabel = if (isNowTomorrow) "tomorrow at" else "today at"
                return PreFilterResult.Handled(
                    responseText = "Got it! I've scheduled '$fallbackTitle' for $dayLabel $formatted on your calendar. 📅$conflictNote",
                    createdEvent = event,
                    actionType = ChatActionType.EVENT_CREATED,
                    quickSuggestions = listOf("Show schedule", "Plan my day", "Find free time")
                )
            }
        }

        // 16. Catch-all: a short, non-question statement that matched nothing
        // above is most likely a to-do the user wants captured (e.g. "Go for
        // petrol"). Create it as a task and say exactly what was created —
        // never let a create attempt die silently in the chat fallback.
        val isBengali = cleaned.any { it in '\u0980'..'\u09FF' }
        val wordCount = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val startsWithQuestionWord = listOf(
            "what", "when", "where", "why", "how", "who", "which", "whose",
            "is ", "are ", "was ", "were ", "can ", "could ", "should ", "would ",
            "do ", "does ", "did ", "tell me", "explain", "describe"
        ).any { lower.startsWith(it) }
        val isSmallTalk = cleaned.length <= 24 && listOf(
            "hi", "hello", "hey", "thanks", "thank you", "ok", "okay", "bye",
            "good morning", "good afternoon", "good evening", "good night"
        ).any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it!") }
        if (!cleaned.contains("?") && !startsWithQuestionWord && !isSmallTalk &&
            cleaned.length in 3..80 && wordCount in 1..10
        ) {
            val isTomorrow = mentionsTomorrow(cleaned, lower)
            val cal = Calendar.getInstance()
            if (isTomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)
            val timeMatcher = Pattern.compile("(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))", Pattern.CASE_INSENSITIVE).matcher(cleanedNorm)
            if (timeMatcher.find()) {
                parseTimeIntoCalendar(timeMatcher.group(1) ?: "9:00 AM", cal)
            } else {
                cal.add(Calendar.HOUR_OF_DAY, 2)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            var catchTitle = cleaned
                .replace(Regex("(?i)\\b(tomorrow|today)\\b"), "")
                .replace(Regex("(?i)at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?"), "")
                .replace(Regex("(?i)\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)"), "")
                .replace(Regex("^[,:\\s-]+"), "")
                .replace(Regex("[,:\\s-]+$"), "")
                .trim()
            if (catchTitle.isBlank()) catchTitle = cleaned
            catchTitle = catchTitle.replaceFirstChar { it.uppercase() }
            val task = taskRepository.createTask(
                userId = userId,
                title = catchTitle,
                dueEpochMillis = cal.timeInMillis,
                priority = Priority.MEDIUM,
                category = TaskCategory.PERSONAL,
                reminderSet = true
            )
            val formatted = formatTime(cal.timeInMillis)
            val isNowTomorrow = cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            val responseText = if (isBengali) {
                val dayLabel = if (isNowTomorrow) "আগামীকাল" else "আজ"
                "টাস্ক তৈরি হয়েছে: \"$catchTitle\" — $dayLabel $formatted সময়ে মনে করিয়ে দেব। 🔔"
            } else {
                val dayLabel = if (isNowTomorrow) "tomorrow at" else "today at"
                "Task created: '$catchTitle' — I'll remind you $dayLabel $formatted. 🔔"
            }
            return PreFilterResult.Handled(
                responseText = responseText,
                createdTask = task,
                actionType = ChatActionType.TASK_PREVIEW,
                quickSuggestions = listOf("Show today's tasks", "Plan my day", "Find free time")
            )
        }

        return PreFilterResult.Unhandled
    }

    private fun normalizeBengaliDigits(text: String): String {
        val bn = "০১২৩৪৫৬৭৮৯"
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val idx = bn.indexOf(ch)
            sb.append(if (idx >= 0) ('0' + idx) else ch)
        }
        return sb.toString()
    }

    private fun mentionsTomorrow(cleaned: String, lower: String): Boolean {
        if (lower.contains("tomorrow") || cleaned.contains("আগামীকাল")) return true
        // Standalone কাল / কালকে only — a bare contains("কাল") also matches
        // সকাল (morning) and বিকাল (afternoon) and would shift tasks a day.
        return Regex("(^|\\s)কাল(কে)?(\\s|$|[,.!?])").containsMatchIn(cleaned)
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
        val lower = normalizeBengaliDigits(timeStr).lowercase().trim()
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
        cal.set(Calendar.MILLISECOND, 0)

        // Prevent scheduling reminders in the past: roll over to tomorrow
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }
}
