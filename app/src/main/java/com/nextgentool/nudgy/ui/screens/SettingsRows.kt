package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.settings.SettingsPreferences
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
 *
 * Sprint 7 — Settings restyle (prototype scr-settings): every row
 * carries its prototype leading icon (muted, 24dp) + the prototype
 * type scale (title 15.5sp/500, subtitle 12.5sp) + a muted chevron
 * on tappable rows. The Appearance row is an inline Light/Dark/
 * System seg now (the S6-2 dialog is gone); Language keeps its
 * dialog (the prototype's select is the same choice). The
 * Statistics row is new (Sprint 7): it opens the Stats screen.
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
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Medium,
            color = titleColor
        )
        Text(
            text = subtitle,
            fontSize = 12.5.sp,
            color = NudgyMuted
        )
    }
}

/** A prototype leading icon (muted, 24dp) for a Settings row. */
@Composable
private fun SettingsRowIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = NudgyMuted,
        modifier = Modifier.size(24.dp)
    )
    Spacer(modifier = Modifier.width(12.dp))
}

/** A prototype chevron (muted) for a tappable Settings row. */
@Composable
private fun SettingsRowChevron() {
    Text(
        text = "›",
        fontSize = 20.sp,
        color = NudgyMuted
    )
}

/**
 * Profile row: initials circle + name + email (guest: no fake email).
 *
 * Step 2-2 — a signed-in user gets a soft "Edit" button at the row
 * end (the prototype's editProfileBtn); guests get none — there is
 * no Firebase user to rename ("Guest User" is a local label).
 */
@Composable
fun SettingsProfileRow(
    user: NudgyUser?,
    onEditNameClick: () -> Unit = {}
) {
    val isGuest = user == null || user.isGuest
    SettingsRowCard(slug = "profile") {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(NudgyPrimary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isGuest) "GU" else user!!.initials,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        if (isGuest) {
            SettingsRowTexts(
                title = stringResource(R.string.settings_guest_user),
                subtitle = stringResource(R.string.settings_guest_subtitle)
            )
        } else {
            SettingsRowTexts(
                title = user!!.displayName.ifBlank { user.email },
                subtitle = user.email
            )
            Button(
                onClick = onEditNameClick,
                modifier = Modifier.testTag("settings_profile_edit_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.nudgy_teal_soft),
                    contentColor = colorResource(R.color.nudgy_teal_dark)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_profile_edit),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
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
                Text(stringResource(R.string.settings_sign_in_button), color = Color.White)
            }
        }
    } else {
        SettingsRowCard(slug = "sign_in", onClick = onSignOutClick) {
            SettingsRowTexts(
                title = stringResource(R.string.settings_sign_out),
                subtitle = stringResource(R.string.settings_sign_out_sub)
            )
            SettingsRowChevron()
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
        SettingsRowIcon(Icons.Filled.CalendarMonth)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (connected) stringResource(R.string.settings_calendar_synced)
                else stringResource(R.string.settings_calendar_connect),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                color = NudgyCharcoal
            )
            Text(
                text = if (connected) stringResource(R.string.settings_calendar_synced_sub)
                else stringResource(R.string.settings_calendar_connect_sub),
                fontSize = 12.5.sp,
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
                Text(
                    if (isSyncing) stringResource(R.string.settings_calendar_syncing)
                    else stringResource(R.string.settings_calendar_sync_now),
                    color = Color.White
                )
            }
        } else {
            Button(
                onClick = onConnectClick,
                modifier = Modifier.testTag("settings_calendar_connect_button"),
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
            ) {
                Text(stringResource(R.string.settings_calendar_connect_button), color = Color.White)
            }
        }
    }
}

/** A tappable row with title + subtitle (Statistics, BYOK, Privacy, Help, Memories, Delete). */
@Composable
fun SettingsActionRow(
    slug: String,
    title: String,
    subtitle: String,
    titleColor: Color = NudgyCharcoal,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    SettingsRowCard(slug = slug, onClick = onClick) {
        if (icon != null) SettingsRowIcon(icon)
        SettingsRowTexts(title = title, subtitle = subtitle, titleColor = titleColor)
        SettingsRowChevron()
    }
}

/**
 * Sprint 6-2 — the notifications row (spec §2 Q8=(a), §8).
 *
 * The two v1 toggles, now PERSISTED (v1's were `remember` only and
 * reset to ON every time the sheet opened): Morning Brief (the 8:00
 * AM daily summary — MorningBriefWorker) and Sound & Haptics (the
 * reminder notification channel — ReminderNotification). Same
 * `settings_row_notifications` testTag as the S6-0 stub, so the
 * shell contract holds; the "Soon" text is gone.
 */
@Composable
fun SettingsNotificationsRow(
    morningBriefEnabled: Boolean,
    soundEnabled: Boolean,
    onMorningBriefChange: (Boolean) -> Unit,
    onSoundChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row_notifications"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsRowIcon(Icons.Filled.WbSunny)
                SettingsRowTexts(
                    title = stringResource(R.string.settings_morning_brief_title),
                    subtitle = stringResource(R.string.settings_morning_brief_sub)
                )
                Switch(
                    checked = morningBriefEnabled,
                    onCheckedChange = onMorningBriefChange,
                    modifier = Modifier.testTag("settings_morning_brief_switch")
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsRowIcon(Icons.Filled.VolumeUp)
                SettingsRowTexts(
                    title = stringResource(R.string.settings_sound_title),
                    subtitle = stringResource(R.string.settings_sound_sub)
                )
                Switch(
                    checked = soundEnabled,
                    onCheckedChange = onSoundChange,
                    modifier = Modifier.testTag("settings_sound_switch")
                )
            }
        }
    }
}

/**
 * Sprint 6-2 — the Appearance row (spec §2 Q9=(a)).
 * Sprint 7 — the prototype's inline Light/Dark/System seg (the
 * S6-2 AppearanceDialog is gone: same preference write, no dialog
 * in between — the Templates/Groups inline precedent). The subtitle
 * is the prototype's static line; the selected pill shows the
 * current mode. Pill testTags keep the dialog rows' names
 * (`settings_theme_<mode>`) so the contract carries over.
 */
@Composable
fun SettingsAppearanceRow(
    themeMode: String,
    onThemeModeChange: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row_appearance"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_section_appearance),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                color = NudgyCharcoal
            )
            Text(
                text = stringResource(R.string.settings_appearance_hint),
                fontSize = 12.5.sp,
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = colorResource(R.color.nudgy_track),
                        shape = CircleShape
                    )
                    .padding(4.dp)
            ) {
                for ((mode, labelRes) in listOf(
                    SettingsPreferences.THEME_LIGHT to R.string.settings_theme_light,
                    SettingsPreferences.THEME_DARK to R.string.settings_theme_dark,
                    SettingsPreferences.THEME_SYSTEM to R.string.settings_theme_system_short
                )) {
                    val selected = themeMode == mode
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .then(
                                if (selected) Modifier.shadow(1.dp, CircleShape) else Modifier
                            )
                            .background(
                                color = if (selected) Color.White else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { onThemeModeChange(mode) }
                            .testTag("settings_theme_$mode")
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (selected) NudgyCharcoal else NudgyMuted
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sprint 6-2 — the Language row (spec §2 Q10=(a)).
 *
 * Shows the current UI language; tapping opens the Language
 * dialog (English / বাংলা). Bengali strings cover the Settings
 * screen + the main screens (Today/Tasks/navigation) this sprint;
 * the rest of the app follows later.
 */
@Composable
fun SettingsLanguageRow(
    language: String,
    onClick: () -> Unit
) {
    SettingsRowCard(slug = "language", onClick = onClick) {
        SettingsRowIcon(Icons.Filled.Language)
        SettingsRowTexts(
            title = stringResource(R.string.settings_section_language),
            subtitle = if (language == SettingsPreferences.LANGUAGE_BENGALI) {
                stringResource(R.string.settings_language_bengali)
            } else {
                stringResource(R.string.settings_language_english)
            }
        )
        SettingsRowChevron()
    }
}

/**
 * Sprint 6-6 (step 4d) — the App lock row (spec §2 Q6=(a), §8).
 *
 * One Switch on the `appLockEnabled` preference (step 4a key,
 * default OFF). The Switch is the only control: the enable-time
 * biometric/PIN prompt lives in the Settings route — the row only
 * reports the flip, and the row's state always follows the stored
 * preference, so a cancelled prompt leaves the switch OFF.
 * OFF needs no prompt: the user is already inside an unlocked
 * session, and the lock gates entry, not its own removal.
 */
@Composable
fun SettingsAppLockRow(
    appLockEnabled: Boolean,
    onAppLockChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row_app_lock"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            SettingsRowIcon(Icons.Filled.Lock)
            SettingsRowTexts(
                title = stringResource(R.string.settings_section_app_lock),
                subtitle = if (appLockEnabled) {
                    stringResource(R.string.settings_app_lock_on_sub)
                } else {
                    stringResource(R.string.settings_app_lock_off_sub)
                }
            )
            Switch(
                checked = appLockEnabled,
                onCheckedChange = onAppLockChange,
                modifier = Modifier.testTag("settings_app_lock_switch")
            )
        }
    }
}

/**
 * Sprint 6-6 (step 4d) — the Backup & restore row (spec §2 Q7=(a),
 * §8).
 *
 * One card, two tappable rows (the notifications card's shape):
 * Export writes the versioned JSON document to a file the user
 * picks (step 4b), Restore merges one back with id-merge, no
 * duplicates (step 4c). Each inner row carries its own clickable
 * + testTag ON the clickable row itself (the clickable-merging
 * rule) — the outer card has no onClick. The SAF pickers are
 * device-gated; the rows only report the tap.
 */
@Composable
fun SettingsDataRow(
    onExportClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row_data"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExportClick() }
                    .testTag("settings_export_row")
            ) {
                SettingsRowIcon(Icons.Filled.Download)
                SettingsRowTexts(
                    title = stringResource(R.string.settings_export_title),
                    subtitle = stringResource(R.string.settings_export_sub)
                )
                SettingsRowChevron()
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRestoreClick() }
                    .testTag("settings_restore_row")
            ) {
                SettingsRowIcon(Icons.Filled.Restore)
                SettingsRowTexts(
                    title = stringResource(R.string.settings_restore_title),
                    subtitle = stringResource(R.string.settings_restore_sub)
                )
                SettingsRowChevron()
            }
        }
    }
}
