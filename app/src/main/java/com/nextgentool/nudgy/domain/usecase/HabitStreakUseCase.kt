package com.nextgentool.nudgy.domain.usecase

import com.nextgentool.nudgy.domain.dateStringOf
import com.nextgentool.nudgy.domain.parseStrictDate
import java.util.Calendar
import javax.inject.Inject

/**
 * Sprint 4-0 — habit streak math (spec §5, §6 Q6=(a)). Pure, like
 * WeeklyReviewUseCase: no Room, no clock of its own — every function
 * takes its dates as "yyyy-MM-dd" strings and [today] as a parameter,
 * so tests pin dates instead of depending on the real today.
 *
 * Day boundary is DEVICE-LOCAL midnight, always. The input dates come
 * from [com.nextgentool.nudgy.domain.dateStringOf] (device-local
 * SimpleDateFormat), and day-stepping uses Calendar.add — never
 * +86_400_000 arithmetic, so a DST transition never miscounts a day.
 * There is no java.time here: minSdk is 24 and the app has no
 * core-library desugaring, so LocalDate is unavailable (see
 * DayDates.kt) — Calendar gives the same device-local semantics.
 *
 * - Current streak: consecutive checked-in local days ending today —
 *   or yesterday, when today is not checked in yet. The day is not
 *   over, so the streak is "at risk", not broken (spec §5). A miss
 *   yesterday means the streak is 0 — there is no grace day (Q6=(a)).
 * - Longest streak: the longest unbroken run anywhere in the history.
 */
class HabitStreakUseCase @Inject constructor() {

    fun currentStreak(checkInDates: Set<String>, today: String): Int {
        // Today checked in: count back from today. Otherwise count back
        // from yesterday — today is still open, the streak only breaks
        // when yesterday is missing too.
        var cursor: String? = if (today in checkInDates) today else previousDayString(today)
        var count = 0
        while (cursor != null && cursor in checkInDates) {
            count++
            cursor = previousDayString(cursor)
        }
        return count
    }

    fun longestStreak(checkInDates: Set<String>): Int {
        if (checkInDates.isEmpty()) return 0
        // "yyyy-MM-dd" sorts chronologically as plain strings.
        val sorted = checkInDates.sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (nextDayString(sorted[i - 1]) == sorted[i]) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /** One local day back, stepping with Calendar.add (DST-safe). Null on bad input. */
    fun previousDayString(dateString: String): String? = stepDayString(dateString, -1)

    /** One local day forward, stepping with Calendar.add (DST-safe). Null on bad input. */
    fun nextDayString(dateString: String): String? = stepDayString(dateString, 1)

    private fun stepDayString(dateString: String, days: Int): String? {
        val startOfDay = parseStrictDate(dateString) ?: return null
        val stepped = Calendar.getInstance().apply {
            timeInMillis = startOfDay
            add(Calendar.DAY_OF_MONTH, days)
        }.timeInMillis
        return dateStringOf(stepped)
    }
}
