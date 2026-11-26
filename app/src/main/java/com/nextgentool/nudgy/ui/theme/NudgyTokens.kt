package com.nextgentool.nudgy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Sprint 0 — design tokens for the scratch rebuild (v2).
 *
 * Colours live in res/values/brand_colors.xml (kit HEX, single source
 * of truth) and are read in composables via colorResource(). This file
 * holds only the non-colour tokens so the HEX is never duplicated.
 *
 * Font (locked 2026-11-15 night): Material default — Roboto / system
 * sans-serif. Compose Typography uses the default font family; no
 * custom font files in this rebuild.
 */
object NudgyTokens {
    /** Card corner radius (prototype: 22px). */
    val cardRadius = RoundedCornerShape(22.dp)

    /** Generous screen padding (Figma: 16-20px). */
    val screenPadding = 16.dp

    /** One soft shadow only — no heavy gradients, no decoration. */
    val cardElevation = 2.dp
}
