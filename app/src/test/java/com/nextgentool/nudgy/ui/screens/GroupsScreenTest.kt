package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.GroupCompletion
import com.nextgentool.nudgy.data.repository.GroupMember
import com.nextgentool.nudgy.data.repository.GroupNotice
import com.nextgentool.nudgy.data.repository.GroupResult
import com.nextgentool.nudgy.ui.theme.NudgyTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
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
 * (members + placeholder, no group-work UI), the create/join sheets,
 * and the deep-link prefill. Compose 1.7 member rules apply:
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
        completions: Map<String, GroupCompletion> = emptyMap(),
        membersOverride: List<GroupMember>? = null,
        groupTasksEnabled: Boolean = false,
        groupNoticesEnabled: Boolean = false,
        noticesOverride: List<GroupNotice> = emptyList(),
        onSendNotice: (String, String) -> Unit = { _, _ -> },
        onToggleTask: (TaskEntity) -> Unit = {},
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
                    allEvents = emptyList(),
                    onCreateGroup = onCreateGroup,
                    onJoinGroup = onJoinGroup,
                    onObserveMembers = { flowOf(shownMembers) },
                    onRefreshMembers = { shownMembers },
                    onToggleTask = onToggleTask,
                    completions = completions,
                    groupTasksEnabled = groupTasksEnabled,
                    groupNoticesEnabled = groupNoticesEnabled,
                    onObserveNotices = { flowOf(noticesOverride) },
                    onRefreshNotices = { noticesOverride },
                    onSendNotice = onSendNotice,
                    openGroupId = openGroupId,
                    prefillJoinCode = prefillJoinCode,
                    initialSelectedGroup = initialSelectedGroup
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
    fun `list shows groups with name code and role pill`() {
        render()

        composeRule.onNodeWithText("DIU 41").assertIsDisplayed()
        composeRule.onNodeWithText("K7Q2XA").assertIsDisplayed()
        // user-2 is a member of user-1's group, not the creator.
        composeRule.onNodeWithText("MEMBER").assertIsDisplayed()
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
        composeRule.onNodeWithText("Create").assertDoesNotExist()
        composeRule.onNodeWithText("Join with code").assertDoesNotExist()
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

        composeRule.onNodeWithText("All groups").assertIsDisplayed()
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

    @Test
    fun `create sheet calls onCreateGroup with the typed name`() {
        var captured: String? = null
        render(onCreateGroup = { name ->
            captured = name
            GroupResult.Ok(group())
        })

        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Group name").performTextInput("DIU 41")
        composeRule.onNodeWithText("Create group").performClick()
        composeRule.waitForIdle()

        assertEquals("DIU 41", captured)
    }

    @Test
    fun `join sheet shows the error for a wrong code`() {
        render(onJoinGroup = {
            GroupResult.Err("That code doesn't match any group. Check the code and try again.")
        })

        composeRule.onNodeWithText("Join with code").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Join code").performTextInput("ZZZZZZ")
        composeRule.onNodeWithText("Join group").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            "That code doesn't match any group. Check the code and try again."
        ).assertIsDisplayed()
    }

    @Test
    fun `deep-link prefill opens the join sheet with the code`() {
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
        composeRule.onNodeWithText("Join group").assertIsEnabled()
        composeRule.onNodeWithText("Join group").performClick()
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

        composeRule.onNodeWithText("Add task").assertExists()
        composeRule.onNodeWithText("Add schedule").assertExists()
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

        composeRule.onNodeWithText("Add task").assertDoesNotExist()
        composeRule.onNodeWithText("Add schedule").assertDoesNotExist()
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
        // "Member", never a blank row.
        composeRule.onNodeWithText("Member").assertIsDisplayed()
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

        composeRule.onNodeWithText("Add task").performScrollTo().performClick()
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
        composeRule.onNodeWithText("Send notice").assertDoesNotExist()
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

    @Test
    fun `creator sends a notice through the sheet`() {
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
        composeRule.onNodeWithTag("send_notice_field").performTextInput("Class at 10")
        composeRule.onNodeWithTag("send_notice_submit").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("g-1" to "Class at 10"), sent)
    }

    @Test
    fun `openGroupId opens the detail for the notified group`() {
        render(
            groupTasksEnabled = true,
            groupNoticesEnabled = true,
            openGroupId = "g-1"
        )

        composeRule.onNodeWithText("All groups").assertIsDisplayed()
        composeRule.onNodeWithText("Ayesha").assertExists()
    }

    @Test
    fun `openGroupId with an unknown group stays on the list`() {
        render(
            groupTasksEnabled = true,
            groupNoticesEnabled = true,
            openGroupId = "g-gone"
        )

        composeRule.onNodeWithText("All groups").assertDoesNotExist()
        composeRule.onNodeWithText("DIU 41").assertExists()
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
