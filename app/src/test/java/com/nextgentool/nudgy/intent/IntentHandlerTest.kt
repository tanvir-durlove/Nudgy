package com.nextgentool.nudgy.intent

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 2-3 — share parsing (Feature 12, Q5(a) + Q6(a)).
 *
 * The parser is pure (ShareTextParser), so most cases need no
 * Android at all; the Intent-level cases prove the handler claims
 * exactly ACTION_SEND text shares and never touches EXTRA_STREAM.
 * The manifest case proves the filter is present — without it, no
 * other app can offer Nudgy as a share target at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IntentHandlerTest {

    // ------------------------------------------------------- Q5(a): parsing

    @Test
    fun `subject present becomes the title and the whole text the description`() {
        val parsed = ShareTextParser.parseShareText(
            subject = "Dentist appointment",
            text = "Call the clinic\ntomorrow morning"
        )
        assertEquals("Dentist appointment", parsed?.title)
        assertEquals("Call the clinic\ntomorrow morning", parsed?.description)
    }

    @Test
    fun `no subject - first non-empty line is the title and the rest is the description`() {
        val parsed = ShareTextParser.parseShareText(
            subject = null,
            text = "Buy milk\n2% fat\nfrom the corner shop"
        )
        assertEquals("Buy milk", parsed?.title)
        assertEquals("2% fat\nfrom the corner shop", parsed?.description)
    }

    @Test
    fun `no subject and a single line - title only, no description`() {
        val parsed = ShareTextParser.parseShareText(subject = null, text = "Buy milk")
        assertEquals("Buy milk", parsed?.title)
        assertNull(parsed?.description)
    }

    @Test
    fun `leading blank lines are skipped when picking the title`() {
        val parsed = ShareTextParser.parseShareText(
            subject = null,
            text = "\n\n  \nBuy milk\n2% fat"
        )
        assertEquals("Buy milk", parsed?.title)
        assertEquals("2% fat", parsed?.description)
    }

    @Test
    fun `blank subject counts as no subject`() {
        val parsed = ShareTextParser.parseShareText(subject = "   ", text = "Buy milk\n2% fat")
        assertEquals("Buy milk", parsed?.title)
        assertEquals("2% fat", parsed?.description)
    }

    @Test
    fun `empty share - no subject and blank text - parses to null`() {
        assertNull(ShareTextParser.parseShareText(null, null))
        assertNull(ShareTextParser.parseShareText(null, ""))
        assertNull(ShareTextParser.parseShareText(null, "   \n  "))
        assertNull(ShareTextParser.parseShareText("  ", "   "))
    }

    // ------------------------------------------------------- Intent-level

    private fun sendIntent(
        subject: String? = null,
        text: String? = null,
        type: String = "text/plain",
        stream: Uri? = null
    ): Intent = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
    }

    @Test
    fun `ACTION_SEND text share parses to ShareText`() {
        val parsed = IntentHandler.parse(
            sendIntent(subject = "Dentist", text = "Call tomorrow")
        ) as? IncomingIntent.ShareText
        assertEquals("Dentist", parsed?.title)
        assertEquals("Call tomorrow", parsed?.description)
    }

    @Test
    fun `EXTRA_STREAM is ignored - the text still parses and nothing crashes`() {
        val parsed = IntentHandler.parse(
            sendIntent(
                text = "Buy milk\n2% fat",
                stream = Uri.parse("content://fake.provider/image.jpg")
            )
        ) as? IncomingIntent.ShareText
        assertEquals("Buy milk", parsed?.title)
        assertEquals("2% fat", parsed?.description)
    }

    @Test
    fun `stream-only share - no text at all - parses to null, no crash`() {
        val parsed = IntentHandler.parse(
            sendIntent(stream = Uri.parse("content://fake.provider/image.jpg"))
        )
        assertNull(parsed)
    }

    @Test
    fun `non-share intents are not claimed`() {
        assertNull(IntentHandler.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(IntentHandler.parse(Intent(Intent.ACTION_VIEW)))
        assertNull(IntentHandler.parse(null))
    }

    @Test
    fun `non-text SEND type is not claimed`() {
        assertNull(
            IntentHandler.parse(
                sendIntent(
                    text = "Buy milk",
                    type = "image/jpeg",
                    stream = Uri.parse("content://fake.provider/image.jpg")
                )
            )
        )
    }

    // ------------------------------------------------------- Manifest filter

    @Test
    fun `ACTION_SEND text plain resolves to MainActivity`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        val resolved = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        assertTrue(
            "No activity resolves ACTION_SEND text/plain — the manifest " +
                "filter is missing, so no app can share text to Nudgy",
            resolved.any { it.activityInfo.name.contains("MainActivity") }
        )
    }

    // ------------------------------------------- Sprint 2-4: date links

    private fun dateLinkUri(
        date: String? = "2026-11-20",
        time: String? = "15:00",
        title: String? = "Doctor"
    ): Uri {
        val query = listOfNotNull(
            date?.let { "d=$it" },
            time?.let { "t=$it" },
            title?.let { "title=$it" }
        ).joinToString("&")
        return Uri.parse("https://tanvir-durlove.github.io/nudgy/date?$query")
    }

    private fun viewIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

    @Test
    fun `date link with d t and title parses to DateLink`() {
        val parsed = DateLinkParser.parseDateLink(dateLinkUri()) as? IncomingIntent.DateLink
        assertEquals("2026-11-20", parsed?.date)
        assertEquals("15:00", parsed?.time)
        assertEquals("Doctor", parsed?.title)
    }

    @Test
    fun `date link with only d parses - time and title are null`() {
        val parsed = DateLinkParser.parseDateLink(dateLinkUri(time = null, title = null))
            as? IncomingIntent.DateLink
        assertEquals("2026-11-20", parsed?.date)
        assertEquals(null, parsed?.time)
        assertEquals(null, parsed?.title)
    }

    @Test
    fun `date link title is percent-decoded`() {
        val parsed = DateLinkParser.parseDateLink(
            Uri.parse("https://tanvir-durlove.github.io/nudgy/date?d=2026-11-20&title=Doctor%20visit")
        ) as? IncomingIntent.DateLink
        assertEquals("Doctor visit", parsed?.title)
    }

    @Test
    fun `missing or invalid d means the link is not claimed`() {
        assertNull(DateLinkParser.parseDateLink(dateLinkUri(date = null)))
        assertNull(DateLinkParser.parseDateLink(dateLinkUri(date = "2026-13-01")))
        assertNull(DateLinkParser.parseDateLink(dateLinkUri(date = "2026-02-30")))
        assertNull(DateLinkParser.parseDateLink(dateLinkUri(date = "20-11-2026")))
        assertNull(DateLinkParser.parseDateLink(dateLinkUri(date = "2026-2-3")))
        assertNull(DateLinkParser.parseDateLink(null))
    }

    @Test
    fun `invalid t is ignored - the date still opens`() {
        assertEquals(
            "15:00",
            (DateLinkParser.parseDateLink(dateLinkUri(time = "15:00")) as? IncomingIntent.DateLink)?.time
        )
        assertEquals(
            null,
            (DateLinkParser.parseDateLink(dateLinkUri(time = "25:00")) as? IncomingIntent.DateLink)?.time
        )
        assertEquals(
            null,
            (DateLinkParser.parseDateLink(dateLinkUri(time = "3pm")) as? IncomingIntent.DateLink)?.time
        )
        // …but the date survives in all three cases.
        assertEquals(
            "2026-11-20",
            (DateLinkParser.parseDateLink(dateLinkUri(time = "25:00")) as? IncomingIntent.DateLink)?.date
        )
    }

    @Test
    fun `a date link with a trailing slash path still parses`() {
        val parsed = DateLinkParser.parseDateLink(
            Uri.parse("https://tanvir-durlove.github.io/nudgy/date/?d=2026-11-20")
        ) as? IncomingIntent.DateLink
        assertEquals("2026-11-20", parsed?.date)
    }

    @Test
    fun `only the exact date-link host and path are claimed`() {
        // Wrong host, wrong scheme, group-join path, near-miss path.
        assertNull(
            DateLinkParser.parseDateLink(
                Uri.parse("https://example.com/nudgy/date?d=2026-11-20")
            )
        )
        assertNull(
            DateLinkParser.parseDateLink(
                Uri.parse("http://tanvir-durlove.github.io/nudgy/date?d=2026-11-20")
            )
        )
        assertNull(
            DateLinkParser.parseDateLink(
                Uri.parse("https://tanvir-durlove.github.io/nudgy/join?code=ABC123")
            )
        )
        assertNull(
            DateLinkParser.parseDateLink(
                Uri.parse("https://tanvir-durlove.github.io/nudgy/dateXYZ?d=2026-11-20")
            )
        )
        assertNull(
            DateLinkParser.parseDateLink(
                Uri.parse("https://tanvir-durlove.github.io/nudgy/?d=2026-11-20")
            )
        )
    }

    @Test
    fun `IntentHandler claims a date link VIEW intent and nothing else VIEW`() {
        val parsed = IntentHandler.parse(viewIntent(dateLinkUri()))
        assertTrue(parsed is IncomingIntent.DateLink)
        assertEquals("2026-11-20", (parsed as IncomingIntent.DateLink).date)
        // The group-join deep link stays with the NavHost, untouched.
        assertNull(
            IntentHandler.parse(
                viewIntent(Uri.parse("https://tanvir-durlove.github.io/nudgy/join?code=ABC123"))
            )
        )
        assertNull(IntentHandler.parse(Intent(Intent.ACTION_VIEW)))
    }

    // ---------------------------------------- Sprint 2-4: create-event

    private fun insertIntent(
        title: String? = "Design review",
        begin: Long? = 1_764_000_000_000L,
        end: Long? = 1_764_003_600_000L,
        location: String? = "Room 4B",
        description: String? = "Sprint planning"
    ): Intent = Intent(Intent.ACTION_INSERT).apply {
        setDataAndType(
            android.provider.CalendarContract.Events.CONTENT_URI,
            "vnd.android.cursor.dir/event"
        )
        title?.let { putExtra(android.provider.CalendarContract.Events.TITLE, it) }
        begin?.let { putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        end?.let { putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, it) }
        location?.let { putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, it) }
        description?.let { putExtra(android.provider.CalendarContract.Events.DESCRIPTION, it) }
    }

    @Test
    fun `ACTION_INSERT with full extras parses to InsertEvent`() {
        val parsed = IntentHandler.parse(insertIntent()) as? IncomingIntent.InsertEvent
        assertEquals("Design review", parsed?.title)
        assertEquals(1_764_000_000_000L, parsed?.beginMillis)
        assertEquals(1_764_003_600_000L, parsed?.endMillis)
        assertEquals("Room 4B", parsed?.location)
        assertEquals("Sprint planning", parsed?.description)
    }

    @Test
    fun `ACTION_INSERT with no extras still claims - everything is null`() {
        val parsed = IntentHandler.parse(insertIntent(null, null, null, null, null))
            as? IncomingIntent.InsertEvent
        assertEquals(null, parsed?.title)
        assertEquals(null, parsed?.beginMillis)
        assertEquals(null, parsed?.endMillis)
        assertEquals(null, parsed?.location)
        assertEquals(null, parsed?.description)
    }

    @Test
    fun `ACTION_INSERT end at or before begin is dropped, begin survives`() {
        val parsed = IntentHandler.parse(
            insertIntent(begin = 1_764_000_000_000L, end = 1_764_000_000_000L)
        ) as? IncomingIntent.InsertEvent
        assertEquals(1_764_000_000_000L, parsed?.beginMillis)
        assertEquals(null, parsed?.endMillis)
        val parsed2 = IntentHandler.parse(
            insertIntent(begin = 1_764_000_000_000L, end = 1_763_999_000_000L)
        ) as? IncomingIntent.InsertEvent
        assertEquals(null, parsed2?.endMillis)
    }

    @Test
    fun `ACTION_INSERT without event data or type is not claimed`() {
        assertNull(IntentHandler.parse(Intent(Intent.ACTION_INSERT)))
        assertNull(
            IntentHandler.parse(
                Intent(Intent.ACTION_INSERT).apply {
                    data = Uri.parse("content://com.android.calendar/calendars")
                }
            )
        )
        // A plain VIEW of an event URI is Filter 3 (not v1) — not claimed.
        assertNull(
            IntentHandler.parse(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("content://com.android.calendar/events/42")
                )
            )
        )
    }

    // ------------------------------------------------------- Manifest filters

    @Test
    fun `date link App Link resolves to MainActivity`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = viewIntent(dateLinkUri())
        val resolved = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        assertTrue(
            "No activity resolves the /nudgy/date App Link — the manifest " +
                "filter is missing, so tapping a date link never offers Nudgy",
            resolved.any { it.activityInfo.name.contains("MainActivity") }
        )
    }

    @Test
    fun `ACTION_INSERT event resolves to MainActivity`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // setDataAndType mirrors what the system does when Gmail's
        // CONTENT_URI intent is resolved (the provider reports the
        // event-dir MIME type); a bare setData would leave the type
        // unresolved under Robolectric.
        val intent = insertIntent()
        val resolved = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        assertTrue(
            "No activity resolves ACTION_INSERT for events — the manifest " +
                "filter is missing, so Gmail create-event never offers Nudgy",
            resolved.any { it.activityInfo.name.contains("MainActivity") }
        )
    }
}
