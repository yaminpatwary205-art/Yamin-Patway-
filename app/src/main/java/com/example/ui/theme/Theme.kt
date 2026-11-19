package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val LocalIsDarkTheme = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = PinkRedPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD0DF),
    onPrimaryContainer = Color(0xFF44061B),
    secondary = CoralSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCE6),
    onSecondaryContainer = Color(0xFF44061B),
    tertiary = AmberAccent,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEDD5),
    onTertiaryContainer = Color(0xFF451A03),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF5F8),
    surfaceContainer = Color(0xFFFFECF2),
    surfaceContainerHigh = Color(0xFFFFE1EA),
    surfaceContainerHighest = Color(0xFFFFD4E1),
    outline = LightOutline,
    error = RoseError,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DarkColors = darkColorScheme(
    primary = PinkRedLightPrimary,
    onPrimary = Color(0xFF3E0518),
    primaryContainer = Color(0xFF6B1836),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFFDA4AF),
    onSecondary = Color(0xFF3E0518),
    secondaryContainer = Color(0xFF5D1530),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF2B1B00),
    tertiaryContainer = Color(0xFF4A2511),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF19040C),
    surfaceContainerLow = Color(0xFF2B0A17),
    surfaceContainer = Color(0xFF330F1D),
    surfaceContainerHigh = Color(0xFF3F1324),
    surfaceContainerHighest = Color(0xFF4E192E),
    outline = DarkOutline,
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0909),
    errorContainer = Color(0xFF63171B),
    onErrorContainer = Color(0xFFFEE2E2)
)

val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

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
    val colorScheme = if (useDark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !useDark
                    isAppearanceLightNavigationBars = !useDark
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes
    ) {
        CompositionLocalProvider(
            LocalIsDarkTheme provides useDark,
            LocalContentColor provides colorScheme.onBackground,
            content = content
        )
    }
}

