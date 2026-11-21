package com.nextgentool.nudgy.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AdMob wiring — one preloaded interstitial, one preloaded rewarded ad,
 * and the anchored adaptive banner (see NudgyBannerAd).
 *
 * Flow: UMP consent first (legally required before requesting ads in
 * EEA/UK and good practice everywhere), then the Mobile Ads SDK, then
 * preloaded full-screen ads that reload after every show/failure.
 * Whether an ad is actually SHOWN is decided by the caller using Remote
 * Config (ads_enabled, default OFF, and the task-completion frequency),
 * so this object never shows anything on its own — except the rewarded
 * ad, which is shown only when the user taps "Watch ad for +1 turn" in
 * Chat after hitting the free daily cap.
 *
 * All unit IDs come from Remote Config (ad_interstitial_unit_id,
 * ad_banner_unit_id, ad_rewarded_unit_id). The values below are Google's
 * public TEST IDs: they are both the in-code defaults and the fallback
 * whenever a configured ID is empty or malformed, so test units are safe
 * to load and click during development and a bad console value can never
 * break ad loading. Swap the IDs in the Remote Config console — no app
 * update needed. The App ID in the manifest is separate and stays
 * hardcoded. Real unit IDs must NEVER be hardcoded here.
 */
object NudgyAds {
    private const val TAG = "NudgyAds"
    const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    // AdMob unit IDs look like ca-app-pub-<16 digits>/<10 digits>.
    private val UNIT_ID_REGEX = Regex("^ca-app-pub-\\d{16}/\\d{10}$")

    /**
     * Validates an ad unit ID; an empty or malformed ID falls back to
     * [fallback] (logged, never a crash).
     */
    fun validatedUnitId(unitId: String, fallback: String): String =
        if (UNIT_ID_REGEX.matches(unitId)) {
            unitId
        } else {
            Log.w(TAG, "Invalid ad unit id \"$unitId\" — falling back to test id")
            fallback
        }

    /** True once UMP consent allows ad requests (SDK start gate). */
    fun canRequestAds(context: Context): Boolean {
        return try {
            UserMessagingPlatform.getConsentInformation(context).canRequestAds()
        } catch (e: Exception) {
            false
        }
    }

    @Volatile
    private var interstitial: InterstitialAd? = null

    @Volatile
    private var loading = false

    @Volatile
    private var initialized = false

    // Last requested (validated) unit ID. All internal reloads reuse it,
    // so a shown real-ID ad is never reloaded with the test ID.
    @Volatile
    private var currentUnitId: String = TEST_INTERSTITIAL_UNIT_ID

    // Unit ID of the currently preloaded ad, if any.
    @Volatile
    private var loadedUnitId: String? = null

    // Unit ID of the load currently in flight, if any.
    @Volatile
    private var loadingUnitId: String? = null

    // A different ID requested while a load was in flight; applied when
    // that load finishes instead of being silently dropped.
    @Volatile
    private var pendingUnitId: String? = null

    fun initialize(activity: Activity) {
        if (initialized) return
        initialized = true
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    if (consentInformation.canRequestAds()) {
                        startSdk(activity)
                    }
                }
            },
            { error ->
                // A consent failure must never take the app down with it.
                Log.w(TAG, "Consent update failed: ${error.message}")
                if (consentInformation.canRequestAds()) {
                    startSdk(activity)
                }
            }
        )
        // Already-consented users skip straight to SDK start.
        if (consentInformation.canRequestAds()) {
            startSdk(activity)
        }
    }

    private fun startSdk(context: Context) {
        try {
            MobileAds.initialize(context.applicationContext) {
                loadInterstitial(context, currentUnitId)
                // Rewarded is preloaded here too — nothing else triggers a
                // first load (the MainActivity effect only reloads when the
                // Remote Config ID changes, and the Chat button is disabled
                // while no ad is ready).
                loadRewarded(context, currentRewardedUnitId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MobileAds init failed: ${e.message}")
        }
    }

    /**
     * Preloads an interstitial for [unitId]. An empty or malformed ID
     * falls back to the test ID (logged, never a crash). If an ad is
     * already preloaded for this ID, this is a no-op; if one is preloaded
     * for a different ID, it is dropped and reloaded with the new ID.
     * A different ID requested mid-load is queued and applied when the
     * in-flight load finishes.
     */
    fun loadInterstitial(context: Context, unitId: String = TEST_INTERSTITIAL_UNIT_ID) {
        val effectiveId = if (UNIT_ID_REGEX.matches(unitId)) {
            unitId
        } else {
            Log.w(TAG, "Invalid interstitial unit id \"$unitId\" — falling back to test id")
            TEST_INTERSTITIAL_UNIT_ID
        }
        currentUnitId = effectiveId
        if (interstitial != null) {
            if (loadedUnitId == effectiveId) return
            // Preloaded ad is for a stale ID — drop it and reload.
            interstitial = null
            loadedUnitId = null
        }
        if (loading) {
            if (loadingUnitId != effectiveId) {
                pendingUnitId = effectiveId
            }
            return
        }
        loading = true
        loadingUnitId = effectiveId
        try {
            InterstitialAd.load(
                context.applicationContext,
                effectiveId,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        val pending = pendingUnitId
                        if (pending != null && pending != effectiveId) {
                            // A newer ID arrived mid-load; skip this ad
                            // and load with the newer ID instead.
                            pendingUnitId = null
                            loading = false
                            loadingUnitId = null
                            loadInterstitial(context, pending)
                            return
                        }
                        pendingUnitId = null
                        interstitial = ad
                        loadedUnitId = effectiveId
                        loading = false
                        loadingUnitId = null
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitial = null
                        loadedUnitId = null
                        loading = false
                        loadingUnitId = null
                        Log.w(TAG, "Interstitial load failed: ${error.message}")
                        val pending = pendingUnitId
                        if (pending != null) {
                            pendingUnitId = null
                            loadInterstitial(context, pending)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            loading = false
            loadingUnitId = null
            Log.w(TAG, "Interstitial load error: ${e.message}")
            val pending = pendingUnitId
            if (pending != null) {
                pendingUnitId = null
                loadInterstitial(context, pending)
            }
        }
    }

    /** Shows the preloaded interstitial if one is ready. True when shown. */
    fun showInterstitial(activity: Activity): Boolean {
        val ad = interstitial ?: run {
            loadInterstitial(activity, currentUnitId)
            return false
        }
        interstitial = null
        loadedUnitId = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadInterstitial(activity, currentUnitId)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                loadInterstitial(activity, currentUnitId)
            }
        }
        return try {
            ad.show(activity)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Interstitial show failed: ${e.message}")
            loadInterstitial(activity, currentUnitId)
            false
        }
    }

    // ---- Rewarded (Chat: +1 AI turn after the free daily cap) ----

    @Volatile
    private var rewardedAd: RewardedAd? = null

    @Volatile
    private var rewardedLoading = false

    // Last requested (validated) rewarded unit ID. All reloads reuse it.
    @Volatile
    private var currentRewardedUnitId: String = TEST_REWARDED_UNIT_ID

    // Unit ID of the currently preloaded rewarded ad, if any.
    @Volatile
    private var loadedRewardedUnitId: String? = null

    // True when a rewarded ad is preloaded and ready to show. The Chat
    // screen disables the "Watch ad" button while this is false.
    private val _rewardedReady = MutableStateFlow(false)
    val rewardedReady: StateFlow<Boolean> = _rewardedReady.asStateFlow()

    // Last rewarded load failure, if any. Cleared when a load starts or
    // succeeds — the Chat screen shows a retry affordance while it is set,
    // so a failed load can never strand the button on "Loading ad…" forever.
    private val _rewardedLoadError = MutableStateFlow<String?>(null)
    val rewardedLoadError: StateFlow<String?> = _rewardedLoadError.asStateFlow()

    /**
     * Preloads a rewarded ad for [unitId]. An empty or malformed ID falls
     * back to the test ID (logged, never a crash). If an ad is already
     * preloaded for this ID, this is a no-op; a load already in flight is
     * left alone (the caller retries on show if nothing is ready).
     */
    fun loadRewarded(context: Context, unitId: String = TEST_REWARDED_UNIT_ID) {
        val effectiveId = validatedUnitId(unitId, TEST_REWARDED_UNIT_ID)
        currentRewardedUnitId = effectiveId
        if (rewardedAd != null) {
            if (loadedRewardedUnitId == effectiveId) return
            // Preloaded ad is for a stale ID — drop it and reload.
            rewardedAd = null
            loadedRewardedUnitId = null
            _rewardedReady.value = false
        }
        if (rewardedLoading) return
        rewardedLoading = true
        _rewardedLoadError.value = null
        try {
            RewardedAd.load(
                context.applicationContext,
                effectiveId,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        rewardedAd = ad
                        loadedRewardedUnitId = effectiveId
                        rewardedLoading = false
                        _rewardedReady.value = true
                        _rewardedLoadError.value = null
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        rewardedAd = null
                        loadedRewardedUnitId = null
                        rewardedLoading = false
                        _rewardedReady.value = false
                        _rewardedLoadError.value = error.message
                        Log.w(TAG, "Rewarded load failed: ${error.message}")
                    }
                }
            )
        } catch (e: Exception) {
            rewardedLoading = false
            _rewardedReady.value = false
            _rewardedLoadError.value = e.message
            Log.w(TAG, "Rewarded load error: ${e.message}")
        }
    }

    /**
     * Retries the rewarded preload with the LAST requested (validated)
     * unit id — the Chat screen's retry button. A plain loadRewarded()
     * call with no id would fall back to the TEST id and overwrite the
     * stored real id, so the retry must reuse the stored one.
     * Sprint 3-4 (rewarded entry wiring).
     */
    fun reloadRewarded(context: Context) {
        loadRewarded(context, currentRewardedUnitId)
    }

    /**
     * Shows the preloaded rewarded ad. [onRewardEarned] fires ONLY when
     * the user earns the reward (completed viewing) — never on load,
     * click, dismiss, or failure. Returns true when an ad was shown.
     * After the ad closes (or fails to show) a fresh ad is preloaded.
     * Callers must grant the reward only inside [onRewardEarned].
     */
    fun showRewarded(activity: Activity, onRewardEarned: () -> Unit): Boolean {
        val ad = rewardedAd ?: run {
            loadRewarded(activity, currentRewardedUnitId)
            return false
        }
        rewardedAd = null
        loadedRewardedUnitId = null
        _rewardedReady.value = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadRewarded(activity, currentRewardedUnitId)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                loadRewarded(activity, currentRewardedUnitId)
            }
        }
        return try {
            ad.show(activity) { onRewardEarned() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Rewarded show failed: ${e.message}")
            loadRewarded(activity, currentRewardedUnitId)
            false
        }
    }
}
