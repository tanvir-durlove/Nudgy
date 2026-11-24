package com.nextgentool.nudgy.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.NudgyMemoryEntity
import kotlinx.coroutines.flow.SharedFlow
import com.nextgentool.nudgy.settings.SettingsPreferences
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens

/**
 * Sprint 0 — shell screens for the scratch rebuild (v2).
 *
 * Placeholders only: each destination shows its title + skeleton
 * loading rows. Sprint 1+ fills each shell with the real screen.
 * Content must arrive in steps with skeleton/shimmer covering the
 * load — never pop in all at once (the feel rule).
 */

@Composable
private fun ShellScaffold(
    title: String,
    note: String,
    // Sprint 4-4 — a real row above the note (the Settings weekly
    // review entry). Null on the other shells: they stay placeholders.
    content: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .padding(NudgyTokens.screenPadding),
        verticalArrangement = Arrangement.Top
    ) {
        Text(text = title, color = NudgyCharcoal)
        Spacer(modifier = Modifier.height(16.dp))
        NudgyStaticPlaceholderRow()
        Spacer(modifier = Modifier.height(12.dp))
        NudgyStaticPlaceholderRow()
        Spacer(modifier = Modifier.height(12.dp))
        NudgyStaticPlaceholderRow()
        Spacer(modifier = Modifier.height(16.dp))
        content?.invoke()
        Text(text = note, color = NudgyMuted)
    }
}

/** Shimmer placeholder: a pulsing rounded box (skeleton loading). */
@Composable
fun NudgySkeletonRow() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .alpha(alpha)
            .background(
                color = colorResource(R.color.nudgy_cream),
                shape = NudgyTokens.cardRadius
            )
    )
}

/**
 * Sprint 5 fix round — STATIC placeholder row for the shell screens
 * (device test on ci-792: the Settings placeholder's shimmer pulsed
 * dim/bright forever, which reads as a blinking light). The real
 * Settings screen lands in Sprint 6; until then the placeholder
 * stands still. [NudgySkeletonRow] keeps its shimmer for REAL
 * transient loading (Plan/Tasks/Today) — never use it for a
 * permanent screen.
 */
@Composable
fun NudgyStaticPlaceholderRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .testTag("settings_placeholder_row")
            .alpha(0.6f)
            .background(
                color = colorResource(R.color.nudgy_cream),
                shape = NudgyTokens.cardRadius
            )
    )
}

@Composable
fun CalendarShell() = ShellScaffold(
    title = "Calendar",
    note = "Sprint 2 fills this in: Google / Outlook sync, time blocking, drag to slot."
)

/**
 * Sprint 6-0 — the Settings sections (foundation shell, spec §4).
 *
 * One section per spec §4 entry, in the v1 sheet's order. S6-1
 * wired profile/sign-in, Memories, BYOK, Privacy, Help & feedback
 * and calendar; S6-2 wired notifications, Appearance and language
 * — no stub row remains on this screen (SettingsStubRow stays
 * only as the `else` fallback in the shell's `when`).
 * The section headers + rows are the contract the later sub-PRs
 * fill in — a later PR replaces a stub row's onClick/behaviour, it
 * never re-lays-out the screen.
 *
 * Sprint 6-2 — the titles/subtitles are string resources (the
 * language setting renders them in বাংলা when "bn" is chosen).
 */
private data class SettingsSection(
    val slug: String,
    val titleRes: Int,
    val subtitleRes: Int
)

private val SETTINGS_SECTIONS = listOf(
    SettingsSection("profile", R.string.settings_section_profile, R.string.settings_section_profile_sub),
    SettingsSection("sign_in", R.string.settings_section_sign_in, R.string.settings_section_sign_in_sub),
    SettingsSection("notifications", R.string.settings_section_notifications, R.string.settings_section_notifications_sub),
    SettingsSection("appearance", R.string.settings_section_appearance, R.string.settings_section_appearance_sub),
    SettingsSection("language", R.string.settings_section_language, R.string.settings_section_language_sub),
    SettingsSection("calendar", R.string.settings_section_calendar, R.string.settings_section_calendar_sub),
    SettingsSection("gemini_key", R.string.settings_section_gemini_key, R.string.settings_section_gemini_key_sub),
    SettingsSection("privacy", R.string.settings_section_privacy, R.string.settings_section_privacy_sub),
    SettingsSection("help_feedback", R.string.settings_section_help_feedback, R.string.settings_section_help_feedback_sub),
    SettingsSection("memories", R.string.settings_section_memories, R.string.settings_section_memories_sub),
    SettingsSection("delete_account", R.string.settings_section_delete_account, R.string.settings_section_delete_account_sub)
)

@Composable
private fun SettingsSectionHeader(title: String, slug: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = NudgyMuted,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_section_$slug")
            .padding(start = 4.dp, top = 16.dp, bottom = 8.dp)
    )
}

/** Inert stub row — S6-1/S6-2 replace it with the real row. No onClick: it does nothing. */
@Composable
private fun SettingsStubRow(section: SettingsSection) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row_${section.slug}"),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(section.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = NudgyCharcoal
                )
                Text(
                    text = stringResource(section.subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = NudgyMuted
                )
            }
            Text(
                text = "Soon",
                style = MaterialTheme.typography.bodySmall,
                color = NudgyMuted
            )
        }
    }
}

@Composable
fun SettingsShell(
    // Sprint 4-4 — the Settings entry to the weekly review screen
    // (spec §7, Q9=(a)): the same screen the Today progress ring
    // opens. Defaults to no-op so older call sites keep compiling.
    onWeeklyReviewClick: () -> Unit = {},
    // Sprint 6-1 — the wired rows (spec §4–§5). All defaulted so the
    // S6-0 shell test (no params) still renders one row per section:
    // with no user the profile/sign-in rows render the guest state.
    user: NudgyUser? = null,
    memories: List<NudgyMemoryEntity> = emptyList(),
    byokKey: String = "",
    calendarConnected: Boolean = false,
    isCalendarSyncing: Boolean = false,
    isDeleting: Boolean = false,
    deleteCompleted: SharedFlow<Unit>? = null,
    onSignInClick: () -> Unit = {},
    onSignOutClick: () -> Unit = {},
    onDeleteAccountClick: () -> Unit = {},
    onCalendarConnectClick: () -> Unit = {},
    onCalendarSyncClick: () -> Unit = {},
    onSaveByokKey: (String) -> Unit = {},
    onResetByokKey: () -> Unit = {},
    onPrivacyClick: () -> Unit = {},
    onHelpFeedbackClick: () -> Unit = {},
    onAddMemory: (String) -> Unit = {},
    onDeleteMemory: (String) -> Unit = {},
    // Sprint 6-2 — the notifications / appearance / language state
    // (spec §2 Q8/Q9/Q10). Defaults keep the S6-0 shell test (no
    // params) rendering: both toggles ON, system theme, English.
    morningBriefEnabled: Boolean = true,
    soundEnabled: Boolean = true,
    themeMode: String = SettingsPreferences.THEME_SYSTEM,
    language: String = SettingsPreferences.LANGUAGE_ENGLISH,
    onMorningBriefChange: (Boolean) -> Unit = {},
    onSoundChange: (Boolean) -> Unit = {},
    onThemeModeChange: (String) -> Unit = {},
    onLanguageChange: (String) -> Unit = {}
) {
    // Sprint 6-1 — dialog state lives in the shell; the dialogs
    // themselves are in SettingsDialogs.kt (v1 texts verbatim).
    var showByokDialog by remember { mutableStateOf(false) }
    var showMemoriesDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // Sprint 6-2 — the Appearance + Language dialogs.
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    // The delete dialog closes on success (v1 C7: it stays up while
    // the deletion runs, and a failure keeps it open for a retry).
    LaunchedEffect(deleteCompleted) {
        deleteCompleted?.collect { showDeleteDialog = false }
    }
    val isGuest = user == null || user.isGuest
    // Sprint 6-0 — the real Settings structure (spec §4): the
    // static placeholder rows are gone (the S5 fix-round blink
    // guard stays relevant — no shimmer anywhere on this screen).
    // Scrollable: eleven sections + the Weekly review row do not
    // fit one screen. The Weekly review row is unchanged from
    // Sprint 4-4 (same testTag, same behaviour).
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .padding(NudgyTokens.screenPadding)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = stringResource(R.string.settings_title), color = NudgyCharcoal)
        for (section in SETTINGS_SECTIONS) {
            SettingsSectionHeader(title = stringResource(section.titleRes), slug = section.slug)
            // Sprint 6-1 — the wired sections replace their stub row
            // with the real row (same `settings_row_<slug>` testTag).
            // Sprint 6-2 — notifications/appearance/language are wired
            // now; no stub row remains on this screen.
            when (section.slug) {
                "profile" -> SettingsProfileRow(user = user)
                "sign_in" -> SettingsSignInRow(
                    isGuest = isGuest,
                    onSignInClick = onSignInClick,
                    onSignOutClick = onSignOutClick
                )
                "notifications" -> SettingsNotificationsRow(
                    morningBriefEnabled = morningBriefEnabled,
                    soundEnabled = soundEnabled,
                    onMorningBriefChange = onMorningBriefChange,
                    onSoundChange = onSoundChange
                )
                "appearance" -> SettingsAppearanceRow(
                    themeMode = themeMode,
                    onClick = { showAppearanceDialog = true }
                )
                "language" -> SettingsLanguageRow(
                    language = language,
                    onClick = { showLanguageDialog = true }
                )
                "calendar" -> SettingsCalendarRow(
                    connected = calendarConnected,
                    isSyncing = isCalendarSyncing,
                    onConnectClick = onCalendarConnectClick,
                    onSyncClick = onCalendarSyncClick
                )
                "gemini_key" -> SettingsActionRow(
                    slug = "gemini_key",
                    title = stringResource(R.string.settings_section_gemini_key),
                    subtitle = if (byokKey.isNotBlank()) stringResource(R.string.settings_gemini_custom_key)
                    else stringResource(R.string.settings_gemini_builtin),
                    onClick = { showByokDialog = true }
                )
                "privacy" -> SettingsActionRow(
                    slug = "privacy",
                    title = stringResource(R.string.settings_section_privacy),
                    subtitle = stringResource(R.string.settings_section_privacy_sub),
                    onClick = onPrivacyClick
                )
                "help_feedback" -> SettingsActionRow(
                    slug = "help_feedback",
                    title = stringResource(R.string.settings_section_help_feedback),
                    subtitle = stringResource(R.string.settings_section_help_feedback_sub),
                    onClick = onHelpFeedbackClick
                )
                "memories" -> SettingsActionRow(
                    slug = "memories",
                    title = stringResource(R.string.settings_section_memories),
                    subtitle = if (memories.isEmpty()) stringResource(R.string.settings_memories_empty)
                    else stringResource(R.string.settings_memories_facts, memories.size),
                    onClick = { showMemoriesDialog = true }
                )
                "delete_account" -> SettingsActionRow(
                    slug = "delete_account",
                    title = stringResource(R.string.settings_section_delete_account),
                    subtitle = stringResource(R.string.settings_section_delete_account_sub),
                    titleColor = Color(0xFFD32F2F),
                    onClick = { showDeleteDialog = true }
                )
                else -> SettingsStubRow(section = section)
            }
        }
        SettingsSectionHeader(title = stringResource(R.string.settings_section_weekly_review), slug = "weekly_review")
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_weekly_review"),
            shape = NudgyTokens.cardRadius,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = NudgyTokens.cardElevation
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onWeeklyReviewClick() }
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_section_weekly_review),
                        style = MaterialTheme.typography.titleMedium,
                        color = NudgyCharcoal
                    )
                    Text(
                        text = stringResource(R.string.settings_weekly_review_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = NudgyMuted
                    )
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = NudgyAccent
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
    // Sprint 6-1 — the dialogs (v1 texts verbatim, SettingsDialogs.kt).
    if (showByokDialog) {
        ByokKeyDialog(
            initialKey = byokKey,
            onSave = { key ->
                onSaveByokKey(key)
                showByokDialog = false
            },
            onReset = {
                onResetByokKey()
                showByokDialog = false
            },
            onDismiss = { showByokDialog = false }
        )
    }
    if (showMemoriesDialog) {
        MemoriesDialog(
            memories = memories,
            onAddMemory = onAddMemory,
            onDeleteMemory = onDeleteMemory,
            onDismiss = { showMemoriesDialog = false }
        )
    }
    if (showDeleteDialog) {
        DeleteAccountDialog(
            isDeleting = isDeleting,
            onConfirm = onDeleteAccountClick,
            onDismiss = { showDeleteDialog = false }
        )
    }
    // Sprint 6-2 — the Appearance + Language dialogs (SettingsDialogs.kt).
    if (showAppearanceDialog) {
        AppearanceDialog(
            currentMode = themeMode,
            onSelect = { mode ->
                onThemeModeChange(mode)
                showAppearanceDialog = false
            },
            onDismiss = { showAppearanceDialog = false }
        )
    }
    if (showLanguageDialog) {
        LanguageDialog(
            currentLanguage = language,
            onSelect = { code ->
                onLanguageChange(code)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}
