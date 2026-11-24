package com.nextgentool.nudgy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.SignInScreen
import com.nextgentool.nudgy.settings.SettingsPreferences
import com.nextgentool.nudgy.ui.navigation.NudgyApp
import com.nextgentool.nudgy.ui.viewmodel.ShareIntentViewModel
import com.nextgentool.nudgy.work.GroupPollWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Sprint 1-0 — the app root is now NudgyApp() (NavHost + bottom nav).
 *
 * The old 1,494-line MainActivity (DI, chat handling, ads, sheets,
 * state-based navigation) is NOT moved here — it lives on in the git
 * history (scratch/rebuild-v2 @ 859cb4bc and earlier) and its behaviour
 * is rebuilt screen by screen in Sprint 1–6. What this step knowingly
 * drops until its sprint lands: sign-in gate, splash timing, ads,
 * and the App Links join-code prefill while signed out (the
 * /nudgy/join deep link itself still routes via the NavHost).
 * NudgyApp() owns the theme (NudgyTheme) so this file stays
 * a one-line root.
 * Sprint 5-3 wires the notification tap → group detail (the dropped
 * item from this list) through the same one-shot pattern as the
 * date link: the worker's launch intent carries the group id, the
 * Activity holds it as Compose state, NudgyApp navigates to Groups,
 * and the Groups destination opens the detail and consumes it.
 *
 * The @AndroidEntryPoint annotation is REQUIRED, not decorative: the
 * screens use hiltViewModel(), and without it the app crashes on first
 * use ("does not implement GeneratedComponentManager", S1-1 2026-11-16).
 * MainActivityEntryPointTest fails if it is ever dropped.
 *
 * Sprint 2-3 — share-to-task (Feature 12, spec §5): this is the
 * single intent owner. The Activity parses nothing itself; it feeds
 * its initial intent (onCreate) and every later intent (onNewIntent,
 * singleTop) to ShareIntentViewModel, which decides what to claim.
 * The group-join deep link is NOT handled here — it still routes via
 * the NavHost deep link (GROUP_JOIN_DEEP_LINK).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val shareIntentViewModel: ShareIntentViewModel by viewModels()

    // Sprint 5 fix round — the single auth seam (device test on ci-792:
    // nothing ever constructed an AuthManager, so a returning session
    // never restored and Groups had no sign-in). Hilt owns the
    // @Singleton; the call below is the v1 one.
    @Inject
    lateinit var groupAuth: GroupAuth

    // Sprint 6-1 — the same @Singleton AuthManager the seam wraps,
    // for the first-run sign-in gate (the carried SignInScreen takes
    // the AuthManager directly, v1 MainActivity pattern).
    @Inject
    lateinit var authManager: AuthManager

    // Sprint 6-0 — the Settings store (foundation): the single
    // @Singleton that reads "nudgy_settings". Its themeMode flow is
    // collected below and passed to NudgyApp, so an S6-2 Appearance
    // change recomposes the theme with no restart.
    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    /** Sprint 5-3 — pending group-notification tap (group id, one-shot). */
    private var openGroupId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sprint 5 fix round — restore a returning Google session at
        // startup (v1 MainActivity did the same); a guest stays guest.
        groupAuth.attemptSilentSignIn(lifecycleScope)
        handleIncomingIntent(intent)
        setContent {
            // Sprint 6-0 — the theme mode is a live flow off the
            // store, not a value captured once at launch (the PR #101
            // lesson: a value frozen at construction never follows a
            // later change).
            val themeMode by settingsPreferences.themeMode.collectAsState()
            // Sprint 6-1 — the first-run sign-in gate (v1 MainActivity
            // pattern, spec §5): no auth info yet means the carried
            // SignInScreen full-screen — "Warm Welcome", Google button
            // + a plain "Continue as Guest" button. A returning user
            // never sees it: AuthManager seeds userState synchronously
            // from the cached Firebase user or the guest flag, so the
            // gate only opens on a truly fresh install (or after a
            // sign-out, when the state goes null again). Signing in or
            // continuing as guest flips the flow and the gate opens
            // the app with no restart.
            val user by groupAuth.userState.collectAsState()
            if (user == null) {
                val isLoading by authManager.isLoading.collectAsState()
                val errorMessage by authManager.errorMessage.collectAsState()
                SignInScreen(
                    authManager = authManager,
                    scope = lifecycleScope,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    onSignInSuccess = {}
                )
            } else {
                NudgyApp(
                    themeMode = themeMode,
                    openGroupId = openGroupId,
                    onOpenGroupIdConsumed = { openGroupId = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Without setIntent, getIntent() keeps returning the ORIGINAL
        // intent — the new one would never be seen.
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Hands the intent to the share flow. When a NEW share is
     * accepted, the Activity's intent is cleared — a plain rotation
     * then never re-offers it (the ViewModel's SavedStateHandle flag
     * is the second layer, covering process-death restore).
     *
     * Sprint 5-3 — a group-notification tap also arrives here (the
     * worker's launch intent carries the group id): it is read into
     * Compose state, never into the share flow, and consumed by the
     * Groups destination once the detail opens.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        intent?.getStringExtra(GroupPollWorker.EXTRA_OPEN_GROUP_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { openGroupId = it }
        if (shareIntentViewModel.handleIntent(intent)) {
            setIntent(Intent())
        }
    }
}
