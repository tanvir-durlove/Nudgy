package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ui.components.NudgyLogoMark
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens

/**
 * Sprint 7 — the first-run tutorial rebuilt to the prototype
 * (scr-onboarding): centred hero (logo mark 64px + "Nudgy" 30sp +
 * tagline), one white card holding one of 3 slides (pill
 * "Step N of 3", h2 23sp, illustration), dots, Next (teal) + a
 * "Skip the tour" ghost button. The S6-3 four-page layout (icon in
 * a teal circle + top-right Skip) is gone.
 *
 * Design-only: the behaviour is unchanged — OnboardingViewModel,
 * the MainActivity gate, finish() semantics, rememberSaveable page
 * state and isReView are all untouched. Two disclosed deviations
 * from the static prototype, both behaviour winning over design:
 * (1) the sample-offer checkbox stays on the last slide, first
 * launch only, checked by default — the prototype shows no
 * checkbox, but dropping it would silently change what finishing
 * seeds; (2) the slide illustrations (task rows, carry banner,
 * chat bubbles) are STATIC — they picture the feature, they are
 * not live data; the real seeded samples are unchanged.
 *
 * The whole column scrolls: slide 3 (bubbles + sample offer) is
 * taller than a short screen, and clipping it would hide the
 * checkbox that decides what finishing seeds. Each slide starts at
 * the top (LaunchedEffect resets the scroll on every page change) —
 * otherwise a user who scrolled down to reach Next would land
 * mid-way into the new slide.
 */
@Composable
fun OnboardingScreen(
    isReView: Boolean = false,
    onFinish: (includeSamples: Boolean) -> Unit
) {
    var page by rememberSaveable { mutableStateOf(0) }
    var includeSamples by rememberSaveable { mutableStateOf(true) }
    val isLastPage = page == ONBOARDING_SLIDES.size - 1
    val scrollState = rememberScrollState()
    // Each slide starts at the top: without this, a user who scrolled
    // down to reach Next would land mid-way into the new slide (its
    // pill and heading scrolled out of view) instead of at its top.
    LaunchedEffect(page) {
        scrollState.scrollTo(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .verticalScroll(scrollState)
            .padding(NudgyTokens.screenPadding)
    ) {
        // Hero (prototype scr-onboarding): centred logo + name + tagline.
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            NudgyLogoMark(width = 64.dp, height = 28.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.onboarding_tagline),
                fontSize = 13.sp,
                color = NudgyMuted
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        // The slide card: one slide at a time (prototype .card, 18px padding).
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NudgyTokens.cardRadius,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = NudgyTokens.cardElevation,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                val slide = ONBOARDING_SLIDES[page]
                // Pill "Step N of 3 · …" (prototype .pill-note).
                Box(
                    modifier = Modifier.background(
                        color = colorResource(R.color.nudgy_teal_soft),
                        shape = CircleShape
                    )
                ) {
                    Text(
                        text = stringResource(slide.pillRes),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorResource(R.color.nudgy_teal_dark),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(slide.titleRes),
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    color = NudgyCharcoal,
                    modifier = Modifier.testTag("onboarding_page_title")
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(slide.bodyRes),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = NudgyMuted
                )
                when (page) {
                    0 -> OnboardingSlideOne()
                    1 -> OnboardingSlideTwo()
                    else -> OnboardingSlideThree(
                        isLastPage = isLastPage,
                        isReView = isReView,
                        includeSamples = includeSamples,
                        onIncludeSamplesChange = { includeSamples = it }
                    )
                }
            }
        }
        // Page dots (the existing 8dp pattern: teal current, muted others).
        Spacer(modifier = Modifier.height(18.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            ONBOARDING_SLIDES.indices.forEach { index ->
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
        Spacer(modifier = Modifier.height(18.dp))
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
        // "Skip the tour" ghost (prototype .btn-ghost) — this replaces
        // the old top-right Skip TextButton; the prototype has none.
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = { onFinish(false) },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_skip")
        ) {
            Text(
                text = stringResource(R.string.onboarding_skip_tour),
                color = NudgyCharcoal
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** Slide 1 — two static illustrative task rows + the tiny note. */
@Composable
private fun OnboardingSlideOne() {
    Spacer(modifier = Modifier.height(16.dp))
    OnboardingTaskRow(
        checked = true,
        titleRes = R.string.onboarding_slide1_task1_title,
        metaRes = R.string.onboarding_slide1_task1_meta
    )
    Spacer(modifier = Modifier.height(10.dp))
    OnboardingTaskRow(
        checked = false,
        titleRes = R.string.onboarding_slide1_task2_title,
        metaRes = R.string.onboarding_slide1_task2_meta
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.onboarding_slide1_note),
        fontSize = 12.sp,
        color = NudgyMuted
    )
}

/** One illustrative task row (prototype .task): 22px checkbox + title + meta. */
@Composable
private fun OnboardingTaskRow(checked: Boolean, titleRes: Int, metaRes: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        color = if (checked) {
                            colorResource(R.color.nudgy_teal)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(4.dp)
                    )
                    .then(
                        if (checked) {
                            Modifier
                        } else {
                            Modifier.border(
                                width = 2.dp,
                                color = NudgyMuted,
                                shape = RoundedCornerShape(4.dp)
                            )
                        }
                    )
            ) {
                if (checked) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = colorResource(R.color.nudgy_cream),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column {
                Text(
                    text = stringResource(titleRes),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = NudgyCharcoal
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = stringResource(metaRes),
                    fontSize = 12.sp,
                    color = NudgyMuted
                )
            }
        }
    }
}

/** Slide 2 — the static carry banner (prototype .banner, cream). */
@Composable
private fun OnboardingSlideTwo() {
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = colorResource(R.color.nudgy_cream),
                shape = NudgyTokens.cardRadius
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = colorResource(R.color.nudgy_teal),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column {
            Text(
                text = stringResource(R.string.onboarding_slide2_banner_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.onboarding_slide2_banner_sub),
                fontSize = 13.sp,
                color = NudgyMuted
            )
        }
    }
}

/**
 * Slide 3 — the two static chat bubbles (prototype .bubble.me / .ai)
 * plus the sample offer, which stays on the last slide, first launch
 * only, checked by default (a disclosed deviation — the prototype
 * shows no checkbox; dropping it would change what finishing seeds).
 */
@Composable
private fun OnboardingSlideThree(
    isLastPage: Boolean,
    isReView: Boolean,
    includeSamples: Boolean,
    onIncludeSamplesChange: (Boolean) -> Unit
) {
    Spacer(modifier = Modifier.height(16.dp))
    // "Me" bubble: teal, right-aligned, 82% max width.
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
            color = colorResource(R.color.nudgy_teal)
        ) {
            Text(
                text = stringResource(R.string.onboarding_slide3_user_bubble),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = colorResource(R.color.nudgy_cream),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
            )
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
    // "AI" bubble: white card with the NUDGY who-label.
    Surface(
        modifier = Modifier.fillMaxWidth(0.82f),
        shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                text = "NUDGY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onboarding_slide3_ai_bubble),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = NudgyCharcoal
            )
        }
    }
    // The sample offer lives on the LAST page only, and only on the
    // first launch — never in a re-view (S6-3 behaviour, unchanged).
    if (isLastPage && !isReView) {
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_sample_offer")
        ) {
            Checkbox(
                checked = includeSamples,
                onCheckedChange = onIncludeSamplesChange,
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

private data class OnboardingSlide(
    val pillRes: Int,
    val titleRes: Int,
    val bodyRes: Int
)

/** The three tutorial slides (prototype scr-onboarding). */
private val ONBOARDING_SLIDES = listOf(
    OnboardingSlide(
        pillRes = R.string.onboarding_slide1_pill,
        titleRes = R.string.onboarding_slide1_title,
        bodyRes = R.string.onboarding_slide1_body
    ),
    OnboardingSlide(
        pillRes = R.string.onboarding_slide2_pill,
        titleRes = R.string.onboarding_slide2_title,
        bodyRes = R.string.onboarding_slide2_body
    ),
    OnboardingSlide(
        pillRes = R.string.onboarding_slide3_pill,
        titleRes = R.string.onboarding_slide3_title,
        bodyRes = R.string.onboarding_slide3_body
    )
)
