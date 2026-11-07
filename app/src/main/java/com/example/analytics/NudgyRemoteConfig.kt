package com.example.analytics

import android.os.SystemClock
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RemoteConfigValues(
    val adsEnabled: Boolean = false,
    val adFrequencyTasks: Long = 5L,
    val adInterstitialUnitId: String = "ca-app-pub-3940256099942544/1033173712",
    val adBannerUnitId: String = "ca-app-pub-3940256099942544/6300978111",
    val adRewardedUnitId: String = "ca-app-pub-3940256099942544/5224354917",
    // Free Gemini turns per local day (0 = unlimited is NOT allowed: a
    // non-positive fetched value falls back to the default, see below).
    val chatFreeTurnsPerDay: Long = 5L,
    // Max rewarded (+1 turn) grants per local day.
    val adRewardedMaxTurnsPerDay: Long = 3L,
    val promoCardEnabled: Boolean = true,
    val promoHeadline: String = "Invite friends to Nudgy",
    val promoSubtitle: String = "Share daily rhythms & achieve streaks together.",
    val geminiModelVersion: String = "gemini-3.5-flash-lite"
)

class NudgyRemoteConfig {
    // Nullable: Firebase is unavailable when google-services.json is absent (e.g. CI builds).
    private val remoteConfig: FirebaseRemoteConfig? = try {
        Firebase.remoteConfig
    } catch (e: Exception) {
        Log.w(TAG, "RemoteConfig unavailable: ${e.message}")
        null
    }

    private val _configState = MutableStateFlow(RemoteConfigValues())
    val configState: StateFlow<RemoteConfigValues> = _configState.asStateFlow()

    // Splash: true once the first fetchAndActivate completes — success or
    // failure. MainActivity keeps the splash screen up only until this is
    // true or the splash timeout elapses, whichever comes first. A failed
    // fetch must never hold the launch hostage: defaults/cached values
    // apply instead.
    private val _fetchCompleted = MutableStateFlow(false)
    val fetchCompleted: StateFlow<Boolean> = _fetchCompleted.asStateFlow()

    // Elapsed-realtime clock at fetch start (Application.onCreate), so the
    // splash timeout is measured from app start, not from Activity creation.
    val fetchStartElapsedRealtime: Long = SystemClock.elapsedRealtime()

    init {
        // If Firebase is unavailable (no google-services.json), keep the default values.
        remoteConfig?.let { rc ->
            try {
                val configSettings = remoteConfigSettings {
                    minimumFetchIntervalInSeconds = 3600
                }
                rc.setConfigSettingsAsync(configSettings)

                val defaults = mapOf<String, Any>(
                    KEY_ADS_ENABLED to false,
                    KEY_AD_FREQUENCY to 5L,
                    KEY_AD_INTERSTITIAL_UNIT_ID to "ca-app-pub-3940256099942544/1033173712",
                    KEY_AD_BANNER_UNIT_ID to "ca-app-pub-3940256099942544/6300978111",
                    KEY_AD_REWARDED_UNIT_ID to "ca-app-pub-3940256099942544/5224354917",
                    KEY_CHAT_FREE_TURNS_PER_DAY to 5L,
                    KEY_AD_REWARDED_MAX_TURNS_PER_DAY to 3L,
                    KEY_PROMO_CARD_ENABLED to true,
                    KEY_PROMO_HEADLINE to "Invite friends to Nudgy",
                    KEY_PROMO_SUBTITLE to "Share daily rhythms & achieve streaks together.",
                    KEY_MODEL_VERSION to "gemini-3.5-flash-lite"
                )
                rc.setDefaultsAsync(defaults)

                rc.fetchAndActivate().addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val fetchedModel = rc.getString(KEY_MODEL_VERSION)
                        val sanitizedModel = when {
                            fetchedModel.isEmpty() || fetchedModel.contains("1.5") || fetchedModel.contains("2.0") || fetchedModel.contains("3.1") -> "gemini-3.5-flash-lite"
                            else -> fetchedModel
                        }
                        _configState.value = RemoteConfigValues(
                            adsEnabled = rc.getBoolean(KEY_ADS_ENABLED),
                            adFrequencyTasks = rc.getLong(KEY_AD_FREQUENCY),
                            adInterstitialUnitId = rc.getString(KEY_AD_INTERSTITIAL_UNIT_ID).ifEmpty { "ca-app-pub-3940256099942544/1033173712" },
                            adBannerUnitId = rc.getString(KEY_AD_BANNER_UNIT_ID).ifEmpty { "ca-app-pub-3940256099942544/6300978111" },
                            adRewardedUnitId = rc.getString(KEY_AD_REWARDED_UNIT_ID).ifEmpty { "ca-app-pub-3940256099942544/5224354917" },
                            chatFreeTurnsPerDay = rc.getLong(KEY_CHAT_FREE_TURNS_PER_DAY).takeIf { it > 0 } ?: 5L,
                            adRewardedMaxTurnsPerDay = rc.getLong(KEY_AD_REWARDED_MAX_TURNS_PER_DAY).takeIf { it > 0 } ?: 3L,
                            promoCardEnabled = rc.getBoolean(KEY_PROMO_CARD_ENABLED),
                            promoHeadline = rc.getString(KEY_PROMO_HEADLINE).ifEmpty { "Invite friends to Nudgy" },
                            promoSubtitle = rc.getString(KEY_PROMO_SUBTITLE).ifEmpty { "Share daily rhythms & achieve streaks together." },
                            geminiModelVersion = sanitizedModel
                        )
                    }
                    // Success or failure: the fetch is done either way.
                    _fetchCompleted.value = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Remote Config initialization warning: ${e.message}")
                _fetchCompleted.value = true
            }
        } ?: run {
            // No Firebase (e.g. CI builds without google-services.json):
            // there is no fetch to wait for.
            _fetchCompleted.value = true
        }
    }

    companion object {
        private const val TAG = "NudgyRemoteConfig"
        const val KEY_ADS_ENABLED = "ads_enabled"
        const val KEY_AD_FREQUENCY = "ad_interstitial_task_frequency"
        const val KEY_AD_INTERSTITIAL_UNIT_ID = "ad_interstitial_unit_id"
        const val KEY_AD_BANNER_UNIT_ID = "ad_banner_unit_id"
        const val KEY_AD_REWARDED_UNIT_ID = "ad_rewarded_unit_id"
        const val KEY_CHAT_FREE_TURNS_PER_DAY = "chat_free_turns_per_day"
        const val KEY_AD_REWARDED_MAX_TURNS_PER_DAY = "ad_rewarded_max_turns_per_day"
        const val KEY_PROMO_CARD_ENABLED = "promo_card_enabled"
        const val KEY_PROMO_HEADLINE = "promo_card_headline"
        const val KEY_PROMO_SUBTITLE = "promo_card_subtitle"
        const val KEY_MODEL_VERSION = "gemini_model_version"
    }
}
