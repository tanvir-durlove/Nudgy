package com.nextgentool.nudgy.ai

import android.content.res.AssetManager
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.screens.ChatActionType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

/**
 * Pre-filter for chat messages: handles structured intents locally
 * (tasks, calendar events, reminders, focus time, quick queries) before
 * the message ever reaches Gemini. Parsing is FULLY INDEPENDENT — no
 * Gemini, no network, no cap consumption.
 *
 * Rebuilt 2026-11-11 (chat-intent-bank rebuild, ships as 1.0.4) around
 * ONE entry point, [parseIntent], and ONE set of helpers:
 *
 * 1. ONE parseIntent() — every creation/message is routed through it.
 *    A local TF-IDF matcher over a curated sentence bank
 *    (assets/chat_intent_bank.json, 6275 sentences) decides INTENT ONLY;
 *    deterministic functions resolve time and title on ONE path; a
 *    single reply builder writes every creation reply.
 * 2. NEVER-GUESS invariant — a bare hour with no AM/PM/daypart marker
 *    creates NOTHING. The user is asked (quick-reply chips) and the
 *    pending intent waits in memory until they answer. The old
 *    silent-AM rule (resolveBareHour) is DELETED — it was the bug class
 *    behind "কালকে ৩ টায়" becoming tomorrow 3:00 AM.
 *    A bare English hour ("at 5", trailing "5") is a time token too,
 *    so it asks the same way; and a SCHEDULE with no time token at
 *    all asks for the time instead of silently inventing 14:00 (the
 *    4 PM bug — 14:00 +2h when past).
 *    A past resolved time moves to the NEXT DAY for every kind — never
 *    +2h (that was the 4 PM / 7 PM bug shape: a past 17:00 became
 *    19:00 the same day). And a kind the matcher merely guessed is
 *    never frozen: after the hour is answered, the app asks
 *    task-vs-schedule ("টাস্ক নাকি শিডিউল?" / "Task or schedule?")
 *    once before creating anything.
 * 3. ONE reply builder — the EVENT time is stated first, the reminder
 *    is labeled separately. The old code stated the reminder time as if
 *    it were the event time.
 * 4. ONE title cleanup — Bengali date words (কালকে/কাল/আজকে/পরশু),
 *    dayparts, time tokens and AM/PM markers are stripped from BOTH
 *    task and meeting titles.
 *
 * Guards that run BEFORE the matcher (negative rejection lives here,
 * not in the threshold — the matcher cannot separate near-miss commands
 * by score alone; LOO report: negatives reach 0.794):
 * - small-talk messages and question-word / "?" messages never reach
 *   the matcher (they are not creations; same lists as the catch-all).
 * - explicit schedule words (এপয়েন্টমেন্ট/অ্যাপয়েন্টমেন্ট/appointment
 *   + শিডিউল করো) route to SCHEDULE_CREATE.
 * - the call-task trigger ("কল" + করতে/করব/করার/করো/করুন/time,
 *   or English "call" + a callee/time) routes to TASK before the
 *   matcher — the bank is skewed toward call-BOOKING
 *   (SCHEDULE_CREATE) for "কল করো" / "call ..." phrasings, and a
 *   call to a person is a task, not a calendar event, in both
 *   languages.
 * - the 30-min reminder, free-time, focus, plan-my-day, memory,
 *   clear-completed and complete-task branches run verbatim before
 *   parseIntent, as do the today query branches.
 *
 * Known limitation (deliberate, do not chase): negation is not handled
 * ("মনে করিয়ে দিও না, তবে টাস্ক বানাও") — a TF-IDF matcher cannot do
 * negation. Behaviour is left as-is.
 *
 * Sprint 3-2 (Q2 = (a)) — draft-then-confirm: NO creation path in
 * this class saves directly anymore. [buildDraftFromParsed] and the
 * step-4 / step-5 / 30-minute-reminder / focus branches return
 * [PreFilterResult.PendingDraft]; the user confirms on the preview
 * card and only [confirmDraft] writes to the DB (createTask /
 * addEvent / blockFocusTime). The 6 original tests were updated to
 * the new flow for exactly this reason — a test asserting immediate
 * creation would now be asserting the old bug.
 */
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

    /**
     * Sprint 3-2 (Q2 = (a)) — draft-then-confirm: a creation parsed
     * but NOT saved yet. The chat shows the preview card with
     * [previewText]; NOTHING is created until the user confirms,
     * which calls [ClientIntentPreFilter.confirmDraft]. Discard is
     * simply never calling confirm — the prefilter holds no draft
     * state of its own (the ViewModel owns the pending draft).
     */
    data class PendingDraft(
        val draft: ChatDraft,
        val previewText: String,
        val quickSuggestions: List<String> = emptyList()
    ) : PreFilterResult()
}

enum class ParsedKind { TASK, SCHEDULE, REMINDER, QUERY_SCHEDULE, QUERY_TASKS, UNKNOWN }

data class ParsedIntent(
    val kind: ParsedKind,
    val title: String,
    val dayCal: Calendar?,
    val timeToken: String?,
    val isBareHour: Boolean,
    val confidence: Double,
    val isBengali: Boolean,
    val priority: Priority,
    val category: TaskCategory?,
    val reminderAtDue: Boolean,
    val scheduleLocation: String?,
    val scheduleType: String,
    /** True when the kind came from an explicit shortcut (call-task,
     *  schedule-word, reminder-word) — the kind is certain. False
     *  when the bank matcher merely guessed it: the hour answer then
     *  asks task-vs-schedule first instead of creating. */
    val kindCertain: Boolean = true
)

data class PendingClarification(
    val kind: ParsedKind,
    val title: String,
    val dayCal: Calendar?,
    val timeToken: String?,
    val isBengali: Boolean,
    val priority: Priority,
    val category: TaskCategory?,
    val reminderAtDue: Boolean,
    val scheduleLocation: String?,
    val scheduleType: String,
    /** True when the kind is certain (explicit shortcut, or a by-design
     *  fallback such as the step-4 event fallback / step-5 catch-all
     *  task capture) — no kind question is asked. False when the bank
     *  matcher merely guessed it. */
    val kindCertain: Boolean = true,
    /** Hour (0-23) resolved by a clarification answer while the kind
     *  question is pending; null while waiting for the time answer. */
    val resolvedHour: Int? = null
)

class ClientIntentPreFilter(
    private val taskRepository: TaskRepository,
    private val calendarRepository: CalendarRepository,
    private val memoryRepository: MemoryRepository,
    assets: AssetManager? = null
) {
    private val matcher: IntentBankMatcher? = try {
        if (assets != null) IntentBankMatcher(assets) else null
    } catch (_: Exception) {
        null
    }

    @Volatile
    private var pendingClarification: PendingClarification? = null

    /**
     * Sprint 3-4 — the brain dump processes several pieces in a row
     * through [tryHandle]. A piece that asks a question (bare hour,
     * schedule with no time) stores a pending clarification; the NEXT
     * piece must not be eaten as its answer. The dump loop reads this
     * after each piece, records the question, and clears it with
     * [clearPendingClarification] before the next piece. The single
     * -message send path never uses these: there, the clarification
     * stays stored until the user's next message answers it.
     */
    fun hasPendingClarification(): Boolean = pendingClarification != null

    fun clearPendingClarification() {
        pendingClarification = null
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(millis))

    private fun formatTime(date: java.util.Date): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)

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

    // ==================== Deterministic time/title helpers ====================
    // ONE path for all branches. No branch owns its own time parsing,
    // title cleaning, or reply text anymore.

    private fun normalizeDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in '০'..'৯') sb.append('0' + (ch - '০')) else sb.append(ch)
        }
        return sb.toString()
    }

    private fun isBengali(text: String): Boolean =
        text.any { it in 'অ'..'হ' || it in 'া'..'ৌ' || it == 'ং' || it == 'ঃ' || it == 'ঁ' }

    private val hourWordToNumber = mapOf(
        "এক" to 1, "দুই" to 2, "তিন" to 3, "চার" to 4, "পাঁচ" to 5,
        "ছয়" to 6, "সাত" to 7, "আট" to 8, "নয়" to 9, "দশ" to 10,
        "এগারো" to 11, "বারো" to 12
    )

    // Bengali time-of-day words and the hour (24h) they mean when no
    // explicit hour is given — the OLD rule, preserved: সকালে 9 AM,
    // দুপুরে 12 PM, বিকেলে/বিকালে 5 PM, সন্ধ্যায় 7 PM, রাতে 9 PM.
    private val bengaliDaypartHour = mapOf(
        "সকালে" to 9, "সকাল" to 9, "দুপুরে" to 12, "দুপুর" to 12,
        "বিকালে" to 17, "বিকেলে" to 17, "সন্ধ্যায়" to 19, "সন্ধ্যা" to 19,
        "রাতে" to 21, "রাত" to 21
    )

    /** The daypart word present in the message, if any (ভোর included —
     *  it matters for clarification answers, though a lone ভোর is not a
     *  time token by the old rule). */
    private fun findDaypartWord(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("ভোর") -> "ভোর"
            lower.contains("সকালে") || lower.contains("সকাল") -> "সকাল"
            lower.contains("দুপুরে") || lower.contains("দুপুর") -> "দুপুর"
            lower.contains("বিকালে") || lower.contains("বিকেলে") || lower.contains("বিকেল") -> "বিকেল"
            lower.contains("সন্ধ্যায়") || lower.contains("সন্ধ্যা") -> "সন্ধ্যা"
            lower.contains("রাতে") || lower.contains("রাত") -> "রাত"
            else -> null
        }
    }

    /**
     * ONE unified time-token scan, in priority order (mirrors the old
     * extractTimeToken):
     *  1. am/pm — explicit ("5 PM", "5:30pm").
     *  2. Bengali টা token, suffix য়/ই/তে REQUIRED — a bare "৩টা"
     *     count is NOT a time ("৩টা কাজ"). Minutes allowed ("5:30টায়").
     *  3. 24-hour "15:00" — explicit.
     *  4. Bengali word-hour টা, suffix required ("তিনটায়").
     *  5. Daypart word alone ("বিকেলে" -> 5 PM) — the token IS the
     *     daypart word; parseTimeIntoCalendar resolves its hour.
     *  6. Bare English hour — "at 5", or a trailing bare number in a
     *     SHORT message ("Tasnim 5"). The token is just the digits
     *     ("5"); isBareHourToken then asks AM/PM. Restricted to 1-12.
     * Returns null when the message carries no time token at all.
     */
    private fun extractTimeToken(text: String): String? {
        val normalized = normalizeDigits(text)
        val lower = normalized.lowercase(Locale.ROOT)
        val timeRegex =
            Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)\b""")
        timeRegex.find(lower)?.let { match ->
            val hour = match.groupValues[1]
            val minute = match.groupValues[2].ifEmpty { "00" }
            val ampm = match.groupValues[3].replace(".", "").uppercase()
            return "$hour:$minute $ampm"
        }
        val bengaliTimeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*টা(য়|ই|তে)""")
        bengaliTimeRegex.find(normalized)?.let { match ->
            val hour = match.groupValues[1]
            val minute = match.groupValues[2]
            return if (minute.isNotEmpty()) "$hour:$minute টা" else "$hour টা"
        }
        val militaryRegex = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
        militaryRegex.find(lower)?.let { match ->
            return "${match.groupValues[1]}:${match.groupValues[2]}"
        }
        val bengaliWordRegex = Regex("""(এক|দুই|তিন|চার|পাঁচ|ছয়|সাত|আট|নয়|দশ|এগারো|বারো)\s*টা(য়|ই|তে)""")
        bengaliWordRegex.find(text)?.let { match ->
            val hour = hourWordToNumber[match.groupValues[1]] ?: return@let null
            return "$hour টা"
        }
        findDaypartWord(text)?.let { daypart ->
            if (bengaliDaypartHour.containsKey(daypart)) return daypart
        }
        // 6. Bare English hour. "at 5" is unambiguously a time; a
        // trailing bare number in a short message ("Tasnim 5") is one
        // too — in a long message a trailing number is more likely a
        // quantity than an hour, so that form stays short-only.
        // 1-12 only: 13+ can never be a bare hour.
        Regex("""\bat\s+([1-9]|1[0-2])\b""").find(lower)?.let { match ->
            return match.groupValues[1]
        }
        val words = normalized.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size in 1..5) {
            Regex("""^([1-9]|1[0-2])$""").find(words.last())?.let { match ->
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun parseTimeIntoCalendar(
        calendar: Calendar,
        timeToken: String,
        isBengali: Boolean,
        daypart: String? = null
    ) {
        // Daypart-only token: the token itself is the daypart word.
        val tokenDaypartHour = bengaliDaypartHour[normalizeDigits(timeToken).trim()]
        if (tokenDaypartHour != null) {
            calendar.set(Calendar.HOUR_OF_DAY, tokenDaypartHour)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return
        }
        val normalized = normalizeDigits(timeToken)
        val upper = normalized.uppercase()
        val hourMinuteRegex = Regex("""(\d{1,2}):(\d{2})\s*(AM|PM)?""")
        val hmMatch = hourMinuteRegex.find(upper)
        if (hmMatch != null) {
            var hour = hmMatch.groupValues[1].toIntOrNull() ?: 0
            val minute = hmMatch.groupValues[2].toIntOrNull() ?: 0
            val ampm = hmMatch.groupValues[3]
            if (ampm == "PM" && hour < 12) hour += 12
            if (ampm == "AM" && hour == 12) hour = 0
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return
        }
        if (isBengali) {
            // Bengali টা token: a daypart word in the message moves the
            // hour to the right half of the day ("৫টায় বিকেলে" -> 5 PM).
            // am/pm wins over a daypart when both are present (handled
            // above); a 24h token never reaches this branch.
            val hour = Regex("""(\d{1,2})\s*টা""").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            if (hour != null) {
                val resolved = if (daypart != null) applyDaypartToHour(daypart, hour) else hour % 24
                calendar.set(Calendar.HOUR_OF_DAY, resolved)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }
            return
        }
        val hourOnlyRegex = Regex("""\b(\d{1,2})\s*(AM|PM)\b""")
        val hourMatch = hourOnlyRegex.find(upper)
        if (hourMatch != null) {
            var hour = hourMatch.groupValues[1].toIntOrNull() ?: 0
            val ampm = hourMatch.groupValues[2]
            if (ampm == "PM" && hour < 12) hour += 12
            if (ampm == "AM" && hour == 12) hour = 0
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }
    }

    /** ONE title cleanup for task and meeting titles. Strips English and
     *  Bengali date words, dayparts, time tokens and AM/PM markers, so a
     *  date word never survives into a stored title. */
    private fun cleanTitle(text: String, kind: ParsedKind): String {
        var cleaned = normalizeDigits(text)
        val tokensToRemove = listOf(
            "আগামীকাল", "আগামিকাল", "কালকে", "আজকে", "পরশু", "কাল", "আজ",
            "tomorrow", "today", "yesterday",
            "সকালে", "দুপুরে", "বিকালে", "বিকেলে", "সন্ধ্যায়", "রাতে",
            "সকাল", "দুপুর", "বিকেল", "সন্ধ্যা", "রাত", "ভোর",
            "morning", "afternoon", "evening", "night"
        )
        for (token in tokensToRemove) {
            // No \b here: on the JVM (Robolectric) \b is ASCII-only, so
            // \bTOKEN\b never matches a Bengali word and the date word
            // survives into the title (S1-1 CI failure, 2026-11-16:
            // "Bengali date word is stripped from the title"). The
            // \p{L}/\p{N} lookarounds are the same "not next to a letter
            // or digit" boundary, Unicode-aware on BOTH Android and JVM.
            // Do NOT re-add the (?U) flag — it was the suspect behind the
            // 2026-11-11 chat failure.
            cleaned = cleaned.replace(Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(token)}(?![\\p{L}\\p{N}])"), " ")
        }
        cleaned = cleaned.replace(Regex("""\b\d{1,2}(:\d{2})?\s*(am|pm|a\.m\.|p\.m\.)\b""", RegexOption.IGNORE_CASE), " ")
        // Bare English hour: "at 5" / "at 5:30" is a time token, never
        // part of the title. A bare "at" left behind by the am/pm strip
        // above ("call tasnim at 5pm" -> "call tasnim at") is stripped
        // too. A trailing bare number in a short title is the hour
        // ("Tasnim 5" -> "Tasnim") — same short-message bound as the
        // extractor, so quantities in longer titles survive.
        cleaned = cleaned.replace(Regex("""\bat\s+\d{1,2}(?::\d{2})?\b""", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("""\s+at\s*$""", RegexOption.IGNORE_CASE), " ")
        val titleWords = cleaned.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (titleWords.size in 1..5) {
            cleaned = cleaned.replace(Regex("""\s+\d{1,2}\s*$"""), " ")
        }
        cleaned = cleaned.replace(Regex("""\d{1,2}\s*টা(য়|ই|তে)"""), " ")
        cleaned = cleaned.replace(
            Regex("""(এক|দুই|তিন|চার|পাঁচ|ছয়|সাত|আট|নয়|দশ|এগারো|বারো)\s*টা(য়|ই|তে)"""), " "
        )
        cleaned = cleaned.replace(Regex("""\b\d{1,2}:\d{2}\b"""), " ")
        if (kind == ParsedKind.REMINDER) {
            cleaned = cleaned.replace("কথা", " ")
        }
        val stripWords = when (kind) {
            ParsedKind.SCHEDULE -> listOf(
                "শিডিউল করো", "schedule", "শিডিউল", "বুক করো", "বুক", "book",
                "এপয়েন্টমেন্ট", "অ্যাপয়েন্টমেন্ট", "appointment", "মিটিং", "meeting"
            )
            ParsedKind.TASK -> listOf(
                "টাস্ক", "task", "করতে হবে", "করতে", "করো", "করব", "করার",
                "বানাও", "অ্যাড করো", "add", "তৈরি করো", "সেট করো", "রাখো"
            )
            ParsedKind.REMINDER -> listOf(
                "মনে করিয়ে দিও", "মনে করিয়ে দিবে", "মনে করিয়ে", "রিমাইন্ডার",
                "remind me to", "remind me", "reminder", "don't forget to",
                "don't forget", "remember to", "remember", "মনে করিয়ে দাও"
            )
            else -> emptyList()
        }
        for (word in stripWords) {
            cleaned = cleaned.replace(word, " ", ignoreCase = true)
        }
        cleaned = cleaned.replace("এর", " ")
        return cleaned.trim().replace(Regex("\\s+"), " ")
    }

    private fun extractRelativeDayInfo(text: String): Int? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("আগামীকাল") || lower.contains("আগামিকাল") ||
                lower.contains("কালকে") || lower.contains("tomorrow") ||
                // Standalone কাল / কালকে only — a bare contains("কাল") also
                // matches সকাল (morning) and বিকাল (afternoon).
                Regex("(^|\\s)কাল(কে)?(\\s|$|[,.!?])").containsMatchIn(text) -> 1
            lower.contains("পরশু") || lower.contains("day after tomorrow") -> 2
            lower.contains("আজকে") || lower.contains("আজ") ||
                lower.contains("today") -> 0
            else -> null
        }
    }

    private fun formatBengaliHourWord(hour24: Int): String {
        val hour12 = when (hour24 % 12) {
            0 -> 12
            else -> hour24 % 12
        }
        val hourWord = when (hour12) {
            1 -> "১টা"; 2 -> "২টা"; 3 -> "৩টা"; 4 -> "৪টা"; 5 -> "৫টা"; 6 -> "৬টা"
            7 -> "৭টা"; 8 -> "৮টা"; 9 -> "৯টা"; 10 -> "১০টা"; 11 -> "১১টা"
            else -> "১২টা"
        }
        return hourWord
    }

    private fun getDaypartLabel(hour24: Int): String = when (hour24) {
        in 0..4 -> "ভোর"
        in 5..11 -> "সকাল"
        12 -> "দুপুর"
        in 13..16 -> "বিকেল"
        in 17..18 -> "সন্ধ্যা"
        else -> "রাত"
    }

    private fun hourOfDay(text: String): Int? {
        val normalized = normalizeDigits(text)
        val upper = normalized.uppercase()
        val hourMinuteRegex = Regex("""(\d{1,2}):(\d{2})\s*(AM|PM)?""")
        val hmMatch = hourMinuteRegex.find(upper)
        if (hmMatch != null) {
            var hour = hmMatch.groupValues[1].toIntOrNull() ?: 0
            val ampm = hmMatch.groupValues[3]
            if (ampm == "PM" && hour < 12) hour += 12
            if (ampm == "AM" && hour == 12) hour = 0
            return hour
        }
        val bengaliRegex = Regex("""(\d{1,2})\s*টা""")
        val bengaliMatch = bengaliRegex.find(normalized)
        if (bengaliMatch != null) return bengaliMatch.groupValues[1].toIntOrNull()
        val wordRegex =
            Regex("""(এক|দুই|তিন|চার|পাঁচ|ছয়|সাত|আট|নয়|দশ|এগারো|বারো)\s*টা""")
        val wordMatch = wordRegex.find(text)
        if (wordMatch != null) return hourWordToNumber[wordMatch.groupValues[1]]
        val hourOnlyRegex = Regex("""\b(\d{1,2})\s*(AM|PM)\b""")
        val hourMatch = hourOnlyRegex.find(upper)
        if (hourMatch != null) {
            var hour = hourMatch.groupValues[1].toIntOrNull() ?: 0
            val ampm = hourMatch.groupValues[2]
            if (ampm == "PM" && hour < 12) hour += 12
            if (ampm == "AM" && hour == 12) hour = 0
            return hour
        }
        // A bare-hour token is just digits ("5") — the hour itself.
        // Needed so the clarification chips show the right hour.
        val bareHour = Regex("""^(\d{1,2})$""")
            .find(normalized.trim())?.groupValues?.get(1)?.toIntOrNull()
        if (bareHour != null) return bareHour
        return null
    }

    /** "আজ"/"আগামীকাল"/"পরশু"/weekday label for the EVENT date. */
    private fun dayLabel(eventCal: Calendar, now: Calendar): String {
        val eventDay = (eventCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val today = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val diffDays = ((eventDay.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
        return when (diffDays) {
            0 -> "আজ"
            1 -> "আগামীকাল"
            2 -> "পরশু"
            else -> SimpleDateFormat("EEEE, d MMMM", Locale("bn")).format(eventCal.time)
        }
    }

    /** "today"/"tomorrow"/"day after tomorrow"/weekday label for the
     *  EVENT date — the English mirror of dayLabel. English replies
     *  must never interpolate the Bengali dayLabel: sharing one
     *  Bengali label across both languages was the "আজ ৯টা" leak in
     *  the clarification question ("You said আজ ৯টা — AM or PM?"). */
    private fun dayLabelEn(eventCal: Calendar, now: Calendar): String {
        val eventDay = (eventCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val today = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val diffDays = ((eventDay.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
        return when (diffDays) {
            0 -> "today"
            1 -> "tomorrow"
            2 -> "day after tomorrow"
            else -> SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(eventCal.time)
        }
    }

    /** "আগামীকাল বিকেল ৩টা" — event day + daypart + hour, from the EVENT
     *  calendar. One place, used by every creation reply. */
    private fun formatDayTimeLabel(eventCal: Calendar, now: Calendar): String {
        val day = dayLabel(eventCal, now)
        val hour24 = eventCal.get(Calendar.HOUR_OF_DAY)
        val minute = eventCal.get(Calendar.MINUTE)
        val daypart = getDaypartLabel(hour24)
        val hourWord = formatBengaliHourWord(hour24)
        return if (minute == 0) "$day $daypart $hourWord" else "$day $daypart $hourWord ${minute} মিনিট"
    }

    /** "tomorrow at 3:00 PM" — event day + time, English. */
    private fun formatDayTimeLabelEn(eventCal: Calendar, now: Calendar): String {
        val eventDay = (eventCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val today = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val diffDays = ((eventDay.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
        val day = when (diffDays) {
            0 -> "today"
            1 -> "tomorrow"
            2 -> "day after tomorrow"
            else -> SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(eventCal.time)
        }
        return "$day at ${formatTime(eventCal.timeInMillis)}"
    }

    // ==================== ONE reply builder ====================
    // Event time FIRST, reminder labeled separately. No branch writes its
    // own reply text. Bengali input -> Bengali reply, English -> English.

    private fun buildCreationReply(
        title: String,
        eventCal: Calendar,
        reminderCal: Calendar?,
        reminderAtDue: Boolean,
        kind: ParsedKind,
        now: Calendar,
        isBengali: Boolean,
        conflictingTitle: String? = null
    ): String {
        return when (kind) {
            ParsedKind.SCHEDULE -> buildScheduleReply(title, eventCal, now, isBengali, conflictingTitle)
            ParsedKind.REMINDER -> {
                if (isBengali) {
                    val eventLabel = formatDayTimeLabel(eventCal, now)
                    "ঠিক আছে, $eventLabel \"$title\"-এর কথা মনে করিয়ে দেব।"
                } else {
                    val eventLabel = formatDayTimeLabelEn(eventCal, now)
                    "Done! I'll remind you about \"$title\" $eventLabel. 🔔"
                }
            }
            ParsedKind.TASK -> {
                if (reminderAtDue) {
                    if (isBengali) {
                        val eventLabel = formatDayTimeLabel(eventCal, now)
                        "ঠিক আছে, $eventLabel \"$title\"-এর কথা মনে করিয়ে দেব।"
                    } else {
                        val eventLabel = formatDayTimeLabelEn(eventCal, now)
                        "Done! I'll remind you about \"$title\" $eventLabel. 🔔"
                    }
                } else if (reminderCal != null) {
                    if (isBengali) {
                        val eventLabel = formatDayTimeLabel(eventCal, now)
                        val reminderLabel = formatDayTimeLabel(reminderCal, now)
                        "ঠিক আছে, \"$title\" টাস্ক তৈরি করেছি। $eventLabel নির্ধারিত আছে, আর $reminderLabel মনে করিয়ে দেব।"
                    } else {
                        val eventLabel = formatDayTimeLabelEn(eventCal, now)
                        val reminderLabel = formatDayTimeLabelEn(reminderCal, now)
                        "Done! Created task \"$title\" for $eventLabel. I'll remind you at $reminderLabel. 🔔"
                    }
                } else {
                    if (isBengali) {
                        val eventLabel = formatDayTimeLabel(eventCal, now)
                        "ঠিক আছে, \"$title\" টাস্ক তৈরি করেছি। $eventLabel নির্ধারিত আছে।"
                    } else {
                        val eventLabel = formatDayTimeLabelEn(eventCal, now)
                        "Done! Created task \"$title\" for $eventLabel. 🔔"
                    }
                }
            }
            else -> {
                if (isBengali) {
                    val eventLabel = formatDayTimeLabel(eventCal, now)
                    "ঠিক আছে, \"$title\" তৈরি করেছি। $eventLabel নির্ধারিত আছে।"
                } else {
                    val eventLabel = formatDayTimeLabelEn(eventCal, now)
                    "Done! Created \"$title\" for $eventLabel. 🔔"
                }
            }
        }
    }

    /** Schedule reply: event time first, with the pre-creation conflict
     *  note. The conflict is computed BEFORE the event is created, so
     *  the new event can never report a conflict with itself. */
    private fun buildScheduleReply(
        title: String,
        eventCal: Calendar,
        now: Calendar,
        isBengali: Boolean,
        conflictingTitle: String?
    ): String {
        return if (isBengali) {
            val eventLabel = formatDayTimeLabel(eventCal, now)
            val base = "ঠিক আছে, $eventLabel \"$title\" মিটিং শিডিউল করেছি।"
            if (conflictingTitle != null) {
                "$base সতর্কতা: ওই সময়ে \"$conflictingTitle\" আছে — সময় মিলিয়ে নিও।"
            } else {
                base
            }
        } else {
            val eventLabel = formatDayTimeLabelEn(eventCal, now)
            val base = "Done! Scheduled \"$title\" for $eventLabel on your calendar. 📅"
            if (conflictingTitle != null) {
                "$base ⚠ Heads up: this overlaps with '$conflictingTitle'."
            } else {
                base
            }
        }
    }

    // ==================== parseIntent: ONE entry point ====================
    // Every creation/message routes through here. Order inside:
    //   guards (small talk / questions) -> call-task shortcut ->
    //   explicit schedule words -> explicit reminder words ->
    //   bank matcher -> null (UNKNOWN).

    private val smallTalkPhrases = listOf(
        "hi", "hello", "hey", "good morning", "good afternoon", "good evening",
        "good night", "thanks", "thank you", "bye", "goodbye", "ok", "okay",
        "হাই", "হ্যালো", "ধন্যবাদ", "শুভ সকাল", "শুভ বিকাল", "শুভ সন্ধ্যা",
        "শুভ রাত্রি", "আসসালামু আলাইকুম", "সালাম"
    )

    private val questionStartWords = listOf(
        "what", "when", "where", "why", "how", "who", "which", "whose",
        "is ", "are ", "was ", "were ", "can ", "could ", "should ", "would ",
        "do ", "does ", "did ", "tell me", "explain", "describe",
        "কী", "কি", "কখন", "কোথায়", "কেন", "কেমন", "কে", "কোন"
    )

    private val explicitReminderPhrases = listOf(
        "মনে করিয়ে দিও", "মনে করিয়ে দিবে", "মনে করিয়ে দাও", "মনে করিয়ে",
        "remind me to", "remind me", "don't forget to", "dont forget to",
        "remember to", "remember"
    )

    private fun parseIntent(rawInput: String): ParsedIntent {
        val cleaned = rawInput.trim()
        val lower = cleaned.lowercase(Locale.ROOT)
        val bn = isBengali(cleaned)
        val now = Calendar.getInstance()

        // Day resolution — shared by every kind.
        val dayOffset = extractRelativeDayInfo(cleaned) ?: 0
        val dayCal = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }

        // --- Guard 1: small talk is never a creation. ---
        val normalizedForTalk = lower.trim()
        if (smallTalkPhrases.any { normalizedForTalk == it || normalizedForTalk.startsWith("$it ") }) {
            return ParsedIntent(ParsedKind.UNKNOWN, "", null, null, false, 0.0, bn, Priority.MEDIUM, null, false, null, "meeting")
        }
        // --- Guard 2: questions are never creations. ---
        if (cleaned.contains("?") || cleaned.contains("؟") ||
            questionStartWords.any { lower.startsWith(it) }) {
            return ParsedIntent(ParsedKind.UNKNOWN, "", null, null, false, 0.0, bn, Priority.MEDIUM, null, false, null, "meeting")
        }

        val timeToken = extractTimeToken(cleaned)
        val priority = if (lower.contains("urgent") || lower.contains("important") ||
            lower.contains("high") || cleaned.contains("জরুরি")) Priority.HIGH else Priority.MEDIUM
        val category = when {
            lower.contains("work") || lower.contains("launch") || lower.contains("project") ||
                lower.contains("client") -> TaskCategory.WORK
            lower.contains("study") || lower.contains("class") || lower.contains("book") -> TaskCategory.STUDY
            else -> TaskCategory.PERSONAL
        }

        // --- Shortcut: call task ("কল" / "call" + করতে/করব/করার/করো/করুন/time/callee). ---
        // A call to a person is a TASK, not a calendar event — the bank is
        // skewed toward call-BOOKING (SCHEDULE_CREATE) for "কল করো"
        // phrasings, so the matcher must not see these. Same rule in
        // English: "call" is the verb, so the guard is a word after it
        // (the callee) or a time token. "call off" is excluded — that
        // verb means cancel, not phone someone. The Bengali side has the
        // same callee guard ("কল" + a following word, e.g. "কল তাসনিম"),
        // so a bare "কল <name>" with no verb form and no time is still a
        // task. "কল" alone (no following word) does not fire it.
        val isCallTask = (cleaned.contains("কল") && (
            cleaned.contains("করতে") || cleaned.contains("করব") ||
                cleaned.contains("করার") || cleaned.contains("করো") ||
                cleaned.contains("করুন") || timeToken != null ||
                Regex("""(^|\s)কল\s+\S+""").containsMatchIn(cleaned)
            )) || (
            Regex("""\bcall\b""").containsMatchIn(lower) &&
                !lower.contains("call off") &&
                (timeToken != null || Regex("""\bcall\b\s+\S+""").containsMatchIn(lower))
            )
        if (isCallTask) {
            val title = cleanTitle(cleaned, ParsedKind.TASK)
            return ParsedIntent(
                kind = ParsedKind.TASK, title = title.ifBlank { "কল করো" },
                dayCal = dayCal, timeToken = timeToken,
                isBareHour = isBareHourToken(timeToken, cleaned),
                confidence = 1.0, isBengali = bn, priority = Priority.HIGH,
                category = TaskCategory.WORK, reminderAtDue = true,
                scheduleLocation = null, scheduleType = "MEETING"
            )
        }

        // --- Shortcut: explicit schedule words. ---
        // "এপয়েন্টমেন্ট শিডিউল করো" must never become a task (defect 1).
        val hasScheduleWord = cleaned.contains("এপয়েন্টমেন্ট") ||
            cleaned.contains("অ্যাপয়েন্টমেন্ট") ||
            lower.contains("appointment") ||
            cleaned.contains("শিডিউল করো") ||
            lower.contains("schedule")
        if (hasScheduleWord) {
            val title = cleanTitle(cleaned, ParsedKind.SCHEDULE)
            return ParsedIntent(
                kind = ParsedKind.SCHEDULE, title = title.ifBlank { "মিটিং" },
                dayCal = dayCal, timeToken = timeToken,
                isBareHour = isBareHourToken(timeToken, cleaned),
                confidence = 1.0, isBengali = bn, priority = priority,
                category = category, reminderAtDue = false,
                scheduleLocation = "Google Meet", scheduleType = "MEETING"
            )
        }

        // --- Shortcut: explicit reminder words. ---
        // "মনে করিয়ে দিও" is unambiguous — the matcher confuses
        // TASK_CREATE <-> REMINDER on 800/6275 bank sentences, so the
        // explicit phrase wins over the match.
        if (explicitReminderPhrases.any { lower.contains(it) || cleaned.contains(it) }) {
            val title = cleanTitle(cleaned, ParsedKind.REMINDER)
            return ParsedIntent(
                kind = ParsedKind.REMINDER, title = title.ifBlank { "রিমাইন্ডার" },
                dayCal = dayCal, timeToken = timeToken,
                isBareHour = isBareHourToken(timeToken, cleaned),
                confidence = 1.0, isBengali = bn, priority = priority,
                category = category, reminderAtDue = true,
                scheduleLocation = null, scheduleType = "MEETING"
            )
        }

        // --- Bank matcher: intent only. ---
        val match = matcher?.match(cleaned)
        if (match != null) {
            val kind = when (match.intent) {
                BankIntent.TASK_CREATE -> ParsedKind.TASK
                BankIntent.SCHEDULE_CREATE -> ParsedKind.SCHEDULE
                BankIntent.REMINDER -> ParsedKind.REMINDER
                BankIntent.QUERY_SCHEDULE -> ParsedKind.QUERY_SCHEDULE
                BankIntent.QUERY_TASKS -> ParsedKind.QUERY_TASKS
            }
            // A task phrased as an explicit reminder still reminds AT due.
            val explicitReminder = kind == ParsedKind.TASK &&
                explicitReminderPhrases.any { lower.contains(it) || cleaned.contains(it) }
            val title = cleanTitle(cleaned, kind)
            val fallbackTitle = when (kind) {
                ParsedKind.TASK -> "New Task"
                ParsedKind.SCHEDULE -> "মিটিং"
                ParsedKind.REMINDER -> "রিমাইন্ডার"
                else -> ""
            }
            return ParsedIntent(
                kind = kind, title = title.ifBlank { fallbackTitle },
                dayCal = dayCal, timeToken = timeToken,
                isBareHour = isBareHourToken(timeToken, cleaned),
                confidence = match.score, isBengali = bn, priority = priority,
                category = category,
                reminderAtDue = kind == ParsedKind.REMINDER || explicitReminder,
                scheduleLocation = if (kind == ParsedKind.SCHEDULE) "Google Meet" else null,
                scheduleType = "MEETING",
                // Matcher guess, not an explicit shortcut — the kind is
                // NOT certain; the hour answer asks task-vs-schedule.
                kindCertain = false
            )
        }

        return ParsedIntent(ParsedKind.UNKNOWN, "", null, null, false, 0.0, bn, Priority.MEDIUM, null, false, null, "meeting")
    }

    /**
     * A bare hour has a time token but NO AM/PM/daypart marker anywhere
     * in the message. Such a message creates NOTHING — the caller asks
     * the user instead. (resolveBareHour's silent-AM rule is deleted.)
     */
    private fun isBareHourToken(timeToken: String?, rawInput: String): Boolean {
        if (timeToken == null) return false
        // An hour above 12 is unambiguous 24h ("15:00টায়") — not bare.
        val tokenHour = Regex("""(\d{1,2})""")
            .find(normalizeDigits(timeToken))?.groupValues?.get(1)?.toIntOrNull()
        if (tokenHour != null && tokenHour > 12) return false
        val normalized = normalizeDigits(rawInput)
        val lower = normalized.lowercase(Locale.ROOT)
        val hasAmPm = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)\b""").containsMatchIn(lower)
        // Only dayparts the creation path can actually apply count as
        // resolving markers: Bengali ones (parseTimeIntoCalendar +
        // applyDaypartToHour). An English daypart next to a bare hour
        // ("5 in the evening") cannot be resolved — findDaypartWord
        // only returns Bengali words — so the message stays bare and
        // asks instead of creating at a wrong hour.
        val hasDaypart = bengaliDaypartHour.keys.any { lower.contains(it) } ||
            lower.contains("ভোর")
        // A standalone 24h time ("15:00") is explicit — but NOT one
        // followed by টা ("5:30টায়" is a bare Bengali hour).
        val hasExplicitHour = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b(?! *টা)""").containsMatchIn(lower)
        return !hasAmPm && !hasDaypart && !hasExplicitHour
    }

    /** Resolves the event/due calendar for a parsed creation intent.
     *  Per-kind no-token defaults (preserved branch semantics):
     *  REMINDER -> 3 PM on the day; TASK -> +2h from now (on the day).
     *  The SCHEDULE 2 PM default below is UNREACHABLE by design — the
     *  step-3 dispatch asks for the time first when a SCHEDULE has no
     *  token (the 4 PM bug); it stays only so this function is total.
     *  An explicit past time rolls +1 day for EVERY kind — never +2h
     *  (the +2h rule was the 4 PM / 7 PM bug shape: a past 17:00
     *  became 19:00 the same day). */
    private fun resolveCreationTime(parsed: ParsedIntent, now: Calendar, daypart: String? = null): Calendar {
        val dayCal = parsed.dayCal ?: (now.clone() as Calendar)
        val eventCal = (dayCal.clone() as Calendar)
        val token = parsed.timeToken
        if (token != null) {
            parseTimeIntoCalendar(eventCal, token, parsed.isBengali, daypart)
            if (eventCal.timeInMillis <= now.timeInMillis) {
                // Past time moves to the next day — never +2h (the old
                // 4 PM / 7 PM bug shape). Same rule for every kind.
                when (parsed.kind) {
                    ParsedKind.SCHEDULE, ParsedKind.TASK, ParsedKind.REMINDER ->
                        eventCal.add(Calendar.DAY_OF_YEAR, 1)
                    else -> Unit
                }
            }
            return eventCal
        }
        return when (parsed.kind) {
            ParsedKind.REMINDER -> {
                eventCal.set(Calendar.HOUR_OF_DAY, 15)
                eventCal.set(Calendar.MINUTE, 0)
                eventCal.set(Calendar.SECOND, 0)
                eventCal.set(Calendar.MILLISECOND, 0)
                eventCal
            }
            ParsedKind.SCHEDULE -> {
                eventCal.set(Calendar.HOUR_OF_DAY, 14)
                eventCal.set(Calendar.MINUTE, 0)
                eventCal.set(Calendar.SECOND, 0)
                eventCal.set(Calendar.MILLISECOND, 0)
                if (eventCal.timeInMillis <= now.timeInMillis) {
                    eventCal.add(Calendar.HOUR_OF_DAY, 2)
                }
                eventCal
            }
            else -> { // TASK
                eventCal.timeInMillis = now.timeInMillis
                eventCal.add(Calendar.HOUR_OF_DAY, 2)
                eventCal.set(Calendar.MINUTE, 0)
                eventCal.set(Calendar.SECOND, 0)
                eventCal
            }
        }
    }

    // ==================== Clarification (never-guess) ====================

    // NOTE: the day + hour words are computed PER LANGUAGE below.
    // One shared Bengali day/hour word across both branches was the
    // "আজ ৯টা" leak: an English question interpolated Bengali
    // ("You said আজ ৯টা — AM or PM?"). English gets its own day
    // label (dayLabelEn) and hour number.
    private fun buildClarificationQuestion(parsed: ParsedIntent, now: Calendar): PreFilterResult.Handled {
        val hour = hourOfDay(parsed.timeToken ?: "") ?: 12
        val question = if (parsed.isBengali) {
            val day = dayLabel(parsed.dayCal ?: now, now)
            val hourWord = formatBengaliHourWord(hour)
            "$day $hourWord — সকাল নাকি বিকেল/রাত? সময়টা নিশ্চিত করো, তাহলে \"${
                parsed.title
            }\" তৈরি করব।"
        } else {
            val dayEn = dayLabelEn(parsed.dayCal ?: now, now)
            val hour12 = if (hour % 12 == 0) 12 else hour % 12
            "You said $dayEn at $hour12 — AM or PM? Please confirm so I can create \"${
                parsed.title
            }\"."
        }
        val chips = if (parsed.isBengali) {
            val hourWord = formatBengaliHourWord(hour)
            val amLabel = "${getDaypartLabel(if (hour % 12 == 0) 0 else hour % 12)} $hourWord"
            val pmHour = (hour % 12) + 12
            val pmLabel = "${getDaypartLabel(pmHour)} $hourWord"
            listOf(amLabel, pmLabel)
        } else {
            val hour12 = if (hour % 12 == 0) 12 else hour % 12
            listOf("$hour12 AM", "$hour12 PM")
        }
        return PreFilterResult.Handled(
            responseText = question,
            quickSuggestions = chips
        )
    }

    /** A SCHEDULE with no time token at all: ask for the time instead
     *  of inventing one. The chips are common hours; tapping one
     *  answers through tryAnswerClarification (am/pm, or a
     *  daypart-qualified hour), and any typed time works the same way.
     *  No hour is ever defaulted here. */
    private fun buildNoTimeQuestion(parsed: ParsedIntent, now: Calendar): PreFilterResult.Handled {
        val day = dayLabel(parsed.dayCal ?: now, now)
        val diffDays = ((run {
            val d = (parsed.dayCal ?: now).clone() as Calendar
            d.set(Calendar.HOUR_OF_DAY, 0); d.set(Calendar.MINUTE, 0)
            d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0)
            d.timeInMillis
        } - run {
            val t = now.clone() as Calendar
            t.set(Calendar.HOUR_OF_DAY, 0); t.set(Calendar.MINUTE, 0)
            t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0)
            t.timeInMillis
        }) / 86_400_000L).toInt()
        val dayEn = when (diffDays) {
            0 -> "today"
            1 -> "tomorrow"
            2 -> "day after tomorrow"
            else -> SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH)
                .format((parsed.dayCal ?: now).time)
        }
        val question = if (parsed.isBengali) {
            "\"${parsed.title}\" $day কয়টায়? সময়টা লিখে জানাও — যেমন ৫টায় বিকেলে।"
        } else {
            "What time is \"${parsed.title}\" $dayEn? Reply with a time — for example, 5 PM."
        }
        val chips = if (parsed.isBengali) {
            // টায় forms, not bare টা: "৫টা বিকেলে" parses as daypart-only
            // (the টা regex needs the য়/ই/তে suffix), which would resolve
            // to noon via the pending hour. "৫টায় বিকেলে" parses as hour
            // + daypart and resolves to 17:00.
            listOf("৯টায় সকালে", "১২টায় দুপুরে", "৩টায় বিকেলে", "৫টায় বিকেলে")
        } else {
            listOf("9 AM", "12 PM", "3 PM", "5 PM")
        }
        return PreFilterResult.Handled(
            responseText = question,
            quickSuggestions = chips
        )
    }

    /** Builds the event calendar for a resolved hour on the pending
     *  day. A past time moves to the next day — never +2h (the old
     *  4 PM / 7 PM bug shape). Same rule for every kind. */
    private fun resolvedEventCal(
        pending: PendingClarification,
        resolvedHour: Int,
        now: Calendar
    ): Calendar {
        val eventCal = (pending.dayCal ?: now).clone() as Calendar
        eventCal.set(Calendar.HOUR_OF_DAY, resolvedHour)
        eventCal.set(Calendar.MINUTE, 0)
        eventCal.set(Calendar.SECOND, 0)
        eventCal.set(Calendar.MILLISECOND, 0)
        if (eventCal.timeInMillis <= now.timeInMillis) {
            eventCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return eventCal
    }

    /** The matcher guessed the kind (no verb in the message) and the
     *  hour is now resolved — ask task-vs-schedule instead of creating
     *  with the guessed kind. The label shows the adjusted time (a
     *  past hour already moved to the next day), so the question
     *  states exactly what will be created. */
    private fun buildKindQuestion(
        pending: PendingClarification,
        resolvedHour: Int,
        now: Calendar
    ): PreFilterResult.Handled {
        val eventCal = resolvedEventCal(pending, resolvedHour, now)
        val question = if (pending.isBengali) {
            "\"${pending.title}\" ${formatDayTimeLabel(eventCal, now)} — টাস্ক নাকি শিডিউল?"
        } else {
            "\"${pending.title}\" ${formatDayTimeLabelEn(eventCal, now)} — Task or schedule?"
        }
        val chips = if (pending.isBengali) listOf("টাস্ক", "শিডিউল") else listOf("Task", "Schedule")
        return PreFilterResult.Handled(
            responseText = question,
            quickSuggestions = chips
        )
    }

    /**
     * A follow-up answering a pending clarification. Returns null when
     * the message does not answer it — the caller then clears pending
     * and processes the message normally.
     *
     * Answers, in priority order: an AM/PM token; a time token WITH a
     * daypart marker (the marker may also correct the hour, e.g.
     * "৪টায় বিকেলে" for a pending "৩ টায়"); a bare daypart word
     * ("বিকেল") applied to the pending hour. A bare hour alone
     * ("৩টা") is NOT an answer — it has no marker.
     *
     *  When the pending kind was only a matcher guess, answering the
     *  hour does NOT create — it asks task-vs-schedule once, and the
     *  kind answer (chip or typed) creates with the stored hour.
     */
    private suspend fun tryAnswerClarification(rawInput: String, userId: String): PreFilterResult? {
        val pending = pendingClarification ?: return null
        val cleaned = rawInput.trim()
        val lower = cleaned.lowercase(Locale.ROOT)
        val now = Calendar.getInstance()

        // (A) Awaiting a kind answer: the hour is already resolved.
        // A chip tap ("Task"/"Schedule"/"টাস্ক"/"শিডিউল") or the same
        // words typed out chooses the kind; anything else is not an
        // answer — return null so the caller clears the pending state
        // and processes the message normally.
        if (pending.resolvedHour != null) {
            val chosenKind = when {
                lower == "task" || lower.startsWith("task ") ||
                    cleaned == "টাস্ক" || cleaned.startsWith("টাস্ক ") -> ParsedKind.TASK
                lower == "schedule" || lower.startsWith("schedule ") ||
                    cleaned == "শিডিউল" || cleaned.startsWith("শিডিউল ") -> ParsedKind.SCHEDULE
                else -> null
            } ?: return null
            pendingClarification = null
            val eventCal = resolvedEventCal(pending, pending.resolvedHour, now)
            return buildDraftFromParsed(
                ParsedIntent(
                    kind = chosenKind, title = pending.title,
                    dayCal = pending.dayCal, timeToken = pending.timeToken,
                    isBareHour = false, confidence = 1.0,
                    isBengali = pending.isBengali, priority = pending.priority,
                    category = pending.category, reminderAtDue = pending.reminderAtDue,
                    scheduleLocation = pending.scheduleLocation,
                    scheduleType = pending.scheduleType
                ),
                eventCal, now, userId
            )
        }

        val pendingHour = hourOfDay(pending.timeToken ?: "") ?: 12

        val token = extractTimeToken(cleaned)
        val tokenHour = token?.let { hourOfDay(it) }
        val hasMarker = token != null && !isBareHourToken(token, cleaned)
        val daypart = findDaypartWord(cleaned)
        val ampmMatch = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)\b""").find(lower)
        val ampmHour = ampmMatch?.let {
            var hour = it.groupValues[1].toIntOrNull() ?: 12
            val ampm = it.groupValues[3].replace(".", "").uppercase()
            if (ampm == "PM" && hour < 12) hour += 12
            if (ampm == "AM" && hour == 12) hour = 0
            hour
        }

        val resolvedHour: Int? = when {
            ampmHour != null -> ampmHour
            tokenHour != null && daypart != null -> applyDaypartToHour(daypart, tokenHour)
            tokenHour != null && hasMarker -> tokenHour // 24h form, e.g. "15:00"
            daypart != null -> applyDaypartToHour(daypart, pendingHour)
            else -> null
        }
        if (resolvedHour == null) return null

        // (B) The kind was only a matcher guess (no verb in the
        // message): do NOT create with the guessed kind. Store the
        // resolved hour and ask task-vs-schedule once.
        if (!pending.kindCertain) {
            pendingClarification = pending.copy(resolvedHour = resolvedHour)
            return buildKindQuestion(pending, resolvedHour, now)
        }

        pendingClarification = null
        val eventCal = resolvedEventCal(pending, resolvedHour, now)
        return buildDraftFromParsed(
            ParsedIntent(
                kind = pending.kind, title = pending.title,
                dayCal = pending.dayCal, timeToken = pending.timeToken,
                isBareHour = false, confidence = 1.0,
                isBengali = pending.isBengali, priority = pending.priority,
                category = pending.category, reminderAtDue = pending.reminderAtDue,
                scheduleLocation = pending.scheduleLocation,
                scheduleType = pending.scheduleType
            ),
            eventCal, now, userId
        )
    }

    /** Applies a Bengali daypart to a 1-12 hour: ভোর/সকাল -> AM,
     *  দুপুর/বিকেল/সন্ধ্যা -> PM (12 stays 12), রাত -> AM for 1-4,
     *  PM for 5-11, midnight for 12. */
    private fun applyDaypartToHour(daypart: String, hour12: Int): Int {
        val h = if (hour12 % 12 == 0) 12 else hour12 % 12
        return when (daypart) {
            "ভোর", "সকাল" -> h % 12
            "দুপুর", "বিকেল", "সন্ধ্যা" -> if (h == 12) 12 else h + 12
            "রাত" -> when {
                h == 12 -> 0
                h <= 4 -> h
                else -> h + 12
            }
            else -> h
        }
    }

    /** S3-2 (Q2 = (a)) — builds the DRAFT for a fully-resolved parsed
     *  intent and returns the preview text. One place — clarification
     *  answers and fresh parses both land here. NOTHING is saved here:
     *  the draft is created only when the user confirms
     *  ([confirmDraft]). The conflict check is read-only, so it is
     *  safe to run at draft time; [confirmDraft] re-checks it fresh
     *  before saving. */
    private suspend fun buildDraftFromParsed(
        parsed: ParsedIntent,
        eventCal: Calendar,
        now: Calendar,
        userId: String
    ): PreFilterResult {
        return when (parsed.kind) {
            ParsedKind.SCHEDULE -> {
                val startMillis = eventCal.timeInMillis
                val endMillis = startMillis + 45 * 60 * 1000L
                // Conflict checked BEFORE creation, so the new event can
                // never report a conflict with itself. Read-only here;
                // confirmDraft re-checks fresh.
                val conflict = calendarRepository.checkConflict(userId, startMillis, endMillis)
                val conflictingTitle = if (conflict.hasConflict) conflict.conflictingEvent?.title else null
                val draft = ChatDraft(
                    kind = DraftKind.SCHEDULE,
                    title = parsed.title,
                    startEpochMillis = startMillis,
                    endEpochMillis = endMillis,
                    priority = parsed.priority,
                    category = parsed.category,
                    scheduleLocation = parsed.scheduleLocation ?: "Google Meet",
                    scheduleType = parsed.scheduleType,
                    isBengali = parsed.isBengali,
                    conflictingTitle = conflictingTitle
                )
                PreFilterResult.PendingDraft(
                    draft = draft,
                    previewText = buildPreviewText(draft, now),
                    quickSuggestions = listOf("Show schedule", "Plan my day", "Find free time")
                )
            }
            ParsedKind.TASK, ParsedKind.REMINDER -> {
                val reminderMillis = if (parsed.reminderAtDue) {
                    eventCal.timeInMillis
                } else {
                    eventCal.timeInMillis - 2 * 60 * 60 * 1000L
                }
                val draft = ChatDraft(
                    kind = if (parsed.kind == ParsedKind.REMINDER) DraftKind.REMINDER else DraftKind.TASK,
                    title = parsed.title,
                    startEpochMillis = eventCal.timeInMillis,
                    priority = parsed.priority,
                    category = parsed.category ?: TaskCategory.PERSONAL,
                    reminderAtDue = parsed.reminderAtDue,
                    reminderEpochMillis = reminderMillis,
                    isBengali = parsed.isBengali
                )
                PreFilterResult.PendingDraft(
                    draft = draft,
                    previewText = buildPreviewText(draft, now),
                    quickSuggestions = listOf("Show today's tasks", "Plan my day", "Find free time")
                )
            }
            else -> PreFilterResult.Unhandled
        }
    }

    /** S3-2 — the preview question for a draft: what WILL be created,
     *  as a question, never past tense. The card shows the details
     *  (title / time / priority); this text is the ask. Mirrors
     *  [buildCreationReply]'s branches (event time first, reminder
     *  labelled separately) so preview and receipt never disagree. */
    private fun buildPreviewText(draft: ChatDraft, now: Calendar): String {
        val eventCal = Calendar.getInstance().apply { timeInMillis = draft.startEpochMillis }
        return when (draft.kind) {
            DraftKind.SCHEDULE -> {
                val conflictNote = draft.conflictingTitle?.let {
                    if (draft.isBengali) " সতর্কতা: ওই সময়ে \"$it\" আছে — সময় মিলিয়ে নিও।"
                    else " ⚠ Heads up: this overlaps with '$it'."
                } ?: ""
                if (draft.isBengali) {
                    "\"${draft.title}\" ${formatDayTimeLabel(eventCal, now)} শিডিউল করব?$conflictNote"
                } else {
                    "Schedule \"${draft.title}\" for ${formatDayTimeLabelEn(eventCal, now)}?$conflictNote"
                }
            }
            DraftKind.REMINDER -> if (draft.isBengali) {
                "${formatDayTimeLabel(eventCal, now)} \"${draft.title}\"-এর কথা মনে করিয়ে দেব?"
            } else {
                "Remind you about \"${draft.title}\" ${formatDayTimeLabelEn(eventCal, now)}?"
            }
            DraftKind.TASK -> if (draft.reminderAtDue) {
                if (draft.isBengali) {
                    "${formatDayTimeLabel(eventCal, now)} \"${draft.title}\"-এর কথা মনে করিয়ে দেব — টাস্কটা তৈরি করব?"
                } else {
                    "Remind you about \"${draft.title}\" ${formatDayTimeLabelEn(eventCal, now)} — create this task?"
                }
            } else if (draft.reminderEpochMillis != null) {
                val reminderCal = Calendar.getInstance().apply { timeInMillis = draft.reminderEpochMillis }
                if (draft.isBengali) {
                    val eventLabel = formatDayTimeLabel(eventCal, now)
                    val reminderLabel = formatDayTimeLabel(reminderCal, now)
                    "\"${draft.title}\" টাস্কটা $eventLabel তৈরি করব, আর $reminderLabel মনে করিয়ে দেব?"
                } else {
                    val eventLabel = formatDayTimeLabelEn(eventCal, now)
                    val reminderLabel = formatDayTimeLabelEn(reminderCal, now)
                    "Create task \"${draft.title}\" for $eventLabel? I'll remind you at $reminderLabel."
                }
            } else {
                if (draft.isBengali) {
                    "\"${draft.title}\" টাস্কটা ${formatDayTimeLabel(eventCal, now)} তৈরি করব?"
                } else {
                    "Create task \"${draft.title}\" for ${formatDayTimeLabelEn(eventCal, now)}?"
                }
            }
            DraftKind.FOCUS_EVENT -> {
                val endMillis = draft.endEpochMillis
                    ?: (draft.startEpochMillis + draft.focusDurationMinutes * 60 * 1000L)
                val range = "${formatTime(java.util.Date(draft.startEpochMillis))} – ${formatTime(java.util.Date(endMillis))}"
                if (draft.isBengali) {
                    "${draft.focusDurationMinutes} মিনিটের Deep Work ($range) ব্লক করব?"
                } else {
                    "Block ${draft.focusDurationMinutes} minutes of Deep Work ($range)?"
                }
            }
        }
    }

    /**
     * S3-2 — saves a confirmed draft: the ONLY place a chat creation
     * writes to the DB. Called by ChatViewModel after the user taps
     * Confirm on the preview card. Returns the post-save receipt (the
     * same reply text + card the old immediate-save path showed). The
     * schedule conflict is re-checked fresh here — the draft-time
     * check may be stale by confirm time.
     */
    suspend fun confirmDraft(userId: String, draft: ChatDraft): PreFilterResult {
        val now = Calendar.getInstance()
        val eventCal = Calendar.getInstance().apply { timeInMillis = draft.startEpochMillis }
        return when (draft.kind) {
            DraftKind.SCHEDULE -> {
                val startMillis = draft.startEpochMillis
                val endMillis = draft.endEpochMillis ?: startMillis + 45 * 60 * 1000L
                val conflict = calendarRepository.checkConflict(userId, startMillis, endMillis)
                val conflictingTitle = if (conflict.hasConflict) conflict.conflictingEvent?.title else null
                val event = calendarRepository.addEvent(
                    userId = userId,
                    title = draft.title,
                    startEpochMillis = startMillis,
                    endEpochMillis = endMillis,
                    locationOrLink = draft.scheduleLocation ?: "Google Meet",
                    eventType = draft.scheduleType
                )
                PreFilterResult.Handled(
                    responseText = buildCreationReply(
                        draft.title, eventCal, null, false, ParsedKind.SCHEDULE, now,
                        isBengali = draft.isBengali,
                        conflictingTitle = conflictingTitle
                    ),
                    createdEvent = event,
                    actionType = ChatActionType.EVENT_CREATED,
                    focusStartMillis = startMillis,
                    focusEndMillis = endMillis,
                    quickSuggestions = listOf("Show schedule", "Plan my day", "Find free time")
                )
            }
            DraftKind.TASK, DraftKind.REMINDER -> {
                val kind = if (draft.kind == DraftKind.REMINDER) ParsedKind.REMINDER else ParsedKind.TASK
                val reminderCal = draft.reminderEpochMillis?.let {
                    Calendar.getInstance().apply { timeInMillis = it }
                }
                val task = taskRepository.createTask(
                    userId = userId,
                    title = draft.title,
                    dueEpochMillis = draft.startEpochMillis,
                    priority = draft.priority,
                    category = draft.category ?: TaskCategory.PERSONAL,
                    reminderSet = true,
                    reminderEpochMillis = draft.reminderEpochMillis
                )
                PreFilterResult.Handled(
                    responseText = buildCreationReply(
                        draft.title, eventCal, reminderCal, draft.reminderAtDue, kind, now,
                        isBengali = draft.isBengali
                    ),
                    createdTask = task,
                    actionType = ChatActionType.TASK_PREVIEW,
                    quickSuggestions = listOf("Show today's tasks", "Plan my day", "Find free time")
                )
            }
            DraftKind.FOCUS_EVENT -> {
                val startMillis = draft.startEpochMillis
                val endMillis = draft.endEpochMillis
                    ?: (startMillis + draft.focusDurationMinutes * 60 * 1000L)
                val event = calendarRepository.blockFocusTime(userId, startMillis, endMillis, draft.title)
                val timeRangeText = "${formatTime(java.util.Date(startMillis))} – ${formatTime(java.util.Date(endMillis))}"
                PreFilterResult.Handled(
                    responseText = "Done! Reserved ${draft.focusDurationMinutes} minutes of Deep Work on your calendar ($timeRangeText). Let's stay in the zone! 🎯",
                    createdEvent = event,
                    quickSuggestions = listOf("Plan my day", "Show today's tasks", "What do you remember?")
                )
            }
        }
    }

    // Bengali time-of-day words and hour tokens, stripped from generated
    // titles in the verbatim fallback branches (15/16).
    private val BENGALI_DAYPART_REGEX = Regex("(সকালে|দুপুরে|বিকেলে|বিকালে|সন্ধ্যা(?:\u09df|\u09af\u09bc)|সন্ধ্যাবেলা|রাতে)")
    private val BENGALI_TIME_TOKEN_REGEX = Regex("[০-৯0-9]{1,2}(?::[০-৯0-9]{2})?\\s*(?:\u099f\u09be(?:\u09df|\u09af\u09bc|\u0987))")

    suspend fun tryHandle(
        userId: String,
        userPrompt: String,
        todayTasks: List<TaskEntity>,
        events: List<CalendarEventEntity>
    ): PreFilterResult {
        val trimmed = userPrompt.trim()
        // Fold precomposed nukta letters to the bank's decomposed form
        // once, up front — every contains()/matcher check below sees the
        // same spelling. Stored titles render identically.
        val cleaned = foldBengaliNukta(cleanUserPrompt(trimmed))
        val lower = cleaned.lowercase(Locale.ROOT)
        // Timed tasks count as busy time when suggesting focus slots.
        val taskBusyIntervals = todayTasks.filter { !it.isCompleted }
            .map { it.dueDateEpochMillis to (it.dueDateEpochMillis + 30 * 60 * 1000L) }

        // (0) Pending clarification first. An answer completes the stored
        // creation; anything else clears it and is processed normally.
        if (pendingClarification != null) {
            val answer = tryAnswerClarification(cleaned, userId)
            if (answer != null) return answer
            pendingClarification = null
        }

        // (1) Precise non-creation branches, verbatim from the old parser.
        // They run before parseIntent: a command that matches one exactly
        // never reaches the matcher.

        // 5. English / Bengali: "Remind me in 30 minutes"
        if (lower.contains("30 minute") || lower.contains("30 minutes") || cleaned.contains("৩০ মিনিট")) {
            val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 30) }
            // S3-2 — a draft, not a save: the 30-minute reminder is a
            // chat creation like any other (Q2 = (a)).
            val draft = ChatDraft(
                kind = DraftKind.TASK,
                title = "Follow-up reminder",
                startEpochMillis = cal.timeInMillis,
                priority = Priority.MEDIUM,
                category = TaskCategory.PERSONAL,
                reminderAtDue = true,
                reminderEpochMillis = cal.timeInMillis,
                isBengali = isBengali(cleaned)
            )
            return PreFilterResult.PendingDraft(
                draft = draft,
                previewText = buildPreviewText(draft, Calendar.getInstance()),
                quickSuggestions = listOf("Plan my day", "Show today's tasks")
            )
        }

        // 6. English / Bengali: "Find free time" / "Show free time" / "ফাঁকা সময়"
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

            // S3-2 — a draft, not a save: "Block 45m focus" is a chat
            // creation (Q2 = (a)). The FOCUS_TIME_PROPOSAL card's own
            // "Block Time" button is unchanged — that proposal IS the
            // preview and the button IS the confirm.
            val draft = ChatDraft(
                kind = DraftKind.FOCUS_EVENT,
                title = "Focus Time (Deep Work)",
                startEpochMillis = startMillis,
                endEpochMillis = endMillis,
                isBengali = isBengali(cleaned),
                focusDurationMinutes = durationMinutes
            )
            return PreFilterResult.PendingDraft(
                draft = draft,
                previewText = buildPreviewText(draft, Calendar.getInstance()),
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

        // 10. Memory command: "Remember that [fact]" / "Note that [fact]" / "মনে রাখো [fact]"
        // Explicitly guard against actionable tasks like "remember to buy milk" or "মনে করিয়ে দিও"
        val isActionableTask = lower.startsWith("remember to ") || cleaned.contains("মনে করিয়")
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

        // (2) Query branches, verbatim. Shared builders below so the
        // parseIntent QUERY kinds reuse the exact same replies.
        // 2. Bengali: "আজকের সব কাজ দেখাও"
        if (cleaned.contains("আজকের") && (cleaned.contains("কাজ") || cleaned.contains("দেখাও"))) {
            return PreFilterResult.Handled(
                responseText = buildTodayTasksBengaliReply(todayTasks),
                quickSuggestions = listOf("দিনের প্ল্যান", "ফাঁকা সময়", "নতুন কাজ")
            )
        }

        // 3. English: "What's on my schedule today?" / "Show schedule" / "Scheduled"
        val isScheduleQuery = lower == "scheduled" || lower == "schedule" ||
                lower.contains("schedule today") || lower.contains("what's on my schedule") ||
                lower.contains("what is on my schedule") || lower == "my schedule" || lower == "show schedule" ||
                lower.contains("scheduled items") || lower.contains("scheduled tasks") ||
                lower.contains("what is scheduled") || lower.contains("what's scheduled") ||
                lower.contains("show scheduled") || lower.contains("view schedule") ||
                cleaned.contains("শিডিউল দেখাও") || cleaned.contains("আজকের শিডিউল")

        if (isScheduleQuery) {
            return PreFilterResult.Handled(
                responseText = buildScheduleQueryReply(todayTasks, events),
                quickSuggestions = listOf("Plan my day", "Find free time", "Show today's tasks")
            )
        }

        // 9. English: "Show today's tasks" / "List tasks" / "Show tasks"
        if (lower.contains("today's tasks") || lower.contains("my tasks") || lower == "show tasks" || lower == "tasks") {
            return PreFilterResult.Handled(
                responseText = buildTodayTasksEnglishReply(todayTasks),
                quickSuggestions = listOf("Plan my day", "Find free time", "Clear completed tasks")
            )
        }

        // (3) parseIntent — bank matcher + unified time/title/reply.
        val parsed = parseIntent(cleaned)
        handleParsedIntentStep3(parsed, cleaned, userId, todayTasks, events)?.let { return it }

        // (4) Event fallback (old branch 15).
        handleEventFallbackStep4(cleaned, lower, userId)?.let { return it }

        // (5) Catch-all (old branch 16).
        return handleCatchAllStep5(cleaned, lower, userId)
    }

    // Shared query reply builders (verbatim text from old branches 2/3/9),
    // so the parseIntent QUERY kinds answer exactly like the old branches.
    private fun buildTodayTasksBengaliReply(todayTasks: List<TaskEntity>): String {
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
        return "আজকের কাজ ($completed/$total সম্পন্ন):\n$listSummary"
    }

    private fun buildScheduleQueryReply(
        todayTasks: List<TaskEntity>,
        events: List<CalendarEventEntity>
    ): String {
        val totalTasks = todayTasks.size
        val totalMeetings = events.size
        return if (totalTasks == 0 && totalMeetings == 0) {
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
            lines.joinToString("\n").trim()
        }
    }

    private fun buildTodayTasksEnglishReply(todayTasks: List<TaskEntity>): String {
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
        return "You have $total tasks today ($completed completed):\n$taskList"
    }

    /** A weekday name means the query is NOT about today — hand it to
     *  Gemini rather than answering with today's schedule. */
    private fun mentionsWeekday(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val weekdays = listOf(
            "সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার", "রবিবার",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
        )
        return weekdays.any { lower.contains(it) }
    }

    private suspend fun handleParsedIntentStep3(
        parsed: ParsedIntent,
        cleaned: String,
        userId: String,
        todayTasks: List<TaskEntity>,
        events: List<CalendarEventEntity>
    ): PreFilterResult? {
        // (3) parseIntent — bank matcher + unified time/title/reply.
        return when (parsed.kind) {
            ParsedKind.QUERY_SCHEDULE -> {
                if ((extractRelativeDayInfo(cleaned) ?: 0) != 0 || mentionsWeekday(cleaned)) {
                    PreFilterResult.Unhandled
                } else {
                    PreFilterResult.Handled(
                        responseText = buildScheduleQueryReply(todayTasks, events),
                        quickSuggestions = listOf("Plan my day", "Find free time", "Show today's tasks")
                    )
                }
            }
            ParsedKind.QUERY_TASKS -> {
                if ((extractRelativeDayInfo(cleaned) ?: 0) != 0 || mentionsWeekday(cleaned)) {
                    PreFilterResult.Unhandled
                } else if (parsed.isBengali) {
                    PreFilterResult.Handled(
                        responseText = buildTodayTasksBengaliReply(todayTasks),
                        quickSuggestions = listOf("দিনের প্ল্যান", "ফাঁকা সময়", "নতুন কাজ")
                    )
                } else {
                    PreFilterResult.Handled(
                        responseText = buildTodayTasksEnglishReply(todayTasks),
                        quickSuggestions = listOf("Plan my day", "Find free time", "Clear completed tasks")
                    )
                }
            }
            ParsedKind.TASK, ParsedKind.SCHEDULE, ParsedKind.REMINDER -> {
                val now = Calendar.getInstance()
                if (parsed.isBareHour) {
                    // NEVER-GUESS: store the intent, ask, create nothing.
                    pendingClarification = PendingClarification(
                        kind = parsed.kind, title = parsed.title,
                        dayCal = parsed.dayCal, timeToken = parsed.timeToken,
                        isBengali = parsed.isBengali, priority = parsed.priority,
                        category = parsed.category, reminderAtDue = parsed.reminderAtDue,
                        scheduleLocation = parsed.scheduleLocation,
                        scheduleType = parsed.scheduleType,
                        kindCertain = parsed.kindCertain
                    )
                    buildClarificationQuestion(parsed, now)
                } else if (parsed.kind == ParsedKind.SCHEDULE && parsed.timeToken == null) {
                    // SCHEDULE with no time at all: ask for the time
                    // instead of silently inventing 14:00 (the 4 PM bug
                    // — 14:00 +2h when past). A schedule without a time
                    // is not a schedule; the TASK no-token default
                    // (now+2h) is untouched.
                    pendingClarification = PendingClarification(
                        kind = parsed.kind, title = parsed.title,
                        dayCal = parsed.dayCal, timeToken = parsed.timeToken,
                        isBengali = parsed.isBengali, priority = parsed.priority,
                        category = parsed.category, reminderAtDue = parsed.reminderAtDue,
                        scheduleLocation = parsed.scheduleLocation,
                        scheduleType = parsed.scheduleType,
                        kindCertain = parsed.kindCertain
                    )
                    buildNoTimeQuestion(parsed, now)
                } else {
                    val eventCal = resolveCreationTime(parsed, now, findDaypartWord(cleaned))
                    buildDraftFromParsed(parsed, eventCal, now, userId)
                }
            }
            ParsedKind.UNKNOWN -> null
        }
    }

    private suspend fun handleEventFallbackStep4(
        cleaned: String,
        lower: String,
        userId: String
    ): PreFilterResult? {
        // (4) Intelligent Fallback for Specific Scheduled Events
        // (e.g. "Doctor appointment 5pm", "Gym session tomorrow at 6am").
        // Strictly require a valid time and an actionable noun so casual
        // phrases like "I am busy" or names like "Sam" are not hijacked.
        val isEventCancellation = lower.contains("cancel") || lower.contains("delete") ||
                lower.contains("remove") || cleaned.contains("বাতিল") || cleaned.contains("মুছে")
        val dayOffset = extractRelativeDayInfo(cleaned) ?: 0
        val eventCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        val eventTimeToken = extractTimeToken(cleaned)
        val hasTimePattern = eventTimeToken != null
        val hasActionContext = lower.contains("appointment") || lower.contains("session") ||
                lower.contains("doctor") || lower.contains("dentist") || lower.contains("workout") ||
                lower.contains("gym") || lower.contains("flight") || lower.contains("pickup") ||
                lower.contains("class") || lower.contains("practice")

        if (!isEventCancellation && hasTimePattern && hasActionContext) {
            val token = eventTimeToken!!
            var fallbackTitle = cleaned
                .replace(Regex("(?i)\\b(tomorrow|today)\\b"), "")
                .replace(Regex("\\b(আগামীকাল|আগামিকাল|কালকে|আজকে|পরশু|কাল|আজ)\\b"), " ")
                .replace(Regex("(?i)at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?"), "")
                .replace(Regex("(?i)\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)"), "")
                .replace(BENGALI_DAYPART_REGEX, " ")
                .replace(BENGALI_TIME_TOKEN_REGEX, " ")
                .replace(Regex("^[,:\\s-]+"), "")
                .replace(Regex("[,:\\s-]+$"), "")
                .trim()
            if (fallbackTitle.isNotBlank() && fallbackTitle.length in 3..60) {
                if (isBareHourToken(token, cleaned)) {
                    // Bare hour: ask, don't create (never-guess invariant).
                    val bn = isBengali(cleaned)
                    pendingClarification = PendingClarification(
                        kind = ParsedKind.SCHEDULE, title = fallbackTitle,
                        dayCal = eventCal, timeToken = token, isBengali = bn,
                        priority = Priority.MEDIUM, category = null,
                        reminderAtDue = false, scheduleLocation = "Personal",
                        scheduleType = "EVENT"
                    )
                    return buildClarificationQuestion(
                        ParsedIntent(
                            kind = ParsedKind.SCHEDULE, title = fallbackTitle,
                            dayCal = eventCal, timeToken = token, isBareHour = true,
                            confidence = 1.0, isBengali = bn, priority = Priority.MEDIUM,
                            category = null, reminderAtDue = false,
                            scheduleLocation = "Personal", scheduleType = "EVENT"
                        ),
                        Calendar.getInstance()
                    )
                }
                fallbackTitle = fallbackTitle.replaceFirstChar { it.uppercase() }
                parseTimeIntoCalendar(eventCal, token, isBengali(cleaned), findDaypartWord(cleaned))
                // S3-2 — a draft, not a save. The conflict is read-only
                // here; confirmDraft re-checks it fresh. (Preserved
                // quirk, not fixed here: unlike step 3, this fallback
                // does not roll a past time +1 day.)
                val conflict = calendarRepository.checkConflict(
                    userId, eventCal.timeInMillis, eventCal.timeInMillis + (45 * 60 * 1000L)
                )
                val conflictingTitle = if (conflict.hasConflict) conflict.conflictingEvent?.title else null
                // Scheduled appointments/sessions are events — Calendar only,
                // no shadow task (keeps Tasks and Calendar separate).
                val draft = ChatDraft(
                    kind = DraftKind.SCHEDULE,
                    title = fallbackTitle,
                    startEpochMillis = eventCal.timeInMillis,
                    endEpochMillis = eventCal.timeInMillis + (45 * 60 * 1000L),
                    scheduleLocation = "Personal",
                    scheduleType = "EVENT",
                    isBengali = isBengali(cleaned),
                    conflictingTitle = conflictingTitle
                )
                return PreFilterResult.PendingDraft(
                    draft = draft,
                    previewText = buildPreviewText(draft, Calendar.getInstance()),
                    quickSuggestions = listOf("Show schedule", "Plan my day", "Find free time")
                )
            }
        }
        return null
    }

    private suspend fun handleCatchAllStep5(
        cleaned: String,
        lower: String,
        userId: String
    ): PreFilterResult {
        // (5) Catch-all: a short, non-question statement that matched
        // nothing above is most likely a to-do the user wants captured
        // (e.g. "Go for petrol"). Create it as a task and say exactly
        // what was created — never let a create attempt die silently in
        // the chat fallback. The reply states the EVENT time first, the
        // reminder labeled separately (unified reply builder).
        val bn = isBengali(cleaned)
        val wordCount = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val startsWithQuestionWord = questionStartWords.any { lower.startsWith(it) }
        val isSmallTalk = cleaned.length <= 24 && smallTalkPhrases.any {
            lower == it || lower.startsWith("$it ") || lower.startsWith("$it!")
        }
        if (!cleaned.contains("?") && !startsWithQuestionWord && !isSmallTalk &&
            cleaned.length in 3..80 && wordCount in 1..10
        ) {
            val dayOffset = extractRelativeDayInfo(cleaned) ?: 0
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
            // ONE unified extraction (am/pm, Bengali টায়/টাই, daypart).
            // The +2h fallback fires ONLY when no time token exists.
            val token = extractTimeToken(cleaned)
            if (token != null) {
                if (isBareHourToken(token, cleaned)) {
                    // Bare hour: ask, don't create (never-guess invariant).
                    var bareTitle = cleanTitle(cleaned, ParsedKind.TASK)
                    if (bareTitle.isBlank()) bareTitle = cleaned
                    pendingClarification = PendingClarification(
                        kind = ParsedKind.TASK, title = bareTitle,
                        dayCal = cal, timeToken = token, isBengali = bn,
                        priority = Priority.MEDIUM, category = TaskCategory.PERSONAL,
                        reminderAtDue = false, scheduleLocation = null,
                        scheduleType = "MEETING"
                    )
                    return buildClarificationQuestion(
                        ParsedIntent(
                            kind = ParsedKind.TASK, title = bareTitle,
                            dayCal = cal, timeToken = token, isBareHour = true,
                            confidence = 1.0, isBengali = bn, priority = Priority.MEDIUM,
                            category = TaskCategory.PERSONAL, reminderAtDue = false,
                            scheduleLocation = null, scheduleType = "MEETING"
                        ),
                        Calendar.getInstance()
                    )
                }
                parseTimeIntoCalendar(cal, token, bn, findDaypartWord(cleaned))
            } else {
                cal.add(Calendar.HOUR_OF_DAY, 2)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            var catchTitle = cleaned
                .replace(Regex("(?i)\\b(tomorrow|today)\\b"), "")
                .replace(Regex("\\b(আগামীকাল|আগামিকাল|কালকে|আজকে|পরশু|কাল|আজ)\\b"), " ")
                .replace(Regex("(?i)at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?"), "")
                .replace(Regex("(?i)\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)"), "")
                .replace(BENGALI_DAYPART_REGEX, " ")
                .replace(BENGALI_TIME_TOKEN_REGEX, " ")
                .replace(Regex("^[,:\\s-]+"), "")
                .replace(Regex("[,:\\s-]+$"), "")
                .trim()
            if (catchTitle.isBlank()) catchTitle = cleaned
            catchTitle = catchTitle.replaceFirstChar { it.uppercase() }
            // S3-2 — a draft, not a save. The reminder lead is the same
            // due − 2h the old code got back from the repository default
            // (dueReminderTriggerMillis); storing it explicitly keeps the
            // preview faithful and confirm passes it through unchanged.
            val draft = ChatDraft(
                kind = DraftKind.TASK,
                title = catchTitle,
                startEpochMillis = cal.timeInMillis,
                priority = Priority.MEDIUM,
                category = TaskCategory.PERSONAL,
                reminderAtDue = false,
                reminderEpochMillis = cal.timeInMillis - 2 * 60 * 60 * 1000L,
                isBengali = bn
            )
            val now = Calendar.getInstance()
            return PreFilterResult.PendingDraft(
                draft = draft,
                previewText = buildPreviewText(draft, now),
                quickSuggestions = listOf("Show today's tasks", "Plan my day", "Find free time")
            )
        }

        return PreFilterResult.Unhandled
    }

}
