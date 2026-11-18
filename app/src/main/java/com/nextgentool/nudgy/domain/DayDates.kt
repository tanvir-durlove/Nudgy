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

/** "Mon, Nov 17" style label for a day, device-local. */
fun dayLabel(epochMillis: Long): String =
    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(epochMillis)

/** "3:00 PM" style time label, device-local. */
fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(epochMillis)

/** "45 min" / "1h 30m" style duration label. */
fun formatMinutes(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
