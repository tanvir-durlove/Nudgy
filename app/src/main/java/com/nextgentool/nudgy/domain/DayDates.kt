package com.nextgentool.nudgy.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Sprint 1-2 — pure date helpers (device-local time, day = local midnight).
 *
 * `plannedDate` / `carriedOverFromDate` are stored as "yyyy-MM-dd" strings
 * (spec §4). Every function takes its clock as a parameter so unit tests
 * can pin a date instead of depending on the real today. A fresh
 * SimpleDateFormat is built per call: the class is not thread-safe and
 * these helpers run on ViewModel, IO and test threads.
 */

/** Today's date as "yyyy-MM-dd" in device-local time. */
fun todayDateString(now: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)

/** The local date of [epochMillis] as "yyyy-MM-dd". */
fun dateStringOf(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(epochMillis)

/** Start of the local day (00:00) containing [epochMillis]. */
fun startOfDayMillis(epochMillis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = epochMillis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** End of the local day (23:59:59.999) containing [epochMillis]. */
fun endOfDayMillis(epochMillis: Long): Long =
    startOfDayMillis(epochMillis) + 24 * 60 * 60 * 1000L - 1

/** Start of the local day [daysAhead] days from [now] (0 = today). */
fun startOfDayMillis(daysAhead: Int, now: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    cal.add(Calendar.DAY_OF_MONTH, daysAhead)
    return startOfDayMillis(cal.timeInMillis)
}

/** Start of the local week (Monday 00:00) containing [now] (Q5: Mon–Sun). */
fun startOfWeekMillis(now: Long = System.currentTimeMillis()): Long {
    // Same Monday-diff as CalendarScreen: Sunday belongs to the week
    // that started six days earlier, never to the week starting tomorrow.
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    val diffToMonday = if (dayOfWeek == Calendar.SUNDAY) -6 else Calendar.MONDAY - dayOfWeek
    cal.add(Calendar.DAY_OF_MONTH, diffToMonday)
    return startOfDayMillis(cal.timeInMillis)
}

/** "Mon, Nov 17" style label for a day, device-local. */
fun dayLabel(epochMillis: Long): String =
    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(epochMillis)

/**
 * Sprint 7 (Focus data) — "Today" / "Yesterday" / [dayLabel] for a
 * past day, device-local. No established helper covered
 * Today/Yesterday (SearchScreen's searchDayLabel is private and
 * answers Today/Tomorrow instead), so this small pure function
 * lives next to [dayLabel] and follows searchDayLabel's
 * YEAR + DAY_OF_YEAR comparison. The date shape stays [dayLabel].
 */
fun recentDayLabel(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val day = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val sameDay = today.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
        today.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today"
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val isYesterday = yesterday.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
    return if (isYesterday) "Yesterday" else dayLabel(epochMillis)
}

/**
 * Sprint 2-4 — strict "yyyy-MM-dd" → start-of-day millis (local),
 * or null when the string is not a real calendar date.
 *
 * No java.time here: the app has no core-library desugaring and
 * minSdk is 24, so LocalDate is unavailable below API 26. The regex
 * pins the shape (2-digit month/day), the non-lenient parse rejects
 * impossible dates (2026-02-30, month 13), and the round-trip guard
 * catches any normalisation the parser might still do.
 */
private val STRICT_DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")

fun parseStrictDate(dateString: String): Long? {
    if (!STRICT_DATE_REGEX.matches(dateString)) return null
    val parsed = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { isLenient = false }
            .parse(dateString)
    } catch (_: Exception) {
        return null
    } ?: return null
    if (dateStringOf(parsed.time) != dateString) return null
    return startOfDayMillis(parsed.time)
}

/**
 * Sprint 2-4 — strict "HH:mm" (24-hour) → minutes since midnight,
 * or null when invalid. Used for a date link's `t` query param: an
 * invalid time is ignored, the date still opens.
 */
private val STRICT_TIME_REGEX = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

fun minutesOfTime(timeString: String): Int? {
    if (!STRICT_TIME_REGEX.matches(timeString)) return null
    val (hour, minute) = timeString.split(":")
    return hour.toInt() * 60 + minute.toInt()
}

/**
 * Sprint 2-4 — whole weeks between the Monday of [now]'s week and
 * the Monday of [dayMillis]'s week (Mon–Sun weeks, Q5). Steps with
 * Calendar.add so a DST transition never miscounts a week.
 */
fun weekOffsetForDay(dayMillis: Long, now: Long = System.currentTimeMillis()): Int {
    val targetMonday = startOfWeekMillis(dayMillis)
    val currentMonday = startOfWeekMillis(now)
    var offset = 0
    val cal = Calendar.getInstance().apply { timeInMillis = currentMonday }
    while (cal.timeInMillis < targetMonday) {
        cal.add(Calendar.DAY_OF_MONTH, 7)
        offset++
    }
    while (cal.timeInMillis > targetMonday) {
        cal.add(Calendar.DAY_OF_MONTH, -7)
        offset--
    }
    return offset
}

/** Sprint 2-4 — day index inside its Mon–Sun week: Monday = 0 … Sunday = 6. */
fun dayIndexInWeek(dayMillis: Long): Int {
    val dayOfWeek = Calendar.getInstance().apply { timeInMillis = dayMillis }
        .get(Calendar.DAY_OF_WEEK)
    return (dayOfWeek + 5) % 7
}

/** "3:00 PM" style time label, device-local. */
fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(epochMillis)

/** "45 min" / "1h 30m" style duration label. */
fun formatMinutes(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
