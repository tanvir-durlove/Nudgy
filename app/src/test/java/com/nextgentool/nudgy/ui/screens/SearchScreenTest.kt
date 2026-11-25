package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.navigation.NudgyRoute
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import com.nextgentool.nudgy.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * Step 2b — the Tasks section is wired to the real SearchViewModel
 * (Room LIKE results). The screen takes an explicit ViewModel here —
 * the default hiltViewModel() has no Hilt in a unit test. The
 * ViewModel is built inside each test (never in setUp — the init
 * coroutines need the test Main), lives in a ViewModelStore cleared
 * BEFORE the database closes, and in-flight Room IO is drained first
 * (S4-1 round 7). groupAuth is null, so the init launches nothing.
 * Seeding uses runBlocking on the repository (the S6-3-proven shape:
 * test thread + Dispatchers.IO, no viewModelScope, no Main pump).
 *
 * Harness rules, all proven in this repo — do NOT "fix" them:
 * - NO performScrollTo(): it hangs the test worker on this harness
 *   (OnboardingScreenTest KDoc, runs 851/852). The screen is a plain
 *   Column with verticalScroll, so every section composes and
 *   assertExists works without scrolling.
 * - Text is read the PlanScreenTest way: fetchSemanticsNode() is a
 *   rule MEMBER (not a top-level import) and the text is matched by
 *   containment on SemanticsProperties.Text.
 * - waitForIdle() is safe here: the SearchScreen has no always-on
 *   animation (unlike the sign-in mascot — ShellScreensTest KDoc).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(context, db)
    }

    @After
    fun tearDown() {
        store.clear()
        Thread.sleep(500)
        db.close()
    }

    private fun setSearchContent() {
        val viewModel = SearchViewModel(repository).also { store.put("search", it) }
        composeRule.setContent {
            NudgyTheme {
                SearchScreen(viewModel = viewModel, onBackClick = {})
            }
        }
    }

    /**
     * Step 2c — the fully wired ViewModel (schedules + notes +
     * groups). The step-2b tests keep the task-only build above,
     * which still passes unchanged.
     */
    private fun setWiredSearchContent() {
        val viewModel = SearchViewModel(
            repository,
            calendarRepository = CalendarRepository(context, db),
            groupRepository = GroupRepository(context, db)
        ).also { store.put("search-wired", it) }
        composeRule.setContent {
            NudgyTheme {
                SearchScreen(viewModel = viewModel, onBackClick = {})
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
        // The database is empty — so every section honestly reports
        // no results, and no invented row appears.
        listOf("tasks", "schedules", "notes", "groups").forEach { group ->
            assertTrue(
                "empty state for $group should report no results, was: " +
                    nodeText("search_empty_$group"),
                nodeText("search_empty_$group").contains("No results")
            )
        }
    }

    @Test
    fun `typing a matching query renders the task result row`() {
        val task = runBlocking {
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Buy groceries",
                description = "Weekly shop at the market"
            )
        }
        setSearchContent()

        composeRule.onNodeWithTag("search_field").performTextInput("buy")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search_result_task_${task.id}").assertExists()
        assertTrue(
            "result row should show the task title, was: " +
                nodeText("search_result_task_${task.id}"),
            nodeText("search_result_task_${task.id}").contains("Buy groceries")
        )
        // A real result replaces the Tasks empty state; the other
        // three sections still report no results.
        composeRule.onNodeWithTag("search_empty_tasks").assertDoesNotExist()
        assertTrue(
            "schedules empty state should report no results, was: " +
                nodeText("search_empty_schedules"),
            nodeText("search_empty_schedules").contains("No results")
        )
    }

    @Test
    fun `search route resolves from its string`() {
        assertEquals(NudgyRoute.Search, NudgyRoute.fromRoute("search"))
        assertTrue(NudgyRoute.all.contains(NudgyRoute.Search))
    }

    // ---- Sprint 6-4, step 2c: schedules, notes, groups ----

    @Test
    fun `typing a matching query renders the schedule result row`() {
        runBlocking {
            db.calendarDao().insertEvent(
                CalendarEventEntity(
                    id = "event-1",
                    userId = repository.currentUserId(),
                    title = "Dentist appointment",
                    startEpochMillis = 1_700_000_000_000,
                    endEpochMillis = 1_700_000_600_000,
                    locationOrLink = "City Dental Clinic"
                )
            )
        }
        setWiredSearchContent()

        composeRule.onNodeWithTag("search_field").performTextInput("dentist")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search_result_schedule_event-1").assertExists()
        assertTrue(
            "result row should show the event title, was: " +
                nodeText("search_result_schedule_event-1"),
            nodeText("search_result_schedule_event-1").contains("Dentist appointment")
        )
        // A real result replaces the Schedules empty state.
        composeRule.onNodeWithTag("search_empty_schedules").assertDoesNotExist()
    }

    @Test
    fun `typing a description-only query renders the note result row`() {
        val task = runBlocking {
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Buy groceries",
                description = "Weekly shop at the market"
            )
        }
        setWiredSearchContent()

        // "market" matches only the description — the note row shows
        // it as its own group, and the Tasks group shows it too.
        composeRule.onNodeWithTag("search_field").performTextInput("market")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search_result_note_${task.id}").assertExists()
        assertTrue(
            "note row should show the matching description, was: " +
                nodeText("search_result_note_${task.id}"),
            nodeText("search_result_note_${task.id}").contains("Weekly shop at the market")
        )
        composeRule.onNodeWithTag("search_result_task_${task.id}").assertExists()
        composeRule.onNodeWithTag("search_empty_notes").assertDoesNotExist()
    }

    @Test
    fun `typing a title-only query leaves the notes section empty`() {
        val task = runBlocking {
            repository.createTask(
                userId = repository.currentUserId(),
                title = "Buy groceries",
                description = "Weekly shop at the market"
            )
        }
        setWiredSearchContent()

        // "groceries" matches only the title — the Tasks group shows
        // the row, the Notes group honestly reports no results.
        composeRule.onNodeWithTag("search_field").performTextInput("groceries")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search_result_task_${task.id}").assertExists()
        assertTrue(
            "notes empty state should report no results, was: " +
                nodeText("search_empty_notes"),
            nodeText("search_empty_notes").contains("No results")
        )
    }

    @Test
    fun `typing a matching query renders the group result row`() {
        runBlocking {
            db.groupDao().insertGroup(
                GroupEntity(
                    groupId = "g1",
                    name = "Weekend Runners",
                    code = "RUN123",
                    creatorUid = repository.currentUserId()
                )
            )
        }
        setWiredSearchContent()

        composeRule.onNodeWithTag("search_field").performTextInput("runners")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search_result_group_g1").assertExists()
        assertTrue(
            "result row should show the group name, was: " +
                nodeText("search_result_group_g1"),
            nodeText("search_result_group_g1").contains("Weekend Runners")
        )
        // A real result replaces the Groups empty state.
        composeRule.onNodeWithTag("search_empty_groups").assertDoesNotExist()
    }
}
