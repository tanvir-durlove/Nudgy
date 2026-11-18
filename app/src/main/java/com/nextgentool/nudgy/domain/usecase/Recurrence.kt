package com.nextgentool.nudgy.domain.usecase

import java.util.Calendar

/**
 * Sprint 1-3a — recurrence rules (Feature 3), RRULE-lite format stored in
 * tasks.recurrenceRule (String, v8 schema — no new column, no schema change).
 *
 * Format (defined here — no v1 format existed in code; the only prior
 * occurrence of a rule string was the Migration7To8Test fixture "DAILY"):
 * - "DAILY"
 * - "WEEKLY:MON,WED" — ISO day abbreviations, comma-separated, any order
 * - "MONTHLY:15" — day-of-month 1..31; clamps to the last day of shorter
 *   months (MONTHLY:31 in February becomes Feb 28/29)
 * - "EVERY_N_DAYS:3" — N >= 1
 *
 * Pure JVM (java.util.Calendar, device-local zone — the same convention as
 * the rest of the codebase): [nextOccurrence] is computed in local calendar
 * fields, so a timezone change keeps the local time-of-day and the weekday.
 * The next occurrence is the first candidate STRICTLY after [nowMillis],
 * stepped from the original due — so a task completed late still lands in
 * the future instead of creating a past-due occurrence.
 */
object Recurrence {

    private val WEEKDAY_NAMES = mapOf(
        Calendar.MONDAY to "Mon",
        Calendar.TUESDAY to "Tue",
        Calendar.WEDNESDAY to "Wed",
        Calendar.THURSDAY to "Thu",
        Calendar.FRIDAY to "Fri",
        Calendar.SATURDAY to "Sat",
        Calendar.SUNDAY to "Sun"
    )
    private val WEEKDAY_BY_NAME = WEEKDAY_NAMES.entries.associate { (k, v) -> v.uppercase() to k }

    sealed interface Rule {
        data object Daily : Rule
        data class Weekly(val days: Set<Int>) : Rule
        data class Monthly(val dayOfMonth: Int) : Rule
        data class EveryNDays(val n: Int) : Rule
    }

    /** Parses a stored rule string; null for blank/invalid input. Case-insensitive. */
    fun parse(rule: String?): Rule? {
        val clean = rule?.trim()?.uppercase() ?: return null
        if (clean.isEmpty()) return null
        if (clean == "DAILY") return Rule.Daily
        if (clean.startsWith("WEEKLY:")) {
            val days = clean.removePrefix("WEEKLY:").split(",")
                .map { it.trim() }
                .map { WEEKDAY_BY_NAME[it] ?: return null }
                .toSet()
            if (days.isEmpty()) return null
            return Rule.Weekly(days)
        }
        if (clean.startsWith("MONTHLY:")) {
            val day = clean.removePrefix("MONTHLY:").trim().toIntOrNull() ?: return null
            if (day !in 1..31) return null
            return Rule.Monthly(day)
        }
        if (clean.startsWith("EVERY_N_DAYS:")) {
            val n = clean.removePrefix("EVERY_N_DAYS:").trim().toIntOrNull() ?: return null
            if (n < 1) return null
            return Rule.EveryNDays(n)
        }
        return null
    }

    /** Serialises a rule back to its stored string form. */
    fun format(rule: Rule): String = when (rule) {
        is Rule.Daily -> "DAILY"
        is Rule.Weekly -> "WEEKLY:" + rule.days
            .sortedBy { (it + 5) % 7 } // Mon..Sun order for display
            .joinToString(",") { WEEKDAY_NAMES.getValue(it).uppercase() }
        is Rule.Monthly -> "MONTHLY:${rule.dayOfMonth}"
        is Rule.EveryNDays -> "EVERY_N_DAYS:${rule.n}"
    }

    /**
     * The next due instant for a task whose previous due was [fromDueMillis],
     * or null when the rule is invalid. The time-of-day comes from the
     * original due; the date steps forward until the candidate is strictly
     * after [nowMillis]. Iteration caps are defensive (a valid rule always
     * finds a candidate within them).
     */
    fun nextOccurrence(
        rule: String,
        fromDueMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        val parsed = parse(rule) ?: return null
        val anchor = Calendar.getInstance().apply { timeInMillis = fromDueMillis }
        val hour = anchor.get(Calendar.HOUR_OF_DAY)
        val minute = anchor.get(Calendar.MINUTE)
        val second = anchor.get(Calendar.SECOND)
        val millis = anchor.get(Calendar.MILLISECOND)

        fun at(year: Int, month: Int, day: Int): Long =
            Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, second)
                set(Calendar.MILLISECOND, millis)
            }.timeInMillis

        return when (parsed) {
            is Rule.Daily -> {
                var candidate = anchor.timeInMillis
                repeat(400) {
                    candidate = addDays(candidate, 1)
                    if (candidate > nowMillis) return candidate
                }
                null
            }
            is Rule.Weekly -> {
                var cursor = Calendar.getInstance().apply {
                    timeInMillis = fromDueMillis
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                repeat(400) {
                    val candidate = at(
                        cursor.get(Calendar.YEAR),
                        cursor.get(Calendar.MONTH),
                        cursor.get(Calendar.DAY_OF_MONTH)
                    )
                    if (cursor.get(Calendar.DAY_OF_WEEK) in parsed.days && candidate > nowMillis) {
                        return candidate
                    }
                    cursor.add(Calendar.DAY_OF_YEAR, 1)
                }
                null
            }
            is Rule.Monthly -> {
                var year = anchor.get(Calendar.YEAR)
                var month = anchor.get(Calendar.MONTH)
                repeat(24) {
                    month += 1
                    if (month > Calendar.DECEMBER) {
                        month = Calendar.JANUARY
                        year += 1
                    }
                    val maxDay = daysInMonth(year, month)
                    val candidate = at(year, month, minOf(parsed.dayOfMonth, maxDay))
                    if (candidate > nowMillis) return candidate
                }
                null
            }
            is Rule.EveryNDays -> {
                var candidate = anchor.timeInMillis
                repeat(400) {
                    candidate = addDays(candidate, parsed.n)
                    if (candidate > nowMillis) return candidate
                }
                null
            }
        }
    }

    /** Human label for the UI ("Every day", "Every Mon, Wed", ...). Null for invalid rules. */
    fun describe(rule: String): String? {
        return when (val parsed = parse(rule)) {
            null -> null
            is Rule.Daily -> "Every day"
            is Rule.Weekly -> "Every " + parsed.days
                .sortedBy { (it + 5) % 7 }
                .joinToString(", ") { WEEKDAY_NAMES.getValue(it) }
            is Rule.Monthly -> "Monthly on the ${ordinal(parsed.dayOfMonth)}"
            is Rule.EveryNDays -> if (parsed.n == 1) "Every day" else "Every ${parsed.n} days"
        }
    }

    private fun addDays(epochMillis: Long, days: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = epochMillis
            add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis

    private fun daysInMonth(year: Int, month: Int): Int =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)

    private fun ordinal(n: Int): String = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }
}
