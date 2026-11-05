package com.example.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auth.NudgyUser
import com.example.data.local.entity.CalendarEventEntity
import com.example.data.local.entity.GroupEntity
import com.example.data.local.entity.TaskEntity
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import com.example.data.repository.GroupMember
import com.example.data.repository.GroupResult
import com.example.ui.components.NudgyCheckbox
import com.example.ui.sheets.CreateGroupSheet
import com.example.ui.sheets.CreateTaskSheet
import com.example.ui.sheets.EditTaskSheet
import com.example.ui.sheets.GroupScheduleSheet
import com.example.ui.sheets.GroupTaskDetailSheet
import com.example.ui.sheets.JoinGroupSheet
import com.example.ui.theme.NudgyAvatarPalettes
import com.example.ui.theme.NudgyBorder
import com.example.ui.theme.NudgyCanvas
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyGreen
import com.example.ui.theme.NudgyLavender
import com.example.ui.theme.NudgyMemberPillText
import com.example.ui.theme.NudgyPeach
import com.example.ui.theme.NudgySage
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPrimary
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Groups tab (Group v1).
 *
 * List view: the user's groups with join codes. Detail view: the group's items
 * (tasks + schedules) with origin context. Rules enforced here:
 * - Guests see a sign-in prompt, never create/join controls.
 * - Only the creator sees Add task / Add schedule and the task/schedule delete icons.
 * - Figma redesign: hero + avatar cards, invite-code card, local progress bar;
 *   copy is generic; theme is Groups-only, token-based.
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
 */
@Composable
fun GroupsScreen(
    user: NudgyUser,
    groups: List<GroupEntity>,
    allTasks: List<TaskEntity>,
    allEvents: List<CalendarEventEntity>,
    onCreateGroup: suspend (name: String) -> GroupResult<GroupEntity>,
    onJoinGroup: suspend (code: String) -> GroupResult<GroupEntity>,
    onCreateGroupTask: (
        groupId: String, title: String, description: String?, dueEpochMillis: Long,
        priority: Priority, category: TaskCategory, reminderSet: Boolean
    ) -> Unit,
    onCreateGroupSchedule: (
        groupId: String, title: String, startEpochMillis: Long, endEpochMillis: Long,
        locationOrLink: String?, eventType: String
    ) -> Unit,
    onUpdateGroupTask: (groupId: String, task: TaskEntity) -> Unit,
    onDeleteGroupItem: (groupId: String, itemId: String, isTask: Boolean) -> Unit,
    onDeleteGroup: (groupId: String) -> Unit,
    onLeaveGroup: (groupId: String) -> Unit,
    onLoadMembers: suspend (groupId: String) -> List<GroupMember>,
    onToggleTask: (TaskEntity) -> Unit,
    prefillJoinCode: String? = null,
    onPrefillJoinCodeConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val taskDateFormatter = remember {
        SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
    }
    val eventTimeFormatter = remember {
        SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
    }

    var selectedGroup by remember { mutableStateOf<GroupEntity?>(null) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showJoinGroup by remember { mutableStateOf(false) }
    // Deep-link prefill (one-shot): MainActivity delivers a join code from an
    // App Links intent; the sheet opens prefilled and the user still taps Join.
    var joinPrefill by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(prefillJoinCode) {
        if (prefillJoinCode != null) {
            joinPrefill = prefillJoinCode
            showJoinGroup = true
            onPrefillJoinCodeConsumed()
        }
    }
    var showCreateTask by remember { mutableStateOf(false) }
    var showCreateSchedule by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<TaskEntity?>(null) }
    var detailTask by remember { mutableStateOf<TaskEntity?>(null) }
    var schedulePendingDelete by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var taskPendingDelete by remember { mutableStateOf<TaskEntity?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<GroupEntity?>(null) }
    var leaveGroupTarget by remember { mutableStateOf<GroupEntity?>(null) }
    var members by remember { mutableStateOf<List<GroupMember>?>(null) }
    // A group deleted elsewhere (creator cascade, member purge) must not
    // linger as a stale detail view.
    LaunchedEffect(groups) {
        val selected = selectedGroup
        if (selected != null && groups.none { it.groupId == selected.groupId }) {
            selectedGroup = null
        }
    }
    LaunchedEffect(selectedGroup?.groupId) {
        val groupId = selectedGroup?.groupId
        members = null
        if (groupId != null) {
            members = try {
                onLoadMembers(groupId)
            } catch (e: Exception) {
                emptyList()
            }
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
            .padding(horizontal = 20.dp, vertical = 16.dp)
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
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Sign in to use groups",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = NudgyCharcoal
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Groups need a signed-in account. Sign in from your " +
                            "profile to create a group or join one with a code.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = NudgyMuted
                    )
                }
            }
            return@Column
        }

        val group = selectedGroup
        if (group == null) {
            // ------------------------------------------------ list view
            // Hero banner (Figma redesign, generic copy)
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = NudgyPeach,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "BUILT FOR YOUR PEOPLE",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = NudgyPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Keep everyone in the loop.",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = NudgyCharcoal
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Shared deadlines and schedules from your group owner, " +
                            "right alongside your personal plans.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = NudgyMuted
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { showCreateGroup = true },
                            colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Create",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                                ),
                                color = Color.White
                            )
                        }
                        OutlinedButton(
                            onClick = { showJoinGroup = true },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Group,
                                contentDescription = null,
                                tint = NudgyPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Join with code",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                                ),
                                color = NudgyPrimary
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Your groups",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(NudgyPrimary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${groups.size} active",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                        ),
                        color = NudgyPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (groups.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
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
                    val (avatarBg, avatarText) = groupAvatarPalette(item.name)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = NudgyCard,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clickable { selectedGroup = item }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(avatarBg)
                            ) {
                                Text(
                                    text = groupInitials(item.name),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                                    ),
                                    color = avatarText
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = NudgyCharcoal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    RolePill(isCreator = item.creatorUid == user.uid)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "$itemTaskCount open",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                        color = NudgyMuted
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = item.code,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                                        ),
                                        color = NudgyPrimary
                                    )
                                    IconButton(
                                        onClick = { copyCode(item.code) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy join code",
                                            tint = NudgyPrimary,
                                            modifier = Modifier.size(15.dp)
                                        )
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
            val doneCount = groupTasks.count { it.isCompleted }
            val (detailAvatarBg, detailAvatarText) = groupAvatarPalette(group.name)

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedGroup = null }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back to groups",
                        tint = NudgyCharcoal,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "All groups",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = NudgyMuted
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(detailAvatarBg)
                ) {
                    Text(
                        text = groupInitials(group.name),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold, fontSize = 16.sp
                        ),
                        color = detailAvatarText
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    RolePill(isCreator = isCreator)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = NudgyCharcoal
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            // Invite code card (dashed border). Share sends an App Links deep link.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NudgyPeach, RoundedCornerShape(20.dp))
                    .drawBehind {
                        drawRoundRect(
                            color = NudgyPrimary.copy(alpha = 0.5f),
                            topLeft = Offset(1f, 1f),
                            size = Size(size.width - 2f, size.height - 2f),
                            cornerRadius = CornerRadius(20.dp.toPx()),
                            style = Stroke(
                                width = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                            )
                        )
                    }
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Invite code",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = NudgyMuted
                        )
                        Text(
                            text = group.code,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                            ),
                            color = NudgyPrimary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { copyCode(group.code) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy join code",
                                tint = NudgyPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "Copy",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            ),
                            color = NudgyPrimary,
                            modifier = Modifier.clickable { copyCode(group.code) }
                        )
                        IconButton(
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Join \"${group.name}\" on Nudgy: https://tanvir-durlove.github.io/nudgy/join?code=${group.code}"
                                    )
                                }
                                context.startActivity(
                                    Intent.createChooser(sendIntent, "Invite to ${group.name}")
                                )
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share invite",
                                tint = NudgyPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            if (groupTasks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = NudgySage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "My progress",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = NudgyGreen,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "$doneCount/${groupTasks.size} done",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                ),
                                color = NudgyGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(100.dp))
                                .background(Color.White)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(
                                        if (groupTasks.isNotEmpty()) {
                                            doneCount.toFloat() / groupTasks.size
                                        } else {
                                            0f
                                        }
                                    )
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(100.dp))
                                    .background(NudgyGreen)
                            )
                        }
                    }
                }
            }
            if (isCreator) {
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { showCreateTask = true },
                        colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Add task",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold, fontSize = 14.sp
                            ),
                            color = Color.White
                        )
                    }
                    OutlinedButton(
                        onClick = { showCreateSchedule = true },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Add schedule",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold, fontSize = 14.sp
                            ),
                            color = NudgyPrimary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            // Members section card: header inside the card (never bare text),
            // member rows with avatar + role pill.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = NudgyCard,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Members",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = NudgyCharcoal,
                            modifier = Modifier.weight(1f)
                        )
                        if (members != null) {
                            Text(
                                text = "${members!!.size}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                ),
                                color = NudgyMuted
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = NudgyBorder, thickness = 1.dp)
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
            // Tasks section card: header inside the card, rows below a rule.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = NudgyCard,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SHARED WORK",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = NudgyPrimary
                            )
                            Text(
                                text = "Tasks",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = NudgyCharcoal
                            )
                        }
                        Text(
                            text = "${groupTasks.count { !it.isCompleted }} open",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = NudgyBorder, thickness = 1.dp)
                    if (groupTasks.isEmpty()) {
                        Text(
                            text = "No tasks in this group yet.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    } else {
                        Column {
                            groupTasks.forEachIndexed { index, task ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                        .clickable {
                                            if (isCreator) editTask = task else detailTask = task
                                        }
                                ) {
                                    NudgyCheckbox(
                                        checked = task.isCompleted,
                                        onCheckedChange = { onToggleTask(task) }
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
                                    }
                                    if (isCreator) {
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
                                if (index != groupTasks.lastIndex) {
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
            Spacer(modifier = Modifier.height(20.dp))
            // Schedule section card: header inside the card, rows below a rule.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = NudgyCard,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "GROUP PLAN",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = NudgyGreen
                            )
                            Text(
                                text = "Schedule",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = NudgyCharcoal
                            )
                        }
                        Text(
                            text = "${groupEvents.size} events",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = NudgyBorder, thickness = 1.dp)
                    if (groupEvents.isEmpty()) {
                        Text(
                            text = "No schedules in this group yet.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    } else {
                        Column {
                            groupEvents.forEachIndexed { index, event ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
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
                                if (index != groupEvents.lastIndex) {
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
            Spacer(modifier = Modifier.height(20.dp))
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
        }
    }

    // ------------------------------------------------ sheets & dialogs
    if (showCreateGroup) {
        CreateGroupSheet(
            onDismiss = { showCreateGroup = false },
            onCreateGroup = onCreateGroup
        )
    }
    if (showJoinGroup) {
        JoinGroupSheet(
            onDismiss = { showJoinGroup = false; joinPrefill = null },
            onJoinGroup = onJoinGroup,
            initialCode = joinPrefill ?: ""
        )
    }
    val activeGroup = selectedGroup
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

/** OWNER (green) / MEMBER (purple) pill badge, shared by cards and detail. */
@Composable
private fun RolePill(isCreator: Boolean) {
    val (pillBackground, pillText) = if (isCreator) {
        NudgySage to NudgyGreen
    } else {
        NudgyLavender to NudgyMemberPillText
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(pillBackground)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (isCreator) "OWNER" else "MEMBER",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 10.sp, fontWeight = FontWeight.Bold
            ),
            color = pillText
        )
    }
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
                .clip(RoundedCornerShape(12.dp))
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
