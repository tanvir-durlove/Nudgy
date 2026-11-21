package com.nextgentool.nudgy.ai

import com.nextgentool.nudgy.data.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Sprint 3-3 — the LLM structured-output contract (spec §8).
 *
 * Pins the fail-safe rules: strict ISO-8601 dates (a bad date rejects
 * the WHOLE JSON), non-blank titles, unknown action → plain text, any
 * validation failure → plain text and NEVER a save (there is no save
 * path here at all — the test asserts the result type, and the handler
 * tests assert nothing lands in the DB). Runs under Robolectric:
 * org.json lives in the Android framework jar, whose plain-JVM stubs
 * throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LlmStructuredOutputTest {

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    private fun dayOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)

    @Test
    fun `valid single-task JSON parses with title due and priority`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "2026-11-22T09:00:00", "priority": "HIGH"}], "events": [], "subtasks": [], "reply": "Got it."}"""
        )
        assertTrue(result is LlmStructuredResult.SingleTask)
        val task = (result as LlmStructuredResult.SingleTask).task
        assertEquals("Buy milk", task.title)
        assertEquals(Priority.HIGH, task.priority)
        assertEquals(9, hourOf(task.dueEpochMillis!!))
        assertEquals(22, dayOf(task.dueEpochMillis!!))
        assertEquals("Got it.", result.reply)
    }

    @Test
    fun `missing priority defaults to MEDIUM and unknown priority too`() {
        val noPriority = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "2026-11-22T09:00:00"}], "reply": ""}"""
        )
        assertEquals(Priority.MEDIUM, (noPriority as LlmStructuredResult.SingleTask).task.priority)
        val garbage = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "2026-11-22T09:00:00", "priority": "URGENT"}], "reply": ""}"""
        )
        assertEquals(Priority.MEDIUM, (garbage as LlmStructuredResult.SingleTask).task.priority)
    }

    @Test
    fun `offset datetime and date-only due parse strictly`() {
        val offset = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Call", "due": "2026-11-22T09:00:00Z"}], "reply": ""}"""
        )
        assertTrue(offset is LlmStructuredResult.SingleTask)
        val dateOnly = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Call", "due": "2026-11-22"}], "reply": ""}"""
        )
        assertTrue(dateOnly is LlmStructuredResult.SingleTask)
        // Date-only means start of day, device-local zone.
        val millis = (dateOnly as LlmStructuredResult.SingleTask).task.dueEpochMillis!!
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `unparseable due rejects the whole JSON`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "tomorrow 3pm"}], "reply": "Sure, I'll add it."}"""
        )
        assertTrue(result is LlmStructuredResult.Invalid)
        // The model's reply survives as the plain-text answer.
        assertEquals("Sure, I'll add it.", (result as LlmStructuredResult.Invalid).reply)
    }

    @Test
    fun `empty title rejects the whole JSON`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "   ", "due": "2026-11-22T09:00:00"}], "reply": "Done."}"""
        )
        assertTrue(result is LlmStructuredResult.Invalid)
        assertEquals("Done.", (result as LlmStructuredResult.Invalid).reply)
    }

    @Test
    fun `valid single-event JSON parses with start and end`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_event", "events": [{"title": "Dentist", "start": "2026-11-23T15:00:00", "end": "2026-11-23T15:45:00"}], "reply": "Scheduled."}"""
        )
        assertTrue(result is LlmStructuredResult.SingleEvent)
        val event = (result as LlmStructuredResult.SingleEvent).event
        assertEquals("Dentist", event.title)
        assertEquals(15, hourOf(event.startEpochMillis))
        assertTrue(event.endEpochMillis > event.startEpochMillis)
    }

    @Test
    fun `event with end before start is invalid`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_event", "events": [{"title": "Dentist", "start": "2026-11-23T15:45:00", "end": "2026-11-23T15:00:00"}], "reply": "Scheduled."}"""
        )
        assertTrue(result is LlmStructuredResult.Invalid)
    }

    @Test
    fun `event with missing end is invalid`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_event", "events": [{"title": "Dentist", "start": "2026-11-23T15:00:00"}], "reply": "Scheduled."}"""
        )
        assertTrue(result is LlmStructuredResult.Invalid)
    }

    @Test
    fun `create_tasks with no tasks is invalid`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [], "reply": "Nothing to add."}"""
        )
        assertTrue(result is LlmStructuredResult.Invalid)
    }

    @Test
    fun `unknown action becomes a plain-text reply`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "teleport", "tasks": [], "reply": "I cannot teleport you."}"""
        )
        assertTrue(result is LlmStructuredResult.ReplyOnly)
        assertEquals("I cannot teleport you.", (result as LlmStructuredResult.ReplyOnly).reply)
    }

    @Test
    fun `action none is a plain-text reply`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "none", "reply": "Here is what I know about that."}"""
        )
        assertTrue(result is LlmStructuredResult.ReplyOnly)
        assertEquals("Here is what I know about that.", (result as LlmStructuredResult.ReplyOnly).reply)
    }

    @Test
    fun `plain prose with no JSON is a plain-text reply, not an error`() {
        val result = LlmStructuredOutput.parse(
            "I think you should take a short walk first, then start the report."
        )
        assertTrue(result is LlmStructuredResult.ReplyOnly)
        assertEquals(
            "I think you should take a short walk first, then start the report.",
            (result as LlmStructuredResult.ReplyOnly).reply
        )
    }

    @Test
    fun `fenced JSON is parsed`() {
        val result = LlmStructuredOutput.parse(
            "```json\n{\"action\": \"create_tasks\", \"tasks\": [{\"title\": \"Buy milk\", \"due\": \"2026-11-22T09:00:00\"}], \"reply\": \"Added.\"}\n```"
        )
        assertTrue(result is LlmStructuredResult.SingleTask)
        assertEquals("Buy milk", (result as LlmStructuredResult.SingleTask).task.title)
    }

    @Test
    fun `broken JSON is invalid with an empty reply`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", """
        )
        assertTrue(result is LlmStructuredResult.Invalid)
        assertEquals("", (result as LlmStructuredResult.Invalid).reply)
    }

    @Test
    fun `multi-task JSON is reply-only in S3-3`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "2026-11-22T09:00:00"}, {"title": "Call mum", "due": "2026-11-22T18:00:00"}], "reply": "Two things on your list."}"""
        )
        assertTrue(result is LlmStructuredResult.ReplyOnly)
        assertEquals("Two things on your list.", (result as LlmStructuredResult.ReplyOnly).reply)
    }

    @Test
    fun `task with null due is reply-only, never a draft`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": null}], "reply": "When should I remind you?"}"""
        )
        assertTrue(result is LlmStructuredResult.ReplyOnly)
        assertEquals("When should I remind you?", (result as LlmStructuredResult.ReplyOnly).reply)
    }

    @Test
    fun `split_task validates subtasks and returns a SplitTask`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "split_task", "subtasks": [{"title": "Buy flour"}, {"title": "Mix the dough"}], "reply": "Here is a breakdown."}"""
        )
        assertTrue(result is LlmStructuredResult.SplitTask)
        val split = result as LlmStructuredResult.SplitTask
        assertEquals(listOf("Buy flour", "Mix the dough"), split.subtasks.map { it.title })
        assertEquals("Here is a breakdown.", split.reply)
    }

    @Test
    fun `split_task with an empty subtask title is invalid`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "split_task", "subtasks": [{"title": "Buy flour"}, {"title": ""}], "reply": "Here is a breakdown."}"""
        )
        assertTrue(result is LlmStructuredResult.Invalid)
    }

    @Test
    fun `mixed tasks and events are reply-only`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "2026-11-22T09:00:00"}], "events": [{"title": "Dentist", "start": "2026-11-23T15:00:00", "end": "2026-11-23T15:45:00"}], "reply": "Both are noted."}"""
        )
        assertTrue(result is LlmStructuredResult.ReplyOnly)
    }

    @Test
    fun `one bad item rejects the whole JSON even when the other is fine`() {
        val result = LlmStructuredOutput.parse(
            """{"action": "create_tasks", "tasks": [{"title": "Buy milk", "due": "2026-11-22T09:00:00"}, {"title": "Call mum", "due": "sometime later"}], "reply": "Noted."}"""
        )
        assertTrue(result is LlmStructuredResult.Invalid)
    }

    @Test
    fun `empty text is invalid`() {
        val result = LlmStructuredOutput.parse("   ")
        assertTrue(result is LlmStructuredResult.Invalid)
        assertEquals("", (result as LlmStructuredResult.Invalid).reply)
    }

    @Test
    fun `parseIsoEpochMillis accepts offset local and date-only forms`() {
        assertTrue(LlmStructuredOutput.parseIsoEpochMillis("2026-11-22T09:00:00+06:00") != null)
        assertTrue(LlmStructuredOutput.parseIsoEpochMillis("2026-11-22T09:00:00") != null)
        assertTrue(LlmStructuredOutput.parseIsoEpochMillis("2026-11-22") != null)
        assertNull(LlmStructuredOutput.parseIsoEpochMillis("tomorrow 3pm"))
        assertNull(LlmStructuredOutput.parseIsoEpochMillis("3 PM"))
        assertNull(LlmStructuredOutput.parseIsoEpochMillis(""))
        // The offset is honoured: 09:00 at +06:00 is 03:00 UTC.
        val millis = LlmStructuredOutput.parseIsoEpochMillis("2026-11-22T09:00:00+06:00")!!
        val utcHour = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)
        assertEquals(3, utcHour)
    }
}
