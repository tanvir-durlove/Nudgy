package com.nextgentool.nudgy.focus

/**
 * Sprint 4-1 — the Pomodoro cycle, pure (spec §4, Q1=(a)).
 *
 * Standard lengths, no settings: 25 minutes focus / 5 minutes break,
 * and after every 4th completed focus session the next break is 15
 * minutes (long break). The count is "completed focus sessions today"
 * (device-local day — the same day the repository's
 * getCompletedFocusCountSince counts over), so the cycle restarts
 * every morning.
 *
 * Plain JUnit-tested (PomodoroCycleTest): no Android, no clock — the
 * caller passes instants in.
 */
object PomodoroCycle {

    /** One focus session (Q1=(a)). */
    const val FOCUS_MINUTES = 25

    /** The break after most focus sessions (Q1=(a)). */
    const val SHORT_BREAK_MINUTES = 5

    /** The break after every 4th completed focus session (Q1=(a)). */
    const val LONG_BREAK_MINUTES = 15

    /** Completed focus sessions per long break (Q1=(a)). */
    const val SESSIONS_BEFORE_LONG_BREAK = 4

    /**
     * The break that follows the next completed focus session: 15
     * minutes when [completedFocusToday] just hit a multiple of 4
     * (4th, 8th, ...), 5 minutes otherwise. 0 completed means no
     * session has finished yet, so the break is short.
     */
    fun breakMinutesAfter(completedFocusToday: Int): Int =
        if (completedFocusToday > 0 && completedFocusToday % SESSIONS_BEFORE_LONG_BREAK == 0) {
            LONG_BREAK_MINUTES
        } else {
            SHORT_BREAK_MINUTES
        }

    /** Millis left until [plannedEndEpochMillis], never negative. */
    fun remainingMillis(plannedEndEpochMillis: Long, now: Long): Long =
        (plannedEndEpochMillis - now).coerceAtLeast(0L)

    /** The timer has run out (the end signal fires at this point). */
    fun isFinished(plannedEndEpochMillis: Long, now: Long): Boolean =
        now >= plannedEndEpochMillis

    /** "MM:SS" for the countdown (25:00 at the start, 00:00 at the end). */
    fun formatRemaining(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
