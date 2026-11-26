package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.model.BuiltInTemplates
import com.nextgentool.nudgy.domain.model.TemplateDraft
import com.nextgentool.nudgy.ui.viewmodel.TemplatesViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 7 — Templates screen render tests (the prototype rebuild,
 * scr-templates). There was no screen test for TemplatesScreen
 * before this sprint (only the domain-model TaskTemplateTest), so
 * this file is new.
 *
 * The screen renders with a MANUALLY-constructed TemplatesViewModel
 * (real in-memory Room repository, no Hilt) — the TasksScreenSmokeTest
 * pattern. The ViewModel MUST be owned by the store (the S14 round 9
 * lesson): its init launches a never-ending observeTemplates Flow
 * collection in viewModelScope, and a bare ViewModel's scope is never
 * cancelled, so the collection would still be alive when tearDown
 * closes the DB.
 *
 * The built-ins are seeded in setUp (the S14 pattern), so every test
 * starts with the 3 built-ins (DAO order is name ASC: কাজ, দৈনিক
 * রুটিন, পড়াশোনা — card indices 0, 1, 2). The empty state therefore
 * cannot be rendered deterministically here — the ViewModel seeds
 * the built-ins eagerly in init, so the list is never empty in
 * practice; the empty-state COPY is verified against the string
 * resource instead, and the branch is proven to hide once templates
 * load (assertDoesNotExist below).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TemplatesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var viewModel: TemplatesViewModel
    // Owns the ViewModels so tearDown can cancel their scopes before
    // the DB closes (same leak as DarkThemeTest, 2026-11-21).
    private val viewModelStore = ViewModelStore()

    private val userId get() = repository.currentUserId()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(context, db)
        runBlocking { repository.seedBuiltInTemplates(userId) }
        viewModel = TemplatesViewModel(repository)
        viewModelStore.put("templates", viewModel)
    }

    @After
    fun tearDown() {
        // Order matters: cancel the ViewModel scopes FIRST so no
        // viewModelScope coroutine can run Room work against the
        // DB once it is closed (same leak as DarkThemeTest).
        viewModelStore.clear()
        Thread.sleep(500)
        db.close()
    }

    private fun renderTemplates() {
        composeRule.setContent {
            TemplatesScreen(onBack = {}, viewModel = viewModel)
        }
        // The built-ins arrive via the Flow — wait for the first
        // card to compose before asserting anything.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("কাজ")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `backbar intro and ghost button render, no FAB`() {
        renderTemplates()
        composeRule.onNodeWithText("Templates").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Ready-made task sets for days that repeat. Using a " +
                "template copies its tasks — you pick the date."
        ).assertExists()
        // The first built-in card renders at the top of the list.
        composeRule.onNodeWithText("কাজ").assertIsDisplayed()
        // The ghost button is the item after the 3 built-in cards, so
        // it is NOT composed until the list scrolls to it (LazyColumn
        // composes only the visible window) — scroll first.
        composeRule.onNodeWithTag("templates_list").performScrollToIndex(3)
        // The ghost button replaced the FAB: it carries the moved
        // testTag, and the FAB's "New template" icon is gone.
        composeRule.onNodeWithTag("new_template_button").assertExists()
        composeRule.onNodeWithText("Create custom template").assertExists()
        composeRule.onNodeWithContentDescription("New template")
            .assertDoesNotExist()
    }

    @Test
    fun `ghost button reveals the New custom template card`() {
        renderTemplates()
        // The ghost button is the item after the 3 built-in cards.
        composeRule.onNodeWithTag("templates_list").performScrollToIndex(3)
        composeRule.onNodeWithTag("new_template_button").performClick()
        composeRule.waitForIdle()
        // The card is the next item (index 4).
        composeRule.onNodeWithTag("templates_list").performScrollToIndex(4)
        composeRule.onNodeWithTag("create_template_card").assertExists()
        composeRule.onNodeWithText("New custom template").assertExists()
        composeRule.onNodeWithTag("create_template_name").assertExists()
        composeRule.onNodeWithTag("create_template_task_0").assertExists()
        composeRule.onNodeWithTag("create_template_save").assertExists()
    }

    @Test
    fun `creating a template through the inline card adds it to the list`() {
        renderTemplates()
        composeRule.onNodeWithTag("templates_list").performScrollToIndex(3)
        composeRule.onNodeWithTag("new_template_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("templates_list").performScrollToIndex(4)
        composeRule.onNodeWithTag("create_template_name")
            .performTextInput("Weekend reset")
        composeRule.onNodeWithTag("create_template_task_0")
            .performTextInput("Tidy the desk")
        // The typed text must land in the fields — if it did not, the
        // Save button stays disabled (isValid is false) and the click
        // below is a silent no-op, so assert here, not at the waitUntil.
        composeRule.onNodeWithText("Weekend reset").assertExists()
        composeRule.onNodeWithText("Tidy the desk").assertExists()
        // The Save button sits at the bottom of the tall card, below
        // the fold — invoke its semantics action, never a coordinate
        // click (the PR #130 pattern).
        composeRule.onNodeWithTag("create_template_save")
            .performSemanticsAction(SemanticsActions.OnClick)
        // A successful Save closes the card at once; if the card is
        // still there, the click never ran.
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_template_card").assertDoesNotExist()
        // The create runs through the real ViewModel + Room; wait for
        // the Flow to carry it, then it is the first card (Latin
        // "Weekend" sorts before the Bengali built-in names).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.templates.value.any { it.name == "Weekend reset" }
        }
        composeRule.onNodeWithText("Weekend reset").assertExists()
    }

    @Test
    fun `edit still opens the editor dialog with the template name`() {
        runBlocking {
            repository.createTemplate(
                userId,
                "My template",
                listOf(TemplateDraft(title = "Do a thing"))
            )
        }
        renderTemplates()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("My template")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The custom template sorts first (Latin before Bengali), so
        // its card — and its Edit button in the card header — is at
        // the top of the list. Built-ins have no Edit button, so this
        // description is unique.
        composeRule.onNodeWithContentDescription("Edit template")
            .performClick()
        composeRule.onNodeWithText("Edit template").assertExists()
        composeRule.onNodeWithTag("edit_template_name").assertExists()
        // The dialog pre-fills the template's name.
        val nameNode = composeRule.onNodeWithTag("edit_template_name")
            .fetchSemanticsNode()
        val fieldText = nameNode.config[SemanticsProperties.EditableText].text
        assertEquals("My template", fieldText)
    }

    @Test
    fun `Use template still asks for a due date`() {
        renderTemplates()
        // দৈনিক রুটিন is card index 1 (DAO order is name ASC).
        composeRule.onNodeWithTag("templates_list").performScrollToIndex(1)
        // The Use button sits at the bottom of the tall card, below
        // the fold — invoke its semantics action (the PR #130 pattern).
        composeRule.onNodeWithTag(
            "use_template_${BuiltInTemplates.DAILY_ROUTINE_ID}"
        ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Due date for দৈনিক রুটিন").assertExists()
        composeRule.onNodeWithText("Today").assertExists()
        composeRule.onNodeWithText("Tomorrow").assertExists()
        composeRule.onNodeWithText("Pick a date").assertExists()
    }

    @Test
    fun `empty state hides once templates load and its copy is the new text`() {
        renderTemplates()
        composeRule.onNodeWithText("No templates yet. Create one below.")
            .assertDoesNotExist()
        // The empty state cannot be rendered deterministically (the
        // ViewModel seeds the built-ins eagerly in init — see the
        // class KDoc), so the copy itself is verified against the
        // string resource.
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            "No templates yet. Create one below.",
            context.getString(R.string.templates_empty)
        )
        // The same resource check for the intro (prototype-verbatim).
        assertEquals(
            "Ready-made task sets for days that repeat. Using a " +
                "template copies its tasks — you pick the date.",
            context.getString(R.string.templates_intro)
        )
    }
}
