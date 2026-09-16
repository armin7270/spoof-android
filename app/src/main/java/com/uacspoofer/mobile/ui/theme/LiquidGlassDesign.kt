package com.uacspoofer.mobile.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass design tokens, shaders and visual modifiers
 * providing true frosted translucency, specular rim highlights, and dynamic fluid orbs.
 */
object LiquidGlassTokens {
    // Vibrant & joyful accents
    val ElectricIndigo = Color(0xFF6366F1)
    val RadiantCyan = Color(0xFF06B6D4)
    val VividMint = Color(0xFF10B981)
    val SunsetCoral = Color(0xFFF43F5E)
    val SunnyAmber = Color(0xFFF59E0B)
    val NeonSky = Color(0xFF00E5FF)
    val SoftRose = Color(0xFFFB7185)

    // Dark liquid mesh backdrop colors
    val DarkBaseTop = Color(0xFF070B16)
    val DarkBaseMiddle = Color(0xFF0C1324)
    val DarkBaseBottom = Color(0xFF080D1A)

    // Light crystal mesh backdrop colors
    val LightBaseTop = Color(0xFFF4F7FF)
    val LightBaseMiddle = Color(0xFFE8EFFF)
    val LightBaseBottom = Color(0xFFDFE9FC)

    @Composable
    fun cardBackgroundBrush(isDark: Boolean, accent: Color): Brush {
        return if (isDark) {
            Brush.linearGradient(
                colors = listOf(
                    Color(0x351E2B46),
                    Color(0x20101828),
                    Color(0x2E1A2740),
                ),
                start = Offset(0f, 0f),
                end = Offset(400f, 400f),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xF0FFFFFF),
                    Color(0xD8F0F5FF),
                    Color(0xE6FFFFFF),
                ),
                start = Offset(0f, 0f),
                end = Offset(400f, 400f),
            )
        }
    }

    @Composable
    fun specularBorderBrush(isDark: Boolean, accent: Color): Brush {
        return if (isDark) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.42f),
                    accent.copy(alpha = 0.28f),
                    Color.White.copy(alpha = 0.08f),
                    accent.copy(alpha = 0.35f),
                ),
                start = Offset(0f, 0f),
                end = Offset(300f, 300f),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    Color.White.copy(alpha = 0.50f),
                    accent.copy(alpha = 0.25f),
                    Color.White.copy(alpha = 0.85f),
                ),
                start = Offset(0f, 0f),
                end = Offset(300f, 300f),
            )
        }
    }
}

/**
 * Animated floating liquid gradient spheres on a Canvas that gently drift and breathe,
 * making the frosted glass layers above them feel authentically liquid and translucent.
 */
@Composable
fun FloatingLiquidOrbsCanvas(
    modifier: Modifier = Modifier,
    accent: Color,
    isDark: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "liquid-orbs")

    // Primary drifting orb animation
    val orb1X by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb1-x",
    )
    val orb1Y by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.36f,
        animationSpec = infiniteRepeatable(
            animation = tween(9500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb1-y",
    )

    // Secondary drifting orb animation
    val orb2X by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(10500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb2-x",
    )
    val orb2Y by transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(8800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb2-y",
    )

    // Breathing pulse
    val breathingRadius by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-breathing",
    )

    val orb1Color = if (isDark) accent else LiquidGlassTokens.ElectricIndigo
    val orb2Color = if (isDark) LiquidGlassTokens.SunsetCoral else LiquidGlassTokens.RadiantCyan
    val orb3Color = if (isDark) LiquidGlassTokens.NeonSky else LiquidGlassTokens.VividMint

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val baseRadius = width * 0.46f * breathingRadius

        // Orb 1: Accent / Indigo fluid orb
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to orb1Color.copy(alpha = if (isDark) 0.16f else 0.12f),
                    0.45f to orb1Color.copy(alpha = if (isDark) 0.08f else 0.06f),
                    0.80f to orb1Color.copy(alpha = if (isDark) 0.02f else 0.015f),
                    1f to Color.Transparent,
                ),
                center = Offset(width * orb1X, height * orb1Y),
                radius = baseRadius * 1.15f,
            ),
            center = Offset(width * orb1X, height * orb1Y),
            radius = baseRadius * 1.15f,
        )

        // Orb 2: Warm coral / Cyan fluid orb
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to orb2Color.copy(alpha = if (isDark) 0.14f else 0.10f),
                    0.48f to orb2Color.copy(alpha = if (isDark) 0.06f else 0.04f),
                    0.85f to orb2Color.copy(alpha = if (isDark) 0.015f else 0.01f),
                    1f to Color.Transparent,
                ),
                center = Offset(width * orb2X, height * orb2Y),
                radius = baseRadius * 1.05f,
            ),
            center = Offset(width * orb2X, height * orb2Y),
            radius = baseRadius * 1.05f,
        )

        // Orb 3: Radiant bottom-center mint/cyan pool
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to orb3Color.copy(alpha = if (isDark) 0.12f else 0.08f),
                    0.50f to orb3Color.copy(alpha = if (isDark) 0.04f else 0.025f),
                    1f to Color.Transparent,
                ),
                center = Offset(width * 0.5f, height * 0.88f),
                radius = baseRadius * 1.3f,
            ),
            center = Offset(width * 0.5f, height * 0.88f),
            radius = baseRadius * 1.3f,
        )
    }
}

/**
 * Applies a Liquid Glass frosted card styling with specular highlights and soft glowing shadow.
 */
@Composable
fun Modifier.liquidGlassCard(
    shape: Shape = RoundedCornerShape(22.dp),
    accent: Color = UacColors.ConnectingCyan,
    elevation: Dp = 12.dp,
): Modifier {
    val isDark = UacColors.isDark
    val cardBrush = LiquidGlassTokens.cardBackgroundBrush(isDark, accent)
    val borderBrush = LiquidGlassTokens.specularBorderBrush(isDark, accent)

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = if (isDark) accent.copy(alpha = 0.20f) else Color(0x306366F1),
            spotColor = if (isDark) Color.Black.copy(alpha = 0.45f) else Color(0x250F172A),
        )
        .clip(shape)
        .background(brush = cardBrush, shape = shape)
        .border(width = 1.2.dp, brush = borderBrush, shape = shape)
}

/**
 * Applies a rounded, translucent frosted glass bubble styling designed for circular icons.
 */
@Composable
fun Modifier.liquidGlassBubble(
    shape: Shape = CircleShape,
    accent: Color = UacColors.ConnectingCyan,
): Modifier {
    val isDark = UacColors.isDark
    val bgBrush = if (isDark) {
        Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.07f),
                Color(0x1A142034),
            ),
        )
    } else {
        Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.95f),
                accent.copy(alpha = 0.12f),
                Color(0xD9FFFFFF),
            ),
        )
    }
    val borderBrush = if (isDark) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.45f),
                accent.copy(alpha = 0.35f),
                Color.White.copy(alpha = 0.10f),
            ),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.90f),
                accent.copy(alpha = 0.40f),
                Color.White.copy(alpha = 0.60f),
            ),
        )
    }

    return this
        .clip(shape)
        .background(brush = bgBrush, shape = shape)
        .border(width = 1.dp, brush = borderBrush, shape = shape)
}

/**
 * Tactile spring bounce on click interaction.
 */
@Composable
fun Modifier.springBounceClick(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "spring-bounce-scale",
    )

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
}
