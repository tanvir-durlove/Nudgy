package com.nextgentool.nudgy.di

import android.content.Context
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.settings.ByokKeyStore
import com.nextgentool.nudgy.settings.CalendarConnection
import com.nextgentool.nudgy.settings.RealCalendarConnection
import com.nextgentool.nudgy.settings.RealSettingsAccountActions
import com.nextgentool.nudgy.settings.SettingsAccountActions
import com.nextgentool.nudgy.settings.SettingsPreferences
import com.nextgentool.nudgy.settings.SharedPreferencesByokKeyStore
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

    /**
     * Sprint 6-1 — the Settings account actions (sign-out + delete):
     * the single AuthManager behind the seam, so the Settings sign-out
     * and the Groups sign-in share one auth state.
     */
    @Provides
    @Singleton
    fun provideSettingsAccountActions(
        authManager: AuthManager
    ): SettingsAccountActions = RealSettingsAccountActions(authManager)

    /**
     * Sprint 6-1 — the BYOK key store: the v1 "nudgy_settings" file,
     * the same file SettingsPreferences reads.
     */
    @Provides
    @Singleton
    fun provideByokKeyStore(
        @ApplicationContext context: Context
    ): ByokKeyStore = SharedPreferencesByokKeyStore(
        context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    )

    /**
     * Sprint 6-1 — the calendar connection: the CalendarRepository
     * behind the seam, so the Settings row and the Calendar tab read
     * the same permission state.
     */
    @Provides
    @Singleton
    fun provideCalendarConnection(
        calendarRepository: CalendarRepository
    ): CalendarConnection = RealCalendarConnection(calendarRepository)
}
