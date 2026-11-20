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
 * Sprint 2-4 — the same single intent path now also carries date
 * links (DateLink) and create-event intents (InsertEvent) — spec §6.
 * The share flow below is untouched; the link flows mirror it
 * (pending state + consumed-once content hash in SavedStateHandle).
 *
 * Activity-scoped (MainActivity is the owner: it feeds intents in
 * from onCreate/onNewIntent, and NudgyApp reads the pending state
 * with hiltViewModel() at the root, so a date link navigates to the
 * Calendar and an insert opens its dialog over whatever tab the user
 * was on). No sign-in gate yet (Sprint 6) — a link works for the
 * active user, guest included ([TaskRepository.currentUserId]).
 *
 * Consumed-once, two layers (spec §5):
 * 1. [handleIntent] marks every parsed intent consumed in the
 *    SavedStateHandle the moment it is seen — rotation and a
 *    process-death restore re-offer the same intent (the system
 *    re-delivers it), and the stored key makes the second delivery a
 *    no-op. The key is the intent's content hash, so a *different*
 *    intent is never blocked by it.
 * 2. MainActivity clears its own intent after a NEW intent of any
 *    type is accepted (setIntent with an empty intent), so a plain
 *    rotation never even re-parses. Either layer alone would cover
 *    rotation; the SavedStateHandle flag is what also covers process
 *    death.
 *
 * Deliberate trade-off, recorded (same as shares): the consumed key
 * is never cleared within an app session. An identical date link /
 * insert arriving twice in one session drops the second — the spec's
 * "double share = one sheet" rule, applied strictly to links too. A
 * fresh app start (task swiped away) resets it.
 */
@HiltViewModel
class ShareIntentViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository
) : ViewModel() {

    /** The share awaiting confirmation (null = no sheet). */
    private val _pendingShare = MutableStateFlow<IncomingIntent.ShareText?>(null)
    val pendingShare: StateFlow<IncomingIntent.ShareText?> = _pendingShare.asStateFlow()

    /** Sprint 2-4 — the date link awaiting navigation (null = none). */
    private val _pendingDateLink = MutableStateFlow<IncomingIntent.DateLink?>(null)
    val pendingDateLink: StateFlow<IncomingIntent.DateLink?> = _pendingDateLink.asStateFlow()

    /** Sprint 2-4 — the create-event intent awaiting its dialog (null = none). */
    private val _pendingInsertEvent = MutableStateFlow<IncomingIntent.InsertEvent?>(null)
    val pendingInsertEvent: StateFlow<IncomingIntent.InsertEvent?> = _pendingInsertEvent.asStateFlow()

    /**
     * Offers an incoming intent to the intent flow. Returns true when
     * a NEW intent was accepted (the Activity should then clear its
     * intent); false when nothing was claimed — not an intent this app
     * handles, an empty share, an already-consumed intent, or a second
     * intent arriving while one of the same kind is already pending
     * (the second is marked consumed so it never resurfaces).
     */
    fun handleIntent(intent: Intent?): Boolean {
        val parsed = IntentHandler.parse(intent) ?: return false
        return when (parsed) {
            is IncomingIntent.ShareText -> handleShare(parsed)
            is IncomingIntent.DateLink -> handleDateLink(parsed)
            is IncomingIntent.InsertEvent -> handleInsertEvent(parsed)
            // GroupJoin: NavHost deep link — not claimed here; the
            // intent is left untouched.
            is IncomingIntent.GroupJoin -> false
        }
    }

    /** S2-3 — share routing, unchanged. */
    private fun handleShare(share: IncomingIntent.ShareText): Boolean {
        val key = shareKey(share)
        if (savedStateHandle.get<String>(KEY_CONSUMED_SHARE) == key) return false
        savedStateHandle[KEY_CONSUMED_SHARE] = key
        if (_pendingShare.value != null) return false
        _pendingShare.value = share
        return true
    }

    /** Sprint 2-4 — date-link routing (same consumed-once rule). */
    private fun handleDateLink(link: IncomingIntent.DateLink): Boolean {
        val key = dateLinkKey(link)
        if (savedStateHandle.get<String>(KEY_CONSUMED_DATE_LINK) == key) return false
        savedStateHandle[KEY_CONSUMED_DATE_LINK] = key
        if (_pendingDateLink.value != null) return false
        _pendingDateLink.value = link
        return true
    }

    /** Sprint 2-4 — create-event routing (same consumed-once rule). */
    private fun handleInsertEvent(event: IncomingIntent.InsertEvent): Boolean {
        val key = insertEventKey(event)
        if (savedStateHandle.get<String>(KEY_CONSUMED_INSERT_EVENT) == key) return false
        savedStateHandle[KEY_CONSUMED_INSERT_EVENT] = key
        if (_pendingInsertEvent.value != null) return false
        _pendingInsertEvent.value = event
        return true
    }

    /** Sprint 2-4 — the Calendar destination consumed the date link. */
    fun consumeDateLink() {
        _pendingDateLink.value = null
    }

    /** Sprint 2-4 — the Calendar destination consumed the insert. */
    fun consumeInsertEvent() {
        _pendingInsertEvent.value = null
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
        internal const val KEY_CONSUMED_DATE_LINK = "date_link_consumed_key"
        internal const val KEY_CONSUMED_INSERT_EVENT = "insert_event_consumed_key"

        /** Content hash of a share — the consumed-once key. */
        internal fun shareKey(share: IncomingIntent.ShareText): String {
            val bytes = "${share.title}\u0000${share.description.orEmpty()}"
                .toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }

        /** Content hash of a date link — the consumed-once key. */
        internal fun dateLinkKey(link: IncomingIntent.DateLink): String =
            hashParts(link.date, link.time, link.title)

        /** Content hash of a create-event intent — the consumed-once key. */
        internal fun insertEventKey(event: IncomingIntent.InsertEvent): String =
            hashParts(
                event.title,
                event.beginMillis?.toString(),
                event.endMillis?.toString(),
                event.location,
                event.description
            )

        /** SHA-256 over NUL-joined parts (null = empty). */
        private fun hashParts(vararg parts: String?): String {
            val bytes = parts.joinToString("\u0000") { it.orEmpty() }
                .toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
