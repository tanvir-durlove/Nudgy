package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R

/**
 * Step 5b (polish item 2) — the Privacy Policy as an in-app screen
 * (approved Sprint 6 spec scope via the step-5 polish list: the
 * Settings privacy row opened only the hosted web page).
 *
 * Shape: a native scrollable text screen — Scaffold + TopAppBar
 * (the SearchScreen pattern) over a plain Column with
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(
    onBackClick: () -> Unit,
    onWebClick: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.testTag("privacy_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_section_privacy)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("privacy_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Last updated: $PRIVACY_POLICY_LAST_UPDATED",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("privacy_updated"),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = PRIVACY_POLICY_HEADER,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("privacy_header"),
            )
            Spacer(modifier = Modifier.height(16.dp))
            PRIVACY_POLICY_INTRO.forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            PRIVACY_POLICY_SECTIONS.forEachIndexed { index, section ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = section.heading,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("privacy_section_${index + 1}"),
                )
                Spacer(modifier = Modifier.height(4.dp))
                section.paragraphs.forEach { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.privacy_policy_view_on_web),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onWebClick)
                    .testTag("privacy_web_link")
                    .padding(vertical = 8.dp),
            )
        }
    }
}
