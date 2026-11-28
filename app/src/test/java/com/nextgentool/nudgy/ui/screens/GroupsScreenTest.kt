package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.SharedListEntity
import com.nextgentool.nudgy.data.local.entity.SharedListItemEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.GroupActivityEntry
import com.nextgentool.nudgy.data.repository.GroupActivityType
import com.nextgentool.nudgy.data.repository.GroupCompletion
import com.nextgentool.nudgy.data.repository.GroupMember
import com.nextgentool.nudgy.data.repository.GroupNotice
import com.nextgentool.nudgy.data.repository.GroupResult
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 5-1 — Groups tab over the v1 screen (spec §4).
 *
 * Renders the pure [GroupsScreen] with hand-built state (no Hilt, no
 * ViewModel): the list, the empty state, the guest prompt, the detail
 * (members + placeholder, no group-work UI), the create/join inline
 * cards (Sprint 7 — the sheets are gone), and the deep-link prefill. Compose 1.7 member rules apply:
 * assertExists/assertDoesNotExist are members, never top-level imports.
 *
 * Sprint 5-2 — group tasks (spec §5, feature 31): the same screen
 * with groupTasksEnabled=true — the task rows with the shared
 * completion count, the who-completed dialog, the creator-only
 * Add task/schedule buttons, and the member toggle via the detail
 * sheet. The detail is a scrolling column: below-fold nodes are
 * scrolled to before tapping (performScrollTo) and asserted with
 * assertExists, not assertIsDisplayed.
 *
 * Sprint 5-3 — notices (spec §6, feature 32) + notification tap
 * (feature 34): the same screen with groupNoticesEnabled=true — the
 * notices card (newest first), the creator-only Send notice sheet,
 * and openGroupId opening the detail (the tap entry point).
 *
 * Sprint 5-4 — delete/leave (spec §7, feature 33): the same screen
 * with groupWorkEnabled=true — the creator sees Delete group, members
 * see Leave group; both open a confirm dialog that fires the
 * callback. The buttons are not rendered at all when the flag is off.
 *
 * Sprint 7 — the detail restyle (spec §4, prototype scr-group-detail):
 * backbar with the group name + RolePill, the pinned notice card with
 * the inline send card (the sheet is gone), the tasks seg + .task rows
 * with donechips, the cream invite codebox. The list tests above are
 * untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val creator = NudgyUser(uid = "user-1", displayName = "Ayesha", email = "")
    private val memberUser = NudgyUser(uid = "user-2", displayName = "Tanvir", email = "")
    private val guestUser = NudgyUser(uid = "guest", displayName = "", email = "", isGuest = true)

    private fun group(
        id: String = "g-1",
        name: String = "DIU 41",
        code: String = "K7Q2XA",
        creatorUid: String = "user-1"
    ) = GroupEntity(groupId = id, name = name, code = code, creatorUid = creatorUid)

    private val members = listOf(
        GroupMember(uid = "user-1", displayName = "Ayesha"),
        GroupMember(uid = "user-2", displayName = "Tanvir")
    )

    private fun render(
        user: NudgyUser = memberUser,
        groups: List<GroupEntity> = listOf(group()),
        allTasks: List<TaskEntity> = emptyList(),
        allEvents: List<CalendarEventEntity> = emptyList(),
        completions: Map<String, GroupCompletion> = emptyMap(),
        membersOverride: List<GroupMember>? = null,
        groupTasksEnabled: Boolean = false,
        groupNoticesEnabled: Boolean = false,
        groupWorkEnabled: Boolean = false,
        groupAssignEnabled: Boolean = false,
        noticesOverride: List<GroupNotice> = emptyList(),
        activityOverride: List<GroupActivityEntry> = emptyList(),
        sharedListsOverride: List<SharedListEntity> = emptyList(),
        sharedListsFlowOverride: Flow<List<SharedListEntity>>? = null,
        onCreateSharedList: (String, String) -> Unit = { _, _ -> },
        onGetSharedListCounts: suspend (String) -> Pair<Int, Int> = { 0 to 0 },
        sharedListItemsOverride: List<SharedListItemEntity> = emptyList(),
        sharedListItemsFlowOverride: Flow<List<SharedListItemEntity>>? = null,
        onAddSharedListItem: (String, String, String) -> Unit = { _, _, _ -> },
        onToggleSharedListItem: (String, String, SharedListItemEntity) -> Unit = { _, _, _ -> },
        onDeleteSharedListItem: (String, String, String) -> Unit = { _, _, _ -> },
        initialSelectedSharedList: SharedListEntity? = null,
        onAssignGroupTask: (String, String, String?) -> Unit = { _, _, _ -> },
        onSendNotice: (String, String) -> Unit = { _, _ -> },
        onDeleteGroup: (String) -> Unit = {},
        onLeaveGroup: (String) -> Unit = {},
        onToggleTask: (TaskEntity) -> Unit = {},
        onSignInClick: () -> Unit = {},
        onCreateGroup: suspend (String) -> GroupResult<GroupEntity> =
            { GroupResult.Ok(group()) },
        onJoinGroup: suspend (String) -> GroupResult<GroupEntity> =
            { GroupResult.Ok(group()) },
        prefillJoinCode: String? = null,
        initialSelectedGroup: GroupEntity? = null,
        openGroupId: String? = null
    ) {
        val shownMembers = membersOverride ?: members
        composeRule.setContent {
            NudgyTheme {
                GroupsScreen(
                    user = user,
                    groups = groups,
                    allTasks = allTasks,
                    allEvents = allEvents,
                    onCreateGroup = onCreateGroup,
                    onJoinGroup = onJoinGroup,
                    onObserveMembers = { flowOf(shownMembers) },
                    onRefreshMembers = { shownMembers },
                    onToggleTask = onToggleTask,
                    completions = completions,
                    groupTasksEnabled = groupTasksEnabled,
                    groupNoticesEnabled = groupNoticesEnabled,
                    groupWorkEnabled = groupWorkEnabled,
                    groupAssignEnabled = groupAssignEnabled,
                    onAssignGroupTask = onAssignGroupTask,
                    onDeleteGroup = onDeleteGroup,
                    onLeaveGroup = onLeaveGroup,
                    onObserveNotices = { flowOf(noticesOverride) },
                    onRefreshNotices = { noticesOverride },
                    onObserveActivity = { flowOf(activityOverride) },
                    onRefreshActivity = { activityOverride },
                    onObserveSharedLists = { sharedListsFlowOverride ?: flowOf(sharedListsOverride) },
                    onCreateSharedList = onCreateSharedList,
                    onGetSharedListCounts = onGetSharedListCounts,
                    onObserveSharedListItems = { sharedListItemsFlowOverride ?: flowOf(sharedListItemsOverride) },
                    onAddSharedListItem = onAddSharedListItem,
                    onToggleSharedListItem = onToggleSharedListItem,
                    onDeleteSharedListItem = onDeleteSharedListItem,
                    onSendNotice = onSendNotice,
                    onSignInClick = onSignInClick,
                    openGroupId = openGroupId,
                    prefillJoinCode = prefillJoinCode,
                    initialSelectedGroup = initialSelectedGroup,
                    initialSelectedSharedList = initialSelectedSharedList
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun groupTask(
        id: String = "t-1",
        title: String = "Homework",
        isCompleted: Boolean = false
    ) = TaskEntity(
        id = id, userId = "user-1", title = title,
        dueDateEpochMillis = 1_800_000_000_000L,
        isCompleted = isCompleted, groupId = "g-1"
    )

    @Test
    fun `list shows groups with name meta and role pill`() {
        render()

        composeRule.onNodeWithText("Groups").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Tasks and schedules shared with your group — anything added here appears in every member's own list, with a group badge so you know where it came from."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("DIU 41").assertIsDisplayed()
        // No tasks seeded: the prototype meta is "N members · M open
        // tasks", but member counts are not loaded for the list, so the
        // meta is task-only for now.
        composeRule.onNodeWithText("0 open tasks").assertIsDisplayed()
        // user-2 is a member of user-1's group, not the creator.
        composeRule.onNodeWithText("Member").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Add task at the top of its task list", substring = true
        ).assertIsDisplayed()
        // The join code is gone from the row (the prototype has none) —
        // it still lives in the detail's invite card.
        composeRule.onNodeWithText("K7Q2XA").assertDoesNotExist()
        // Members get no "+ Add task" chip (admin-only, Sprint 5 Q3).
        composeRule.onNodeWithText("+ Add task").assertDoesNotExist()
    }

    // Sprint 7 — the "+ Add task" chip: creators only, and it opens the
    // detail where Add task lives. The chip is a plain clickable Box
    // inside the scrolling column (the Calendar grid-day pattern), so
    // performClick + waitUntil is the reliable path here.
    @Test
    fun `creator row shows the Add task chip and tapping it opens the detail`() {
        render(user = creator)

        composeRule.onNodeWithTag("group_add_task_g-1").assertIsDisplayed()
        composeRule.onNodeWithText("+ Add task").assertIsDisplayed()
        composeRule.onNodeWithText("+ Add task").performClick()
        // Sprint 7 — the detail-only sec-title is the open signal
        // (the old "All groups" back text is gone); the backbar tag
        // is asserted right after.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Group tasks & schedules")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("group_detail_backbar").assertIsDisplayed()
    }

    @Test
    fun `empty list shows the no-groups state`() {
        render(groups = emptyList())

        composeRule.onNodeWithText("No groups yet").assertIsDisplayed()
    }

    @Test
    fun `guest sees the sign-in prompt and no create or join controls`() {
        render(user = guestUser, groups = emptyList())

        composeRule.onNodeWithText("Sign in to use groups").assertIsDisplayed()
        composeRule.onNodeWithText("Create a group").assertDoesNotExist()
        composeRule.onNodeWithText("Join a group").assertDoesNotExist()
    }

    @Test
    fun `guest sign-in button fires the sign-in callback`() {
        // Sprint 5 fix round — the ci-792 device test found this button
        // dead (the NavHost never passed onSignInClick, so it defaulted
        // to {}): the screen must honour the callback it is given.
        var clicked = false
        render(user = guestUser, groups = emptyList(), onSignInClick = { clicked = true })

        composeRule.onNodeWithText("Sign in").performClick()

        assertTrue(clicked)
    }

    @Test
    fun `tapping a group shows members and the placeholder, no group-work UI`() {
        // The detail opens via initialSelectedGroup: the row tap itself
        // is verified on the device (§10) — in the Robolectric harness
        // it opened the detail in only 1 of 4 diagnostic CI rounds
        // with identical tap code (performClick, scrollTo+click, and
        // touch-tap all flaked the same way), while the detail
        // rendering + members flow are deterministic once open.
        render(initialSelectedGroup = group())

        // Sprint 7 — the detail backbar (group name + RolePill) is the
        // open signal now; the old "All groups" back text is gone.
        composeRule.onNodeWithTag("group_detail_backbar").assertIsDisplayed()
        // Members arrive via the observed flow — wait for them like
        // ChatScreenSmokeTest waits for the seeded welcome message.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Ayesha")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Ayesha").assertIsDisplayed()
        composeRule.onNodeWithText("Tanvir").assertIsDisplayed()
        composeRule.onNodeWithText("Creator").assertIsDisplayed()
        composeRule.onNodeWithText("You").assertIsDisplayed()
        // The placeholder sits at the bottom of the scrollable detail,
        // below the fold in the harness viewport — assertExists, not
        // assertIsDisplayed (the below-fold lesson from the board tests).
        composeRule.onNodeWithText("Group tasks and notices").assertExists()
        composeRule.onNodeWithText("Add task").assertDoesNotExist()
        composeRule.onNodeWithText("Add schedule").assertDoesNotExist()
        composeRule.onNodeWithText("Delete group").assertDoesNotExist()
        composeRule.onNodeWithText("Leave group").assertDoesNotExist()
    }

    // Sprint 7 — the join/create sheets are inline cards now
    // (prototype scr-groups): the join card is always visible, the
    // create card opens from the "Create a group" button. Same
    // callbacks, same validation — the tests below prove the flows
    // through the cards.

    @Test
    fun `create card calls onCreateGroup with the typed name`() {
        var captured: String? = null
        render(onCreateGroup = { name ->
            captured = name
            GroupResult.Ok(group())
        })

        // The button sits below the fold (join card above it): scroll
        // it into view first — a below-fold performClick did not
        // reliably deliver here (run 1002 opened the card, run 1003
        // did not, identical click code), while the displayed chip
        // and Join clicks worked both runs.
        composeRule.onNodeWithText("Create a group").performScrollTo()
        composeRule.onNodeWithText("Create a group").performClick()
        composeRule.waitForIdle()
        // The card opens below the fold (title + intro + row + note +
        // join card + button sit above it), so assertExists — the card
        // is composed, just not visible on the test viewport.
        composeRule.onNodeWithText("New group").assertExists()
        // Scroll the field into view before typing: performTextInput
        // silently drops text typed into a composed-but-not-displayed
        // field. performScrollTo works on this screen — the detail
        // tests below use it throughout (no infinite transition here).
        composeRule.onNodeWithText("Group name, e.g. Flat 7B").performScrollTo()
        composeRule.onNodeWithText("Group name, e.g. Flat 7B").assertIsDisplayed()
        composeRule.onNodeWithText("Group name, e.g. Flat 7B").performTextInput("DIU 41")
        // The typed text appears twice in the semantics tree — the
        // field's EditableText AND its inner Text — so count both,
        // like "Ayesha"/"Member" elsewhere in this file. This is the
        // guard against the silent-drop trap: if the text had been
        // dropped, the count would be 0.
        assertEquals(
            2,
            composeRule.onAllNodesWithText("DIU 41").fetchSemanticsNodes().size
        )
        // The button sits below the field, still past the fold after
        // the field scroll — scroll it into view first, same
        // below-fold click-delivery lesson as the button above.
        composeRule.onNodeWithText("Create group").performScrollTo()
        composeRule.onNodeWithText("Create group").performClick()
        composeRule.waitForIdle()

        assertEquals("DIU 41", captured)
    }

    @Test
    fun `create a group button toggles the create card`() {
        render()

        composeRule.onNodeWithText("New group").assertDoesNotExist()
        // Below the fold — scroll into view first (see the test above).
        composeRule.onNodeWithText("Create a group").performScrollTo()
        composeRule.onNodeWithText("Create a group").performClick()
        composeRule.waitForIdle()
        // Below the fold once open (see the test above) — assertExists.
        composeRule.onNodeWithText("New group").assertExists()
        composeRule.onNodeWithText("Create a group").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("New group").assertDoesNotExist()
    }

    @Test
    fun `join card joins with the typed code`() {
        var captured: String? = null
        render(onJoinGroup = {
            captured = it
            GroupResult.Ok(group())
        })

        composeRule.onNodeWithText("Join a group").assertIsDisplayed()
        composeRule.onNodeWithText("Join code, e.g. FAM-2481").assertExists()
        composeRule.onNodeWithText("Join code, e.g. FAM-2481").performTextInput("K7Q2XA")
        composeRule.onNodeWithText("Join").performClick()
        composeRule.waitForIdle()

        assertEquals("K7Q2XA", captured)
    }

    @Test
    fun `join card shows the error for a wrong code`() {
        render(onJoinGroup = {
            GroupResult.Err("That code doesn't match any group. Check the code and try again.")
        })

        composeRule.onNodeWithText("Join code, e.g. FAM-2481").performTextInput("ZZZZZZ")
        composeRule.onNodeWithText("Join").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            "That code doesn't match any group. Check the code and try again."
        ).assertIsDisplayed()
    }

    @Test
    fun `deep-link prefill fills the join card field and Join sends the code`() {
        var captured: String? = null
        render(
            prefillJoinCode = "K7Q2XA",
            onJoinGroup = {
                captured = it
                GroupResult.Ok(group())
            }
        )

        composeRule.onNodeWithText("Join a group").assertIsDisplayed()
        // The prefill fills the code field: the Join button is only
        // enabled with a non-blank code, and tapping it sends exactly the
        // prefilled code. Assert the value through that real flow instead
        // of through TextField semantics (assertTextEquals did not see the
        // prefilled value in round 4).
        composeRule.onNodeWithText("Join").assertIsEnabled()
        composeRule.onNodeWithText("Join").performClick()
        composeRule.waitForIdle()

        assertEquals("K7Q2XA", captured)
    }

    // ---- Sprint 5-2: group tasks + shared completion count (feature 31)

    @Test
    fun `creator detail with tasks enabled shows Add task and the shared count`() {
        render(
            user = creator,
            allTasks = listOf(groupTask()),
            completions = mapOf("t-1" to GroupCompletion(listOf("user-1"), 1)),
            groupTasksEnabled = true,
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("+ Add a task").assertExists()
        composeRule.onNodeWithText("+ Add a schedule").assertExists()
        composeRule.onNodeWithText("Group tasks and notices").assertDoesNotExist()
        composeRule.onNodeWithText("১/২ করেছে").assertExists()
        composeRule.onNodeWithText("Homework").assertExists()
    }

    @Test
    fun `member detail shows the count but no Add task`() {
        render(
            allTasks = listOf(groupTask()),
            completions = mapOf("t-1" to GroupCompletion(listOf("user-1"), 1)),
            groupTasksEnabled = true,
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("+ Add a task").assertDoesNotExist()
        composeRule.onNodeWithText("+ Add a schedule").assertDoesNotExist()
        composeRule.onNodeWithText("১/২ করেছে").assertExists()
    }

    @Test
    fun `tapping the count lists who completed it, blank names read as Member`() {
        render(
            allTasks = listOf(groupTask()),
            completions = mapOf(
                "t-1" to GroupCompletion(listOf("user-1", "user-2"), 2)
            ),
            membersOverride = listOf(
                GroupMember(uid = "user-1", displayName = "Ayesha"),
                GroupMember(uid = "user-2", displayName = "")
            ),
            groupTasksEnabled = true,
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("২/২ করেছে").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Who completed this").assertIsDisplayed()
        // "Ayesha" appears twice — the members list AND the dialog —
        // so onNodeWithText (exactly one node) fails; count both via
        // fetchSemanticsNodes (assertCountEquals does not exist in
        // Compose 1.7 — same member-only API gap as onAllNodes).
        assertEquals(
            2,
            composeRule.onAllNodesWithText("Ayesha").fetchSemanticsNodes().size
        )
        // user-2 has a blank display name: the dialog falls back to
        // "Member", never a blank row. "Member" now appears twice —
        // the detail header's RolePill (Sprint 7) AND the dialog
        // fallback — so count both, like "Ayesha" above.
        assertEquals(
            2,
            composeRule.onAllNodesWithText("Member").fetchSemanticsNodes().size
        )
    }

    @Test
    fun `tapping the count with no completions says no one has completed it`() {
        render(
            allTasks = listOf(groupTask()),
            completions = emptyMap(),
            groupTasksEnabled = true,
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("০/২ করেছে").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Who completed this").assertIsDisplayed()
        composeRule.onNodeWithText("No one has completed this yet.").assertIsDisplayed()
    }

    @Test
    fun `member marks a task done from the detail sheet`() {
        var toggled: TaskEntity? = null
        render(
            allTasks = listOf(groupTask()),
            completions = emptyMap(),
            groupTasksEnabled = true,
            onToggleTask = { toggled = it },
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("Homework").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Mark as done").performClick()
        composeRule.waitForIdle()

        assertEquals("t-1", toggled?.id)
    }

    @Test
    fun `creator tapping Add task opens the create sheet`() {
        render(
            user = creator,
            groupTasksEnabled = true,
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("+ Add a task").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Post a group task").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- Sprint 5-3: notices + notification tap

    private fun notice(
        id: String = "n-1",
        text: String = "Class at 10",
        createdAt: Long = 2_000L
    ) = GroupNotice(id, "g-1", "user-1", "", text, createdAt)

    @Test
    fun `notices card lists notices newest first, no Send button for members`() {
        render(
            user = memberUser,
            groupTasksEnabled = true,
            groupNoticesEnabled = true,
            noticesOverride = listOf(
                notice("n-2", "Moved to 11", 3_000L),
                notice("n-1", "Class at 10", 2_000L)
            ),
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithTag("notices_card").assertExists()
        composeRule.onNodeWithText("Class at 10").assertExists()
        composeRule.onNodeWithText("Moved to 11").assertExists()
        composeRule.onNodeWithText("Post a new notice").assertDoesNotExist()
    }

    @Test
    fun `notices card shows the empty state when there are no notices`() {
        render(
            user = creator,
            groupTasksEnabled = true,
            groupNoticesEnabled = true,
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("No notices yet.").assertExists()
    }

    // Sprint 7 — the send flow lives in the inline card on the
    // Notices card now (prototype scr-group-detail), not in a sheet:
    // same validation (blank → disabled) and the same callback. The
    // field sits below the fold — scroll it into view before typing
    // (the silent-drop trap) and scroll the submit into view before
    // tapping it (the below-fold click lesson).
    @Test
    fun `creator sends a notice through the inline card`() {
        val sent = mutableListOf<Pair<String, String>>()
        render(
            user = creator,
            groupTasksEnabled = true,
            groupNoticesEnabled = true,
            onSendNotice = { groupId, text -> sent += groupId to text },
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithTag("send_notice_open").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("send_notice_field").performScrollTo()
        composeRule.onNodeWithTag("send_notice_field").assertIsDisplayed()
        composeRule.onNodeWithTag("send_notice_field").performTextInput("Class at 10")
        // The typed text lives in exactly one semantics node — the
        // field's EditableText. (The "2" copied from the list
        // create-card test counted the field PLUS the group row, whose
        // name "DIU 41" equalled the typed name; no other node here
        // shows "Class at 10".) The callback assertion below is the
        // real proof the text reached onSendNotice.
        assertEquals(
            1,
            composeRule.onAllNodesWithText("Class at 10").fetchSemanticsNodes().size
        )
        composeRule.onNodeWithTag("send_notice_submit").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("g-1" to "Class at 10"), sent)
    }

    // ---- Sprint 7: group detail restyle (spec §4, prototype
    // scr-group-detail) — backbar, pinned notice, tasks seg +
    // .task rows, invite codebox. The list tests above are untouched.

    @Test
    fun `detail backbar shows the group name and the role pill`() {
        render(user = creator, initialSelectedGroup = group())

        composeRule.onNodeWithTag("group_detail_backbar").assertIsDisplayed()
        composeRule.onNodeWithText("DIU 41").assertIsDisplayed()
        composeRule.onNodeWithText("You are admin").assertIsDisplayed()
    }

    @Test
    fun `detail shows the pinned notice with the prototype copy`() {
        render(
            user = creator,
            groupNoticesEnabled = true,
            noticesOverride = listOf(notice("n-1", "Class at 10", 2_000L)),
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("Group notice").assertExists()
        composeRule.onNodeWithText("From the admin").assertExists()
        composeRule.onNodeWithText("Class at 10").assertExists()
        composeRule.onNodeWithText(
            "stays pinned at the top of the group", substring = true
        ).assertExists()
        composeRule.onNodeWithText("Post a new notice").assertExists()
    }

    @Test
    fun `detail shows the tasks sec-title and intro`() {
        render(
            user = creator,
            groupTasksEnabled = true,
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("Group tasks & schedules").assertExists()
        composeRule.onNodeWithText(
            "Tap a “done” chip on a task to see who has finished it.",
            substring = true
        ).assertExists()
        // The "Members · N" sec-title needs the observed member flow —
        // wait for a member row first, like the detail test above.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Ayesha")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Members · 2").assertExists()
    }

    @Test
    fun `detail invite card shows the join code and share copy`() {
        render(initialSelectedGroup = group())

        composeRule.onNodeWithText("Invite members").assertExists()
        composeRule.onNodeWithText("JOIN CODE").assertExists()
        composeRule.onNodeWithText("K7Q2XA").assertExists()
        composeRule.onNodeWithText("Share code").assertExists()
        composeRule.onNodeWithText("Copy").assertExists()
        composeRule.onNodeWithText(
            "Members who leave can rejoin with the same code.",
            substring = true
        ).assertExists()
    }

    // Shared lists (micro-task 3): the detail section sits between
    // Members and Invite (prototype scr-group-detail) — sec-title +
    // verbatim intro + empty state + the "New shared list" button
    // (no creator gate — any member may create, Q1=(a)).
    @Test
    fun `detail shows the Shared lists section with the empty state`() {
        render(initialSelectedGroup = group())

        composeRule.onNodeWithText("Shared lists").assertExists()
        composeRule.onNodeWithText(
            "A shared list belongs to the whole group",
            substring = true
        ).assertExists()
        composeRule.onNodeWithText("No shared lists yet.").assertExists()
        composeRule.onNodeWithTag("new_shared_list_open").assertExists()
    }

    @Test
    fun `detail shared-list row shows the name and counts`() {
        render(
            sharedListsOverride = listOf(
                SharedListEntity(
                    id = "l-1", groupId = "g-1", name = "Monthly shopping",
                    createdByUid = "user-1", createdByName = "Ayesha"
                )
            ),
            onGetSharedListCounts = { 3 to 1 },
            initialSelectedGroup = group()
        )

        // The counts load in an effect, so wait for the meta text
        // instead of asserting the initial 0/0.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("3 items · 1 done · tap to open")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Monthly shopping").assertExists()
    }

    @Test
    fun `new shared list card creates through the callback and closes when the list lands`() {
        val created = mutableListOf<Pair<String, String>>()
        val listsFlow = MutableStateFlow<List<SharedListEntity>>(emptyList())
        render(
            sharedListsFlowOverride = listsFlow,
            onCreateSharedList = { groupId, name -> created += groupId to name },
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithTag("new_shared_list_open").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_shared_list_field").performScrollTo()
        composeRule.onNodeWithTag("new_shared_list_field").assertIsDisplayed()
        composeRule.onNodeWithTag("new_shared_list_field").performTextInput("Eid shopping")
        composeRule.onNodeWithTag("create_shared_list_submit").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("g-1" to "Eid shopping"), created)
        // The list has not landed in the flow yet, so the card stays
        // open with the typed name intact (the error case behaves
        // the same — the card only closes on a real new row).
        composeRule.onNodeWithTag("new_shared_list_field").assertIsDisplayed()

        listsFlow.value = listOf(
            SharedListEntity(
                id = "l-1", groupId = "g-1", name = "Eid shopping",
                createdByUid = "user-2", createdByName = "Tanvir"
            )
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("create_shared_list_submit")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("create_shared_list_submit").assertDoesNotExist()
    }

    // Shared lists (micro-task 4): the list detail screen — backbar
    // + intro + items card (checkbox + text) + ADD AN ITEM card +
    // Activity history filtered to this list. The row body tap is a
    // no-op (the item sheet lands in micro-task 5); the checkbox
    // toggles, the delete icon is adder-or-creator only.
    private fun sharedList(
        id: String = "l-1",
        name: String = "Monthly shopping"
    ) = SharedListEntity(
        id = id, groupId = "g-1", name = name,
        createdByUid = "user-1", createdByName = "Ayesha"
    )

    private fun sharedListItem(
        id: String = "i-1",
        text: String = "Milk 2 L",
        addedByUid: String = "user-1",
        done: Boolean = false
    ) = SharedListItemEntity(
        id = id, listId = "l-1", groupId = "g-1", text = text,
        addedByUid = addedByUid, addedByName = "Ayesha", done = done
    )

    @Test
    fun `shared list detail shows items and the add card`() {
        render(
            sharedListItemsOverride = listOf(
                sharedListItem(),
                sharedListItem(id = "i-2", text = "Rice 10 kg", addedByUid = "user-2", done = true)
            ),
            initialSelectedGroup = group(),
            initialSelectedSharedList = sharedList()
        )

        composeRule.onNodeWithTag("shared_list_detail_backbar").assertExists()
        composeRule.onNodeWithText("Monthly shopping").assertExists()
        composeRule.onNodeWithText("Milk 2 L").assertExists()
        composeRule.onNodeWithText("Rice 10 kg").assertExists()
        composeRule.onNodeWithText("ADD AN ITEM").assertExists()
        composeRule.onNodeWithTag("add_shared_list_item_field").assertExists()
        composeRule.onNodeWithTag("add_shared_list_item_submit").assertExists()
        composeRule.onNodeWithText("Activity history").assertExists()
        composeRule.onNodeWithText("No activity yet.").assertExists()
    }

    @Test
    fun `shared list detail checkbox toggles through the callback`() {
        val toggled = mutableListOf<Triple<String, String, SharedListItemEntity>>()
        render(
            sharedListItemsOverride = listOf(sharedListItem()),
            onToggleSharedListItem = { groupId, listId, item ->
                toggled += Triple(groupId, listId, item)
            },
            initialSelectedGroup = group(),
            initialSelectedSharedList = sharedList()
        )

        // The checkbox carries its own test tag (NudgyCheckbox's
        // optional testTag param) — neither onAllNodesWithRole nor
        // hasRole exists in this Compose 1.7 build.
        composeRule.onNodeWithTag("shared_list_item_checkbox_i-1").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(Triple("g-1", "l-1", sharedListItem())), toggled)
    }

    @Test
    fun `add shared list item calls the callback and clears the field when it lands`() {
        val added = mutableListOf<Triple<String, String, String>>()
        val itemsFlow = MutableStateFlow<List<SharedListItemEntity>>(emptyList())
        render(
            sharedListItemsFlowOverride = itemsFlow,
            onAddSharedListItem = { groupId, listId, text ->
                added += Triple(groupId, listId, text)
            },
            initialSelectedGroup = group(),
            initialSelectedSharedList = sharedList()
        )

        composeRule.onNodeWithTag("add_shared_list_item_field").performScrollTo()
        composeRule.onNodeWithTag("add_shared_list_item_field").assertIsDisplayed()
        composeRule.onNodeWithTag("add_shared_list_item_field").performTextInput("Milk 2 L")
        composeRule.onNodeWithTag("add_shared_list_item_submit").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(Triple("g-1", "l-1", "Milk 2 L")), added)
        // The item has not landed in the flow yet, so the field keeps
        // its text (the error case behaves the same — the field only
        // clears on a real new row).
        composeRule.onNodeWithTag("add_shared_list_item_submit").assertIsEnabled()

        itemsFlow.value = listOf(sharedListItem())
        // The placeholder only renders when the field is empty, so
        // waiting for it is waiting for the clear itself (the item
        // text "Milk 2 L" would match the field's typed text too).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("e.g. Milk 2 L")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The field cleared once the item landed, so Add is disabled.
        composeRule.onNodeWithTag("add_shared_list_item_submit").assertIsNotEnabled()
    }

    @Test
    fun `delete icon shows only for the adder or the creator`() {
        // memberUser (user-2) added i-2 but not i-1 (added by user-1,
        // the group creator) — only i-2 shows the icon.
        render(
            sharedListItemsOverride = listOf(
                sharedListItem(),
                sharedListItem(id = "i-2", text = "Rice 10 kg", addedByUid = "user-2")
            ),
            initialSelectedGroup = group(),
            initialSelectedSharedList = sharedList()
        )

        composeRule.onNodeWithTag("delete_shared_list_item_i-1").assertDoesNotExist()
        composeRule.onNodeWithTag("delete_shared_list_item_i-2").assertExists()
    }

    @Test
    fun `delete icon shows for the group creator on every item`() {
        render(
            user = creator,
            sharedListItemsOverride = listOf(
                sharedListItem(),
                sharedListItem(id = "i-2", text = "Rice 10 kg", addedByUid = "user-2")
            ),
            initialSelectedGroup = group(),
            initialSelectedSharedList = sharedList()
        )

        composeRule.onNodeWithTag("delete_shared_list_item_i-1").assertExists()
        composeRule.onNodeWithTag("delete_shared_list_item_i-2").assertExists()
    }

    @Test
    fun `shared list detail activity shows only this lists entries`() {
        render(
            activityOverride = listOf(
                GroupActivityEntry(
                    id = "a-1", groupId = "g-1", actorUid = "user-1", actorName = "Ayesha",
                    type = GroupActivityType.LIST_CREATED, itemId = "l-1",
                    itemTitle = "Monthly shopping",
                    createdAtEpochMillis = 1_700_000_000_000L
                ),
                GroupActivityEntry(
                    id = "a-2", groupId = "g-1", actorUid = "user-1", actorName = "Ayesha",
                    type = GroupActivityType.LIST_ITEM_ADDED, itemId = "i-1",
                    itemTitle = "Milk 2 L",
                    createdAtEpochMillis = 1_700_000_000_000L
                ),
                GroupActivityEntry(
                    id = "a-3", groupId = "g-1", actorUid = "user-2", actorName = "Tanvir",
                    type = GroupActivityType.LIST_CREATED, itemId = "l-2",
                    itemTitle = "Other list",
                    createdAtEpochMillis = 1_700_000_000_000L
                )
            ),
            sharedListItemsOverride = listOf(sharedListItem()),
            initialSelectedGroup = group(),
            initialSelectedSharedList = sharedList()
        )

        composeRule.onNodeWithText("Ayesha created list \u201CMonthly shopping\u201D").assertExists()
        composeRule.onNodeWithText("Ayesha added \u201CMilk 2 L\u201D").assertExists()
        composeRule.onNodeWithText("Tanvir created list \u201COther list\u201D").assertDoesNotExist()
    }

    private fun groupEvent(
        id: String = "e-1",
        title: String = "Planning meet"
    ) = CalendarEventEntity(
        id = id, userId = "user-1", title = title,
        startEpochMillis = 1_800_000_000_000L,
        endEpochMillis = 1_800_003_600_000L,
        groupId = "g-1"
    )

    @Test
    fun `detail schedule section lists events`() {
        render(
            user = creator,
            groupTasksEnabled = true,
            allEvents = listOf(groupEvent()),
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("Schedule").assertExists()
        composeRule.onNodeWithText("1 events").assertExists()
        composeRule.onNodeWithText("Planning meet").assertExists()
    }

    @Test
    fun `openGroupId opens the detail for the notified group`() {
        render(
            groupTasksEnabled = true,
            groupNoticesEnabled = true,
            openGroupId = "g-1"
        )

        composeRule.onNodeWithTag("group_detail_backbar").assertIsDisplayed()
        composeRule.onNodeWithText("Ayesha").assertExists()
    }

    @Test
    fun `openGroupId with an unknown group stays on the list`() {
        render(
            groupTasksEnabled = true,
            groupNoticesEnabled = true,
            openGroupId = "g-gone"
        )

        composeRule.onNodeWithTag("group_detail_backbar").assertDoesNotExist()
        composeRule.onNodeWithText("DIU 41").assertExists()
    }

    // ---- Sprint 5-4: delete / leave (feature 33, spec §7)

    @Test
    fun `creator sees Delete group and confirming fires the callback`() {
        var deleted: String? = null
        render(
            user = creator,
            groupWorkEnabled = true,
            initialSelectedGroup = group(),
            onDeleteGroup = { deleted = it }
        )

        composeRule.onNodeWithText("Delete group").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete this group?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()

        assertEquals("g-1", deleted)
    }

    @Test
    fun `member sees Leave group and confirming fires the callback`() {
        var left: String? = null
        render(
            groupWorkEnabled = true,
            initialSelectedGroup = group(),
            onLeaveGroup = { left = it }
        )

        composeRule.onNodeWithText("Leave group").performScrollTo().performClick()
        composeRule.onNodeWithText("Leave this group?").assertIsDisplayed()
        composeRule.onNodeWithText("Leave").performClick()

        assertEquals("g-1", left)
    }

    @Test
    fun `delete and leave buttons are hidden when groupWorkEnabled is off`() {
        render(initialSelectedGroup = group())

        composeRule.onNodeWithText("Delete group").assertDoesNotExist()
        composeRule.onNodeWithText("Leave group").assertDoesNotExist()
    }

    // ---- Sprint 5-5: assign + activity history (feature 36, Q10=(a))

    private fun activity(
        id: String = "a-1",
        type: String = GroupActivityType.TASK_CREATED,
        actorUid: String = "user-1",
        actorName: String = "Ayesha",
        itemTitle: String? = "Homework",
        assigneeName: String? = null,
        createdAt: Long = 2_000L
    ) = GroupActivityEntry(
        id, "g-1", actorUid, actorName, type,
        itemId = "i-1", itemTitle = itemTitle,
        assigneeName = assigneeName, createdAtEpochMillis = createdAt
    )

    @Test
    fun `creator detail shows the assignee name on a task row`() {
        render(
            user = creator,
            groupTasksEnabled = true,
            groupAssignEnabled = true,
            allTasks = listOf(
                groupTask().copy(assigneeUid = "user-2", assigneeName = "Tanvir")
            ),
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("Assigned to Tanvir").assertExists()
    }

    @Test
    fun `creator assign button opens the dialog and picking a member fires the callback`() {
        val assigned = mutableListOf<Triple<String, String, String?>>()
        render(
            user = creator,
            groupTasksEnabled = true,
            groupAssignEnabled = true,
            allTasks = listOf(groupTask()),
            onAssignGroupTask = { groupId, taskId, uid -> assigned += Triple(groupId, taskId, uid) },
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithTag("assign_task_open").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Assign task").assertIsDisplayed()
        composeRule.onNodeWithTag("assign_member_row_user-2").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(Triple("g-1", "t-1", "user-2")), assigned)
    }

    @Test
    fun `assign dialog marks the current assignee and Nobody unassigns`() {
        val assigned = mutableListOf<Triple<String, String, String?>>()
        render(
            user = creator,
            groupTasksEnabled = true,
            groupAssignEnabled = true,
            allTasks = listOf(
                groupTask().copy(assigneeUid = "user-2", assigneeName = "Tanvir")
            ),
            onAssignGroupTask = { groupId, taskId, uid -> assigned += Triple(groupId, taskId, uid) },
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithTag("assign_task_open").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tanvir (assigned)").assertExists()
        composeRule.onNodeWithTag("assign_member_row_nobody").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(Triple("g-1", "t-1", null)), assigned)
    }

    @Test
    fun `member detail shows the assignee but no assign button`() {
        render(
            user = memberUser,
            groupTasksEnabled = true,
            groupAssignEnabled = true,
            allTasks = listOf(
                groupTask().copy(assigneeUid = "user-2", assigneeName = "Tanvir")
            ),
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("Assigned to Tanvir").assertExists()
        composeRule.onNodeWithTag("assign_task_open").assertDoesNotExist()
    }

    @Test
    fun `activity card lists entries with their sentence`() {
        render(
            user = creator,
            groupTasksEnabled = true,
            groupAssignEnabled = true,
            activityOverride = listOf(
                activity("a-2", GroupActivityType.TASK_ASSIGNED, assigneeName = "Tanvir", createdAt = 3_000L),
                activity("a-1", GroupActivityType.TASK_CREATED, createdAt = 2_000L)
            ),
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithTag("activity_card").assertExists()
        composeRule.onNodeWithText("Ayesha assigned \u201CHomework\u201D to Tanvir").assertExists()
        composeRule.onNodeWithText("Ayesha created task \u201CHomework\u201D").assertExists()
    }

    @Test
    fun `activity card shows the empty state when there is no history`() {
        render(
            user = creator,
            groupTasksEnabled = true,
            groupAssignEnabled = true,
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithText("No activity yet.").assertExists()
    }

    @Test
    fun `activity card is hidden when groupAssignEnabled is off`() {
        render(
            user = creator,
            groupTasksEnabled = true,
            activityOverride = listOf(activity()),
            initialSelectedGroup = group()
        )

        composeRule.onNodeWithTag("activity_card").assertDoesNotExist()
    }

}

/** Sprint 5-1 — the invite link format (pure, no Compose). */
class GroupInviteLinkTest {

    @Test
    fun `join link carries the code as a query param`() {
        assertEquals(
            "https://tanvir-durlove.github.io/nudgy/join?code=K7Q2XA",
            groupJoinLink("K7Q2XA")
        )
    }

    @Test
    fun `invite text names the group and the link`() {
        assertEquals(
            "Join \"DIU 41\" on Nudgy: https://tanvir-durlove.github.io/nudgy/join?code=K7Q2XA",
            groupInviteText("DIU 41", "K7Q2XA")
        )
    }
}
