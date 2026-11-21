package com.nextgentool.nudgy.ai

/**
 * Sprint 3-4 (Q3 = (a)) — the LOCAL brain-dump splitter. Pure Kotlin,
 * no Android: the transcript is cut into pieces with deterministic
 * rules, and each piece then goes through
 * [ClientIntentPreFilter.tryHandle] on its own.
 *
 * Rules, in order:
 * 1. Split on line breaks and sentence terminators (`. ! ?` and the
 *    Bengali dari `।`). One sentence is one task candidate.
 * 2. Inside a segment, split at a conjunction (`, and` / `and` /
 *    `, then` / `then` / `, আর` / `আর` / `তারপর` / `,`) — but ONLY
 *    when the text on either side of the conjunction contains a TIME
 *    token. "Buy milk and bread" stays ONE piece (no time anywhere,
 *    and "bread" is not a second task — it is one shopping list);
 *    "buy milk and call mom at 3pm" splits (the right side has a
 *    time); "call mom at 3pm and buy milk" splits (the left side has
 *    one). Without the time-token guard the splitter would shred
 *    ordinary "and"-lists into nonsense tasks.
 * 3. Blank / punctuation-only pieces are dropped. Everything else is
 *    kept — the prefilter, not the splitter, decides what is a task.
 *
 * A time token is an English clock (`3pm`, `at 9:30`, `tomorrow`,
 * `today`, `tonight`, a weekday name) or a Bengali one (`৩টায়`,
 * `সকালে`, `কাল`, `আজ`, `পরশু`, ...). The match is deliberately
 * broad: a false split only costs the user a preview row they can
 * remove, while a missed split silently merges two tasks into one.
 */
object BrainDumpSplitter {

    // English time tokens: clock times, day words, weekday names.
    private val EN_TIME_TOKEN = Regex(
        """\b(?:at\s+)?\d{1,2}(?::\d{2})?\s*(?:am|pm)\b""" +
            """|\b(?:tomorrow|today|tonight)\b""" +
            """|\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
        RegexOption.IGNORE_CASE
    )

    // Bengali time tokens: clock (`৩টায়`, `9টা`), dayparts, day words.
    private val BN_TIME_TOKEN = Regex(
        """[০-৯0-9]{1,2}(?::[০-৯0-9]{2})?\s*টা""" +
            """|সকালে|দুপুরে|বিকেলে|বিকালে|সন্ধ্যায়|সন্ধ্যাবেলা|রাতে""" +
            """|আগামীকাল|আগামিকাল|পরশু|কালকে|কাল|আজকে|আজ"""
    )

    // Conjunctions that may separate two tasks. The comma is included:
    // "buy milk, call mom at 3pm" splits at the comma (right side has
    // a time); "buy milk, bread" does not (no time either side).
    private val CONJUNCTION = Regex(
        """\s*,?\s*(?:and|then|আর|তারপর)\s+|\s*,\s*""",
        RegexOption.IGNORE_CASE
    )

    private val SENTENCE_END = Regex("""[.!?।\n]+""")

    /** Splits [transcript] into task-candidate pieces. Never returns
     *  blank pieces; an empty/blank transcript returns an empty list. */
    fun split(transcript: String): List<String> {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) return emptyList()
        val pieces = mutableListOf<String>()
        for (segment in SENTENCE_END.split(trimmed)) {
            pieces += splitAtTimeConjunctions(segment)
        }
        return pieces.map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun hasTimeToken(text: String): Boolean =
        EN_TIME_TOKEN.containsMatchIn(text) || BN_TIME_TOKEN.containsMatchIn(text)

    /**
     * Splits one sentence at conjunctions whose either side carries a
     * time token. Scanned left to right: each conjunction is a split
     * point only if the text already accumulated on the left, or the
     * text up to the NEXT conjunction on the right, has a time token.
     * A conjunction with no time on either side stays inside the piece.
     */
    private fun splitAtTimeConjunctions(segment: String): List<String> {
        val text = segment.trim()
        if (text.isEmpty()) return emptyList()
        val matches = CONJUNCTION.findAll(text).toList()
        if (matches.isEmpty()) return listOf(text)
        val pieces = mutableListOf<String>()
        var start = 0
        for ((index, match) in matches.withIndex()) {
            val left = text.substring(start, match.range.first).trim()
            // The right side runs to the next conjunction (or the end):
            // a time token anywhere in it justifies THIS split.
            val rightEnd = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val right = text.substring(match.range.last + 1, rightEnd).trim()
            if (left.isNotEmpty() && right.isNotEmpty() &&
                (hasTimeToken(left) || hasTimeToken(right))
            ) {
                pieces += left
                start = match.range.last + 1
            }
            // Otherwise the conjunction stays: the text (with the
            // conjunction) remains part of the current piece.
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) pieces += tail
        return pieces
    }
}
