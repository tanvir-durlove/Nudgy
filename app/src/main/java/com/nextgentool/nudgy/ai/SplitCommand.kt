package com.nextgentool.nudgy.ai

/**
 * Sprint 3-5 (feature 20) — a "break this task down" command, parsed
 * locally (no LLM, no cap) so the ViewModel can resolve the target
 * task BEFORE calling the LLM: the split itself is the LLM's job, but
 * WHICH task to split is a local lookup (by title, or the task the
 * detail sheet selected).
 *
 * A command is a split verb plus an optional target title:
 * - "break this down" / "break this task down" → [SplitCommand] with a
 *   null title ("this task" — the caller asks which, or uses the
 *   detail-sheet selection).
 * - "break down Plan Diwali party" / "break \"Plan Diwali party\" down"
 *   → the title after/inside the verb.
 * - "split this into subtasks" → null title; "split Plan Diwali party
 *   into subtasks" → the title.
 * - Bengali: "এই টাস্কটা ভেঙে দাও" → null title; "পার্টির আয়োজনটা
 *   ভেঙে দাও" → the title before the verb; "ভেঙে দাও পার্টির
 *   আয়োজনটা" → the title after it. "ভাগ করে দাও" / "ভাগ করো" and
 *   "ছোট ছোট করে দাও" work the same way.
 *
 * Deliberately NOT a split command:
 * - "give me a breakdown of my day" ("breakdown" is one word — no
 *   "break … down" verb phrase).
 * - "split the bill" — a bare "split X" with no "into subtasks/steps"
 *   suffix is too ambiguous (split the bill, split the cost); the
 *   caller must say "into subtasks" or "split this".
 *
 * Pure parsing — no Android, no DB. The extracted title is cleaned
 * (surrounding quotes and trailing "into subtasks/steps" stripped);
 * an empty remainder, or a remainder that is only "this task" /
 * "এই টাস্কটা", becomes a null title.
 */
data class SplitCommand(val targetTitle: String?)

object SplitCommandParser {

    private val EN_THIS_TASK = setOf("this", "that", "it", "this task", "that task")
    private val BN_THIS_TASK = setOf("এই টাস্কটা", "এই কাজটা", "এইটা", "এটা")

    private val EN_INTO_SUFFIX = Regex(
        """\s+into\s+(subtasks?|sub-tasks?|steps?|smaller tasks?|smaller steps?)\s*[.!?।]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val BN_VERBS = listOf("ভেঙে দাও", "ভাগ করে দাও", "ভাগ করো", "ছোট ছোট করে দাও")

    fun parse(prompt: String): SplitCommand? {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return null
        // "please" up front never changes the meaning.
        val text = trimmed.removePrefix("please ").removePrefix("Please ").trim()
        parseEnglish(text)?.let { return it }
        return parseBengali(text)
    }

    private fun parseEnglish(text: String): SplitCommand? {
        // The title is extracted from the ORIGINAL-case text (the
        // regexes are case-insensitive): lowercasing first lost the
        // title's case ("Plan Diwali party" came back "plan diwali
        // party"). Only the bare "break down" equality needs lower.
        val lower = text.lowercase().trimEnd('.', '!', '?').trim()
        // A bare "break down" (no "this", no title) still means "break
        // [it] down" — the caller asks which task.
        if (lower == "break down") return SplitCommand(null)
        // "break this/that/it/task down" — no title.
        if (Regex("""\bbreak\s+(this|that|it|this task|that task)\s+down\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return SplitCommand(null)
        }
        // "break down <title>" — title after the verb.
        Regex("""\bbreak\s+down\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return SplitCommand(cleanTitle(it.groupValues[1], english = true))
        }
        // "break <title> down" — title between the verb parts.
        Regex("""\bbreak\s+(.+?)\s+down\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            val title = cleanTitle(it.groupValues[1], english = true)
            // "break it down" / "break this down" reached here only when
            // the this-task regex above missed (extra words) — still no title.
            return SplitCommand(title?.takeUnless { it.lowercase() in EN_THIS_TASK })
        }
        // "split this/that/it (into …)" — no title.
        if (Regex("""\bsplit\s+(this|that|it)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return SplitCommand(null)
        }
        // "split <title> into subtasks/steps" — the suffix is REQUIRED
        // for a bare title (see the class KDoc: "split the bill").
        Regex("""\bsplit\s+(.+?)\s+into\s+(subtasks?|sub-tasks?|steps?|smaller tasks?|smaller steps?)\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                return SplitCommand(cleanTitle(it.groupValues[1], english = true))
            }
        return null
    }

    private fun parseBengali(text: String): SplitCommand? {
        // The verb phrase may sit before the title ("ভেঙে দাও X") or
        // after it ("X ভেঙে দাও"). Find the FIRST verb occurrence and
        // take whichever side holds the title.
        val verb = BN_VERBS.firstOrNull { text.contains(it) } ?: return null
        val before = text.substringBefore(verb).trim()
        val after = text.substringAfter(verb).trim()
        val title = when {
            after.isNotEmpty() -> cleanTitle(after, english = false)
            before.isNotEmpty() -> cleanTitle(before, english = false)
            else -> null
        }
        return SplitCommand(title)
    }

    /**
     * Strips surrounding quotes, a trailing "into subtasks/steps"
     * suffix (English), trailing punctuation, and a leading "this
     * task" / "এই টাস্কটা" — any of those leaves a null title ("this
     * task", not a named one).
     */
    private fun cleanTitle(raw: String, english: Boolean): String? {
        var title = raw.trim()
            .removeSuffix(".").removeSuffix("!").removeSuffix("?").removeSuffix("।").trim()
        if (english) title = EN_INTO_SUFFIX.replace(title, "").trim()
        // Surrounding quotes — straight, curly, or Bengali danda quotes.
        title = title.trim('"', '\'', '“', '”', '‘', '’').trim()
        if (title.isEmpty()) return null
        val lower = title.lowercase()
        if (english && lower in EN_THIS_TASK) return null
        if (!english && title in BN_THIS_TASK) return null
        return title
    }
}
