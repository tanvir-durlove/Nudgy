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
 * The ad unit below is Google's public TEST interstitial. Replace it with
 * the real AdMob unit id before ads are switched on in production — test
 * units are safe to load and click during development.
 */
object NudgyAds {
    private const val TAG = "NudgyAds"
    const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    @Volatile
    private var interstitial: InterstitialAd? = null

    @Volatile
    private var loading = false

    @Volatile
    private var initialized = false

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
                loadInterstitial(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MobileAds init failed: ${e.message}")
        }
    }

    fun loadInterstitial(context: Context) {
        if (interstitial != null || loading) return
        loading = true
        try {
            InterstitialAd.load(
                context.applicationContext,
                TEST_INTERSTITIAL_UNIT_ID,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitial = ad
                        loading = false
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitial = null
                        loading = false
                        Log.w(TAG, "Interstitial load failed: ${error.message}")
                    }
                }
            )
        } catch (e: Exception) {
            loading = false
            Log.w(TAG, "Interstitial load error: ${e.message}")
        }
    }

    /** Shows the preloaded interstitial if one is ready. True when shown. */
    fun showInterstitial(activity: Activity): Boolean {
        val ad = interstitial ?: run {
            loadInterstitial(activity)
            return false
        }
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadInterstitial(activity)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                loadInterstitial(activity)
            }
        }
        return try {
            ad.show(activity)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Interstitial show failed: ${e.message}")
            loadInterstitial(activity)
            false
        }
    }
}
