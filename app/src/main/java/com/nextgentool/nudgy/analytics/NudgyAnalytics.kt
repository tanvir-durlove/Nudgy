package com.nextgentool.nudgy.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics

class NudgyAnalytics(context: Context) {
    // Nullable: Firebase is unavailable when google-services.json is absent (e.g. CI builds).
    private val analytics: FirebaseAnalytics? = try {
        Firebase.analytics
    } catch (e: Exception) {
        Log.w(TAG, "Firebase Analytics unavailable: ${e.message}")
        null
    }

    fun logTaskCreated(title: String, priority: String, category: String, hasReminder: Boolean) {
        val bundle = Bundle().apply {
            putString("task_title_length", title.length.toString())
            putString("priority", priority)
            putString("category", category)
            putBoolean("has_reminder", hasReminder)
        }
        analytics?.logEvent("nudgy_task_created", bundle)
    }

    fun logTaskCompleted(taskId: String, streakCount: Int) {
        val bundle = Bundle().apply {
            putString("task_id", taskId)
            putInt("streak_count", streakCount)
        }
        analytics?.logEvent("nudgy_task_completed", bundle)
    }

    fun logVoiceUsed(language: String) {
        val bundle = Bundle().apply {
            putString("language", language)
        }
        analytics?.logEvent("nudgy_voice_command_executed", bundle)
    }

    fun logCalendarConnected() {
        analytics?.logEvent("nudgy_calendar_connected", Bundle())
    }

    fun logFocusTimeBlocked(durationMinutes: Int) {
        val bundle = Bundle().apply {
            putInt("duration_minutes", durationMinutes)
        }
        analytics?.logEvent("nudgy_focus_time_blocked", bundle)
    }

    fun logMorningBriefViewed() {
        analytics?.logEvent("nudgy_morning_brief_viewed", Bundle())
    }

    fun logPromoCardClicked(headline: String) {
        val bundle = Bundle().apply {
            putString("promo_headline", headline)
        }
        analytics?.logEvent("nudgy_promo_card_clicked", bundle)
    }

    fun logScreenViewed(screenName: String) {
        val bundle = Bundle().apply {
            putString("screen_name", screenName)
        }
        analytics?.logEvent("nudgy_screen_viewed", bundle)
    }

    fun logNudgeInteracted(action: String) {
        val bundle = Bundle().apply {
            putString("action", action)
        }
        analytics?.logEvent("nudgy_nudge_interacted", bundle)
    }

    companion object {
        private const val TAG = "NudgyAnalytics"
    }
}
