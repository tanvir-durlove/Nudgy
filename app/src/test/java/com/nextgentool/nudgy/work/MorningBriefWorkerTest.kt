package com.nextgentool.nudgy.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Sprint 6-2 — the Morning Brief scheduler (spec §2 Q8=(a), §8).
 *
 * Only the request is verified here, never the actual 8 AM firing:
 * the delay lands the first tick on the next 8:00 AM device-local,
 * the request repeats every 24 hours, and the summary text covers
 * the empty / one / many cases. The worker's doWork (Room read +
 * notify) is covered by the device test (spec §10.7) — Robolectric
 * has no WorkManager test driver in this codebase.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MorningBriefWorkerTest {

    /** A fixed January day (no DST transition anywhere near it). */
    private fun at(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `delay from 7am is exactly one hour`() {
        assertEquals(3_600_000L, MorningBriefWorker.initialDelayToNext8am(at(7, 0)))
    }

    @Test
    fun `delay from exactly 8am is a full day (strictly after now)`() {
        assertEquals(86_400_000L, MorningBriefWorker.initialDelayToNext8am(at(8, 0)))
    }

    @Test
    fun `delay from 9_30am is 22 and a half hours`() {
        assertEquals(81_000_000L, MorningBriefWorker.initialDelayToNext8am(at(9, 30)))
    }

    @Test
    fun `delay from 11pm is 9 hours`() {
        assertEquals(32_400_000L, MorningBriefWorker.initialDelayToNext8am(at(23, 0)))
    }

    @Test
    fun `delay always lands on the next 8am and is within one day`() {
        for ((hour, minute) in listOf(0 to 0, 5 to 59, 8 to 1, 12 to 0, 19 to 45)) {
            val now = at(hour, minute)
            val delay = MorningBriefWorker.initialDelayToNext8am(now)
            assertTrue("delay must be positive", delay > 0)
            assertTrue("delay must fit in one day", delay <= 86_400_000L)
            val landing = Calendar.getInstance().apply { timeInMillis = now + delay }
            assertEquals(8, landing.get(Calendar.HOUR_OF_DAY))
            assertEquals(0, landing.get(Calendar.MINUTE))
            assertEquals(0, landing.get(Calendar.SECOND))
        }
    }

    @Test
    fun `request repeats every 24 hours with the computed initial delay`() {
        val now = at(7, 0)
        val request = MorningBriefWorker.buildRequest(now)
        assertEquals(
            TimeUnit.HOURS.toMillis(24),
            request.workSpec.intervalDuration
        )
        assertEquals(3_600_000L, request.workSpec.initialDelay)
        assertEquals(MorningBriefWorker::class.java.name, request.workSpec.workerClassName)
    }

    @Test
    fun `brief text covers empty, one and many`() {
        assertEquals(
            "Nothing planned for today yet — open Nudgy to plan your day.",
            MorningBriefWorker.morningBriefText(0, null)
        )
        assertEquals(
            "You have 1 task planned for today. First up: Morning walk.",
            MorningBriefWorker.morningBriefText(1, "Morning walk")
        )
        assertEquals(
            "You have 3 tasks planned for today. First up: Dentist.",
            MorningBriefWorker.morningBriefText(3, "Dentist")
        )
        assertEquals(
            "You have 2 tasks planned for today.",
            MorningBriefWorker.morningBriefText(2, null)
        )
    }
}
