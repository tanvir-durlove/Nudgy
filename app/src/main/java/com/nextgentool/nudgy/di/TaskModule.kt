package com.nextgentool.nudgy.di

import android.content.Context
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.reminders.ReminderManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Sprint 1-1 — Hilt bindings for the Tasks data layer.
 *
 * TaskRepository and the UseCases are constructor-injected, so they need
 * no @Provides here — only the two objects they depend on that Hilt
 * cannot build itself: the Room database (built via its getInstance
 * singleton, which registers MIGRATION_7_8) and the ReminderManager
 * (the single entry point for alarms; the UI never touches AlarmManager).
 */
@Module
@InstallIn(SingletonComponent::class)
object TaskModule {

    @Provides
    @Singleton
    fun provideNudgyDatabase(@ApplicationContext context: Context): NudgyDatabase =
        NudgyDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideReminderManager(@ApplicationContext context: Context): ReminderManager =
        ReminderManager(context)
}
