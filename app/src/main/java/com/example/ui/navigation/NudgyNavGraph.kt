package com.example.ui.navigation

/**
 * Sprint 0 — navigation routes for the scratch rebuild (v2).
 *
 * String routes held in a sealed class (one source of truth for every
 * navigation call). Full @Serializable type-safe routes would need the
 * kotlinx-serialization plugin, which this build does not have yet —
 * that upgrade, if wanted, is a Sprint 1 decision, not a Sprint 0 one.
 *
 * The 5 destinations are the locked design (2026-11-15): Today |
 * Calendar | centre Quick Chat | Groups | Settings. Board lives inside
 * Today (List / Board toggle) and Stats lives behind the progress ring
 * + Settings — neither is a destination.
 *
 * NOT wired into MainActivity yet: the old state-based navigation
 * (currentDestination + when branches) still runs the app. Sprint 1
 * swaps the root to NudgyApp() screen by screen.
 */
sealed class NudgyRoute(val route: String) {
    data object Today : NudgyRoute("today")
    data object Calendar : NudgyRoute("calendar")
    data object Chat : NudgyRoute("chat")
    data object Groups : NudgyRoute("groups")
    data object Settings : NudgyRoute("settings")

    companion object {
        val startDestination: NudgyRoute = Today

        val all: List<NudgyRoute> = listOf(Today, Calendar, Chat, Groups, Settings)

        fun fromRoute(route: String?): NudgyRoute =
            all.firstOrNull { it.route == route } ?: Today
    }
}

/** App Links deep link for group join (manifest already declares it). */
const val GROUP_JOIN_DEEP_LINK = "https://tanvir-durlove.github.io/nudgy/join"
