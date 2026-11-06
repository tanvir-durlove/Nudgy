package com.example.ads

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
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * AdMob wiring — interstitial only, per the product decision (no banners).
 *
 * Flow: UMP consent first (legally required before requesting ads in
 * EEA/UK and good practice everywhere), then the Mobile Ads SDK, then a
 * single preloaded interstitial that reloads after every show/failure.
 * Whether an ad is actually SHOWN is decided by the caller using Remote
 * Config (ads_enabled, default OFF, and the task-completion frequency), so
 * this object never shows anything on its own.
 *
 * The interstitial unit ID comes from Remote Config
 * (ad_interstitial_unit_id). The value below is Google's public TEST
 * interstitial: it is both the in-code default and the fallback whenever
 * the configured ID is empty or malformed, so test units are safe to load
 * and click during development and a bad console value can never break
 * ad loading. Swap the ID in the Remote Config console — no app update
 * needed. The App ID in the manifest is separate and stays hardcoded.
 */
object NudgyAds {
    private const val TAG = "NudgyAds"
    const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    // AdMob unit IDs look like ca-app-pub-<16 digits>/<10 digits>.
    private val UNIT_ID_REGEX = Regex("^ca-app-pub-\\d{16}/\\d{10}$")

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
}
