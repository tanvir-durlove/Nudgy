package com.nextgentool.nudgy.di

import android.content.Context
import com.nextgentool.nudgy.settings.SettingsPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Sprint 6-0 — Settings bindings (foundation).
 *
 * Exactly one SettingsPreferences exists (the @Singleton): it reads
 * the v1 "nudgy_settings" file, so the theme_mode v1 wrote and the
 * BYOK key the rebuild already stores are the same file this store
 * reads. MainActivity injects it and passes the live theme mode to
 * NudgyApp; S6-2's Appearance UI writes through the same instance,
 * so a theme change recomposes the app with no restart.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideSettingsPreferences(
        @ApplicationContext context: Context
    ): SettingsPreferences = SettingsPreferences.from(context)
}
