package com.nextgentool.nudgy.settings

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * Sprint 6-6 (step 4a) — the app-lock gate: in-memory only, never
 * persisted.
 *
 * The unlock lives only for this foreground session: MainActivity
 * marks it locked again in onStop (a real backgrounding — a rotation
 * does NOT re-lock, see MainActivity.onStop), so the next foreground
 * return prompts again. A process death restarts locked, which is
 * the correct behaviour. The preference (lock ON/OFF) lives in
 * SettingsPreferences; this class only tracks "unlocked yet this
 * session".
 *
 * The enable-time prompt ("turning the toggle ON asks for
 * biometric/PIN once, to prove it is the owner") belongs to the
 * Settings toggle itself — step 4d, not here.
 *
 * Pure logic, plain-JUnit testable (no Robolectric): the state is a
 * Compose State only so MainActivity's setContent recomposes when
 * authentication succeeds — reading/writing it needs no composition.
 */
class AppLockGate {

    private val _isUnlocked = mutableStateOf(false)

    /** True once the user authenticated this foreground session. */
    val isUnlocked: State<Boolean> = _isUnlocked

    /**
     * Whether the system prompt must be shown: the lock is enabled
     * and this session has not authenticated yet.
     */
    fun shouldPrompt(lockEnabled: Boolean): Boolean =
        lockEnabled && !_isUnlocked.value

    /** The user authenticated — the app stays open this session. */
    fun onAuthenticated() {
        _isUnlocked.value = true
    }

    /** The app went to background — the next return prompts again. */
    fun onBackgrounded() {
        _isUnlocked.value = false
    }
}
