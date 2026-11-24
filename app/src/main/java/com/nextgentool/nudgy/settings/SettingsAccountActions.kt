package com.nextgentool.nudgy.settings

import com.nextgentool.nudgy.auth.AuthManager
import kotlinx.coroutines.CoroutineScope

/**
 * Sprint 6-1 — the account-actions seam for Settings (spec §5).
 *
 * Sign-out and account deletion live on [AuthManager], which cannot
 * be driven in Robolectric — so the SettingsViewModel depends on
 * this seam and tests substitute a fake. Production gets the single
 * [AuthManager] behind it (see [RealSettingsAccountActions]).
 *
 * This is deliberately NOT part of [com.nextgentool.nudgy.auth.GroupAuth]:
 * that seam is the Groups sign-in contract (live user + sign-in +
 * silent restore) and stays untouched. Sign-out/delete are Settings
 * actions with their own lifecycle (the delete runs in
 * AuthManager's own scope, never a composable's — v1 C7).
 */
interface SettingsAccountActions {

    fun signOut(scope: CoroutineScope, onComplete: () -> Unit = {})

    fun deleteAccount(onComplete: () -> Unit = {}, onError: (String) -> Unit = {})
}

/** Production [SettingsAccountActions]: the single [AuthManager], behind the seam. */
class RealSettingsAccountActions(private val authManager: AuthManager) : SettingsAccountActions {
    override fun signOut(scope: CoroutineScope, onComplete: () -> Unit) =
        authManager.signOut(scope, onComplete)

    override fun deleteAccount(onComplete: () -> Unit, onError: (String) -> Unit) =
        authManager.deleteAccount(onComplete, onError)
}
