package com.nextgentool.nudgy.intent

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.nextgentool.nudgy.domain.minutesOfTime
import com.nextgentool.nudgy.domain.parseStrictDate

/**
 * Sprint 2-3 — the single intent-handling path (spec §5).
 * Sprint 2-4 — DateLink + InsertEvent parsing filled in (spec §6).
 *
 * MainActivity's initial intent (onCreate) and every later intent
 * (onNewIntent, singleTop) go through [IntentHandler.parse] — the
 * Activity never parses extras itself, and no screen reads intents
 * directly. The handler returns a typed [IncomingIntent], or null for
 * anything it does not claim (launcher MAIN, empty intents, the
 * group-join deep link — which the NavHost handles — and any VIEW
 * that is not a Nudgy date link).
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
     * handler does not claim it.
     *
     * Claimed in S2-3/S2-4: ACTION_SEND text shares (ShareText),
     * ACTION_VIEW Nudgy date links (DateLink), ACTION_INSERT event
     * creates (InsertEvent). The group-join deep link is deliberately
     * NOT claimed — it routes via the NavHost deep link
     * (GROUP_JOIN_DEEP_LINK), unchanged.
     */
    fun parse(intent: Intent?): IncomingIntent? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> parseShare(intent)
            Intent.ACTION_VIEW -> DateLinkParser.parseDateLink(intent.data)
            Intent.ACTION_INSERT -> InsertEventParser.parseInsertEvent(intent)
            else -> null
        }
    }

    /** S2-3 — ACTION_SEND text share (Q5(a) + Q6(a)). */
    private fun parseShare(intent: Intent): IncomingIntent.ShareText? {
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
 * Sprint 2-4 — Nudgy date-link parsing (spec §6, Filter 1).
 *
 * A date link is `https://tanvir-durlove.github.io/nudgy/date`
 * with query `d` (yyyy-MM-dd, REQUIRED), `t` (HH:mm, optional) and
 * `title` (optional). Only this host + path is claimed — the
 * group-join link (`/nudgy/join`) and every other VIEW stay with the
 * NavHost / browser. An invalid `t` is ignored (the date still
 * opens); a missing or invalid `d` means the link is not claimed at
 * all. Query values come percent-decoded from the Uri.
 */
object DateLinkParser {

    private const val DATE_LINK_HOST = "tanvir-durlove.github.io"
    private const val DATE_LINK_PATH = "/nudgy/date"

    fun parseDateLink(uri: Uri?): IncomingIntent.DateLink? {
        if (uri == null) return null
        if (uri.scheme != "https") return null
        if (uri.host != DATE_LINK_HOST) return null
        val path = uri.path ?: return null
        if (path != DATE_LINK_PATH && !path.startsWith("$DATE_LINK_PATH/")) return null
        val date = uri.getQueryParameter("d") ?: return null
        if (parseStrictDate(date) == null) return null
        val time = uri.getQueryParameter("t")?.takeIf { minutesOfTime(it) != null }
        val title = uri.getQueryParameter("title")?.trim()?.ifBlank { null }
        return IncomingIntent.DateLink(date = date, time = time, title = title)
    }
}

/**
 * Sprint 2-4 — Gmail-style "create event" parsing (spec §6, Filter 2).
 *
 * Gmail sends ACTION_INSERT with data
 * `CalendarContract.Events.CONTENT_URI` and extras TITLE /
 * EXTRA_EVENT_BEGIN_TIME / EXTRA_EVENT_END_TIME / EVENT_LOCATION /
 * DESCRIPTION. Either the events-URI data or the event-dir MIME type
 * claims the intent; the type check covers senders that set the type
 * instead of the data. Missing extras become nulls — never a crash —
 * and an end at or before the begin is dropped (the dialog then uses
 * its default duration). A valid insert with no extras at all still
 * claims: the add-event dialog opens with its defaults, exactly as
 * if the user had tapped "+" themselves.
 */
object InsertEventParser {

    private const val EVENT_DIR_MIME_TYPE = "vnd.android.cursor.dir/event"

    fun parseInsertEvent(intent: Intent?): IncomingIntent.InsertEvent? {
        if (intent?.action != Intent.ACTION_INSERT) return null
        val isEventData = intent.data == CalendarContract.Events.CONTENT_URI
        val isEventType = intent.type == EVENT_DIR_MIME_TYPE
        if (!isEventData && !isEventType) return null
        val title = intent.getStringExtra(CalendarContract.Events.TITLE)
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        val begin = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L)
            .takeIf { it > 0 }
        val endRaw = intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L)
            .takeIf { it > 0 }
        val end = endRaw?.takeIf { begin == null || it > begin }
        val location = intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION)
        val description = intent.getStringExtra(CalendarContract.Events.DESCRIPTION)
        return IncomingIntent.InsertEvent(
            title = title?.trim()?.ifBlank { null },
            beginMillis = begin,
            endMillis = end,
            location = location?.trim()?.ifBlank { null },
            description = description?.trim()?.ifBlank { null }
        )
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
