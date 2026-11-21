package com.nextgentool.nudgy.di

import android.content.Context
import com.nextgentool.nudgy.NudgyApplication
import com.nextgentool.nudgy.ai.ClientIntentPreFilter
import com.nextgentool.nudgy.ai.GeminiClient
import com.nextgentool.nudgy.ai.LlmConfigProvider
import com.nextgentool.nudgy.ai.LlmConnectivity
import com.nextgentool.nudgy.ai.LlmServerCounter
import com.nextgentool.nudgy.ai.LlmUsageCounter
import com.nextgentool.nudgy.ai.RemoteConfigLlmConfigProvider
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.ai.SystemLlmConnectivity
import com.nextgentool.nudgy.ai.VoiceAssistantManager
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.ChatRepository
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
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

    /**
     * Sprint 1-2 — the Today timeline reads today's calendar events
     * (read-only, spec §6 Feature 15). CalendarRepository is a plain
     * class (constructed manually elsewhere); this is its only Hilt
     * binding, so there is exactly one instance.
     */
    @Provides
    @Singleton
    fun provideCalendarRepository(
        @ApplicationContext context: Context,
        database: NudgyDatabase
    ): CalendarRepository = CalendarRepository(context, database)

    // Sprint 2-1: the CalendarViewModel needs group names for the
    // group badge on group events — same pattern as the calendar repo.
    @Provides
    @Singleton
    fun provideGroupRepository(
        @ApplicationContext context: Context,
        database: NudgyDatabase
    ): GroupRepository = GroupRepository(context, database)

    // Sprint 3-0 — the chat layer. ChatRepository / MemoryRepository /
    // ClientIntentPreFilter / VoiceAssistantManager are plain classes
    // (constructed manually elsewhere, like CalendarRepository), so
    // this module is their only Hilt binding — exactly one instance
    // each. The prefilter is @Singleton on purpose: the spec (§8.2)
    // wants ONE instance, owned by the ChatViewModel, so its
    // in-memory pending-clarification survives rotation.
    @Provides
    @Singleton
    fun provideChatRepository(
        @ApplicationContext context: Context,
        database: NudgyDatabase
    ): ChatRepository = ChatRepository(context, database)

    @Provides
    @Singleton
    fun provideMemoryRepository(
        @ApplicationContext context: Context,
        database: NudgyDatabase
    ): MemoryRepository = MemoryRepository(context, database)

    // Sprint 4-0 — the Focus + Habit data layer. Plain class (constructed
    // manually elsewhere, like CalendarRepository), so this module is
    // its only Hilt binding — exactly one instance. No UI uses it yet
    // (S4-1/S4-2 will); the binding lands now so the foundation is
    // usable the moment the first screen arrives.
    @Provides
    @Singleton
    fun provideFocusHabitRepository(
        @ApplicationContext context: Context,
        database: NudgyDatabase
    ): FocusHabitRepository = FocusHabitRepository(context, database)

    @Provides
    @Singleton
    fun provideClientIntentPreFilter(
        taskRepository: TaskRepository,
        calendarRepository: CalendarRepository,
        memoryRepository: MemoryRepository,
        @ApplicationContext context: Context
    ): ClientIntentPreFilter =
        ClientIntentPreFilter(taskRepository, calendarRepository, memoryRepository, context.assets)

    @Provides
    @Singleton
    fun provideVoiceAssistantManager(
        @ApplicationContext context: Context
    ): VoiceAssistantManager = VoiceAssistantManager(context)

    /** The reply sound (§4.4) is the VoiceAssistantManager itself —
     *  this binding only names it by its seam so tests can substitute
     *  a fake without touching RingtoneManager. */
    @Provides
    @Singleton
    fun provideReplySoundPlayer(
        voiceAssistantManager: VoiceAssistantManager
    ): ReplySoundPlayer = voiceAssistantManager

    /**
     * Sprint 3-4 — the voice-input seam: the ChatViewModel depends on
     * [VoiceInput], not the concrete manager, so the brain-dump
     * restart loop is testable with a fake. Same instance as the
     * manager above (the single recognizer owner).
     */
    @Provides
    @Singleton
    fun provideVoiceInput(
        voiceAssistantManager: VoiceAssistantManager
    ): com.nextgentool.nudgy.ai.VoiceInput = voiceAssistantManager

    /**
     * Sprint 3-4 — the rewarded-ad seam for the Chat cap entry. The
     * production gateway delegates to the NudgyAds singleton; tests
     * substitute a fake. See RewardedAdGateway for the known ads-init
     * gap (NudgyAds.initialize is never called in the rebuild yet).
     */
    @Provides
    @Singleton
    fun provideRewardedAdGateway(
        @ApplicationContext context: Context
    ): com.nextgentool.nudgy.ads.RewardedAdGateway =
        com.nextgentool.nudgy.ads.NudgyAdsRewardedGateway(context)

    /**
     * Sprint 3-0 — bound for S3-3, NOT called by the S3-0 pipeline.
     * The ChatViewModel takes no GeminiClient: there is no LLM in
     * S3-0, and the central-gate decision (Q1(a)) forbids wiring the
     * chain before S3-3 builds the cap chain + activation gate.
     */
    @Provides
    @Singleton
    fun provideGeminiClient(
        @ApplicationContext context: Context
    ): GeminiClient = GeminiClient(context)

    // Sprint 3-3 — the LLM fallback seams. The handler itself is
    // constructor-injected; these three providers are its seams so
    // tests can substitute fakes without Hilt.
    @Provides
    @Singleton
    fun provideLlmConfigProvider(
        @ApplicationContext context: Context
    ): LlmConfigProvider {
        // The Application owns the single NudgyRemoteConfig instance
        // (created in NudgyApplication.onCreate, before any ViewModel
        // exists); the provider reads its LIVE values per call, so a
        // Remote Config flip needs no restart and no code change.
        val remoteConfig = (context.applicationContext as NudgyApplication).remoteConfig
        return RemoteConfigLlmConfigProvider(remoteConfig)
    }

    @Provides
    @Singleton
    fun provideLlmServerCounter(): LlmServerCounter = LlmServerCounter { uid, bucket, freeCap, bonusCap ->
        LlmUsageCounter.tryConsumeServerTurn(uid, bucket, freeCap, bonusCap)
    }

    @Provides
    @Singleton
    fun provideLlmConnectivity(
        @ApplicationContext context: Context
    ): LlmConnectivity = SystemLlmConnectivity(context)
}
