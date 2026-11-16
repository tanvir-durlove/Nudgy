package com.example

import android.app.Application
import android.util.Log
import com.example.analytics.NudgyRemoteConfig
import dagger.hilt.android.HiltAndroidApp
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Application class: starts the Remote Config fetch in onCreate, the
 * earliest possible moment, so the fetch (and the ad preload that depends
 * on it) is already in flight while the splash screen shows. MainActivity
 * uses this single shared instance instead of constructing its own.
 *
 * Also installs the App Check provider (central Gemini key): Play
 * Integrity for release builds, the debug provider for debug builds.
 * App Check attests that Firebase calls (notably the Firebase AI Logic
 * backend behind the central Gemini key) come from a genuine app
 * install. Enforcement stays OFF in the Firebase console (monitor
 * mode) until the device test proves attestation passes — installing
 * the provider alone changes nothing server-side.
 */
@HiltAndroidApp
class NudgyApplication : Application() {

    lateinit var remoteConfig: NudgyRemoteConfig
        private set

    override fun onCreate() {
        super.onCreate()
        remoteConfig = NudgyRemoteConfig()
        try {
            Firebase.appCheck.installAppCheckProviderFactory(
                if (BuildConfig.DEBUG) DebugAppCheckProviderFactory.getInstance()
                else PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        } catch (e: Exception) {
            // Firebase unavailable (e.g. a build without
            // google-services.json): App Check simply stays off.
            Log.w("NudgyApplication", "App Check unavailable: ${e.message}")
        }
    }
}
