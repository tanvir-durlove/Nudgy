package com.nextgentool.nudgy

import android.os.Bundle
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
}
