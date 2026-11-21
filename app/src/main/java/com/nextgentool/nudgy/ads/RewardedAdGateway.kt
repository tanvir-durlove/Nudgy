package com.nextgentool.nudgy.ads

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Sprint 3-4 — the rewarded-ad seam for the Chat cap entry. The
 * ChatScreen's "+1 answer" button needs three things from the ad
 * layer: whether an ad is preloaded, whether the last load failed,
 * and a show call that grants the reward ONLY on a completed view.
 *
 * The production implementation delegates to the [NudgyAds] singleton;
 * tests pass a fake that records show calls and fires the reward on
 * demand — no AdMob SDK in unit tests.
 *
 * Known gap, NOT hidden: [NudgyAds.initialize] is never called in the
 * rebuild (ads init was knowingly dropped in Sprint 1-0), and nothing
 * feeds the Remote Config rewarded unit id into NudgyAds — so in the
 * real app no rewarded ad is preloaded yet, and [show] returns false
 * (triggering a load that fails until the SDK is initialised). The cap
 * state, the offer UI, the grant path and the retry path are all wired
 * here and work end to end the moment the ads-init track lands; the
 * init itself is a separate product decision, not part of S3-4.
 */
interface RewardedAdGateway {
    /** True when a rewarded ad is preloaded and ready to show. */
    val isReady: StateFlow<Boolean>

    /** Last rewarded load failure, if any; null while loading/ready. */
    val loadError: StateFlow<String?>

    /**
     * Shows the preloaded rewarded ad. [onRewardEarned] fires ONLY
     * when the user earns the reward (completed viewing) — never on
     * load, click, dismiss, or failure. Returns true when an ad was
     * shown; false when none was ready (a reload is triggered).
     */
    fun show(activity: Activity, onRewardEarned: () -> Unit): Boolean

    /** Retries the rewarded preload with the last requested unit id. */
    fun retryLoad()
}

/** Production [RewardedAdGateway]: the [NudgyAds] singleton. */
class NudgyAdsRewardedGateway(
    private val context: Context
) : RewardedAdGateway {
    override val isReady: StateFlow<Boolean> = NudgyAds.rewardedReady
    override val loadError: StateFlow<String?> = NudgyAds.rewardedLoadError

    override fun show(activity: Activity, onRewardEarned: () -> Unit): Boolean =
        NudgyAds.showRewarded(activity, onRewardEarned)

    override fun retryLoad() {
        NudgyAds.reloadRewarded(context)
    }
}
