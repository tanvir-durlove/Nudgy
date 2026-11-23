package com.nextgentool.nudgy.auth

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Sprint 5 fix round — the auth seam for Groups sign-in (device
 * test on ci-792: the Groups "Sign in" button did nothing).
 *
 * The Groups tab needs exactly two things from auth: the LIVE user
 * (so the guest prompt disappears right after sign-in, with no
 * restart) and a way to start the Google sign-in flow. The real
 * [AuthManager] cannot be driven in Robolectric — its user state
 * only moves on a real Firebase sign-in — so the ViewModel depends
 * on this seam and tests substitute a fake (see
 * GroupsViewModelTest.FakeGroupAuth). Production gets the single
 * [AuthManager] behind it, via [RealGroupAuth].
 */
interface GroupAuth {
    /** Live signed-in/guest user; null = no auth info yet (use the local fallback). */
    val userState: StateFlow<NudgyUser?>

    fun signIn(
        activity: Activity,
        scope: CoroutineScope,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    )

    fun attemptSilentSignIn(scope: CoroutineScope)
}

/** Production [GroupAuth]: the single [AuthManager], behind the seam. */
class RealGroupAuth(private val authManager: AuthManager) : GroupAuth {
    override val userState: StateFlow<NudgyUser?> = authManager.userState

    override fun signIn(
        activity: Activity,
        scope: CoroutineScope,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) = authManager.signInWithGoogle(activity, scope, onSuccess, onError)

    override fun attemptSilentSignIn(scope: CoroutineScope) =
        authManager.attemptSilentSignIn(scope)
}
