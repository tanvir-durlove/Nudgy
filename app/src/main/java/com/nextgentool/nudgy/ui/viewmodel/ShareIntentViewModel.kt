package com.nextgentool.nudgy.ui.viewmodel

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.intent.IncomingIntent
import com.nextgentool.nudgy.intent.IntentHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Sprint 2-3 — the share-intent state (Feature 12, spec §5).
 *
 * Activity-scoped (MainActivity is the owner: it feeds intents in
 * from onCreate/onNewIntent, and NudgyApp reads the pending share
 * with hiltViewModel() at the root, so the sheet opens over whatever
 * tab the user was on). No sign-in gate yet (Sprint 6) — a share
 * creates a local task for the active user, guest included
 * ([TaskRepository.currentUserId]).
 *
 * Consumed-once, two layers (spec §5):
 * 1. [handleIntent] marks every parsed share consumed in the
 *    SavedStateHandle the moment it is seen — rotation and a
 *    process-death restore re-offer the same intent (the system
 *    re-delivers it), and the stored key makes the second delivery a
 *    no-op. The key is the share's content hash, so a *different*
 *    share is never blocked by it.
 * 2. MainActivity clears its own intent after a share is accepted
 *    (setIntent with an empty intent), so a plain rotation never even
 *    re-parses. Either layer alone would cover rotation; the
 *    SavedStateHandle flag is what also covers process death.
 *
 * Deliberate trade-off, recorded: the consumed key is never cleared
 * within an app session. Sharing the *identical* text twice in one
 * session drops the second share — that is the spec's "double share
 * = one sheet" rule, applied strictly. A fresh app start (task
 * swiped away) resets it, so the same text shared on another day
 * works. The pending sheet itself survives rotation in the
 * ViewModel; a process death while it is open loses only the
 * unconfirmed sheet, never a task.
 */
@HiltViewModel
class ShareIntentViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository
) : ViewModel() {

    /** The share awaiting confirmation (null = no sheet). */
    private val _pendingShare = MutableStateFlow<IncomingIntent.ShareText?>(null)
    val pendingShare: StateFlow<IncomingIntent.ShareText?> = _pendingShare.asStateFlow()

    /**
     * Offers an incoming intent to the share flow. Returns true when a
     * NEW share was accepted (the Activity should then clear its
     * intent); false when nothing was claimed — not a share, an empty
     * share, an already-consumed share, or a second share arriving
     * while a sheet is already open (double share = one sheet; the
     * second share is marked consumed so it never resurfaces).
     */
    fun handleIntent(intent: Intent?): Boolean {
        val parsed = IntentHandler.parse(intent) ?: return false
        val share = when (parsed) {
            is IncomingIntent.ShareText -> parsed
            // DateLink / InsertEvent / GroupJoin: S2-4 / NavHost — not
            // claimed here; the intent is left untouched.
            else -> return false
        }
        val key = shareKey(share)
        if (savedStateHandle.get<String>(KEY_CONSUMED_SHARE) == key) return false
        savedStateHandle[KEY_CONSUMED_SHARE] = key
        if (_pendingShare.value != null) return false
        _pendingShare.value = share
        return true
    }

    /** Dismisses the pending share without creating anything. */
    fun dismissShare() {
        _pendingShare.value = null
    }

    /**
     * Confirms the pending share: creates the task with exactly the
     * values the sheet passes (title, description, due, priority,
     * category, reminder) through the repository's create path — the
     * same path every other task creation uses, so board order
     * (append at the end of To do), the no-date reminder rule and
     * scheduling all behave identically. Idempotent: the first call
     * clears the pending share, so a second confirm is a no-op and
     * can never create a duplicate.
     */
    fun confirmShare(
        title: String,
        description: String?,
        dueEpochMillis: Long,
        priority: Priority,
        category: TaskCategory,
        reminderSet: Boolean
    ) {
        if (_pendingShare.value == null) return
        _pendingShare.value = null
        if (title.isBlank()) return
        viewModelScope.launch {
            taskRepository.createTask(
                userId = taskRepository.currentUserId(),
                title = title.trim(),
                description = description?.trim()?.ifBlank { null },
                dueEpochMillis = dueEpochMillis,
                priority = priority,
                category = category,
                reminderSet = reminderSet
            )
        }
    }

    companion object {
        internal const val KEY_CONSUMED_SHARE = "share_intent_consumed_key"

        /** Content hash of a share — the consumed-once key. */
        internal fun shareKey(share: IncomingIntent.ShareText): String {
            val bytes = "${share.title}\u0000${share.description.orEmpty()}"
                .toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
