package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

// Primary Brand Palette — Pink-Red (গোলাপি লাল) & Crisp Contrast Accents
val PinkRedPrimary = Color(0xFFD81B60)
val PinkRedLightPrimary = Color(0xFFFF7AA8)
val IndigoPrimary = PinkRedPrimary
val IndigoLightPrimary = PinkRedLightPrimary
val AmberAccent = Color(0xFFD97706)
val CoralSecondary = Color(0xFFE11D48)
val EmeraldSuccess = Color(0xFF059669)
val RoseError = Color(0xFFB91C1C)

// Light Theme Surfaces (Soft Pink-Red Canvas + Bright Blush-White Cards + Deep Burgundy-Ink Text)
val LightBackground = Color(0xFFFFE4EC)
val LightSurface = Color(0xFFFFF7F9)
val LightSurfaceVariant = Color(0xFFFFD6E3)
val LightOnSurface = Color(0xFF230611)
val LightOnSurfaceVariant = Color(0xFF5E1E34)
val LightOutline = Color(0xFFF09AB5)

// Dark Theme Surfaces (Deep Wine-Crimson Canvas + Elevated Rose-Maroon Cards + Pure White Text)
val DarkBackground = Color(0xFF230812)
val DarkSurface = Color(0xFF330F1D)
val DarkSurfaceVariant = Color(0xFF471629)
val DarkOnSurface = Color(0xFFFFFFFF)
val DarkOnSurfaceVariant = Color(0xFFFFD6E3)
val DarkOutline = Color(0xFF833352)

// Subject Palette Options
val SubjectColors = listOf(
    "#E11D48", // Rose Red
    "#DB2777", // Deep Pink
    "#BE123C", // Crimson
    "#7C3AED", // Violet
    "#2563EB", // Royal Blue
    "#0891B2", // Cyan
    "#059669", // Emerald
    "#D97706", // Amber
    "#EA580C", // Orange
    "#4F46E5", // Indigo
    "#0D9488"  // Teal
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

