package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Fixed brand colors — identical in light and dark themes.
// Contrast (verified programmatically, WCAG ratios):
//   White on NudgyPrimary 4.87:1, NudgyPrimary on Canvas 4.63:1
//   NudgyPrimaryDark on Canvas 5.20:1, on Peach 4.95:1
//   NudgyMuted (light) on Canvas 5.01:1, on white 5.28:1
val NudgyPrimary = Color(0xFFCC3F27)      // Iconic coral primary (darkened from 0xFFF06449 for contrast)
val NudgyPrimaryLight = Color(0xFFFF8E78) // Highlight coral
val NudgyCardBorder = Color(0xFFECE7DE)   // Subtle warm border (hairlines; visible on both themes)
val NudgyBorder = Color(0xFFE6E1D6)       // Divider and outline color
val NudgyPeachBorder = Color(0xFFF8D8D4)
val NudgyLavenderBorder = Color(0xFFDCDAF8)
val NudgySageBorder = Color(0xFFD0EDDD)
val NudgyFieldBorder = Color(0xFFAEA79C)  // Visible unfocused field border (2.38:1 on white, was near-invisible CardBorder)

// Mascot Specific Colors (illustration — theme-independent)
val MascotCoral = Color(0xFFCC3F27)
val MascotCheek = Color(0xFFFF9F92)
val MascotSnout = Color(0xFFFF8875)
val MascotDark = Color(0xFF1C1B1F)

// Theme-switching palette. Screens keep referring to the token names below;
// NudgyTheme swaps the palette when the system is in dark mode, so every
// composable call site follows the theme with no per-screen changes.
// Dark palette: dark blue-grey surfaces (NOT pure black), retained elevation
// feel via the card/background step, accent sparing.
internal data class NudgyPalette(
    val isDark: Boolean,
    val canvas: Color,
    val card: Color,
    val charcoal: Color,
    val muted: Color,
    val peach: Color,
    val lavender: Color,
    val sage: Color,
    val green: Color,
    val primaryDark: Color,
    val memberPillText: Color,
    val butter: Color,
    val butterBorder: Color,
    val butterText: Color,
    val avatarGold: Color,
    val chipBackground: Color,
    val accent: Color
)

private val NudgyLightPalette = NudgyPalette(
    isDark = false,
    canvas = Color(0xFFFBF9F4),       // Warm linen off-white background
    card = Color(0xFFFFFFFF),         // Crisp pure white cards
    charcoal = Color(0xFF1C1B1F),     // Primary text & active dark pills
    muted = Color(0xFF6F6A72),        // Secondary text & captions (darkened from 0xFF79747E)
    peach = Color(0xFFFDF1F0),        // Meeting cards & tags
    lavender = Color(0xFFF1F0FD),     // Deep work / focus time cards
    sage = Color(0xFFEBF7F0),         // Daily progress card & complete badges
    green = Color(0xFF2E7D32),        // Completed check icon & progress arc
    primaryDark = Color(0xFFBE3B24),  // Pressed coral (darkened from 0xFFD64E34)
    memberPillText = Color(0xFF5D4FD6), // MEMBER pill text (darkened from 0xFF6C5CE7: 5.24:1 on lavender)
    butter = Color(0xFFFEF9E7),       // "Nudgy remembers" & AI suggestion cards
    butterBorder = Color(0xFFF5E8B9),
    butterText = Color(0xFF8D6200),   // Rich gold/amber for insight headlines
    avatarGold = Color(0xFFF6DE95),   // Avatar circle background
    chipBackground = Color(0xFFF3EFE6), // Unselected chip fill
    accent = Color(0xFFCC3F27)        // Small-text accent links (same as primary in light)
)

private val NudgyDarkPalette = NudgyPalette(
    isDark = true,
    canvas = Color(0xFF1C1E26),       // Dark blue-grey background
    card = Color(0xFF262833),         // Dark blue-grey surface (step above background = elevation)
    charcoal = Color(0xFFF5F1EA),     // Primary text (14.77:1 on background, 13.01:1 on card)
    muted = Color(0xFFB8B2A8),        // Secondary text (7.89:1 on background, 6.96:1 on card)
    peach = Color(0xFF3A2B2E),        // Dark meeting tint
    lavender = Color(0xFF2C2C40),     // Dark focus tint
    sage = Color(0xFF22332A),         // Dark progress tint
    green = Color(0xFF81C784),        // Completed accents (7.28:1 on card)
    primaryDark = Color(0xFFFFB4A6),  // Light coral text on dark tints (7.88:1 on dark peach)
    memberPillText = Color(0xFFB7B0F5), // MEMBER pill text (6.84:1 on dark lavender)
    butter = Color(0xFF38311F),       // Dark warm insight card
    butterBorder = Color(0xFF57492A),
    butterText = Color(0xFFE8C87A),   // Gold headlines (7.97:1 on dark butter)
    avatarGold = Color(0xFF6B5A1E),   // Dark gold avatar (light text 6.00:1)
    chipBackground = Color(0xFF2E3038), // Unselected chip fill
    accent = Color(0xFFFF8E78)        // Small-text accent links (7.44:1 on background)
)

internal val LocalNudgyPalette = staticCompositionLocalOf { NudgyLightPalette }
internal fun nudgyPaletteFor(darkTheme: Boolean): NudgyPalette =
    if (darkTheme) NudgyDarkPalette else NudgyLightPalette

// Theme-aware token accessors. Every use below must be inside a @Composable
// function (all current uses are); the two exceptions keep plain values:
//  - NudgyAvatarPalettes stays a static light list because groupAvatarPalette()
//    in GroupsScreen is a plain helper, not a composable (pastel avatar chips
//    read fine on dark backgrounds too).
//  - Typography in Type.kt uses literal hexes for the same reason (static vals).
val NudgyCanvas: Color @Composable get() = LocalNudgyPalette.current.canvas
val NudgyCard: Color @Composable get() = LocalNudgyPalette.current.card
val NudgyCharcoal: Color @Composable get() = LocalNudgyPalette.current.charcoal
val NudgyIsDarkTheme: Boolean @Composable get() = LocalNudgyPalette.current.isDark
val NudgyMuted: Color @Composable get() = LocalNudgyPalette.current.muted
val NudgyPeach: Color @Composable get() = LocalNudgyPalette.current.peach
val NudgyLavender: Color @Composable get() = LocalNudgyPalette.current.lavender
val NudgySage: Color @Composable get() = LocalNudgyPalette.current.sage
val NudgyGreen: Color @Composable get() = LocalNudgyPalette.current.green
val NudgyPrimaryDark: Color @Composable get() = LocalNudgyPalette.current.primaryDark
val NudgyMemberPillText: Color @Composable get() = LocalNudgyPalette.current.memberPillText
val NudgyButter: Color @Composable get() = LocalNudgyPalette.current.butter
val NudgyButterBorder: Color @Composable get() = LocalNudgyPalette.current.butterBorder
val NudgyButterText: Color @Composable get() = LocalNudgyPalette.current.butterText
val NudgyAvatarGold: Color @Composable get() = LocalNudgyPalette.current.avatarGold
val NudgyChipBackground: Color @Composable get() = LocalNudgyPalette.current.chipBackground

// Small-text accent (links like Change/Pick/Tap to pick). Light = primary;
// dark = light coral so small accent text still passes contrast on dark surfaces.
// NOTE: primary-colored small text now uses NudgyAccent everywhere it was found
// (UX/UI pass: Calendar/Chat/Groups/Home/Tasks screens, create/edit sheets,
// group detail, profile sheet, reminder bubble). NudgyPrimary (~3:1 in dark)
// remains only for icon tints, borders, and selected-chip/button backgrounds
// (white text on those passes at 4.87:1).
val NudgyAccent: Color @Composable get() = LocalNudgyPalette.current.accent

// Groups UI tokens (Figma redesign; Groups screens only for now).
// Static light pairs on purpose: see the NudgyAvatarPalettes note above.
val NudgyAvatarPalettes: List<Pair<Color, Color>> = listOf(
    Color(0xFFFDF1F0) to Color(0xFFBE3B24),
    Color(0xFFF1F0FD) to Color(0xFF5D4FD6),
    NudgySageBorder to Color(0xFF2E7D32),
    Color(0xFFF5E8B9) to Color(0xFF8D6200)
)

// M3 shape tokens for this app (defined in Theme.kt as NudgyShapes):
//   small 8dp, medium 14dp, large 16dp, extraLarge 28dp.
// Sheet tops use extraLarge, cards use large, pills use CircleShape.
