package com.example

import android.app.Application
import com.example.analytics.NudgyRemoteConfig

/**
 * Application class: starts the Remote Config fetch in onCreate, the
 * earliest possible moment, so the fetch (and the ad preload that depends
 * on it) is already in flight while the splash screen shows. MainActivity
 * uses this single shared instance instead of constructing its own.
 */
class NudgyApplication : Application() {

    lateinit var remoteConfig: NudgyRemoteConfig
        private set

    override fun onCreate() {
        super.onCreate()
        remoteConfig = NudgyRemoteConfig()
    }
}
