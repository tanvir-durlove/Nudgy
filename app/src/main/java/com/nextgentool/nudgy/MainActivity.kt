package com.nextgentool.nudgy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.nextgentool.nudgy.ads.NudgyAds
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.SignInScreen
import com.nextgentool.nudgy.settings.AppLockGate
import com.nextgentool.nudgy.settings.AppLockPrompt
import com.nextgentool.nudgy.settings.SettingsPreferences
import com.nextgentool.nudgy.settings.AppLocale
import com.nextgentool.nudgy.ui.components.NudgySplashScreen
import com.nextgentool.nudgy.ui.navigation.NudgyApp
import com.nextgentool.nudgy.ui.screens.OnboardingScreen
import com.nextgentool.nudgy.ui.viewmodel.OnboardingViewModel
import com.nextgentool.nudgy.ui.viewmodel.ShareIntentViewModel
import com.nextgentool.nudgy.widget.refreshTodayWidget
import com.nextgentool.nudgy.work.GroupPollWorker
import com.nextgentool.nudgy.work.MorningBriefWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
 *
 * Sprint 6-6 (step 4a) — the optional app lock (spec §2 Q6=(a),
 * §8): when the lock is ON, the system BiometricPrompt shows on
 * launch and on every return from background (AppLockGate, in
 * memory only — a process death restarts locked). The superclass
 * is FragmentActivity, not ComponentActivity, because
 * BiometricPrompt has no ComponentActivity constructor. The lock
 * overlay sits above the sign-in/onboarding gates: a locked phone
 * never shows the sign-in screen. Notifications are untouched.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    companion object {
        /**
         * Sprint 6-5 (step 3b) — the widget "+" quick-add flag: the
         * widget's launch action carries this boolean extra (via
         * Glance ActionParameters, keyed by this name); MainActivity
         * reads it into one-shot Compose state, never into the share
         * flow. Same naming shape as GroupPollWorker's
         * EXTRA_OPEN_GROUP_ID.
         */
        const val EXTRA_OPEN_QUICK_ADD = "nudgy_open_quick_add"

        /**
         * Sprint 6-5 (step 3d) — the widget agenda-row tap: the row's
         * launch action carries this task-id extra (via Glance
         * ActionParameters, keyed by this name); MainActivity reads
         * it into one-shot Compose state, never into the share flow.
         * Same naming shape as EXTRA_OPEN_QUICK_ADD above.
         */
        const val EXTRA_OPEN_TASK_ID = "nudgy_open_task_id"
    }

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

    /** Sprint 6-5 (step 3b) — pending widget "+" tap (one-shot). */
    private var openQuickAdd by mutableStateOf(false)

    /** Sprint 6-5 (step 3d) — pending widget agenda-row tap (task id, one-shot). */
    private var openTaskId by mutableStateOf<String?>(null)

    /**
     * Sprint 6-6 (step 4a) — the app-lock gate (in-memory only:
     * unlocked this foreground session or not). Plain field, no
     * Hilt — nothing else needs it.
     */
    private val appLockGate = AppLockGate()

    /**
     * Sprint 6-6 (step 4a) — whether the next onResume may show the
     * lock prompt. True at first launch and after every real
     * backgrounding (onStop); cleared the moment the prompt is
     * shown, so a user cancel does NOT re-prompt instantly — the
     * lock overlay stays up with its Unlock button instead.
     */
    private var promptOnResume = true

    /**
     * Step 5a-wire — whether the launch splash is still showing.
     * Set once in onCreate from [shouldShowSplash]: true only on a
     * cold start, false on rotation / resume / process-death restore.
     * Cleared by the splash's onFinished (which also fires the lock
     * prompt, so the prompt never covers the brand moment).
     */
    private var showSplash by mutableStateOf(true)

    /**
     * Sprint 6-2 — per-app UI language (spec §2 Q10=(a)): the base
     * context carries the stored language, so a fresh launch already
     * renders Settings/Today/Tasks in the chosen language (string
     * resources resolve values-bn when "bn"). No Hilt here — the
     * prefs file is read directly; injection happens in onCreate.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase, AppLocale.storedCode(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // S7-DIAG — install the androidx SplashScreen API BEFORE
        // super.onCreate() (the required order). Without this call the
        // system splash still shows (from the theme attrs) but
        // postSplashScreenTheme NEVER applies, so MainActivity ran the
        // Splash theme for its whole life instead of swapping to
        // Theme.MyApplication (NoActionBar) after launch.
        //
        // DIAGNOSTIC — device verdict needed, not proven by this change
        // alone: this is the prime suspect for the black "Nudgy" bar on
        // fresh open (D1) and may also affect the launch insets (D2 —
        // clipped hero + missing bottom nav). The compiled resources
        // show BOTH themes are NoActionBar/NoTitle, so the theme swap
        // is not a certain bar fix — the device build must confirm
        // whether the bar (and the launch layout) is gone. If the bar
        // survives, the hypothesis is refuted and the next step is a
        // build that isolates the nav (force it visible) instead.
        // No setKeepOnScreenCondition: the system splash dismisses on
        // the first frame as before; the Compose brand splash
        // (NudgySplashScreen, ~950ms) still follows it unchanged.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Sprint 6-2 — the Morning Brief schedule (spec §2 Q8=(a)):
        // KEEP, safe on every launch; the worker no-ops while the
        // toggle is OFF, so no re-schedule is ever needed.
        MorningBriefWorker.schedule(this)
        // Sprint 5 fix round — restore a returning Google session at
        // startup (v1 MainActivity did the same); a guest stays guest.
        groupAuth.attemptSilentSignIn(lifecycleScope)
        // Step 2-3 — ads init (Chat rewarded +1 offer, spec §10.14):
        // NudgyAds.initialize was never called in the rebuild (ads was
        // knowingly dropped in Sprint 1-0), so no rewarded ad was ever
        // preloaded and the Chat "Watch ad" button sat disabled on
        // "Loading ad…" forever. initialize() is idempotent, so a
        // rotation (a second onCreate) is a no-op; the UMP consent
        // flow runs inside it. The Remote Config rewarded id is fed
        // once here (the fetch usually completed during the splash)
        // and again on every config change — but ONLY for a real id
        // (see NudgyAds.feedRewardedUnitId): the RC defaults ARE the
        // test ids, so a pending/failed fetch must never trigger a
        // test-ad load. No test launches this Activity (see
        // MainActivitySplashTest), so this wiring is verified by CI
        // compile + the batch-end device test, not by a unit test.
        val remoteConfig = (application as NudgyApplication).remoteConfig
        NudgyAds.feedRewardedUnitId(this, remoteConfig.configState.value.adRewardedUnitId)
        NudgyAds.initialize(this)
        lifecycleScope.launch {
            remoteConfig.configState.collect { values ->
                NudgyAds.feedRewardedUnitId(this@MainActivity, values.adRewardedUnitId)
            }
        }
        handleIncomingIntent(intent)
        // Step 5a-wire — the launch splash shows only on a cold
        // start (see shouldShowSplash): never on rotation, resume,
        // or a process-death restore.
        showSplash = shouldShowSplash(savedInstanceState)
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
            // Sprint 6-3 — the onboarding flag, live from the store
            // (same pattern as themeMode above: a value frozen at
            // construction would never follow the finish tap).
            val hasSeenOnboarding by settingsPreferences.hasSeenOnboarding.collectAsState()
            // Sprint 6-6 (step 4a) — the app lock sits ABOVE every
            // other gate: a locked phone shows only the opaque
            // overlay, never the sign-in screen, the tutorial, or
            // the app itself.
            val appLockEnabled by settingsPreferences.appLockEnabled.collectAsState()
            val unlocked by appLockGate.isUnlocked
            // Step 5a-wire — the launch splash sits ABOVE every
            // gate and BEFORE the app lock: it shows no user data
            // (brand mark only), so there is no privacy reason to
            // lock first, and hiding the brand moment behind the
            // lock on every launch would be worse UX for no gain.
            // The lock prompt is held until the splash finishes
            // (see maybePromptForAppLock), so after ~950ms the
            // lock overlay / sign-in / tutorial / app follows.
            if (showSplash) {
                NudgySplashScreen(
                    onFinished = {
                        showSplash = false
                        maybePromptForAppLock()
                    }
                )
            } else if (appLockEnabled && !unlocked) {
                AppLockScreen(onUnlockClick = { promptForAppLock() })
            } else if (user == null) {
                val isLoading by authManager.isLoading.collectAsState()
                val errorMessage by authManager.errorMessage.collectAsState()
                SignInScreen(
                    authManager = authManager,
                    scope = lifecycleScope,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    onSignInSuccess = {}
                )
            } else if (!hasSeenOnboarding) {
                // Sprint 6-3 — the first-run tutorial (spec §6): it
                // shows AFTER the sign-in gate (a guest who continues
                // as guest sees it too), and only once — finishing or
                // skipping sets has_seen_onboarding, so the next
                // launch goes straight to NudgyApp. A returning user
                // never sees it.
                val onboardingViewModel: OnboardingViewModel = hiltViewModel()
                OnboardingScreen(
                    isReView = false,
                    onFinish = { includeSamples ->
                        onboardingViewModel.finish(includeSamples)
                    }
                )
            } else {
                NudgyApp(
                    themeMode = themeMode,
                    openGroupId = openGroupId,
                    onOpenGroupIdConsumed = { openGroupId = null },
                    openQuickAdd = openQuickAdd,
                    onOpenQuickAddConsumed = { openQuickAdd = false },
                    openTaskId = openTaskId,
                    onOpenTaskIdConsumed = { openTaskId = null }
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
     * Sprint 6-5 (step 3c) — the widget's day-boundary refresh (plus
     * catch-up for background writes). The mutation hook in
     * TaskRepository covers changes made while the app is open, but
     * two cases need an app-open refresh: midnight rolling "today"
     * over with no data change at all, and direct DAO writes that
     * bypass TaskRepository (GroupRepository's group sync, the
     * ReminderReceiver's direct completion). Re-providing on every
     * foreground return is one Room read — the same cost as the
     * Today screen loading — and it keeps the widget honest without
     * a new scheduler (updatePeriodMillis stays 0).
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refreshTodayWidget(this@MainActivity) }
        // Sprint 6-6 (step 4a) — the lock prompt on launch and on
        // every return from background (guarded by promptOnResume,
        // so a cancel does not re-prompt instantly).
        maybePromptForAppLock()
    }

    /**
     * Sprint 6-6 (step 4a) — a real backgrounding re-locks the app:
     * the next foreground return prompts again. A rotation does NOT
     * re-lock (isChangingConfigurations) — rotating is not leaving
     * the app, and re-prompting on every rotation would be a defect.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            appLockGate.onBackgrounded()
            promptOnResume = true
        }
    }

    /**
     * Sprint 6-6 (step 4a) — the one-shot prompt trigger for
     * onResume: only when the flag says this foregrounding may
     * prompt (first launch, or a return from background).
     */
    private fun maybePromptForAppLock() {
        // Step 5a-wire — the system prompt must not cover the
        // launch splash: while the splash is showing, hold the
        // prompt (promptOnResume stays true) and let the splash's
        // onFinished fire it instead.
        if (showSplash) return
        if (!promptOnResume) return
        promptOnResume = false
        promptForAppLock()
    }

    /**
     * Sprint 6-6 (step 4a) — shows the system prompt when the lock
     * is ON and this session has not authenticated yet. No usable
     * authenticator (no biometric enrolled AND no secure lock
     * screen) fails OPEN — the gate records unlocked for the
     * session rather than bricking the user out of their own data
     * with no way in (see AppLockPrompt).
     */
    private fun promptForAppLock() {
        if (!appLockGate.shouldPrompt(settingsPreferences.appLockEnabled.value)) return
        if (!AppLockPrompt.canAuthenticate(this)) {
            appLockGate.onAuthenticated()
            return
        }
        AppLockPrompt.show(this, onSuccess = { appLockGate.onAuthenticated() })
    }

    /**
     * Sprint 6-6 (step 4d) — the Settings lock toggle's enable-time
     * prompt succeeded: the user just proved ownership, so this
     * foreground session counts as unlocked. Without this, turning
     * the lock ON would show the lock overlay at once (the gate
     * starts locked) and the user would authenticate a second time
     * to dismiss it. Called from the Settings route only, never from
     * the gate itself.
     */
    fun markAppLockUnlocked() {
        appLockGate.onAuthenticated()
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
     *
     * Sprint 6-5 (step 3b) — a widget "+" tap also arrives here (the
     * launch action carries EXTRA_OPEN_QUICK_ADD): it is read into
     * Compose state the same way, never into the share flow, and
     * consumed by the Today destination once the quick-add field
     * focuses. The share flow does not claim this intent (it is not
     * a SEND/VIEW/INSERT), so handleIntent returns false and the
     * intent is left alone.
     *
     * Sprint 6-5 (step 3d) — a widget agenda-row tap also arrives
     * here (the launch action carries EXTRA_OPEN_TASK_ID): it is
     * read into Compose state the same way, never into the share
     * flow, and consumed by the Tasks destination once the detail
     * sheet opens — the same 2e deep-link path the search tap uses.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        intent?.getStringExtra(GroupPollWorker.EXTRA_OPEN_GROUP_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { openGroupId = it }
        if (intent?.getBooleanExtra(EXTRA_OPEN_QUICK_ADD, false) == true) {
            openQuickAdd = true
        }
        intent?.getStringExtra(EXTRA_OPEN_TASK_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { openTaskId = it }
        if (shareIntentViewModel.handleIntent(intent)) {
            setIntent(Intent())
        }
    }
}

/**
 * Step 5a-wire — the launch splash shows once per cold start, and
 * never again in the same process lifetime:
 * - fresh launch (savedInstanceState == null) → show it;
 * - rotation (the Activity is recreated WITH saved state) → skip it,
 *   the user is mid-session, not launching;
 * - resume from background (no onCreate at all) → the showSplash
 *   field is already false, skip it;
 * - process-death restore (saved state comes back non-null) → skip
 *   it, the user resumes where they were instead of replaying the
 *   brand moment.
 *
 * Pure function on purpose (the repo's seam pattern): the rule is
 * unit-testable without launching the @AndroidEntryPoint Activity.
 */
internal fun shouldShowSplash(savedInstanceState: Bundle?): Boolean =
    savedInstanceState == null

/**
 * Sprint 6-6 (step 4a) — the opaque lock overlay: the locked app
 * shows nothing but this (no task titles, no sign-in screen
 * behind it). The prompt itself is triggered from onResume; this
 * button is the retry after a user cancel. The testTags are for
 * the batch-end device test — the prompt path is device-gated.
 */
@Composable
private fun AppLockScreen(onUnlockClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("app_lock_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_lock_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_lock_subtitle),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onUnlockClick,
                modifier = Modifier.testTag("app_lock_unlock_button")
            ) {
                Text(text = stringResource(R.string.app_lock_unlock))
            }
        }
    }
}
