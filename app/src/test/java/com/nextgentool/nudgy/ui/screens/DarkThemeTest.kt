package com.nextgentool.nudgy.ui.screens

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.ColorUtils
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextgentool.nudgy.data.local.NudgyDatabase
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.usecase.AddSubtaskUseCase
import com.nextgentool.nudgy.domain.usecase.CarryOverUseCase
import com.nextgentool.nudgy.domain.usecase.CreateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.DeleteTaskUseCase
import com.nextgentool.nudgy.domain.usecase.GetTodayTimelineUseCase
import com.nextgentool.nudgy.domain.usecase.SaveViewUseCase
import com.nextgentool.nudgy.domain.usecase.ToggleTaskCompletionUseCase
import com.nextgentool.nudgy.domain.usecase.UpdateTaskUseCase
import com.nextgentool.nudgy.domain.usecase.WeeklyReviewUseCase
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import com.nextgentool.nudgy.ui.theme.nudgyPaletteFor
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device feedback 2026-11-21 (ci-677) — dark mode: card backgrounds
 * went dark (theme-aware surface #262833) while text stayed on the
 * static dark R.color resources — ink 1.21:1, muted 1.93:1 on the
 * dark surface, i.e. dark-on-dark, unreadable. The fix swaps text on
 * dark surfaces to the theme-aware palette tokens (NudgyCharcoal /
 * NudgyMuted / NudgyAccent).
 *
 * What this pins:
 *  - the dark palette's text tokens pass WCAG 4.5:1 on the dark card
 *    and canvas (pure unit test on the palette values);
 *  - the light palette's token values are the approved ones, so the
 *    swap does not move light mode;
 *  - the Today screen renders under NudgyTheme(darkTheme = true)
 *    without crashing, with the dark palette actually resolved during
 *    composition (captured from the CompositionLocal).
 *
 * What it does NOT prove (stays device-verified): the rendered
 * pixels — Robolectric semantics carry no text color, so the final
 * visual check is the device retest of the §10 checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DarkThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: NudgyDatabase
    private lateinit var repository: TaskRepository
    private lateinit var tasksViewModel: TasksViewModel
    private lateinit var todayViewModel: TodayViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NudgyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(context, db)
        tasksViewModel = TasksViewModel(
            repository = repository,
            createTaskUseCase = CreateTaskUseCase(repository),
            toggleCompletion = ToggleTaskCompletionUseCase(repository),
            updateTask = UpdateTaskUseCase(repository),
            deleteTask = DeleteTaskUseCase(repository),
            addSubtask = AddSubtaskUseCase(repository),
            saveView = SaveViewUseCase(repository)
        )
        todayViewModel = TodayViewModel(
            repository = repository,
            calendarRepository = CalendarRepository(context, db),
            toggleCompletion = ToggleTaskCompletionUseCase(repository),
            carryOverUseCase = CarryOverUseCase(),
            timelineUseCase = GetTodayTimelineUseCase(),
            weeklyReviewUseCase = WeeklyReviewUseCase()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `dark palette text passes contrast on dark card and canvas`() {
        val dark = nudgyPaletteFor(true)
        // The exact pairs the swapped call sites sit on: primary and
        // secondary text on cards (surface), and section headers on
        // the screen background (canvas). Small accent text (links,
        // "Group" badges) must pass on the card too.
        assertTrue(ColorUtils.calculateContrast(dark.charcoal.toArgb(), dark.card.toArgb()) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(dark.muted.toArgb(), dark.card.toArgb()) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(dark.accent.toArgb(), dark.card.toArgb()) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(dark.charcoal.toArgb(), dark.canvas.toArgb()) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(dark.muted.toArgb(), dark.canvas.toArgb()) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(dark.accent.toArgb(), dark.canvas.toArgb()) >= 4.5)
    }

    @Test
    fun `light palette token values are the approved ones`() {
        val light = nudgyPaletteFor(false)
        assertEquals(Color(0xFF1C1B1F), light.charcoal)
        assertEquals(Color(0xFF6F6A72), light.muted)
        assertEquals(Color(0xFFFFFFFF), light.card)
        assertEquals(Color(0xFFFBF9F4), light.canvas)
        assertEquals(Color(0xFFCC3F27), light.accent)
    }

    @Test
    fun `today screen renders under the dark theme`() {
        var capturedCharcoal: Color? = null
        var capturedCard: Color? = null
        composeRule.setContent {
            NudgyTheme(darkTheme = true) {
                // The hero card's text resolves through these getters;
                // capturing them proves the dark palette (not the
                // light default) is what the composition actually sees.
                capturedCharcoal = NudgyCharcoal
                capturedCard = NudgyCard
                TodayScreen(
                    onPlanClick = {},
                    onAllTasksClick = {},
                    tasksViewModel = tasksViewModel,
                    todayViewModel = todayViewModel
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add task")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Make today count.").assertExists()
        assertNotNull(capturedCharcoal)
        assertNotNull(capturedCard)
        assertEquals(Color(0xFFF5F1EA), capturedCharcoal)
        assertEquals(Color(0xFF262833), capturedCard)
        assertTrue(
            ColorUtils.calculateContrast(
                capturedCharcoal!!.toArgb(), capturedCard!!.toArgb()
            ) >= 4.5
        )
    }
}
