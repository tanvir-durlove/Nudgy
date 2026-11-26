package com.nextgentool.nudgy.ui.screens

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskCategory
import com.nextgentool.nudgy.data.repository.GroupActivityEntry
import com.nextgentool.nudgy.data.repository.GroupCompletion
import com.nextgentool.nudgy.data.repository.GroupMember
import com.nextgentool.nudgy.data.repository.GroupNotice
import com.nextgentool.nudgy.data.repository.GroupResult
import com.nextgentool.nudgy.data.repository.MAX_NOTICE_TEXT_LENGTH
import com.nextgentool.nudgy.data.repository.activityDisplayText
import com.nextgentool.nudgy.data.repository.effectiveCompletion
import com.nextgentool.nudgy.data.repository.formatCompletionCount
import com.nextgentool.nudgy.ui.components.NudgyCheckbox
import com.nextgentool.nudgy.ui.sheets.CreateTaskSheet
import com.nextgentool.nudgy.ui.sheets.EditTaskSheet
import com.nextgentool.nudgy.ui.sheets.GroupScheduleSheet
import com.nextgentool.nudgy.ui.sheets.GroupTaskDetailSheet
import com.nextgentool.nudgy.ui.theme.NudgyAvatarPalettes
import com.nextgentool.nudgy.ui.theme.NudgyBorder
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyGreen
import com.nextgentool.nudgy.ui.theme.NudgyLavender
import com.nextgentool.nudgy.ui.theme.NudgyMemberPillText
import com.nextgentool.nudgy.ui.theme.NudgySage
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyFieldBorder
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPrimary
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Groups tab (Group v1).
 *
 * List view: the user's groups with join codes. Detail view: the group's items
 * (tasks + schedules) with origin context. Rules enforced here:
 * - Guests see a sign-in prompt, never create/join controls.
 * - Only the creator sees Add task / Add schedule and the task/schedule delete icons.
 * - Figma redesign: hero + avatar cards, invite-code card, local progress bar;
 *   copy is generic; theme is Groups-only, token-based.
 * - Sprint 7 detail restyle (prototype scr-group-detail): backbar with the
 *   group name + RolePill; the notice card pinned at the top with the send
 *   flow in an inline card (the SendNoticeSheet is gone); "Group tasks &
 *   schedules" sec-title + intro with a two-pill seg as the creation entry
 *   (each pill opens the same sheet as the old buttons — no inline
 *   quick-add field, the sheets carry the full forms); task rows as
 *   .task buttons (checkbox + priority dot + donechip); Members, Schedule,
 *   Activity as sec-titles above cards; the invite code as a cream codebox.
 *   The aggregate "My progress" card is gone (the prototype has none —
 *   the per-task donechips carry the same info). Shared lists have no
 *   section yet (no shared-list data flows into the detail — that lands
 *   with the Shared-list micro-task).
 * - Restyle: one card system (20dp radius, 2dp elevation, 16dp card padding,
 *   20dp section gaps). Every section lives inside its own card (header +
 *   divider + rows) so no title is bare text on the background; member rows
 *   are avatar rows in a card.
 * - Tapping a group task opens EditTaskSheet for the creator (cloud write via
 *   GroupRepository) and the read-only GroupTaskDetailSheet for members
 *   (local completion toggle only).
 * - Schedule edit is not in v1: the creator deletes and re-creates a schedule
 *   to change it (see GroupScheduleSheet note).
 * - Phase 3: group detail shows the member list and a share-invite button;
 *   the creator sees Delete group, members see Leave (both confirmed).
 * - Sprint 5-1: the group-work sections (tasks, schedules, delete/leave)
 *   are gated behind groupWorkEnabled and land in S5-2/S5-4; until then
 *   the detail shows members + a placeholder.
 * - Sprint 5-2: the task/schedule sections moved behind groupTasksEnabled
 *   (split from groupWorkEnabled, which gates delete/leave). Each group
 *   task row shows the shared completion count
 *   ("৩/৫ করেছে", denominator = current members); tapping the count
 *   lists who completed it. The count is tasks-only (Q4=(a)).
 * - Sprint 5-4: delete/leave land (feature 33) — the creator's Delete
 *   group cascade and the member's Leave + rejoin, both confirmed, now
 *   behind groupWorkEnabled (ON in the NavHost).
 * - Sprint 5-3: the notices card behind groupNoticesEnabled — text-only
 *   admin notices (Q2=(a)), newest first, live via the listener; only
 *   the creator sees Post a new notice. A notification tap opens the detail
 *   for the notified group (openGroupId).
 * - Sprint 5-5: assign + activity history (feature 36, Q10=(a)) behind
 *   groupAssignEnabled — only the creator assigns (Q3=(b)); the
 *   assignee's name shows on the task row (and in the member's own
 *   list, from the denormalized name on the row); the Activity card
 *   lists who did what, newest first, live via the listener.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupsScreen(
    user: NudgyUser,
    groups: List<GroupEntity>,
    allTasks: List<TaskEntity>,
    allEvents: List<CalendarEventEntity>,
    onCreateGroup: suspend (name: String) -> GroupResult<GroupEntity>,
    onJoinGroup: suspend (code: String) -> GroupResult<GroupEntity>,
    // The group-work callbacks (tasks, schedules, delete/leave) all
    // landed by S5-4; they still default to no-ops here so the screen
    // stays renderable in tests that pass only the create/join/member
    // wiring, never dead buttons.
    onCreateGroupTask: (
        groupId: String, title: String, description: String?, dueEpochMillis: Long,
        priority: Priority, category: TaskCategory, reminderSet: Boolean
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onCreateGroupSchedule: (
        groupId: String, title: String, startEpochMillis: Long, endEpochMillis: Long,
        locationOrLink: String?, eventType: String
    ) -> Unit = { _, _, _, _, _, _ -> },
    onUpdateGroupTask: (groupId: String, task: TaskEntity) -> Unit = { _, _ -> },
    onSignInClick: () -> Unit = {},
    onDeleteGroupItem: (groupId: String, itemId: String, isTask: Boolean) -> Unit = { _, _, _ -> },
    onDeleteGroup: (groupId: String) -> Unit = {},
    onLeaveGroup: (groupId: String) -> Unit = {},
    // Member list (minimization): cached rows flow from Room instantly,
    // refresh fetches remotely in the background on every detail open.
    onObserveMembers: (groupId: String) -> Flow<List<GroupMember>>,
    onRefreshMembers: suspend (groupId: String) -> List<GroupMember>,
    onToggleTask: (TaskEntity) -> Unit = {},
    // Sprint 5-2 — the shared completion per item doc (raw map from the
    // ViewModel; the detail filters each row to current members with
    // effectiveCompletion, Q6=(a)). Tasks only — schedules carry no count.
    completions: Map<String, GroupCompletion> = emptyMap(),
    // Sprint 5-2 — task/schedule sections. Split from groupWorkEnabled
    // (S5-4: delete/leave) so the task UI lands without dead buttons.
    groupTasksEnabled: Boolean = false,
    // Sprint 5-4 — group delete/leave (feature 33, spec §7): the
    // creator's Delete group cascade and the member's Leave + rejoin,
    // both confirmed. ON in the NavHost.
    groupWorkEnabled: Boolean = false,
    // Sprint 5-3 — notices (feature 32, Q2=(a) text-only). Same
    // observe + refresh pattern as members; the list is live via the
    // listener, the refresh warms it on detail open.
    groupNoticesEnabled: Boolean = false,
    onObserveNotices: (groupId: String) -> Flow<List<GroupNotice>> = { flowOf(emptyList()) },
    onRefreshNotices: suspend (groupId: String) -> List<GroupNotice> = { emptyList() },
    onSendNotice: (groupId: String, text: String) -> Unit = { _, _ -> },
    // Sprint 5-5 — assign + activity history (feature 36, Q10=(a)).
    // Same observe + refresh pattern as notices; the assign dialog is
    // creator-only (Q3=(b) — assigning is an edit). A null assignee
    // unassigns.
    groupAssignEnabled: Boolean = false,
    onObserveActivity: (groupId: String) -> Flow<List<GroupActivityEntry>> = { flowOf(emptyList()) },
    onRefreshActivity: suspend (groupId: String) -> List<GroupActivityEntry> = { emptyList() },
    onAssignGroupTask: (groupId: String, itemId: String, assigneeUid: String?) -> Unit = { _, _, _ -> },
    // Sprint 5-3 — notification tap: opens the detail for this group
    // (the worker puts the group id in the launch intent; MainActivity
    // feeds it through NudgyApp → NavHost). Consumed once — even when
    // the group is not found (stale id), so a later tap can never loop.
    openGroupId: String? = null,
    onOpenGroupIdConsumed: () -> Unit = {},
    prefillJoinCode: String? = null,
    onPrefillJoinCodeConsumed: () -> Unit = {},
    // Detail entry: opens the detail for this group on first composition.
    // The NavHost leaves it null (tap-to-open); S5-3 notification taps
    // will pass the notified group here. The detail test also uses it:
    // the row tap proved untestable in the Robolectric harness (the
    // detail opened in only 1 of 4 diagnostic CI rounds with identical
    // tap code), so the tap itself is verified on the device (§10) and
    // CI verifies the detail rendering through this entry point.
    initialSelectedGroup: GroupEntity? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    // Sprint 7 — prototype accents for the list restyle. The Groups
    // screen's own palette already matches the Sprint 7 light tokens
    // (canvas #F4F6F5, charcoal #111827, muted #475569), so those stay.
    val teal = colorResource(R.color.nudgy_teal)
    val tealSoft = colorResource(R.color.nudgy_teal_soft)
    val tealDark = colorResource(R.color.nudgy_teal_dark)
    val cream = colorResource(R.color.nudgy_cream)
    val creamDark = colorResource(R.color.nudgy_cream_dark)
    val taskDateFormatter = remember {
        SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
    }
    val eventTimeFormatter = remember {
        SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
    }

    var selectedGroup by remember { mutableStateOf(initialSelectedGroup) }
    // Sprint 7 — the join/create flows live in inline cards on the
    // list now (prototype scr-groups), not in bottom sheets: the join
    // card is always visible, the create card opens from the
    // "Create a group" button. Same callbacks, same validation, same
    // results — only the presentation moved.
    val scope = rememberCoroutineScope()
    var joinCode by remember { mutableStateOf("") }
    var joinError by remember { mutableStateOf<String?>(null) }
    var joinBusy by remember { mutableStateOf(false) }
    var showCreateCard by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }
    var createBusy by remember { mutableStateOf(false) }
    // Deep-link prefill (one-shot): MainActivity delivers a join code
    // from an App Links intent; it fills the join card's field and the
    // user still taps Join — never auto-joined.
    LaunchedEffect(prefillJoinCode) {
        if (prefillJoinCode != null) {
            joinCode = prefillJoinCode.uppercase().filter { it.isLetterOrDigit() }
            onPrefillJoinCodeConsumed()
        }
    }
    var showCreateTask by remember { mutableStateOf(false) }
    var showCreateSchedule by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<TaskEntity?>(null) }
    var detailTask by remember { mutableStateOf<TaskEntity?>(null) }
    // Sprint 5-2 — the task whose "who completed" list is open.
    var whoCompletedTask by remember { mutableStateOf<TaskEntity?>(null) }
    // Sprint 5-5 — the task whose assign dialog is open (creator only).
    var assignTask by remember { mutableStateOf<TaskEntity?>(null) }
    var schedulePendingDelete by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var taskPendingDelete by remember { mutableStateOf<TaskEntity?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<GroupEntity?>(null) }
    var leaveGroupTarget by remember { mutableStateOf<GroupEntity?>(null) }
    // Members for the selected group, observed declaratively:
    // collectAsState is driven by the composition, so the rows arrive
    // with recomposition. (A manual LaunchedEffect + collect did not
    // deliver in the Robolectric harness — the detail test's members
    // never arrived in rounds 5-8.) The refresh effect only warms the
    // cache and records whether the refresh came back empty, for the
    // "No members yet." fallback.
    val selectedGroupId = selectedGroup?.groupId
    val membersFlow = remember(selectedGroupId) {
        if (selectedGroupId == null) flowOf(emptyList())
        else onObserveMembers(selectedGroupId)
    }
    val flowMembers by membersFlow.collectAsState(initial = null)
    var refreshEmpty by remember(selectedGroupId) { mutableStateOf(false) }
    LaunchedEffect(selectedGroupId) {
        val groupId = selectedGroupId ?: return@LaunchedEffect
        val refreshed = try {
            onRefreshMembers(groupId)
        } catch (e: Exception) {
            emptyList()
        }
        if (refreshed.isEmpty()) refreshEmpty = true
    }
    // An empty flow emission means "not loaded yet" (a group always has
    // its creator as a member): keep the loading state until real rows
    // arrive, unless the refresh already came back empty.
    val members: List<GroupMember>? = when {
        flowMembers != null && flowMembers!!.isNotEmpty() -> flowMembers
        refreshEmpty -> emptyList()
        else -> null
    }
    // Sprint 5-3 — notices for the selected group, observed
    // declaratively (same pattern as members). An empty list is a real
    // state ("No notices yet."), not loading — no tri-state needed.
    var showSendNotice by remember { mutableStateOf(false) }
    // Sprint 7 — the notice draft for the inline send card (same
    // validation as the old sheet: capped at MAX_NOTICE_TEXT_LENGTH).
    var noticeText by remember { mutableStateOf("") }
    val noticesFlow = remember(selectedGroupId) {
        if (selectedGroupId == null) flowOf(emptyList())
        else onObserveNotices(selectedGroupId)
    }
    val notices by noticesFlow.collectAsState(initial = emptyList())
    LaunchedEffect(selectedGroupId) {
        val groupId = selectedGroupId ?: return@LaunchedEffect
        try {
            onRefreshNotices(groupId)
        } catch (e: Exception) {
            // Refresh is best-effort — the listener feeds the list.
        }
    }
    // Sprint 5-5 — activity history for the selected group, observed
    // declaratively (same pattern as notices). An empty list is a real
    // state ("No activity yet."), not loading — no tri-state needed.
    val activityFlow = remember(selectedGroupId) {
        if (selectedGroupId == null) flowOf(emptyList())
        else onObserveActivity(selectedGroupId)
    }
    val activity by activityFlow.collectAsState(initial = emptyList())
    LaunchedEffect(selectedGroupId) {
        val groupId = selectedGroupId ?: return@LaunchedEffect
        try {
            onRefreshActivity(groupId)
        } catch (e: Exception) {
            // Refresh is best-effort — the listener feeds the list.
        }
    }
    // Sprint 5-3 — notification tap: open the detail once the group
    // list has loaded (a stale id is consumed without opening).
    LaunchedEffect(openGroupId, groups) {
        if (openGroupId != null && groups.isNotEmpty()) {
            groups.firstOrNull { it.groupId == openGroupId }?.let { selectedGroup = it }
            onOpenGroupIdConsumed()
        }
    }
    // A group deleted elsewhere (creator cascade, member purge) must not
    // linger as a stale detail view.
    LaunchedEffect(groups) {
        val selected = selectedGroup
        if (selected != null && groups.none { it.groupId == selected.groupId }) {
            selectedGroup = null
        }
    }

    fun copyCode(code: String) {
        clipboardManager.setText(AnnotatedString(code))
        Toast.makeText(context, "Code copied: $code", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NudgyTokens.screenPadding, vertical = 16.dp)
    ) {
        if (user.isGuest) {
            Text(
                text = "Groups",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = NudgyCard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Animated placeholder: three people bobbing in turn
                    // (AnimatedVectorDrawable — Compose has no raw SVG).
                    AndroidView(
                        factory = { ctx ->
                            ImageView(ctx).apply {
                                setImageResource(R.drawable.avd_groups_placeholder)
                                (drawable as? AnimatedVectorDrawable)?.start()
                            }
                        },
                        modifier = Modifier.size(120.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Sign in to use groups",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = NudgyCharcoal,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Groups need a signed-in account. Sign in from your " +
                            "profile to create a group or join one with a code.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = NudgyMuted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onSignInClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NudgyPrimary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(100.dp)
                    ) {
                        Text(
                            text = "Sign in",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            return@Column
        }

        val group = selectedGroup
        if (group == null) {
            // ------------------------------------------------ list view
            // Sprint 7 — title + intro (prototype scr-groups,
            // verbatim). The hero banner and its Create / Join with
            // code buttons are gone: join and create live in inline
            // cards below the list now.
            Text(
                text = "Groups",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 21.sp, fontWeight = FontWeight.ExtraBold
                ),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tasks and schedules shared with your group — anything added here appears in every member's own list, with a group badge so you know where it came from.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(14.dp))
            if (groups.isEmpty()) {
                Surface(
                    shape = NudgyTokens.cardRadius,
                    color = NudgyCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            tint = NudgyMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No groups yet",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = NudgyCharcoal
                        )
                        Text(
                            text = "Create one and share the code, or join with a code " +
                                "from the group owner.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted
                        )
                    }
                }
            } else {
                groups.sortedBy { it.name.lowercase() }.forEach { item ->
                    val itemTaskCount = allTasks.count {
                        it.groupId == item.groupId && !it.isCompleted
                    }
                    // Sprint 7 — prototype avatar (.avatar): a 44dp
                    // circle with the group's first letter; teal-soft
                    // by default, cream for the hashed variant (the
                    // prototype's .coralbg).
                    val isCreamAvatar = (item.name.hashCode() and Int.MAX_VALUE) % 2 == 1
                    val isCreator = item.creatorUid == user.uid
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = NudgyCard,
                        shadowElevation = 2.dp,
                        onClick = { selectedGroup = item },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .testTag("group_row_${item.groupId}")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (isCreamAvatar) cream else tealSoft)
                            ) {
                                Text(
                                    text = item.name.firstOrNull { it.isLetter() }?.uppercase() ?: "G",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                                    ),
                                    color = if (isCreamAvatar) NudgyCharcoal else tealDark
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = NudgyCharcoal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Sprint 7 — the prototype meta is
                                    // "N members · M open tasks", but
                                    // member counts are not loaded for
                                    // the list (members are observed only
                                    // for the selected group); the count
                                    // stays task-only until that data
                                    // lands. The join code is gone from
                                    // the row (the prototype has none) —
                                    // it still lives in the detail's
                                    // invite card.
                                    Text(
                                        text = "$itemTaskCount open tasks",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                                        color = NudgyMuted
                                    )
                                    RolePill(isCreator = isCreator)
                                    // Sprint 7 — the prototype shows the
                                    // teal "+ Add task" chip on every
                                    // row, but the admin-only rule
                                    // (Sprint 5 Q3, Tanvir's correction)
                                    // forbids a member from creating a
                                    // group task: the chip renders for
                                    // creators only and opens the detail,
                                    // where Add task lives at the top of
                                    // the task list (the prototype's own
                                    // note says creation happens there).
                                    // Never a dead button for members.
                                    if (isCreator) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(100.dp))
                                                .background(teal)
                                                .clickable { selectedGroup = item }
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                                .testTag("group_add_task_${item.groupId}")
                                        ) {
                                            Text(
                                                text = "+ Add task",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold
                                                ),
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = NudgyMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            // Sprint 7 — tiny note (prototype-verbatim): creation
            // happens in the detail, at the top of its task list.
            // Only when the list is non-empty — with no groups there is
            // nothing to tap yet.
            if (groups.isNotEmpty()) {
                Text(
                    text = buildAnnotatedString {
                        append("Tap a group, then use ")
                        withStyle(SpanStyle(color = tealDark, fontWeight = FontWeight.Bold)) {
                            append("Add task")
                        }
                        append(" at the top of its task list to create a group task.")
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = NudgyMuted,
                    modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            // Sprint 7 — Join card (prototype scr-groups): always
            // visible; same validation, error display and callback as
            // the old sheet — only the presentation moved inline.
            Surface(
                shape = NudgyTokens.cardRadius,
                color = NudgyCard,
                shadowElevation = NudgyTokens.cardElevation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Join a group",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold, fontSize = 15.5.sp
                        ),
                        color = NudgyCharcoal
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Enter a join code, or open an invite link someone shared with you.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = NudgyMuted
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = joinCode,
                            onValueChange = {
                                joinCode = it.uppercase().filter { ch -> ch.isLetterOrDigit() }
                                joinError = null
                            },
                            placeholder = { Text("Join code, e.g. FAM-2481") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = teal,
                                unfocusedBorderColor = NudgyFieldBorder
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (joinCode.isBlank() || joinBusy) return@Button
                                joinBusy = true
                                scope.launch {
                                    when (val result = onJoinGroup(joinCode)) {
                                        is GroupResult.Ok -> {
                                            joinCode = ""
                                            joinError = null
                                            joinBusy = false
                                        }
                                        is GroupResult.Err -> {
                                            joinError = result.message
                                            joinBusy = false
                                        }
                                    }
                                }
                            },
                            enabled = joinCode.isNotBlank() && !joinBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = teal),
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Text(
                                text = if (joinBusy) "Joining…" else "Join",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                    joinError?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = Color(0xFFB3261E)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Left a group earlier? Rejoin any time with the same code.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = NudgyMuted
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Sprint 7 — "Create a group" soft block button (prototype
            // scr-groups): toggles the create card below, like the
            // Templates inline-create pattern (S6-4 step 3c).
            Button(
                onClick = { showCreateCard = !showCreateCard },
                colors = ButtonDefaults.buttonColors(
                    containerColor = tealSoft,
                    contentColor = tealDark
                ),
                shape = RoundedCornerShape(100.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Create a group",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = tealDark
                )
            }
            if (showCreateCard) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = NudgyTokens.cardRadius,
                    color = NudgyCard,
                    shadowElevation = NudgyTokens.cardElevation,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "New group",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold, fontSize = 15.5.sp
                            ),
                            color = NudgyCharcoal
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = createName,
                            onValueChange = { createName = it; createError = null },
                            placeholder = { Text("Group name, e.g. Flat 7B") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = teal,
                                unfocusedBorderColor = NudgyFieldBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        createError?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = Color(0xFFB3261E)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (createName.isBlank() || createBusy) return@Button
                                createBusy = true
                                scope.launch {
                                    when (val result = onCreateGroup(createName)) {
                                        is GroupResult.Ok -> {
                                            showCreateCard = false
                                            createName = ""
                                            createError = null
                                            createBusy = false
                                        }
                                        is GroupResult.Err -> {
                                            createError = result.message
                                            createBusy = false
                                        }
                                    }
                                }
                            },
                            enabled = createName.isNotBlank() && !createBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = teal),
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (createBusy) "Creating…" else "Create group",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "You become the admin. A join code is generated to share.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = NudgyMuted
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Reserved banner slot: empty space only (no ad SDK) so a future
            // banner slots in without layout shift.
            Spacer(modifier = Modifier.height(64.dp))
        } else {
            // ------------------------------------------------ detail view
            val isCreator = group.creatorUid == user.uid
            val groupTasks = allTasks.filter { it.groupId == group.groupId }
                .sortedBy { it.dueDateEpochMillis }
            val groupEvents = allEvents.filter { it.groupId == group.groupId }
                .sortedBy { it.startEpochMillis }

            // Sprint 7 — backbar (prototype scr-group-detail): back +
            // group name 21sp/800 + RolePill. No "All groups" text, no
            // avatar block — the name + role live in the bar now.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("group_detail_backbar")
            ) {
                IconButton(
                    onClick = { selectedGroup = null },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to groups",
                        tint = NudgyCharcoal,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 21.sp, fontWeight = FontWeight.ExtraBold
                    ),
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                RolePill(isCreator = isCreator)
            }
            // Sprint 5-3 — the Notices card (groupNoticesEnabled).
            // Text-only admin notices (Q2=(a)), newest first; every
            // member reads them, only the creator sends them. The
            // author is always the creator — a blank name reads "Admin"
            // (display names are "" until the Sprint 6 auth flow).
            if (groupNoticesEnabled) {
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                shape = NudgyTokens.cardRadius,
                color = NudgyCard,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notices_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = null,
                            tint = teal,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = "Group notice",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp, fontWeight = FontWeight.Bold
                            ),
                            color = NudgyCharcoal,
                            modifier = Modifier.weight(1f)
                        )
                        DetailTagChip(text = "From the admin")
                    }
                    if (notices.isEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No notices yet.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted
                        )
                    } else {
                        val latest = notices.first()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = latest.text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold
                            ),
                            color = NudgyCharcoal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "— ${latest.authorName.ifBlank { "Admin" }}" +
                                if (latest.createdAtEpochMillis > 0L) {
                                    ", ${taskDateFormatter.format(latest.createdAtEpochMillis)}"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted
                        )
                        if (notices.size > 1) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = NudgyBorder, thickness = 1.dp)
                            notices.drop(1).forEachIndexed { index, notice ->
                                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = notice.authorName.ifBlank { "Admin" },
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                                            ),
                                            color = NudgyCharcoal,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (notice.createdAtEpochMillis > 0L) {
                                            Text(
                                                text = taskDateFormatter.format(notice.createdAtEpochMillis),
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                color = NudgyMuted
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = notice.text,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                        color = NudgyCharcoal
                                    )
                                }
                                if (index != notices.lastIndex - 1) {
                                    HorizontalDivider(
                                        color = NudgyBorder,
                                        thickness = 1.dp
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Only the admin can post a notice. It is text only, and stays pinned at the top of the group.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = NudgyMuted
                    )
                    if (isCreator) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showSendNotice = !showSendNotice },
                            colors = ButtonDefaults.buttonColors(containerColor = teal),
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier.testTag("send_notice_open")
                        ) {
                            Text(
                                text = "Post a new notice",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
            }
            // Sprint 7 — the send flow lives in this inline card now
            // (prototype scr-group-detail), not in SendNoticeSheet:
            // same validation (2000 chars, blank → disabled) and the
            // same onSendNotice callback; failures still surface on
            // the shared group-error toast (no inline result).
            if (showSendNotice) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = NudgyTokens.cardRadius,
                    color = NudgyCard,
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = noticeText,
                            onValueChange = { noticeText = it.take(MAX_NOTICE_TEXT_LENGTH) },
                            placeholder = { Text("Write a notice for all members…") },
                            minLines = 3,
                            maxLines = 6,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NudgyPrimary,
                                unfocusedBorderColor = NudgyFieldBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("send_notice_field")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${noticeText.length} / $MAX_NOTICE_TEXT_LENGTH",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = NudgyMuted,
                            modifier = Modifier.align(Alignment.End)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                onSendNotice(group.groupId, noticeText.trim())
                                noticeText = ""
                                showSendNotice = false
                            },
                            enabled = noticeText.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = teal),
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("send_notice_submit")
                        ) {
                            Text("Post notice")
                        }
                    }
                }
            }
            }
            // Sprint 7 — "Group tasks & schedules" (prototype
            // scr-group-detail): sec-title + verbatim intro. The seg card
            // below is the creation entry (creator only); the task and
            // schedule rows follow it.
            Text(
                text = "Group tasks & schedules",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp, fontWeight = FontWeight.Bold
                ),
                color = NudgyCharcoal,
                modifier = Modifier.padding(start = 2.dp, top = 22.dp, bottom = 10.dp)
            )
            Text(
                text = "Anything added here appears in every member's own list — tasks in their task list, schedules in their calendar. Tap a “done” chip on a task to see who has finished it.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = NudgyMuted,
                modifier = Modifier.padding(start = 2.dp, bottom = 12.dp)
            )
            // Sprint 7 — creation entry as the prototype's two-pill seg
            // (scr-group-detail): each pill opens the same sheet as the
            // old buttons — the sheets carry the full create forms, so
            // there is no inline quick-add field (see the PR report).
            // The task pill reads "on" (teal fill); the schedule pill
            // opens its sheet.
            if (groupTasksEnabled && isCreator) {
                Surface(
                    shape = NudgyTokens.cardRadius,
                    color = NudgyCard,
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(100.dp))
                                .background(colorResource(R.color.nudgy_track))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(100.dp))
                                    .background(teal)
                                    .clickable { showCreateTask = true }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "+ Add a task",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(100.dp))
                                    .clickable { showCreateSchedule = true }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "+ Add a schedule",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                                    ),
                                    color = NudgyCharcoal
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "A group task lands in every member's list. A group schedule lands in every member's calendar.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = NudgyMuted,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
            }
            // Sprint 5-2 — the Tasks card (groupTasksEnabled). The
            // placeholder below takes its place until then (same for the
            // Schedule card); delete/leave stay behind groupWorkEnabled
            // for S5-4.
            if (groupTasksEnabled) {
            Spacer(modifier = Modifier.height(20.dp))
            // Sprint 7 — task rows as prototype .task buttons
            // (scr-group-detail): checkbox + priority dot + title/meta +
            // donechip. No card wrapper, no "SHARED WORK" header, no
            // "N open" count — the sec-title above covers the section.
            if (groupTasks.isEmpty()) {
                Text(
                    text = "No tasks in this group yet.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = NudgyMuted
                )
            } else {
                groupTasks.forEach { task ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = NudgyCard,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isCreator) editTask = task else detailTask = task
                                }
                                .padding(14.dp)
                        ) {
                            NudgyCheckbox(
                                checked = task.isCompleted,
                                onCheckedChange = { onToggleTask(task) }
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(groupPriorityDotColor(task.priority))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                                    ),
                                    color = if (task.isCompleted) NudgyMuted else NudgyCharcoal,
                                    textDecoration = if (task.isCompleted) {
                                        TextDecoration.LineThrough
                                    } else {
                                        TextDecoration.None
                                    }
                                )
                                Text(
                                    text = taskDateFormatter.format(task.dueDateEpochMillis),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                    color = NudgyMuted
                                )
                                // Sprint 5-5 (feature 36): the
                                // assignee's name — denormalized
                                // on the row, so it shows in the
                                // member's own list too. Blank
                                // reads "Member" (the member-list
                                // convention). Shown only when
                                // the task is assigned.
                                if (task.assigneeUid != null) {
                                    Text(
                                        text = "Assigned to ${task.assigneeName?.ifBlank { "Member" } ?: "Member"}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                        color = NudgyMuted
                                    )
                                }
                            }
                            // Sprint 5-2 (feature 31): the shared
                            // completion count as the prototype's
                            // donechip — teal-soft pill with a check;
                            // tapping it lists who completed. Hidden
                            // while the member list is still loading
                            // (no denominator yet).
                            val memberList = members
                            if (memberList != null && memberList.isNotEmpty()) {
                                val completion = effectiveCompletion(
                                    completions[task.id] ?: GroupCompletion(),
                                    memberList.map { it.uid }.toSet()
                                )
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100.dp))
                                        .background(tealSoft)
                                        .border(
                                            BorderStroke(1.dp, teal.copy(alpha = 0.35f)),
                                            RoundedCornerShape(100.dp)
                                        )
                                        .clickable { whoCompletedTask = task }
                                        .padding(horizontal = 11.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = tealDark,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = formatCompletionCount(
                                                completion.completedCount, memberList.size
                                            ),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 12.5.sp, fontWeight = FontWeight.Bold
                                            ),
                                            color = tealDark
                                        )
                                    }
                                }
                            }
                            if (isCreator) {
                                // Sprint 5-5 (feature 36): assign
                                // is creator-only (Q3=(b)) — the
                                // button opens the assign dialog.
                                if (groupAssignEnabled) {
                                    IconButton(
                                        onClick = { assignTask = task },
                                        modifier = Modifier.size(32.dp)
                                            .testTag("assign_task_open")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PersonAdd,
                                            contentDescription = "Assign task",
                                            tint = NudgyMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { taskPendingDelete = task },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete task",
                                        tint = NudgyMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            } else {
            Spacer(modifier = Modifier.height(20.dp))
            // Sprint 5-1 placeholder — shown while the task sections are
            // off (S5-2); the notices card has its own flag (S5-3).
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = NudgyCard,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("group_work_placeholder")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Group tasks and notices",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = NudgyCharcoal
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Shared tasks and admin notices will appear here.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = NudgyMuted
                    )
                }
            }
            }
            if (groupTasksEnabled) {
            Spacer(modifier = Modifier.height(20.dp))
            // Sprint 7 — schedule rows as prototype .task buttons
            // (scr-group-detail): the prototype shows no schedule rows,
            // so the section keeps the app's existing structure with
            // the new row shape. Sec-title + count; no card wrapper.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Schedule",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp, fontWeight = FontWeight.Bold
                    ),
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${groupEvents.size} events",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = NudgyMuted
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (groupEvents.isEmpty()) {
                Text(
                    text = "No schedules in this group yet.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = NudgyMuted
                )
            } else {
                groupEvents.forEach { event ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = NudgyCard,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = event.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                                    ),
                                    color = NudgyCharcoal
                                )
                                Text(
                                    text = eventTimeFormatter.format(event.startEpochMillis) +
                                        (event.locationOrLink?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                    color = NudgyMuted
                                )
                            }
                            if (isCreator) {
                                IconButton(
                                    onClick = { schedulePendingDelete = event },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete schedule",
                                        tint = NudgyMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
            Spacer(modifier = Modifier.height(20.dp))
            // Sprint 7 — "Members · N" sec-title (prototype
            // scr-group-detail) above the card; the card keeps the
            // member rows. The Creator/You pills stay (established
            // model) instead of the prototype's "Admin · You" tiny
            // text.
            Text(
                text = if (members != null) "Members · ${members!!.size}" else "Members",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp, fontWeight = FontWeight.Bold
                ),
                color = NudgyCharcoal,
                modifier = Modifier.padding(start = 2.dp, bottom = 10.dp)
            )
            Surface(
                shape = NudgyTokens.cardRadius,
                color = NudgyCard,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("group_members")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (members == null) {
                        Text(
                            text = "Loading…",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    } else if (members!!.isEmpty()) {
                        Text(
                            text = "No members yet.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    } else {
                        members!!.forEachIndexed { index, member ->
                            MemberRow(
                                member = member,
                                isCreator = member.uid == group.creatorUid,
                                isSelf = member.uid == user.uid
                            )
                            if (index != members!!.lastIndex) {
                                HorizontalDivider(
                                    color = NudgyBorder,
                                    thickness = 1.dp
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            // Sprint 7 — "Invite members" (prototype scr-group-detail):
            // sec-title + cream codebox (JOIN CODE + code + Share code)
            // + verbatim tiny note. Copy stays (existing affordance).
            Text(
                text = "Invite members",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp, fontWeight = FontWeight.Bold
                ),
                color = NudgyCharcoal,
                modifier = Modifier.padding(start = 2.dp, bottom = 10.dp)
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cream,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(14.dp, 16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "JOIN CODE",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = NudgyMuted
                        )
                        Text(
                            text = group.code,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.4.sp
                            ),
                            color = NudgyCharcoal
                        )
                    }
                    TextButton(onClick = { copyCode(group.code) }) {
                        Text(
                            text = "Copy",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            ),
                            color = teal
                        )
                    }
                    Button(
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    groupInviteText(group.name, group.code)
                                )
                            }
                            context.startActivity(
                                Intent.createChooser(sendIntent, "Invite to ${group.name}")
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = teal),
                        shape = RoundedCornerShape(100.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Share code",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            ),
                            color = Color.White
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Anyone with this code or the invite link can join. Members who leave can rejoin with the same code.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = NudgyMuted,
                modifier = Modifier.padding(start = 2.dp)
            )
            // Sprint 7 — "Activity history" sec-title (prototype
            // scr-group-detail) above the card. The sentence is built
            // by the pure activityDisplayText — the screen never
            // invents wording.
            if (groupAssignEnabled) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Activity history",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp, fontWeight = FontWeight.Bold
                ),
                color = NudgyCharcoal,
                modifier = Modifier.padding(start = 2.dp, bottom = 10.dp)
            )
            Surface(
                shape = NudgyTokens.cardRadius,
                color = NudgyCard,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("activity_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (activity.isEmpty()) {
                        Text(
                            text = "No activity yet.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    } else {
                        Column {
                            activity.forEachIndexed { index, entry ->
                                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                    Text(
                                        text = activityDisplayText(entry),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                        color = NudgyCharcoal
                                    )
                                    if (entry.createdAtEpochMillis > 0L) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = taskDateFormatter.format(entry.createdAtEpochMillis),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                            color = NudgyMuted
                                        )
                                    }
                                }
                                if (index != activity.lastIndex) {
                                    HorizontalDivider(
                                        color = NudgyBorder,
                                        thickness = 1.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
            Spacer(modifier = Modifier.height(20.dp))
            if (groupWorkEnabled) {
            if (isCreator) {
                OutlinedButton(
                    onClick = { deleteGroupTarget = group },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Delete group",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold, fontSize = 14.sp
                        ),
                        color = Color(0xFFB3261E)
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { leaveGroupTarget = group },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Leave group",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold, fontSize = 14.sp
                        ),
                        color = Color(0xFFB3261E)
                    )
                }
            }
            // Sprint 7 — verbatim tiny note (prototype
            // scr-group-detail) under the button.
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Only the creator can delete a group. Leaving keeps your own copy of tasks; rejoin later with the same code.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = NudgyMuted,
                modifier = Modifier.padding(start = 2.dp)
            )
            }
        }
    }

    // ------------------------------------------------ sheets & dialogs
    // Sprint 7 — the join/create sheets are gone: both flows live in
    // inline cards on the list now (see the list branch above). The
    // detail sheets below are untouched.
    val activeGroup = selectedGroup
    // Sprint 5-2 — the task/schedule/edit/detail sheets and the
    // item-delete dialogs live behind groupTasksEnabled (their buttons
    // are gated above); the group delete/leave dialogs live behind
    // groupWorkEnabled (S5-4, ON in the NavHost).
    if (groupTasksEnabled) {
    if (showCreateTask && activeGroup != null) {
        CreateTaskSheet(
            onDismiss = { showCreateTask = false },
            headerTitle = "Post a group task",
            headerSubtitle = "This task will appear for every member. " +
                "Each person tracks their own completion.",
            saveButtonText = "Post task",
            onSaveTask = { title, description, due, priority, category, reminderSet ->
                onCreateGroupTask(
                    activeGroup.groupId, title, description, due,
                    priority, category, reminderSet
                )
                showCreateTask = false
            }
        )
    }
    if (showCreateSchedule && activeGroup != null) {
        GroupScheduleSheet(
            onDismiss = { showCreateSchedule = false },
            onSaveSchedule = { title, start, end, location, type ->
                onCreateGroupSchedule(activeGroup.groupId, title, start, end, location, type)
                showCreateSchedule = false
            }
        )
    }
    editTask?.let { task ->
        val groupId = task.groupId
        if (groupId != null) {
            EditTaskSheet(
                task = task,
                onDismiss = { editTask = null },
                onUpdateTask = { updated ->
                    onUpdateGroupTask(groupId, updated)
                    editTask = null
                },
                onDeleteTask = { taskId ->
                    onDeleteGroupItem(groupId, taskId, true)
                    editTask = null
                },
                onToggleComplete = { onToggleTask(it) }
            )
        }
    }
    detailTask?.let { task ->
        GroupTaskDetailSheet(
            task = task,
            groupName = selectedGroup?.name ?: "Group",
            onDismiss = { detailTask = null },
            onToggleComplete = { onToggleTask(it) }
        )
    }
    // Sprint 5-2 (feature 31, Q5=(a)): who completed this task — every
    // member sees the names. UIDs are resolved against the member list;
    // an unresolvable UID (members still loading) reads as "Member".
    whoCompletedTask?.let { task ->
        val memberList = members
        val completion = effectiveCompletion(
            completions[task.id] ?: GroupCompletion(),
            memberList?.map { it.uid }?.toSet() ?: emptySet()
        )
        val names = completion.completedBy.map { uid ->
            memberList?.firstOrNull { it.uid == uid }?.displayName?.ifBlank { "Member" }
                ?: "Member"
        }
        AlertDialog(
            onDismissRequest = { whoCompletedTask = null },
            title = { Text("Who completed this") },
            text = {
                Column {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = NudgyCharcoal
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (names.isEmpty()) {
                        Text(
                            text = "No one has completed this yet.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted
                        )
                    } else {
                        names.forEach { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = NudgyCharcoal,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { whoCompletedTask = null }) {
                    Text("Close")
                }
            }
        )
    }
    // Sprint 5-5 (feature 36): assign this task — the creator-only
    // button on the task row opens it. Members resolve against the
    // member list (already observed for the detail); "Nobody"
    // unassigns. The current assignee is marked in the list.
    if (groupAssignEnabled) {
    assignTask?.let { task ->
        val groupId = task.groupId
        val memberList = members
        if (groupId != null) {
            AlertDialog(
                onDismissRequest = { assignTask = null },
                title = { Text("Assign task") },
                text = {
                    Column(modifier = Modifier.testTag("assign_dialog")) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = NudgyCharcoal
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (task.assigneeUid != null) {
                            Text(
                                text = "Nobody",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = NudgyCharcoal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("assign_member_row_nobody")
                                    .clickable {
                                        onAssignGroupTask(groupId, task.id, null)
                                        assignTask = null
                                    }
                                    .padding(vertical = 6.dp)
                            )
                        }
                        memberList?.forEach { member ->
                            Text(
                                text = if (member.uid == task.assigneeUid) {
                                    "${member.displayName} (assigned)"
                                } else {
                                    member.displayName
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = NudgyCharcoal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("assign_member_row_${member.uid}")
                                    .clickable {
                                        onAssignGroupTask(groupId, task.id, member.uid)
                                        assignTask = null
                                    }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { assignTask = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
    }
    taskPendingDelete?.let { task ->
        val groupId = task.groupId
        AlertDialog(
            onDismissRequest = { taskPendingDelete = null },
            title = { Text("Delete this task?") },
            text = {
                Text(
                    "\"${task.title}\" will be removed from every member's " +
                        "task list. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (groupId != null) {
                        onDeleteGroupItem(groupId, task.id, true)
                    }
                    taskPendingDelete = null
                }) {
                    Text("Delete", color = Color(0xFFB3261E))
                }
            },
            dismissButton = {
                TextButton(onClick = { taskPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    schedulePendingDelete?.let { event ->
        val groupId = event.groupId
        AlertDialog(
            onDismissRequest = { schedulePendingDelete = null },
            title = { Text("Delete this schedule?") },
            text = {
                Text(
                    "\"${event.title}\" will be removed from every member's " +
                        "schedule list. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (groupId != null) {
                        onDeleteGroupItem(groupId, event.id, false)
                    }
                    schedulePendingDelete = null
                }) {
                    Text("Delete", color = Color(0xFFB3261E))
                }
            },
            dismissButton = {
                TextButton(onClick = { schedulePendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    }
    // Sprint 7 — the send-notice sheet is gone: the flow lives in the
    // inline card on the Notices card above (same callbacks, same
    // validation); showSendNotice now toggles that card.
    // Sprint 5-4 — the group delete/leave dialogs, behind
    // groupWorkEnabled (their buttons are gated above).
    if (groupWorkEnabled) {
    deleteGroupTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteGroupTarget = null },
            title = { Text("Delete this group?") },
            text = {
                Text(
                    "\"${target.name}\" and all its tasks and schedules will be deleted " +
                        "for every member. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteGroup(target.groupId)
                    deleteGroupTarget = null
                    selectedGroup = null
                }) {
                    Text("Delete", color = Color(0xFFB3261E))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteGroupTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    leaveGroupTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { leaveGroupTarget = null },
            title = { Text("Leave this group?") },
            text = {
                Text(
                    "You will stop seeing \"${target.name}\"'s tasks and schedules. " +
                        "You can rejoin later with the join code."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onLeaveGroup(target.groupId)
                    leaveGroupTarget = null
                    selectedGroup = null
                }) {
                    Text("Leave", color = Color(0xFFB3261E))
                }
            },
            dismissButton = {
                TextButton(onClick = { leaveGroupTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    }
}


/** The App Links join URL for a group code (S2-4 pattern; the manifest already declares /nudgy/join — keep in sync with GROUP_JOIN_DEEP_LINK). */
fun groupJoinLink(code: String): String =
    "https://tanvir-durlove.github.io/nudgy/join?code=$code"

/** The invite text shared for a group (system share sheet). */
fun groupInviteText(groupName: String, code: String): String =
    "Join \"$groupName\" on Nudgy: ${groupJoinLink(code)}"

/** Initials for the group avatar: first letters of the first two letter-led words. */
private fun groupInitials(name: String): String {
    val words = name.split(" ", "·", "-", "_")
        .filter { it.isNotEmpty() && it[0].isLetter() }
    return when {
        words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
        words.size == 1 && words[0].length >= 2 -> words[0].take(2).uppercase()
        words.size == 1 -> words[0].uppercase()
        else -> "G"
    }
}

/** Stable pastel avatar palette, hashed by group name (same group, same colors). */
private fun groupAvatarPalette(name: String): Pair<Color, Color> {
    val index = (name.hashCode() and Int.MAX_VALUE) % NudgyAvatarPalettes.size
    return NudgyAvatarPalettes[index]
}

/**
 * Sprint 7 — role tagchip (prototype scr-groups .tagchip.grp):
 * "You are admin" / "Member", cream fill, cream-dark border, ink
 * text. Shared by the list rows and the detail header — the detail
 * prototype uses the same chip, so the change lands in both.
 */
@Composable
private fun RolePill(isCreator: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(colorResource(R.color.nudgy_cream))
            .border(
                BorderStroke(1.dp, colorResource(R.color.nudgy_cream_dark)),
                RoundedCornerShape(100.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (isCreator) "You are admin" else "Member",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 11.5.sp, fontWeight = FontWeight.Medium
            ),
            color = NudgyCharcoal
        )
    }
}

/**
 * Sprint 7 — small cream tagchip (prototype .tagchip.grp) for inline
 * labels like "From the admin". Same styling as [RolePill].
 */
@Composable
private fun DetailTagChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(colorResource(R.color.nudgy_cream))
            .border(
                BorderStroke(1.dp, colorResource(R.color.nudgy_cream_dark)),
                RoundedCornerShape(100.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 11.5.sp, fontWeight = FontWeight.Medium
            ),
            color = NudgyCharcoal
        )
    }
}

/** Priority marker: a dot only — coral for High, teal for Medium, muted for Low. Never text. */
@Composable
private fun groupPriorityDotColor(priority: Priority) = when (priority) {
    Priority.HIGH -> colorResource(R.color.nudgy_coral)
    Priority.MEDIUM -> colorResource(R.color.nudgy_teal)
    Priority.LOW -> colorResource(R.color.nudgy_muted)
}

/** Member row: avatar initials + display name + role pill, in the members card. */
@Composable
private fun MemberRow(member: GroupMember, isCreator: Boolean, isSelf: Boolean) {
    val (avatarBg, avatarText) = groupAvatarPalette(member.displayName)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(avatarBg)
        ) {
            Text(
                text = groupInitials(member.displayName),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold, fontSize = 13.sp
                ),
                color = avatarText
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = NudgyCharcoal,
            modifier = Modifier.weight(1f)
        )
        if (isCreator) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(NudgySage)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "Creator",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 10.sp, fontWeight = FontWeight.Bold
                    ),
                    color = NudgyGreen
                )
            }
        } else if (isSelf) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(NudgyLavender)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "You",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 10.sp, fontWeight = FontWeight.Bold
                    ),
                    color = NudgyMemberPillText
                )
            }
        }
    }
}
