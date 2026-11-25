package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.nextgentool.nudgy.ui.navigation.NudgyRoute
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 2a — the global search screen shell (S6-4): the query field
 * plus the four grouped sections from the prototype (Tasks /
 * Schedules / Notes / Groups), each with an honest empty state.
 *
 * Render-only: no ViewModel, no repository, no navigation — the
 * screen owns only its query text (rememberSaveable) until steps
 * 2b/2c wire the Room LIKE queries. The route registration is
 * covered by the pure unit test below (fromRoute resolves "search").
 *
 * Harness rules, all proven in this repo — do NOT "fix" them:
 * - NO performScrollTo(): it hangs the test worker on this harness
 *   (OnboardingScreenTest KDoc, runs 851/852). The screen is a plain
 *   Column with verticalScroll, so every section composes and
 *   assertExists works without scrolling.
 * - Text is read the PlanScreenTest way: fetchSemanticsNode() is a
 *   rule MEMBER (not a top-level import) and the text is matched by
 *   containment on SemanticsProperties.Text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setSearchContent() {
        composeRule.setContent {
            NudgyTheme {
                SearchScreen(onBackClick = {})
            }
        }
    }

    /** The PlanScreenTest text-reading pattern (member call). */
    private fun nodeText(tag: String): String {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config[SemanticsProperties.Text].toString()
    }

    @Test
    fun `search screen shows all four grouped sections with type-to-search empty states`() {
        setSearchContent()

        // assertExists (not assertIsDisplayed): the last section can
        // sit below the fold in the test viewport, and assertIsDisplayed
        // fails for below-fold items on this harness.
        composeRule.onNodeWithTag("search_screen").assertExists()
        composeRule.onNodeWithTag("search_field").assertExists()
        listOf("tasks", "schedules", "notes", "groups").forEach { group ->
            composeRule.onNodeWithTag("search_section_$group").assertExists()
            assertTrue(
                "empty state for $group should invite typing, was: " +
                    nodeText("search_empty_$group"),
                nodeText("search_empty_$group").contains("Type to search")
            )
        }
    }

    @Test
    fun `typing a query shows no-results states and no fake results`() {
        setSearchContent()

        composeRule.onNodeWithTag("search_field").performTextInput("buy")
        // No query logic exists yet (steps 2b/2c) — so every section
        // honestly reports no results, and no invented row appears.
        listOf("tasks", "schedules", "notes", "groups").forEach { group ->
            assertTrue(
                "empty state for $group should report no results, was: " +
                    nodeText("search_empty_$group"),
                nodeText("search_empty_$group").contains("No results")
            )
        }
    }

    @Test
    fun `search route resolves from its string`() {
        assertEquals(NudgyRoute.Search, NudgyRoute.fromRoute("search"))
        assertTrue(NudgyRoute.all.contains(NudgyRoute.Search))
    }
}
