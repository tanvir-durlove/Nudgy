package com.nextgentool.nudgy.domain.model

import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sprint 1-1 FIX-2 — SavedViewFilter JSON is hand-written with Moshi's
 * streaming API (no reflection), so these tests pin the stored format
 * exactly. A format change here silently breaks every saved_views row,
 * so the empty-filter JSON is asserted byte-for-byte, and fromJson must
 * never throw — it degrades to an empty filter on any unreadable input.
 */
class SavedViewFilterTest {

    @Test
    fun `empty filter serializes exactly like the old Moshi adapter`() {
        assertEquals(
            """{"projectId":null,"tagIds":[],"priority":null,"status":null,"groupOnly":false,"text":""}""",
            SavedViewFilter().toJson()
        )
    }

    @Test
    fun `full filter round-trips`() {
        val original = SavedViewFilter(
            projectId = "proj-1",
            tagIds = listOf("tag-a", "tag-b"),
            priority = Priority.HIGH,
            status = TaskStatus.IN_PROGRESS,
            groupOnly = true,
            text = "call Tasnim"
        )
        assertEquals(original, SavedViewFilter.fromJson(original.toJson()))
    }

    @Test
    fun `search text with quotes backslashes newlines and unicode round-trips`() {
        val original = SavedViewFilter(text = "say \"hi\" \\ back\nnew line — বাংলা")
        assertEquals(original, SavedViewFilter.fromJson(original.toJson()))
    }

    @Test
    fun `fromJson reads the old Moshi format`() {
        val json = """{"projectId":"proj-1","tagIds":["t1"],"priority":"LOW","status":"DONE","groupOnly":true,"text":"x"}"""
        assertEquals(
            SavedViewFilter("proj-1", listOf("t1"), Priority.LOW, TaskStatus.DONE, true, "x"),
            SavedViewFilter.fromJson(json)
        )
    }

    @Test
    fun `fromJson tolerates missing keys nulls and unknown keys`() {
        assertEquals(SavedViewFilter(), SavedViewFilter.fromJson("{}"))
        assertEquals(
            SavedViewFilter(priority = Priority.MEDIUM),
            SavedViewFilter.fromJson("""{"priority":"MEDIUM","unknownKey":123}""")
        )
        assertEquals(
            SavedViewFilter(),
            SavedViewFilter.fromJson("""{"projectId":null,"tagIds":null,"priority":null,"status":null,"groupOnly":null,"text":null}""")
        )
    }

    @Test
    fun `fromJson never throws on unreadable input`() {
        assertEquals(SavedViewFilter(), SavedViewFilter.fromJson(""))
        assertEquals(SavedViewFilter(), SavedViewFilter.fromJson("not json"))
        assertEquals(SavedViewFilter(), SavedViewFilter.fromJson("[1,2]"))
        assertEquals(SavedViewFilter(), SavedViewFilter.fromJson("""{"projectId":}"""))
    }

    @Test
    fun `unknown enum name degrades to null for that field only`() {
        val parsed = SavedViewFilter.fromJson("""{"priority":"BOGUS","status":"DONE","text":"keep"}""")
        assertEquals(SavedViewFilter(status = TaskStatus.DONE, text = "keep"), parsed)
    }
}
