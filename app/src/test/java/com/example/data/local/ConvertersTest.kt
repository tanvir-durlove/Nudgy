package com.example.data.local

import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for Room [Converters].
 *
 * These run on the JVM (no Android framework needed): every conversion is
 * pure string/enum mapping. They guard the round-trip the database layer
 * depends on — if a stored value ever fails to parse, the fallback must be
 * the documented default, never a crash.
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `priority round-trips through string form`() {
        for (priority in Priority.values()) {
            assertEquals(priority, converters.toPriority(converters.fromPriority(priority)))
        }
    }

    @Test
    fun `unknown priority string falls back to MEDIUM`() {
        assertEquals(Priority.MEDIUM, converters.toPriority("URGENT"))
        assertEquals(Priority.MEDIUM, converters.toPriority(""))
        assertEquals(Priority.MEDIUM, converters.toPriority("low"))
    }

    @Test
    fun `category round-trips through string form`() {
        for (category in TaskCategory.values()) {
            assertEquals(category, converters.toCategory(converters.fromCategory(category)))
        }
    }

    @Test
    fun `unknown category string falls back to WORK`() {
        assertEquals(TaskCategory.WORK, converters.toCategory("FUN"))
        assertEquals(TaskCategory.WORK, converters.toCategory(""))
        assertEquals(TaskCategory.WORK, converters.toCategory("work"))
    }
}
