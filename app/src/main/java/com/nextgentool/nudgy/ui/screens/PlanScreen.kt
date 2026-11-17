package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ui.theme.NudgyTokens

/**
 * Sprint 1-0 — Plan destination (skeleton only).
 *
 * Nested route "today/plan" inside the Today graph. The real Plan
 * content (auto draft, drag to arrange, Start my day) is S1-2.
 * Back returns to Today. Nothing is saved here.
 */
@Composable
fun PlanScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.nudgy_background))
            .padding(NudgyTokens.screenPadding)
    ) {
        Text(
            text = "Plan",
            style = MaterialTheme.typography.titleLarge,
            color = colorResource(R.color.nudgy_ink)
        )
        Spacer(modifier = Modifier.height(16.dp))
        NudgySkeletonRow()
        Spacer(modifier = Modifier.height(12.dp))
        NudgySkeletonRow()
        Spacer(modifier = Modifier.height(12.dp))
        NudgySkeletonRow()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Sprint 1-2 fills this in: auto draft, drag to arrange, Start my day.",
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(R.color.nudgy_muted)
        )
    }
}
