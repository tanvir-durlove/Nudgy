package com.nextgentool.nudgy.intent

import android.content.Intent

/**
 * Sprint 2-3 — the single intent-handling path (spec §5).
 *
 * MainActivity's initial intent (onCreate) and every later intent
 * (onNewIntent, singleTop) go through [IntentHandler.parse] — the
 * Activity never parses extras itself, and no screen reads intents
 * directly. The handler returns a typed [IncomingIntent], or null for
 * anything it does not claim (launcher MAIN, empty intents, and — in
 * this sub-PR — every non-share intent).
 *
 * Only the ShareText path is built in S2-3. The other result types
 * exist so S2-4 can fill in their parsing without reshaping this
 * file: DateLink (App Link `/nudgy/date`) and InsertEvent (Gmail-style
 * ACTION_INSERT) belong to S2-4, and GroupJoin already routes through
 * the NavHost deep link (GROUP_JOIN_DEEP_LINK) — this handler
 * deliberately does not claim VIEW intents in S2-3, so that routing
 * is unchanged.
 */
sealed interface IncomingIntent {

    /** A text share from another app (Feature 12, Q5(a)). */
    data class ShareText(val title: String, val description: String?) : IncomingIntent

    /**
     * A Nudgy date link (`https://tanvir-durlove.github.io/nudgy/date?d=...`).
     * Parsing belongs to S2-4 (link chooser) — the type exists here so
     * the handler's shape is ready for it.
     */
    data class DateLink(val date: String, val time: String?, val title: String?) : IncomingIntent

    /**
     * A Gmail-style "create event" insert (ACTION_INSERT on
     * CalendarContract.Events.CONTENT_URI). Parsing belongs to S2-4.
     */
    data class InsertEvent(
        val title: String?,
        val beginMillis: Long?,
        val endMillis: Long?,
        val location: String?,
        val description: String?
    ) : IncomingIntent

    /**
     * A group-join deep link (`/nudgy/join?code=...`). Routes via the
     * NavHost deep link today (S2-4 owns the handler path, if any).
     */
    data class GroupJoin(val code: String) : IncomingIntent
}

object IntentHandler {

    /**
     * Parses an incoming intent into a typed result, or null when this
     * handler does not claim it. In S2-3 only ACTION_SEND text shares
     * are claimed; everything else (including VIEW deep links, which
     * the NavHost handles) returns null and is left untouched.
     */
    fun parse(intent: Intent?): IncomingIntent? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val type = intent.type
        if (type != null && !type.startsWith("text/")) return null
        // Q6(a) — text-only v1: EXTRA_STREAM is deliberately never
        // read. A shared image/file contributes nothing; the text (if
        // any) still parses, and a stream-only share yields no sheet.
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        return ShareTextParser.parseShareText(subject, text)
    }
}

/**
 * Pure share-text parsing (Q5(a)). No Android classes — plain
 * unit-testable:
 *
 * - EXTRA_SUBJECT present (non-blank) → it is the title; the whole
 *   EXTRA_TEXT (trimmed) is the description. This is the email-share
 *   shape: Gmail sends subject + body.
 * - No subject → the first non-empty line of EXTRA_TEXT is the title;
 *   the rest (leading/trailing blank lines trimmed, internal
 *   structure kept) is the description. A single line is a title
 *   with no description.
 * - No subject and blank/empty text → null: an empty share never
 *   creates a task and never opens a sheet.
 */
object ShareTextParser {

    fun parseShareText(subject: String?, text: String?): IncomingIntent.ShareText? {
        val cleanSubject = subject?.trim().orEmpty()
        val cleanText = text?.trim().orEmpty()
        if (cleanSubject.isNotBlank()) {
            return IncomingIntent.ShareText(
                title = cleanSubject,
                description = cleanText.ifBlank { null }
            )
        }
        if (cleanText.isBlank()) return null
        val lines = cleanText.lines()
        val firstIndex = lines.indexOfFirst { it.isNotBlank() }
        if (firstIndex == -1) return null
        val title = lines[firstIndex].trim()
        val rest = lines.drop(firstIndex + 1).joinToString("\n").trim()
        return IncomingIntent.ShareText(
            title = title,
            description = rest.ifBlank { null }
        )
    }
}
