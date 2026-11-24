package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.settings.SettingsPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Sprint 6-3 — the onboarding finish (feature 40, Q3=(a), spec §6).
 *
 * v1 was SILENT here (checkAndSeedInitialData was deliberately an
 * empty no-op with no callers), so this is new code, not a carry.
 * The tutorial ends exactly one way: [finish]. Skipping calls it
 * with includeSamples = false; finishing with the sample offer
 * checked calls it with true.
 *
 * Exactly-once, in this order: the has_seen_onboarding flag is set
 * FIRST (it is the gate — once it is true the tutorial never shows
 * again), then the samples seed only if this user has none yet.
 * A double-tap on Finish cannot double-seed: the [AtomicBoolean]
 * guards the seed block in-memory, and the "already has samples"
 * query guards it across process restarts. Re-viewing the tutorial
 * from Settings never reaches this ViewModel's seed — the re-view
 * path only dismisses (see OnboardingScreen's isReView).
 *
 * Sample tasks are local-only personal rows (groupId null, no
 * reminder — nothing fires from them); they carry isSample, so the
 * rows show the "Sample" chip and the one-tap delete.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val settingsPreferences: SettingsPreferences
) : ViewModel() {

    private val seedAttempted = AtomicBoolean(false)

    fun finish(includeSamples: Boolean) {
        viewModelScope.launch {
            // The gate first: even if the seed below fails, the
            // tutorial must not show again (and must not re-seed).
            settingsPreferences.setHasSeenOnboarding(true)
            if (includeSamples && seedAttempted.compareAndSet(false, true)) {
                seedSampleTasks()
            }
        }
    }

    private suspend fun seedSampleTasks() {
        val userId = taskRepository.currentUserId()
        // A previous finish (before a process death, or a re-view
        // that somehow reached here) already seeded: never duplicate.
        val existing = taskRepository.getAllTasksOnce(userId)
        if (existing.any { it.isSample }) return
        val now = System.currentTimeMillis()
        ONBOARDING_SAMPLE_TASKS.forEach { sample ->
            taskRepository.createTask(
                userId = userId,
                title = sample.title,
                dueEpochMillis = now + sample.dueOffsetMillis,
                priority = Priority.MEDIUM,
                category = TaskCategory.PERSONAL,
                isSample = true
            )
        }
    }

    companion object {
        /**
         * The three sample tasks (spec §6): plain demo tasks due
         * later today, so they show up on Today/Upcoming right away
         * and are offered in Plan. No reminder on any of them.
         */
        val ONBOARDING_SAMPLE_TASKS: List<OnboardingSampleTask> = listOf(
            OnboardingSampleTask("Morning walk", 2 * 60 * 60 * 1000L),
            OnboardingSampleTask("Drink a glass of water", 4 * 60 * 60 * 1000L),
            OnboardingSampleTask("Read 10 pages", 6 * 60 * 60 * 1000L)
        )
    }
}

/** One sample task: its title + how far after the seed it is due. */
data class OnboardingSampleTask(
    val title: String,
    val dueOffsetMillis: Long
)
