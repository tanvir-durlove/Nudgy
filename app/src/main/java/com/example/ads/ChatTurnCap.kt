package com.example.ads

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Free daily chat-turn cap + rewarded bonus turns.
 *
 * There was no cap enforcement in code before this file: every Gemini
 * call was free and uncapped. The cap gates ONLY Gemini turns (the
 * Unhandled branch in MainActivity.handleUserChat). Local intent handling
 * (ClientIntentPreFilter) never consumes a turn and is never blocked.
 *
 * Rules (limits come from Remote Config; see NudgyRemoteConfig):
 * - Each user gets [freeTurnsPerDay] free Gemini turns per local day.
 * - Once those are spent, a completed rewarded ad banks ONE bonus turn,
 *   up to [maxRewardedTurnsPerDay] rewarded grants per day.
 * - A bonus turn is spent on the next Gemini call; it does not resend a
 *   pending prompt — the user sends their message again.
 * - Counters reset on the local calendar day (yyyy-MM-dd). All state
 *   lives in SharedPreferences "nudgy_ads" (same file as the interstitial
 *   counters) — no server, no account, no extra permission.
 */
data class ChatCapState(
    val freeTurnsUsed: Int,
    val freeTurnsPerDay: Long,
    val bonusTurns: Int,
    val rewardedTurnsGrantedToday: Int,
    val maxRewardedTurnsPerDay: Long
) {
    /** True when the next Gemini turn is blocked: free allowance spent and no bonus turn banked. */
    val isCapped: Boolean get() = freeTurnsUsed >= freeTurnsPerDay && bonusTurns <= 0

    /** True when another rewarded turn may still be earned today. */
    val canEarnMoreRewarded: Boolean get() = rewardedTurnsGrantedToday < maxRewardedTurnsPerDay
}

enum class ChatTurnConsumeResult { FREE, BONUS, CAPPED }

object ChatTurnCap {
    private const val PREFS = "nudgy_ads"
    private const val KEY_DATE = "chat_turns_date"
    private const val KEY_FREE_USED = "chat_free_turns_used"
    private const val KEY_BONUS = "chat_bonus_turns"
    private const val KEY_REWARDED_TODAY = "chat_rewarded_turns_today"

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Reads today's counters, resetting them when the stored day is not today. */
    fun snapshot(context: Context, freeTurnsPerDay: Long, maxRewardedTurnsPerDay: Long): ChatCapState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = today()
        if (prefs.getString(KEY_DATE, null) != today) {
            prefs.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_FREE_USED, 0)
                .putInt(KEY_BONUS, 0)
                .putInt(KEY_REWARDED_TODAY, 0)
                .apply()
            return ChatCapState(0, freeTurnsPerDay, 0, 0, maxRewardedTurnsPerDay)
        }
        return ChatCapState(
            freeTurnsUsed = prefs.getInt(KEY_FREE_USED, 0),
            freeTurnsPerDay = freeTurnsPerDay,
            bonusTurns = prefs.getInt(KEY_BONUS, 0),
            rewardedTurnsGrantedToday = prefs.getInt(KEY_REWARDED_TODAY, 0),
            maxRewardedTurnsPerDay = maxRewardedTurnsPerDay
        )
    }

    /**
     * Spends one turn for a Gemini call: a free turn first, then a banked
     * bonus turn. CAPPED when neither is available — the caller must not
     * call Gemini in that case.
     */
    fun tryConsumeTurn(
        context: Context,
        freeTurnsPerDay: Long,
        maxRewardedTurnsPerDay: Long
    ): ChatTurnConsumeResult {
        val state = snapshot(context, freeTurnsPerDay, maxRewardedTurnsPerDay)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when {
            state.freeTurnsUsed < state.freeTurnsPerDay -> {
                prefs.edit().putInt(KEY_FREE_USED, state.freeTurnsUsed + 1).apply()
                ChatTurnConsumeResult.FREE
            }
            state.bonusTurns > 0 -> {
                prefs.edit().putInt(KEY_BONUS, state.bonusTurns - 1).apply()
                ChatTurnConsumeResult.BONUS
            }
            else -> ChatTurnConsumeResult.CAPPED
        }
    }

    /**
     * Banks one bonus turn after a completed rewarded ad. Returns false
     * (and banks nothing) when today's rewarded-grant cap is reached.
     * Call ONLY from the rewarded ad's onUserEarnedReward callback.
     */
    fun grantRewardedTurn(
        context: Context,
        freeTurnsPerDay: Long,
        maxRewardedTurnsPerDay: Long
    ): Boolean {
        val state = snapshot(context, freeTurnsPerDay, maxRewardedTurnsPerDay)
        if (!state.canEarnMoreRewarded) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BONUS, state.bonusTurns + 1)
            .putInt(KEY_REWARDED_TODAY, state.rewardedTurnsGrantedToday + 1)
            .apply()
        return true
    }
}
