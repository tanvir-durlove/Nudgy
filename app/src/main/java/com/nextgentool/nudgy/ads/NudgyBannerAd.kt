package com.nextgentool.nudgy.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * Anchored adaptive banner for the bottom of a tab. The unit ID comes
 * from Remote Config (ad_banner_unit_id); an empty or malformed ID falls
 * back to Google's public TEST banner ID, so a bad console value can
 * never break the layout or crash the app.
 *
 * The slot reserves real layout space (fixed height from the adaptive
 * size), so the banner never floats over content, a FAB, or the bottom
 * navigation bar. It is placed by the scaffold only on the four ad tabs
 * (Home, Tasks, Calendar, Groups) — never on Chat or over sheets.
 * A unit-ID change (or rotation, which changes the adaptive size)
 * recreates the AdView with the new ID.
 */
@Composable
fun NudgyBannerAd(unitId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adWidthDp = LocalConfiguration.current.screenWidthDp
    val adSize = remember(adWidthDp) {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidthDp)
    }
    val effectiveId = NudgyAds.validatedUnitId(unitId, NudgyAds.TEST_BANNER_UNIT_ID)
    key(effectiveId, adSize) {
        val adView = remember {
            AdView(context).apply {
                setAdSize(adSize)
                adUnitId = effectiveId
                loadAd(AdRequest.Builder().build())
            }
        }
        DisposableEffect(adView) {
            onDispose { adView.destroy() }
        }
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .height(adSize.height.dp),
            factory = { adView }
        )
    }
}
