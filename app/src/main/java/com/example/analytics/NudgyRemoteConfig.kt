package com.example.analytics

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
    val promoCardEnabled: Boolean = true,
    val promoHeadline: String = "Invite friends to Nudgy",
    val promoSubtitle: String = "Share daily rhythms & achieve streaks together.",
    val geminiModelVersion: String = "gemini-2.5-flash"
)

class NudgyRemoteConfig {
    private val remoteConfig: FirebaseRemoteConfig = try {
        Firebase.remoteConfig
    } catch (e: Exception) {
        Log.w(TAG, "RemoteConfig fallback: ${e.message}")
        FirebaseRemoteConfig.getInstance()
    }

    private val _configState = MutableStateFlow(RemoteConfigValues())
    val configState: StateFlow<RemoteConfigValues> = _configState.asStateFlow()

    init {
        try {
            val configSettings = remoteConfigSettings {
                minimumFetchIntervalInSeconds = 3600
            }
            remoteConfig.setConfigSettingsAsync(configSettings)

            val defaults = mapOf<String, Any>(
                KEY_ADS_ENABLED to false,
                KEY_AD_FREQUENCY to 5L,
                KEY_PROMO_CARD_ENABLED to true,
                KEY_PROMO_HEADLINE to "Invite friends to Nudgy",
                KEY_PROMO_SUBTITLE to "Share daily rhythms & achieve streaks together.",
                KEY_MODEL_VERSION to "gemini-2.5-flash"
            )
            remoteConfig.setDefaultsAsync(defaults)

            remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    _configState.value = RemoteConfigValues(
                        adsEnabled = remoteConfig.getBoolean(KEY_ADS_ENABLED),
                        adFrequencyTasks = remoteConfig.getLong(KEY_AD_FREQUENCY),
                        promoCardEnabled = remoteConfig.getBoolean(KEY_PROMO_CARD_ENABLED),
                        promoHeadline = remoteConfig.getString(KEY_PROMO_HEADLINE).ifEmpty { "Invite friends to Nudgy" },
                        promoSubtitle = remoteConfig.getString(KEY_PROMO_SUBTITLE).ifEmpty { "Share daily rhythms & achieve streaks together." },
                        geminiModelVersion = remoteConfig.getString(KEY_MODEL_VERSION).ifEmpty { "gemini-2.5-flash" }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote Config initialization warning: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NudgyRemoteConfig"
        const val KEY_ADS_ENABLED = "ads_enabled"
        const val KEY_AD_FREQUENCY = "ad_interstitial_task_frequency"
        const val KEY_PROMO_CARD_ENABLED = "promo_card_enabled"
        const val KEY_PROMO_HEADLINE = "promo_card_headline"
        const val KEY_PROMO_SUBTITLE = "promo_card_subtitle"
        const val KEY_MODEL_VERSION = "gemini_model_version"
    }
}
