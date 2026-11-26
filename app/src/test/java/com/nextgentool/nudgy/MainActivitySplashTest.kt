package com.nextgentool.nudgy

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 5a-wire — the cold-start rule for the launch splash
 * (shouldShowSplash, MainActivity): the splash shows once per cold
 * start and never on rotation, resume, or a process-death restore.
 *
 * Pure-function test (the repo's seam pattern): no Activity launch
 * needed — MainActivity is @AndroidEntryPoint and cannot be rendered
 * in a unit test. The wiring itself (splash first, then lock /
 * sign-in / onboarding / app, prompt held until onFinished) is
 * verified by CI compile + the batch-end device test.
 *
 * S7-DIAG — the launch-theme wiring (installSplashScreen): the two
 * theme tests below pin what the fix relies on — the splash theme
 * swaps to the NoActionBar app theme. The installSplashScreen()
 * call itself cannot be tested without launching the
 * @AndroidEntryPoint Activity, so CI compile proves the call
 * resolves and the device build must confirm the black "Nudgy"
 * bar is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivitySplashTest {

    @Test
    fun `fresh launch shows the splash`() {
        assertTrue(shouldShowSplash(null))
    }

    @Test
    fun `recreated Activity skips the splash`() {
        // Rotation and process-death restore both arrive with a
        // non-null saved state: the user is mid-session, not
        // launching, so the brand moment does not replay.
        assertFalse(shouldShowSplash(Bundle()))
    }

    @Test
    fun `launch theme swaps to the app theme after the splash`() {
        // S7-DIAG: installSplashScreen() applies postSplashScreenTheme.
        // If this ever pointed anywhere but Theme.MyApplication, the
        // activity would keep the Splash theme for its whole life —
        // the exact latent bug this fix removes.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val attrs = context.theme.obtainStyledAttributes(
            R.style.Theme_Nudgy_Splash,
            intArrayOf(androidx.core.splashscreen.R.attr.postSplashScreenTheme)
        )
        val postSplashTheme = attrs.getResourceId(0, 0)
        attrs.recycle()
        assertEquals(
            "Theme.Nudgy.Splash must swap to Theme.MyApplication once " +
                "the splash is gone — the swap target is the NoActionBar " +
                "app theme, not the Splash theme (S7-DIAG)",
            R.style.Theme_MyApplication,
            postSplashTheme
        )
    }

    @Test
    fun `post-splash theme has no title bar and no action bar`() {
        // S7-DIAG: the swap target must not be able to draw the bar
        // itself — a title bar or action bar would render the app
        // label ("Nudgy") at the top of every screen. If someone
        // re-parents Theme.MyApplication to a bar theme, this fails.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val attrs = context.theme.obtainStyledAttributes(
            R.style.Theme_MyApplication,
            intArrayOf(
                android.R.attr.windowNoTitle,
                android.R.attr.windowActionBar
            )
        )
        val noTitle = attrs.getBoolean(0, false)
        val hasActionBar = attrs.getBoolean(1, true)
        attrs.recycle()
        assertTrue(
            "Theme.MyApplication must be NoTitle — otherwise the " +
                "activity itself would draw a title bar with the app " +
                "label (S7-DIAG)",
            noTitle
        )
        assertFalse(
            "Theme.MyApplication must be NoActionBar — otherwise the " +
                "activity would draw a 56dp action bar with the app " +
                "label (S7-DIAG)",
            hasActionBar
        )
    }
}
