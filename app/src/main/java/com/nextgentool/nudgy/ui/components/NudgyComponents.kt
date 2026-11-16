package com.nextgentool.nudgy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.ui.theme.NudgyAvatarGold
import com.nextgentool.nudgy.ui.theme.NudgyButter
import com.nextgentool.nudgy.ui.theme.NudgyButterBorder
import com.nextgentool.nudgy.ui.theme.NudgyButterText
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCardBorder
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyChipBackground
import com.nextgentool.nudgy.ui.theme.NudgyGreen
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPrimary

/**
 * Top App Bar matching all Figma screens:
 * Left: Mascot + "Nudgy" title
 * Right: Notification bell with red badge + Avatar circle ("AR")
 */
@Composable
fun NudgyTopBar(
    modifier: Modifier = Modifier,
    userInitials: String = "AR",
    isGuest: Boolean = false,
    hasNotifications: Boolean = true,
    onNotificationsClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onGuestBadgeClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NudgyCanvas)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Mascot + Brand + optional Guest Mode Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onProfileClick() }
        ) {
            NudgyMascot(size = 36.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Nudgy",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = NudgyCharcoal
                )
                if (isGuest) {
                    Surface(
                        color = Color(0xFFFFF3E0),
                        shape = CircleShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D)),
                        modifier = Modifier.clickable { onGuestBadgeClick() }
                    ) {
                        Text(
                            text = "Guest (Local) • Tap to Sync",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }

        // Right: Notifications & Avatar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Notification bell with badge
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(NudgyCard)
                    .border(1.dp, NudgyCardBorder, CircleShape)
                    .clickable(role = Role.Button) { onNotificationsClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = "Notifications",
                    tint = NudgyCharcoal,
                    modifier = Modifier.size(20.dp)
                )
                if (hasNotifications) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp, end = 13.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(NudgyPrimary)
                    )
                }
            }

            // User Avatar (Matches the "AR" yellow circle in designs)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(NudgyAvatarGold)
                    .clickable(role = Role.Button) { onProfileClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = userInitials,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = NudgyCharcoal
                )
            }
        }
    }
}

/**
 * Custom Checkbox matching Figma rounded square checkboxes
 */
@Composable
fun NudgyCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF2E7D32) else Color.Transparent,
        animationSpec = tween(200),
        label = "checkboxBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF2E7D32) else Color(0xFFCCC7BC),
        animationSpec = tween(200),
        label = "checkboxBorder"
    )

    Box(
        modifier = modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(7.dp))
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Checked",
                tint = Color.White,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/**
 * Circular Arc Progress Ring for "Daily Progress" card (Figma screen 5)
 */
@Composable
fun NudgyProgressRing(
    percentage: Float, // 0f to 1f
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    strokeWidth: Dp = 10.dp,
    ringColor: Color = NudgyGreen,
    trackColor: Color = Color(0xFFD6EDE0)
) {
    val animatedProgress by animateFloatAsState(
        targetValue = percentage.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "progressRing"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            // Background track
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke
            )
            // Progress arc
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                style = stroke
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = NudgyCharcoal
            )
            Text(
                text = "complete",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                color = NudgyMuted
            )
        }
    }
}

/**
 * Horizontal Progress Bar for Tasks Tab (Figma screen 3)
 */
@Composable
fun NudgyProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White.copy(alpha = 0.35f),
    progressColor: Color = Color.White,
    height: Dp = 6.dp
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(500),
        label = "progressBar"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(height)
                .clip(CircleShape)
                .background(progressColor)
        )
    }
}

/**
 * Segmented Pill Tab Bar for filtering ("Today", "Upcoming", "Completed")
 */
@Composable
fun NudgyPillTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = NudgyChipBackground
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = index == selectedIndex
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) NudgyCard else Color.Transparent,
                    animationSpec = tween(200),
                    label = "tabBg"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) NudgyCharcoal else NudgyMuted,
                    animationSpec = tween(200),
                    label = "tabText"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(bgColor)
                        .clickable(role = Role.Tab) { onTabSelected(index) }
                        .height(48.dp)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = textColor
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Butter-yellow insight & memory card with sparkle icon
 */
@Composable
fun NudgyInsightCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            ),
        shape = MaterialTheme.shapes.large,
        color = NudgyButter,
        border = androidx.compose.foundation.BorderStroke(1.dp, NudgyButterBorder)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(NudgyButterBorder),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = NudgyButterText,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = NudgyCharcoal
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = NudgyCharcoal.copy(alpha = 0.75f)
                )
            }
            trailingContent?.invoke()
        }
    }
}
