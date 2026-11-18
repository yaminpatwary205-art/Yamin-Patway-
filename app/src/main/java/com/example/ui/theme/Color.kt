package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

// Primary Brand Palette — Deep Indigo & Radiant Amber/Coral
val IndigoPrimary = Color(0xFF4338CA)
val IndigoLightPrimary = Color(0xFFA5B4FC)
val AmberAccent = Color(0xFFD97706)
val CoralSecondary = Color(0xFFE11D48)
val EmeraldSuccess = Color(0xFF059669)
val RoseError = Color(0xFFDC2626)

// Light Theme Surfaces (Crisp Off-White Canvas + Pure White Opaque Cards + Ink-Black Text)
val LightBackground = Color(0xFFF1F4FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE6ECF9)
val LightOnSurface = Color(0xFF0B1120)
val LightOnSurfaceVariant = Color(0xFF334155)
val LightOutline = Color(0xFFCBD5E1)

// Dark Theme Surfaces (Deep Slate Canvas + Elevated Navy Cards + Pure White Text)
val DarkBackground = Color(0xFF090D16)
val DarkSurface = Color(0xFF161D31)
val DarkSurfaceVariant = Color(0xFF222B45)
val DarkOnSurface = Color(0xFFFFFFFF)
val DarkOnSurfaceVariant = Color(0xFFD1D9E8)
val DarkOutline = Color(0xFF3B476B)

// Subject Palette Options
val SubjectColors = listOf(
    "#E11D48", // Rose
    "#7C3AED", // Violet
    "#2563EB", // Royal Blue
    "#0891B2", // Cyan
    "#059669", // Emerald
    "#D97706", // Amber
    "#EA580C", // Orange
    "#DB2777", // Pink
    "#4F46E5", // Indigo
    "#0D9488", // Teal
    "#0284C7"  // Sky
)

/**
 * Adjusts vibrant subject/status accent colors so foreground text & icons always maintain
 * WCAG AA (>= 4.5:1) contrast on both dark (#151A30) and light (#FFFFFF) card surfaces.
 */
fun readableAccentColor(accent: Color, isDark: Boolean): Color {
    return if (isDark) {
        // Target luminance >= 0.30 so contrast vs DarkSurface (#151A30) is >= 5.0:1
        if (accent.luminance() >= 0.30f) return accent
        var low = 0f
        var high = 1f
        var best = Color.White
        repeat(8) {
            val mid = (low + high) / 2f
            val candidate = lerp(accent, Color.White, mid)
            if (candidate.luminance() >= 0.32f) {
                best = candidate
                high = mid
            } else {
                low = mid
            }
        }
        best
    } else {
        // Target luminance <= 0.16 so contrast vs LightSurface (#FFFFFF) is >= 4.9:1
        if (accent.luminance() <= 0.16f) return accent
        var low = 0f
        var high = 1f
        var best = Color.Black
        repeat(8) {
            val mid = (low + high) / 2f
            val candidate = lerp(accent, Color.Black, mid)
            if (candidate.luminance() <= 0.155f) {
                best = candidate
                high = mid
            } else {
                low = mid
            }
        }
        best
    }
}

