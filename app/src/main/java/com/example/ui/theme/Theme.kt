package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = CoralSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4E6),
    onSecondaryContainer = Color(0xFF4C0519),
    tertiary = AmberAccent,
    onTertiary = Color(0xFF1F1600),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = RoseError,
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = IndigoLightPrimary,
    onPrimary = Color(0xFF111033),
    primaryContainer = Color(0xFF2D2B6E),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFFFB7185),
    onSecondary = Color(0xFF3F0212),
    secondaryContainer = Color(0xFF5A1025),
    onSecondaryContainer = Color(0xFFFFE4E6),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF2B1B00),
    tertiaryContainer = Color(0xFF4B3000),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0909)
)

val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun RevisionAlarmTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
