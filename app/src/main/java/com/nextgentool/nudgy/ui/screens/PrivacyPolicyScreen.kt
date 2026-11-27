package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens

/**
 * Step 5b (polish item 2) — the Privacy Policy as an in-app screen
 * (approved Sprint 6 spec scope via the step-5 polish list: the
 * Settings privacy row opened only the hosted web page).
 *
 * Shape: a native scrollable text screen — a plain Column with
 * verticalScroll, NOT a LazyColumn, so all eleven sections compose
 * and are assertable without scrolling. The body is the full
 * published policy ([PRIVACY_POLICY_SECTIONS]), so the screen
 * opens offline; the hosted page stays reachable from the footer
 * row ("View on the web") as the canonical reference — it is a
 * fallback, not the content source. Back pops the stack to
 * Settings (the NavHost wires onBackClick to popBackStack).
 *
 * Honest limitation: the NavHost wiring (row tap → this route) is
 * verified by reading + CI compile — the real NavHost cannot be
 * rendered in a unit test (no Hilt test infra in this repo), same
 * as the Search entry (2d) and the widget tap (3d). The screen
 * itself is fully covered by PrivacyPolicyScreenTest.
 *
 * Sprint 7 — Privacy layout (prototype scr-privacy): the TopAppBar
 * is gone, replaced by the sprint backbar (back + "Privacy Policy"
 * 21sp/800, testTag privacy_backbar; back behavior unchanged);
 * the header keeps the real "Last updated November 6, 2026" date
 * from the content file; the intro paragraphs sit in one card and
 * each of the eleven sections sits in its own white card (22dp
 * radius, 16dp padding), heading 17sp/Bold + body text with a
 * 22sp line height for readability; the web link row keeps its
 * behavior and sits in a card at the bottom.
 *
 * Disclosed deviations (accepted, design-only): the prototype
 * shows ONE card with a SHORT summary — the app shows the FULL
 * published policy instead (§5 Q8, banked decision: the summary
 * omits the ads / analytics / crash-reports sections entirely
 * and invents an "attachments up to 25 MB per file, up to 10 per
 * task" claim the published policy never makes; shipping that
 * summary would put wrong legal text in the app). Layout matches
 * the prototype (backbar + cards); content deliberately does
 * not. No text was changed anywhere in this pass — the content
 * file ([PrivacyPolicyContent.kt]) is untouched.
 */
@Composable
fun PrivacyPolicyScreen(
    onBackClick: () -> Unit,
    onWebClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .verticalScroll(rememberScrollState())
            .padding(NudgyTokens.screenPadding)
            .testTag("privacy_screen"),
    ) {
        // Backbar (prototype): the same back Row Matrix/Stats/Chat
        // use (back + title, no TopAppBar).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("privacy_backbar"),
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.testTag("privacy_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NudgyCharcoal,
                )
            }
            Text(
                text = stringResource(R.string.settings_section_privacy),
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NudgyCharcoal,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Last updated: $PRIVACY_POLICY_LAST_UPDATED",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = NudgyMuted,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .testTag("privacy_updated"),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = PRIVACY_POLICY_HEADER,
            style = MaterialTheme.typography.bodySmall,
            color = NudgyMuted,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .testTag("privacy_header"),
        )
        Spacer(modifier = Modifier.height(16.dp))
        // Intro card (prototype's card shape): the lead-in text.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NudgyTokens.cardRadius,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = NudgyTokens.cardElevation,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                PRIVACY_POLICY_INTRO.forEachIndexed { index, paragraph ->
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 22.sp,
                        ),
                        color = NudgyCharcoal,
                    )
                }
            }
        }
        PRIVACY_POLICY_SECTIONS.forEachIndexed { index, section ->
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = NudgyTokens.cardRadius,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = NudgyTokens.cardElevation,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = section.heading,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = NudgyCharcoal,
                        modifier = Modifier.testTag("privacy_section_${index + 1}"),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    section.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                        if (paragraphIndex > 0) Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                lineHeight = 22.sp,
                            ),
                            color = NudgyCharcoal,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NudgyTokens.cardRadius,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = NudgyTokens.cardElevation,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onWebClick)
                    .testTag("privacy_web_link")
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.privacy_policy_view_on_web),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
