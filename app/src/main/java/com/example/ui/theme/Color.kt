package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Nudgy Brand Palette (from Figma specs)
val NudgyCanvas = Color(0xFFFBF9F4)       // Warm linen off-white background
val NudgyCard = Color(0xFFFFFFFF)         // Crisp pure white cards
val NudgyCardBorder = Color(0xFFECE7DE)   // Subtle warm border
val NudgyPrimary = Color(0xFFF06449)      // Iconic coral primary
val NudgyPrimaryDark = Color(0xFFD64E34)  // Pressed coral
val NudgyPrimaryLight = Color(0xFFFF8E78) // Highlight coral
val NudgyCharcoal = Color(0xFF1C1B1F)     // Primary text & active dark pills
val NudgyMuted = Color(0xFF79747E)        // Secondary text & captions
val NudgyBorder = Color(0xFFE6E1D6)       // Divider and outline color

// Thematic Container Accents
val NudgyPeach = Color(0xFFFDF1F0)        // Google Meet / meeting cards & tags
val NudgyPeachBorder = Color(0xFFF8D8D4)
val NudgyLavender = Color(0xFFF1F0FD)     // Deep work / focus time cards
val NudgyLavenderBorder = Color(0xFFDCDAF8)
val NudgySage = Color(0xFFEBF7F0)         // Daily progress card & complete badges
val NudgySageBorder = Color(0xFFD0EDDD)
val NudgyGreen = Color(0xFF2E7D32)        // Completed check icon & progress arc
val NudgyButter = Color(0xFFFEF9E7)       // "Nudgy remembers" & AI suggestion cards
val NudgyButterBorder = Color(0xFFF5E8B9)
val NudgyButterText = Color(0xFF8D6200)   // Rich gold/amber for insight headlines
val NudgyAvatarGold = Color(0xFFF6DE95)   // AR avatar circle background

// Mascot Specific Colors
val MascotCoral = Color(0xFFF06449)
val MascotCheek = Color(0xFFFF9F92)
val MascotSnout = Color(0xFFFF8875)
val MascotDark = Color(0xFF1C1B1F)

// Groups UI tokens (Figma redesign; Groups screens only for now)
val NudgyMemberPillText = Color(0xFF6C5CE7)
val NudgyAvatarPalettes: List<Pair<Color, Color>> = listOf(
    NudgyPeach to NudgyPrimaryDark,
    NudgyLavender to NudgyMemberPillText,
    NudgySageBorder to NudgyGreen,
    NudgyButterBorder to NudgyButterText
)
