package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPrimary
import com.nextgentool.nudgy.ui.theme.NudgyTokens

/**
 * Sprint 6-1 — the real Settings rows (spec §4).
 *
 * One row per S6-0 section slug, same card styling as the stub
 * rows ([SettingsStubRow]) and the same `settings_row_<slug>`
 * testTag, so the S6-0 contract (one row per section) still holds.
 * The notifications/appearance/language rows stay stubs for S6-2.
 *
 * Profile (v1 YourNudgySheet): initials in a circle + name + email.
 * The circle is teal here, not v1's gold — the locked rebuild
 * design (2026-11-15) is teal/coral, and v1's gold belongs to the
 * old design. A guest shows no fake email — the row says "Guest
 * User" + "Sign in with Google to sync your data".
 */

/** The shared card shell for a Settings row (same as the S6-0 stub). */
@Composable
private fun SettingsRowCard(
    slug: String,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row_$slug"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun RowScope.SettingsRowTexts(title: String, subtitle: String, titleColor: Color = NudgyCharcoal) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = titleColor
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = NudgyMuted
        )
    }
}

/** Profile row: initials circle + name + email (guest: no fake email). */
@Composable
fun SettingsProfileRow(user: NudgyUser?) {
    val isGuest = user == null || user.isGuest
    SettingsRowCard(slug = "profile") {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(NudgyPrimary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isGuest) "GU" else user!!.initials,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        if (isGuest) {
            SettingsRowTexts(
                title = "Guest User",
                subtitle = "Sign in with Google to sync your data"
            )
        } else {
            SettingsRowTexts(
                title = user!!.displayName.ifBlank { user.email },
                subtitle = user.email
            )
        }
    }
}

/** Sign-in row: guest → "Sign in with Google"; signed-in → "Sign out". */
@Composable
fun SettingsSignInRow(
    isGuest: Boolean,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit
) {
    if (isGuest) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_row_sign_in"),
            shape = NudgyTokens.cardRadius,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = NudgyTokens.cardElevation
        ) {
            Button(
                onClick = onSignInClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("settings_sign_in_button"),
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
            ) {
                Text("Sign in with Google", color = Color.White)
            }
        }
    } else {
        SettingsRowCard(slug = "sign_in", onClick = onSignOutClick) {
            SettingsRowTexts(
                title = "Sign out",
                subtitle = "Switch to guest mode on this device"
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = NudgyAccent
            )
        }
    }
}

/** Calendar row: v1's connect card texts ("Calendar synced" / "Bring your calendar in"). */
@Composable
fun SettingsCalendarRow(
    connected: Boolean,
    isSyncing: Boolean,
    onConnectClick: () -> Unit,
    onSyncClick: () -> Unit
) {
    SettingsRowCard(slug = "calendar") {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (connected) "Calendar synced" else "Bring your calendar in",
                style = MaterialTheme.typography.titleMedium,
                color = NudgyCharcoal
            )
            Text(
                text = if (connected) "Events from your device calendar stay in sync."
                else "Allow calendar access to spot conflicts and find open time.",
                style = MaterialTheme.typography.bodySmall,
                color = NudgyMuted
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        if (connected) {
            Button(
                onClick = onSyncClick,
                enabled = !isSyncing,
                modifier = Modifier.testTag("settings_calendar_sync_button"),
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
            ) {
                Text(if (isSyncing) "Syncing…" else "Sync now", color = Color.White)
            }
        } else {
            Button(
                onClick = onConnectClick,
                modifier = Modifier.testTag("settings_calendar_connect_button"),
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
            ) {
                Text("Connect", color = Color.White)
            }
        }
    }
}

/** A tappable row with title + subtitle (BYOK, Privacy, Help, Memories, Delete). */
@Composable
fun SettingsActionRow(
    slug: String,
    title: String,
    subtitle: String,
    titleColor: Color = NudgyCharcoal,
    onClick: () -> Unit
) {
    SettingsRowCard(slug = slug, onClick = onClick) {
        SettingsRowTexts(title = title, subtitle = subtitle, titleColor = titleColor)
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = NudgyAccent
        )
    }
}
