package com.nextgentool.nudgy.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 2-3 — the rewarded unit-id guard: only a real (non-test) id may
 * ever trigger a rewarded load in production. The Remote Config
 * defaults ARE the test ids, so a fetch that hasn't completed (or
 * failed) must skip the load instead of falling back to a test ad.
 *
 * Pure-function test — [NudgyAds.isRealRewardedUnitId] touches only a
 * regex and a string constant, no SDK calls, so no Robolectric is
 * needed. The wiring itself (MainActivity's initialize call + RC feed
 * collector) cannot run in a unit test — MainActivity is
 * @AndroidEntryPoint and is never launched here (see
 * MainActivitySplashTest) — so CI compile + the batch-end device
 * test prove that part: exhaust the 5 free turns, the Watch-ad
 * button enables, a real rewarded ad plays, +1 turn is granted.
 */
class NudgyAdsTest {

    @Test
    fun `rewarded test id is never a real id`() {
        // The in-code default / RC default: feeding this would load a
        // test ad in production.
        assertFalse(NudgyAds.isRealRewardedUnitId(NudgyAds.TEST_REWARDED_UNIT_ID))
    }

    @Test
    fun `blank and malformed ids are never real ids`() {
        assertFalse(NudgyAds.isRealRewardedUnitId(""))
        assertFalse(NudgyAds.isRealRewardedUnitId("not-an-ad-unit-id"))
        // Right shape family, wrong digit counts.
        assertFalse(NudgyAds.isRealRewardedUnitId("ca-app-pub-123/456"))
    }

    @Test
    fun `production Remote Config rewarded id is real`() {
        // Remote Config production v6: ad_rewarded_unit_id.
        assertTrue(NudgyAds.isRealRewardedUnitId("ca-app-pub-7794111343358988/2849033263"))
    }
}
