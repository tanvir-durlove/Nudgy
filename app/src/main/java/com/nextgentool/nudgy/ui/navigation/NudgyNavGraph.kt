package com.nextgentool.nudgy.ui.navigation

import androidx.navigation.navDeepLink

/**
 * Sprint 0 — navigation routes for the scratch rebuild (v2).
 * Wired into MainActivity in Sprint 1-0 (NudgyApp() is the root).
 *
 * String routes held in a sealed class (one source of truth for every
 * navigation call). Full @Serializable type-safe routes would need the
 * kotlinx-serialization plugin, which this build does not have yet —
 * that upgrade, if wanted, is a later decision, not a Sprint 1 one.
 *
 * The 5 destinations are the locked design (2026-11-15): Today |
 * Calendar | centre Quick Chat | Groups | Settings. Board lives inside
 * Today (List / Board toggle) and the weekly review lives behind the
 * progress ring + Settings as its own route — not a bottom destination
 * (Sprint 4-4, spec §7/Q9).
 *
 * Plan is NOT a destination: it is the nested route "today/plan"
 * inside the Today graph (Sprint 1 spec §5). It stays out of `all`, so
 * fromRoute("today/plan") falls back to Today and the bottom nav keeps
 * Today highlighted while Plan is open.
 */
sealed class NudgyRoute(val route: String) {
    data object Today : NudgyRoute("today")
    data object Calendar : NudgyRoute("calendar")
    data object Chat : NudgyRoute("chat")
    data object Groups : NudgyRoute("groups")
    data object Settings : NudgyRoute("settings")

    /** Tasks list — NOT a bottom destination (reached from Today). */
    data object Tasks : NudgyRoute("tasks")

    /** Focus timer — NOT a bottom destination (reached from Today, S4-1). */
    data object Focus : NudgyRoute("focus")

    /** Weekly review — NOT a bottom destination (reached from the Today
     * progress ring and from Settings, S4-4). The one report: the
     * week's lists + focus minutes + streaks (spec §7, Q9=(a)). */
    data object WeeklyReview : NudgyRoute("weekly_review")

    /** Templates — NOT a bottom destination (reached from Tasks, S1-4). */
    data object Templates : NudgyRoute("templates")

    /** Global search — NOT a bottom destination (reached from the
     * top-bar magnifier, S6-4 step 2a; the magnifier itself is 2d). */
    data object Search : NudgyRoute("search")

    /** Privacy Policy — NOT a bottom destination (reached from the
     * Settings privacy row, step 5b; the row used to open the
     * hosted web page, now it opens this in-app screen). */
    data object PrivacyPolicy : NudgyRoute("privacy_policy")

    /** Nested route inside the Today graph — not a bottom destination. */
    data object Plan : NudgyRoute("today/plan")

    companion object {
        // get(), NOT an eager val: the data objects' class init re-enters
        // this companion's constructor while their INSTANCE fields are still
        // null, so an eager listOf(Today, ...) captures a null element and
        // fromRoute() NPEs on the first frame (Crashlytics 2026-11-16,
        // NudgyRoute.getRoute:22). A get() evaluates after init completes.
        val startDestination: NudgyRoute get() = Today

        // Tasks, Templates, Focus, WeeklyReview, Search and
        // PrivacyPolicy are in `all` (so fromRoute resolves and no
        // bottom tab is highlighted there) but NOT in bottomNavEntries.
        val all: List<NudgyRoute> get() = listOf(Today, Calendar, Chat, Groups, Settings, Tasks, Templates, Focus, WeeklyReview, Search, PrivacyPolicy)

        fun fromRoute(route: String?): NudgyRoute =
            all.firstOrNull { it.route == route } ?: Today
    }
}

/** App Links deep link for group join (manifest already declares it). */
const val GROUP_JOIN_DEEP_LINK = "https://tanvir-durlove.github.io/nudgy/join"

/**
 * Sprint 5-1 — the Groups destination's deep links. The code pattern
 * comes FIRST: navigation tries patterns in order, and the plain
 * pattern also matches a URI carrying a query, so the code would never
 * be extracted if the plain pattern came first. A bare /nudgy/join
 * link (no code) falls through to the plain pattern and opens Groups
 * with no prefill.
 */
val GROUP_JOIN_DEEP_LINKS = listOf(
    navDeepLink { uriPattern = "$GROUP_JOIN_DEEP_LINK?code={code}" },
    navDeepLink { uriPattern = GROUP_JOIN_DEEP_LINK }
)

/** Route of the nested Today graph (Today + Plan). The NavHost starts here. */
const val TODAY_GRAPH_ROUTE = "today_graph"
