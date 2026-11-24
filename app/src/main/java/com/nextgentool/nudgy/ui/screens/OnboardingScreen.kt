package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens

/**
 * Sprint 6-3 — the first-run tutorial (feature 40, Q3=(a), spec §6).
 *
 * Four short pages (clean minimal Material, the locked design —
 * teal #0F766E, coral #F97360, cream #FFF1E8): tasks, Plan My Day,
 * reminders, groups. "Skip" sits on every page (spec §6); the last
 * page offers the sample tasks — checked by default — and finishing
 * seeds them. The screen itself owns no data: the caller decides
 * what finishing means ([onFinish]); the first-launch caller seeds
 * + sets the flag (OnboardingViewModel), the Settings re-view caller
 * only dismisses (isReView = true hides the sample offer entirely —
 * re-viewing must never re-seed and never reset the flag).
 *
 * State is rememberSaveable, so a rotation mid-tutorial keeps the
 * page and the sample choice instead of restarting the flow.
 */
@Composable
fun OnboardingScreen(
    isReView: Boolean = false,
    onFinish: (includeSamples: Boolean) -> Unit
) {
    var page by rememberSaveable { mutableStateOf(0) }
    var includeSamples by rememberSaveable { mutableStateOf(true) }
    val isLastPage = page == ONBOARDING_PAGES.size - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .padding(NudgyTokens.screenPadding)
    ) {
        // Skip on every page (spec §6) — it finishes without samples.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = { onFinish(false) },
                modifier = Modifier.testTag("onboarding_skip")
            ) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    color = NudgyMuted
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        // The current page: icon in a teal circle + one line each.
        val current = ONBOARDING_PAGES[page]
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        color = colorResource(R.color.nudgy_teal),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = current.icon,
                    contentDescription = null,
                    tint = colorResource(R.color.nudgy_cream),
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(current.titleRes),
                style = MaterialTheme.typography.titleLarge,
                color = NudgyCharcoal,
                modifier = Modifier.testTag("onboarding_page_title")
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(current.bodyRes),
                style = MaterialTheme.typography.bodyLarge,
                color = NudgyMuted
            )
            // The sample offer lives on the LAST page only (spec §6),
            // and only on the first launch — never in a re-view.
            if (isLastPage && !isReView) {
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_sample_offer")
                ) {
                    Checkbox(
                        checked = includeSamples,
                        onCheckedChange = { includeSamples = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = colorResource(R.color.nudgy_teal)
                        ),
                        modifier = Modifier.testTag("onboarding_sample_checkbox")
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.onboarding_sample_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = NudgyCharcoal
                        )
                        Text(
                            text = stringResource(R.string.onboarding_sample_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyMuted
                        )
                    }
                }
            }
        }
        // Page dots.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            ONBOARDING_PAGES.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (index == page) {
                                colorResource(R.color.nudgy_teal)
                            } else {
                                NudgyMuted.copy(alpha = 0.4f)
                            },
                            shape = CircleShape
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                if (isLastPage) {
                    onFinish(if (isReView) false else includeSamples)
                } else {
                    page++
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(R.color.nudgy_teal)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(if (isLastPage) "onboarding_finish" else "onboarding_next")
        ) {
            Text(
                text = stringResource(
                    when {
                        !isLastPage -> R.string.onboarding_next
                        isReView -> R.string.onboarding_done
                        else -> R.string.onboarding_finish
                    }
                ),
                color = colorResource(R.color.nudgy_cream)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private data class OnboardingPage(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int
)

/** The four tutorial pages (spec §6): tasks, Plan My Day, reminders, groups. */
private val ONBOARDING_PAGES = listOf(
    OnboardingPage(
        icon = Icons.Filled.Checklist,
        titleRes = R.string.onboarding_page1_title,
        bodyRes = R.string.onboarding_page1_body
    ),
    OnboardingPage(
        icon = Icons.Outlined.CalendarMonth,
        titleRes = R.string.onboarding_page2_title,
        bodyRes = R.string.onboarding_page2_body
    ),
    OnboardingPage(
        icon = Icons.Filled.Notifications,
        titleRes = R.string.onboarding_page3_title,
        bodyRes = R.string.onboarding_page3_body
    ),
    OnboardingPage(
        icon = Icons.Outlined.Group,
        titleRes = R.string.onboarding_page4_title,
        bodyRes = R.string.onboarding_page4_body
    )
)
