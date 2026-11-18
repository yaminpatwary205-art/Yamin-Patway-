package com.example.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun KeepScreenOnEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context as? Activity
        if (enabled && activity != null) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (enabled && activity != null) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

@Composable
fun AnimatedMeshBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "bg_orbs")
    val drift1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift1"
    )
    val drift2 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift2"
    )

    val bg = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Box(modifier = modifier.fillMaxSize().background(bg)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(w * (0.15f + 0.25f * drift1), h * (0.12f + 0.1f * drift2)),
                    radius = w * 0.72f
                ),
                radius = w * 0.72f,
                center = Offset(w * (0.15f + 0.25f * drift1), h * (0.12f + 0.1f * drift2))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(secondary.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(w * (0.85f - 0.2f * drift2), h * (0.45f + 0.18f * drift1)),
                    radius = w * 0.65f
                ),
                radius = w * 0.65f,
                center = Offset(w * (0.85f - 0.2f * drift2), h * (0.45f + 0.18f * drift1))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tertiary.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(w * (0.3f + 0.35f * drift2), h * (0.88f - 0.12f * drift1)),
                    radius = w * 0.6f
                ),
                radius = w * 0.6f,
                center = Offset(w * (0.3f + 0.35f * drift2), h * (0.88f - 0.12f * drift1))
            )
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    cornerRadius: Dp = 22.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = 0.35f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        )
    )
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            color = surfaceColor,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, borderBrush)
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            color = surfaceColor,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, borderBrush)
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun AnimatedProgressRing(
    progress: Float,
    color: Color,
    size: Dp = 46.dp,
    strokeWidth: Dp = 4.5.dp,
    label: String? = null
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "ring_progress"
    )
    val trackColor = color.copy(alpha = 0.16f)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val pad = strokeWidth.toPx() / 2f
            val arcSize = Size(this.size.width - strokeWidth.toPx(), this.size.height - strokeWidth.toPx())
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = stroke
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = stroke
            )
        }
        if (label != null) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private data class ConfettiParticle(
    val angle: Float,
    val speed: Float,
    val color: Color,
    val radius: Float
)

@Composable
fun ConfettiBurstOverlay(visible: Boolean) {
    if (!visible) return
    val particles = remember {
        val palette = listOf(
            Color(0xFFF59E0B),
            Color(0xFF10B981),
            Color(0xFF3B82F6),
            Color(0xFFF43F5E),
            Color(0xFF8B5CF6),
            Color(0xFF06B6D4)
        )
        List(44) {
            ConfettiParticle(
                angle = Random.nextFloat() * 360f,
                speed = 0.35f + Random.nextFloat() * 0.75f,
                color = palette[it % palette.size],
                radius = 6f + Random.nextFloat() * 10f
            )
        }
    }
    val anim = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        anim.snapTo(0f)
        anim.animateTo(1f, animationSpec = tween(1800, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val t = anim.value
        val cx = size.width / 2f
        val cy = size.height * 0.42f
        val maxDist = size.minDimension * 0.55f

        // Center celebration glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF10B981).copy(alpha = (1f - t) * 0.35f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = maxDist * (0.4f + t * 0.8f)
            ),
            radius = maxDist * (0.4f + t * 0.8f),
            center = Offset(cx, cy)
        )

        particles.forEach { p ->
            val rad = Math.toRadians(p.angle.toDouble())
            val dist = maxDist * p.speed * t
            val x = cx + (cos(rad) * dist).toFloat()
            val y = cy + (sin(rad) * dist).toFloat() + (t * t * 180f)
            drawCircle(
                color = p.color.copy(alpha = (1f - t).coerceIn(0f, 1f)),
                radius = p.radius * (1f - 0.3f * t),
                center = Offset(x, y)
            )
        }
    }
}

fun parseColorHex(hex: String, fallback: Color = Color(0xFF4F46E5)): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val parsed = cleaned.toLong(16)
        if (cleaned.length == 6) Color(0xFF000000 or parsed) else Color(parsed)
    } catch (_: Exception) {
        fallback
    }
}

fun bookIconForKey(iconKey: String): ImageVector = when (iconKey) {
    "auto_stories" -> Icons.Rounded.AutoStories
    "edit_note" -> Icons.Rounded.EditNote
    "menu_book" -> Icons.Rounded.MenuBook
    "spellcheck" -> Icons.Rounded.Spellcheck
    "calculate" -> Icons.Rounded.Calculate
    "science" -> Icons.Rounded.Science
    "account_balance" -> Icons.Rounded.AccountBalance
    "storefront" -> Icons.Rounded.Storefront
    "payments" -> Icons.Rounded.Payments
    "mosque" -> Icons.Rounded.Mosque
    "computer" -> Icons.Rounded.Computer
    else -> Icons.Rounded.Book
}

fun formatCountdownBn(seconds: Long): String {
    if (seconds < 0) return "নির্ধারিত নেই"
    if (seconds == 0L) return "এখনই!"
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format("%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}
