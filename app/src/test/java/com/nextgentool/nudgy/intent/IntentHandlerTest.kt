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
}
