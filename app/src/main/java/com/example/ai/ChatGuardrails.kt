package com.example.ai

/**
 * Chat content guardrails (PR #35).
 *
 * Runs FIRST in MainActivity.handleUserChat — before the local intent
 * preFilter, before any Gemini cap is consumed, and before any task or
 * schedule is created — so a refused message creates nothing anywhere
 * (including group items) and costs no Gemini turn. The user's own
 * message is not stored either, so refused content never reaches the
 * cloud.
 *
 * Matching is deliberately conservative and phrase-level: only CLEAR
 * intent phrases match. Single generic words never match on their own,
 * so normal planner language ("kill the meeting", "deadline",
 * "bomb the exam", "nude lipstick") is never refused. When in doubt,
 * ALLOW — Gemini's own safety filters catch the gray zone, and the
 * existing local fallback handles a blocked call.
 *
 * Refusals are short and neutral, in the user's language (Bengali
 * default, detected from the prompt). No lecture, no moralizing, no
 * reporting, no stored flags. Self-harm is the one exception to
 * brevity: a short caring reply plus the 988 helpline (US).
 */
object ChatGuardrails {

    sealed interface GuardResult {
        data object Allowed : GuardResult
        data class Refused(val selfHarm: Boolean, val replyText: String) : GuardResult
    }

    fun check(prompt: String): GuardResult {
        val text = prompt.lowercase()
        if (matchesSelfHarm(text)) {
            return GuardResult.Refused(selfHarm = true, replyText = selfHarmReply(prompt))
        }
        if (matchesIllegal(text) || matchesSexual(text) || matchesHate(text)) {
            return GuardResult.Refused(selfHarm = false, replyText = refusalReply(prompt))
        }
        return GuardResult.Allowed
    }

    // ---- Illegal acts: how to commit a crime / evade law enforcement ----
    private val illegalPhrases = listOf(
        // English.
        "how to steal", "how to rob", "how to shoplift",
        "how to kill someone", "how to murder",
        "how to make a bomb", "how to build a bomb",
        "how to make drugs", "how to make meth",
        "how to hack into", "how to commit fraud",
        "evade police", "evade the police", "hide from the police",
        "escape from prison", "how to buy drugs",
        "buy cocaine", "buy heroin", "counterfeit money",
        "fake id", "evade tax",
        "forge a signature", "forge documents",
        // Bengali.
        "চুরি করবো কিভাবে", "চুরি করার উপায়", "চুরি করতে চাই",
        "খুন করবো কিভাবে", "খুন করার উপায়",
        "বোমা বানাবো কিভাবে", "বোমা বানানোর উপায়",
        "পুলিশকে ফাঁকি", "পুলিশের হাত থেকে",
        "জাল টাকা", "মাদক বানানো", "ড্রাগস কিনবো",
        "হ্যাক করবো কিভাবে"
    )

    // ---- Explicit sexual / adult content ----
    // Whole-word terms only: the term itself is unambiguous in a planner
    // ("porn", "xxx"). Generic words like "nude" are deliberately NOT
    // here — "nude lipstick" is a normal shopping task.
    private val sexualWords = listOf(
        "porn", "xxx", "erotic", "naked", "pornhub", "onlyfans"
    )
    private val sexualPhrases = listOf(
        "sex chat", "phone sex", "have sex", "sex with",
        // Bengali (substring-safe: these strings do not occur inside
        // innocent Bengali words).
        "পর্ন", "সেক্স", "নগ্ন", "অশ্লীল", "যৌন"
    )

    // ---- Hate / harassment targeting a protected group ----
    private val hatePhrases = listOf(
        "hate muslims", "hate hindus", "hate jews", "hate christians",
        "hate women", "hate gays", "hate black people", "hate white people",
        "death to",
        "kill all muslims", "kill all hindus", "kill all jews",
        "kill all christians", "kill all women", "kill all gays",
        // Bengali.
        "মুসলিমদের ঘৃণা", "হিন্দুদের ঘৃণা", "মেয়েদের ঘৃণা",
        "মুসলিমদের মারো", "হিন্দুদের মারো"
    )

    // ---- Self-harm ----
    // Only unambiguous intent phrases. "hurt myself" is deliberately NOT
    // here — it is usually an injury report ("I hurt myself playing
    // football"), not self-harm intent.
    private val selfHarmPhrases = listOf(
        "kill myself", "i want to die", "want to die",
        "end my life", "take my own life", "commit suicide",
        "suicide",
        // Bengali.
        "আত্মহত্যা", "মরতে চাই", "মরে যেতে চাই",
        "নিজেকে শেষ", "নিজের ক্ষতি করবো", "নিজেকে মারবো"
    )

    private fun matchesIllegal(text: String): Boolean =
        illegalPhrases.any { text.contains(it) }

    private fun matchesSexual(text: String): Boolean =
        sexualPhrases.any { text.contains(it) } ||
            sexualWords.any { containsWord(text, it) }

    private fun matchesHate(text: String): Boolean =
        hatePhrases.any { text.contains(it) }

    private fun matchesSelfHarm(text: String): Boolean =
        selfHarmPhrases.any { text.contains(it) }

    /** Whole-word match for English terms (tokenized on non-letters). */
    private fun containsWord(text: String, word: String): Boolean =
        text.split(Regex("[^a-z0-9]+")).any { it == word }

    private fun isBengali(prompt: String): Boolean =
        prompt.any { it in '\u0980'..'\u09FF' }

    private fun refusalReply(prompt: String): String =
        if (isBengali(prompt)) "এটা নিয়ে সাহায্য করতে পারবো না।"
        else "I can't help with that."

    private fun selfHarmReply(prompt: String): String =
        if (isBengali(prompt)) {
            "এটা শুনে খারাপ লাগলো। তুমি একা নও — দয়া করে এখনই সাহায্য নাও: 988 (Suicide and Crisis Lifeline, US)।"
        } else {
            "I'm sorry you're feeling this way. You're not alone — please reach out for help right now: 988 (Suicide and Crisis Lifeline, US)."
        }
}
