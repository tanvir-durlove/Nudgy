package com.nextgentool.nudgy.domain.model

import com.nextgentool.nudgy.data.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1-4 — template JSON + built-in seed tests (Feature 13).
 *
 * The JSON is hand-written streaming Moshi (no reflective adapter —
 * see TaskTemplate.kt), so the round-trip and the tolerant parse are
 * pinned here. The seed exactness pins the Q6 draft approved
 * 2026-11-16: exactly 3 templates, 5 tasks each, English titles,
 * no due dates, and NO prayer template (parked by Tanvir's order).
 */
class TaskTemplateTest {

    @Test
    fun `round-trip keeps title, estimate, priority and subtasks`() {
        val drafts = listOf(
            TemplateDraft(
                title = "Morning routine",
                estimatedMinutes = 30,
                priority = Priority.MEDIUM,
                subtaskTitles = listOf("Drink water", "Freshen up")
            ),
            TemplateDraft(title = "Exercise / walk", estimatedMinutes = 30, priority = Priority.HIGH),
            TemplateDraft(title = "No estimate", estimatedMinutes = null, priority = Priority.LOW)
        )
        assertEquals(drafts, drafts.toTemplateJson().parseTemplateDrafts())
    }

    @Test
    fun `malformed JSON parses to an empty list, never a crash`() {
        assertEquals(emptyList<TemplateDraft>(), "not json".parseTemplateDrafts())
        assertEquals(emptyList<TemplateDraft>(), "".parseTemplateDrafts())
        assertEquals(emptyList<TemplateDraft>(), "{}".parseTemplateDrafts())
        assertEquals(emptyList<TemplateDraft>(), """{"drafts": "nope"}""".parseTemplateDrafts())
    }

    @Test
    fun `a draft without a title is skipped, the rest survive`() {
        val json = """{"drafts":[
            {"title":"","estimatedMinutes":10,"priority":"HIGH","subtasks":[]},
            {"title":"Keep me","estimatedMinutes":10,"priority":"LOW","subtasks":[]}
        ]}"""
        val parsed = json.parseTemplateDrafts()
        assertEquals(1, parsed.size)
        assertEquals("Keep me", parsed[0].title)
    }

    @Test
    fun `unknown priority degrades to MEDIUM for that draft only`() {
        val json = """{"drafts":[
            {"title":"A","estimatedMinutes":10,"priority":"WEIRD","subtasks":[]},
            {"title":"B","estimatedMinutes":10,"priority":"HIGH","subtasks":[]}
        ]}"""
        val parsed = json.parseTemplateDrafts()
        assertEquals(Priority.MEDIUM, parsed[0].priority)
        assertEquals(Priority.HIGH, parsed[1].priority)
    }

    @Test
    fun `seed has exactly the 3 approved templates`() {
        assertEquals(3, BuiltInTemplates.all.size)
        assertEquals(
            listOf("দৈনিক রুটিন", "পড়াশোনা", "কাজ"),
            BuiltInTemplates.all.map { it.name }
        )
        assertEquals(
            listOf(
                BuiltInTemplates.DAILY_ROUTINE_ID,
                BuiltInTemplates.STUDY_ID,
                BuiltInTemplates.WORK_ID
            ),
            BuiltInTemplates.all.map { it.id }
        )
        assertTrue(BuiltInTemplates.all.all { it.isBuiltIn })
    }

    @Test
    fun `seed templates have 5 tasks each with the approved totals`() {
        val byId = BuiltInTemplates.all.associateBy { it.id }
        assertEquals(5, byId.getValue(BuiltInTemplates.DAILY_ROUTINE_ID).drafts.size)
        assertEquals(110, byId.getValue(BuiltInTemplates.DAILY_ROUTINE_ID).totalEstimatedMinutes)
        assertEquals(5, byId.getValue(BuiltInTemplates.STUDY_ID).drafts.size)
        assertEquals(160, byId.getValue(BuiltInTemplates.STUDY_ID).totalEstimatedMinutes)
        assertEquals(5, byId.getValue(BuiltInTemplates.WORK_ID).drafts.size)
        assertEquals(180, byId.getValue(BuiltInTemplates.WORK_ID).totalEstimatedMinutes)
    }

    @Test
    fun `seed contents match the approved Q6 draft exactly`() {
        val daily = BuiltInTemplates.all.first { it.id == BuiltInTemplates.DAILY_ROUTINE_ID }
        assertEquals(
            listOf(
                "Morning routine", "Exercise / walk", "Plan the day",
                "Family time", "Prepare tomorrow's list"
            ),
            daily.drafts.map { it.title }
        )
        assertEquals(listOf("Drink water", "Freshen up"), daily.drafts[0].subtaskTitles)
        assertEquals(listOf("Review Today list"), daily.drafts[2].subtaskTitles)
        assertEquals(listOf("Pick top 3 for tomorrow"), daily.drafts[4].subtaskTitles)
        assertEquals(Priority.HIGH, daily.drafts[1].priority)

        val study = BuiltInTemplates.all.first { it.id == BuiltInTemplates.STUDY_ID }
        assertEquals("Focus study session", study.drafts[1].title)
        assertEquals(60, study.drafts[1].estimatedMinutes)
        assertEquals(listOf("Read the chapter", "Write a short summary"), study.drafts[1].subtaskTitles)

        val work = BuiltInTemplates.all.first { it.id == BuiltInTemplates.WORK_ID }
        assertEquals("Deep work — main task", work.drafts[1].title)
        assertEquals(90, work.drafts[1].estimatedMinutes)
        assertEquals(listOf("Break it into 2–3 steps first"), work.drafts[1].subtaskTitles)
    }

    @Test
    fun `seed holds no due dates and no prayer template`() {
        BuiltInTemplates.all.forEach { template ->
            // The seed JSON carries drafts only — no due field anywhere.
            assertFalse(template.drafts.toTemplateJson().contains("due"))
            val haystack = (template.name + " " +
                template.drafts.joinToString(" ") { it.title }).lowercase()
            assertFalse(haystack.contains("নামাজ"))
            assertFalse(haystack.contains("prayer"))
            assertFalse(haystack.contains("নামায"))
        }
        assertNull(BuiltInTemplates.all.firstOrNull { it.name.contains("নামাজ") })
    }
}
