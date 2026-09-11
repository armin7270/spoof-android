package com.armin7270.snispoof.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armin7270.snispoof.state.ConnectionState
import com.armin7270.snispoof.ui.theme.SpoofColors

@Composable
internal fun HomeHeader(
    accent: Color,
    compact: Boolean,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconSize = if (compact) 22.dp else 24.dp
    val buttonSize = if (compact) 38.dp else 42.dp
    Row(
        modifier = modifier.height(if (compact) 44.dp else 48.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(buttonSize)
                .clickable(onClick = onMenuClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = "Open navigation menu",
                tint = SpoofColors.TextPrimary,
                modifier = Modifier.size(iconSize),
            )
        }
        Icon(
            imageVector = Icons.Outlined.VerifiedUser,
            contentDescription = "Connection status",
            tint = accent,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
internal fun AppTitle(compact: Boolean, accent: Color) {
    val localizedFont = homeLocalizedFont()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "SNI SPOOFING",
            fontSize = if (compact) 20.sp else 23.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.55.sp,
            fontFamily = localizedFont,
            textAlign = TextAlign.Center,
            style = TextStyle(
                brush = Brush.horizontalGradient(
                    0f to accent,
                    0.30f to Color(0xFFDFF8FF),
                    0.58f to Color.White,
                    0.82f to Color(0xFFB8DFFF),
                    1f to accent,
                ),
                shadow = Shadow(
                    color = accent.copy(alpha = 0.62f),
                    offset = Offset(0f, 1.5f),
                    blurRadius = 16f,
                ),
            ),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(if (compact) 132.dp else 154.dp)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            accent.copy(alpha = 0.95f),
                            Color.White,
                            accent.copy(alpha = 0.95f),
                            Color.Transparent,
                        ),
                    ),
                    RoundedCornerShape(50),
                ),
        )
    }
}

@Composable
internal fun ConnectButton(
    state: ConnectionState,
    accent: Color,
    diameter: Dp,
    onClick: () -> Unit,
    halo: Dp = 54.dp,
) {
    val isPersian = LocalHomePersian.current
    val localizedFont = homeLocalizedFont()
    val interactionDisabled = state == ConnectionState.DISCONNECTING
    val transition = rememberInfiniteTransition(label = "connect-glow")
    val animatedGlow by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 920, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connect-glow-intensity",
    )
    val loadingRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "connect-loading-rotation",
    )
    val loadingSweep by transition.animateFloat(
        initialValue = 58f,
        targetValue = 292f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 880, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connect-loading-sweep",
    )
    val glowIntensity = if (state == ConnectionState.CONNECTING) animatedGlow else 1f
    val buttonLabel = when (state) {
        ConnectionState.DISCONNECTED -> t("CONNECT", "اتصال")
        ConnectionState.CONNECTING -> t("CANCEL", "لغو")
        ConnectionState.CONNECTED -> t("DISCONNECT", "قطع اتصال")
        ConnectionState.DISCONNECTING -> t("DISCONNECTING...", "در حال قطع...")
        ConnectionState.ERROR -> t("RETRY", "تلاش دوباره")
    }

    Box(
        modifier = Modifier
            .size(diameter + halo)
            .clickable(
                enabled = !interactionDisabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val surfaceRadius = diameter.toPx() / 2f
            val atmosphericRadius = size.minDimension / 2f
            val haloPx = halo.toPx()

            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.54f to accent.copy(alpha = 0.015f * glowIntensity),
                        0.67f to accent.copy(alpha = 0.20f * glowIntensity),
                        0.76f to accent.copy(alpha = 0.34f * glowIntensity),
                        0.87f to accent.copy(alpha = 0.14f * glowIntensity),
                        1f to Color.Transparent,
                    ),
                    center = center,
                    radius = atmosphericRadius,
                ),
                center = center,
                radius = atmosphericRadius,
            )
            drawCircle(
                color = accent.copy(alpha = 0.075f * glowIntensity),
                radius = surfaceRadius + haloPx * 0.50f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                color = accent.copy(alpha = 0.13f * glowIntensity),
                radius = surfaceRadius + haloPx * 0.33f,
                center = center,
                style = Stroke(width = 1.4.dp.toPx()),
            )
            drawCircle(
                color = accent.copy(alpha = 0.29f * glowIntensity),
                radius = surfaceRadius + haloPx * 0.15f,
                center = center,
                style = Stroke(width = 7.dp.toPx()),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.10f * glowIntensity),
                radius = surfaceRadius + haloPx * 0.07f,
                center = center,
                style = Stroke(width = 1.2.dp.toPx()),
            )
            if (state == ConnectionState.CONNECTING) {
                val progressRadius = surfaceRadius + haloPx * 0.15f
                val progressTopLeft = Offset(center.x - progressRadius, center.y - progressRadius)
                val progressSize = Size(progressRadius * 2f, progressRadius * 2f)
                drawArc(
                    color = accent.copy(alpha = 0.09f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 11.dp.toPx()),
                )
                drawArc(
                    color = accent.copy(alpha = 0.18f * animatedGlow),
                    startAngle = loadingRotation,
                    sweepAngle = loadingSweep,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0f to accent.copy(alpha = 0.15f),
                            0.55f to accent,
                            0.82f to Color.White,
                            1f to accent.copy(alpha = 0.20f),
                        ),
                        center = center,
                    ),
                    startAngle = loadingRotation,
                    sweepAngle = loadingSweep,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 4.6.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.96f),
                    startAngle = loadingRotation + loadingSweep - 7f,
                    sweepAngle = 7f,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(SpoofColors.ButtonCenter, SpoofColors.ButtonEdge),
                    ),
                    shape = CircleShape,
                )
                .semantics { contentDescription = buttonLabel },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension / 2f - 2.dp.toPx()
                drawCircle(
                    color = accent.copy(alpha = 0.34f * glowIntensity),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 10.dp.toPx()),
                )
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            accent,
                            Color.White.copy(alpha = 0.90f),
                            accent,
                            accent.copy(alpha = 0.76f),
                            accent,
                        ),
                        center = center,
                    ),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 3.2.dp.toPx()),
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.34f),
                    startAngle = 208f,
                    sweepAngle = 104f,
                    useCenter = false,
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    style = Stroke(width = 0.9.dp.toPx()),
                )
                drawCircle(
                    color = SpoofColors.ButtonInnerRing,
                    radius = outerRadius - 6.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.1.dp.toPx()),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(diameter * 0.21f),
                )
                Spacer(Modifier.height(if (diameter < 150.dp) 4.dp else 8.dp))
                Text(
                    text = buttonLabel,
                    color = accent,
                    fontSize = when {
                        isPersian && diameter < 150.dp -> 14.sp
                        isPersian -> 18.sp
                        buttonLabel.length > 11 -> 10.5.sp
                        else -> 13.sp
                    },
                    fontWeight = if (isPersian) FontWeight.Bold else FontWeight.SemiBold,
                    fontFamily = localizedFont,
                    letterSpacing = if (isPersian) 0.sp else 0.55.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
                        shadow = if (isPersian) {
                            Shadow(color = accent.copy(alpha = 0.42f), offset = Offset.Zero, blurRadius = 9f)
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }
}

@Composable
internal fun ConnectionStatus(state: ConnectionState, accent: Color) {
    val isPersian = LocalHomePersian.current
    val localizedFont = homeLocalizedFont()
    val status = when (state) {
        ConnectionState.DISCONNECTED -> t("Disconnected", "وصل نیست")
        ConnectionState.CONNECTING -> t("Connecting...", "در حال اتصال…")
        ConnectionState.CONNECTED -> t("Connected", "وصل شد")
        ConnectionState.DISCONNECTING -> t("Disconnecting...", "در حال قطع...")
        ConnectionState.ERROR -> t("Connection failed", "اتصال برقرار نشد")
    }
    val hint = when (state) {
        ConnectionState.DISCONNECTED -> t("Tap the button to connect", "برای وصل شدن، دکمه رو بزن")
        ConnectionState.CONNECTING -> t("Establishing a secure tunnel", "در حال ساخت اتصال امن")
        ConnectionState.CONNECTED -> t("Your connection is secure", "اتصال شما امنه")
        ConnectionState.DISCONNECTING -> t("Closing the secure tunnel", "در حال بستن اتصال امن")
        ConnectionState.ERROR -> t("Tap retry to try again", "دوباره امتحان کن")
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = status,
            color = accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = localizedFont,
            textAlign = TextAlign.Center,
            style = TextStyle(
                textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
            ),
            modifier = if (isPersian) Modifier.widthIn(min = 180.dp) else Modifier,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = hint,
            color = SpoofColors.TextSecondary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = localizedFont,
            textAlign = TextAlign.Center,
            style = TextStyle(
                textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
            ),
            modifier = Modifier.padding(horizontal = 28.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SelectedProfileRow(
    label: String,
    onClick: () -> Unit,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val localizedFont = homeLocalizedFont()
    val isPersian = LocalHomePersian.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val chevronOffset by animateFloatAsState(
        targetValue = if (pressed) 2f else 0f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "selected-config-chevron",
    )
    Row(
        modifier = modifier
            .widthIn(max = maxWidth)
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.5.dp).background(SpoofColors.DisconnectedBlue, CircleShape))
        Spacer(Modifier.size(7.dp))
        Text(
            t("Selected", "انتخاب‌شده"),
            color = SpoofColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = localizedFont,
            fontWeight = if (isPersian) FontWeight.Medium else null,
        )
        Spacer(Modifier.size(9.dp))
        Box(Modifier.size(width = 1.dp, height = 16.dp).background(Color.White.copy(alpha = 0.13f)))
        Spacer(Modifier.size(9.dp))
        Text(
            label,
            color = SpoofColors.DisconnectedBlue,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = localizedFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = (maxWidth - 128.dp).coerceAtLeast(88.dp)),
        )
        Spacer(Modifier.size(7.dp))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = SpoofColors.DisconnectedBlue,
            modifier = Modifier
                .size(17.dp)
                .offset { IntOffset(x = chevronOffset.dp.roundToPx(), y = 0) },
        )
    }
}

@Composable
internal fun FeatureCard(accent: Color, compact: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (compact) 15.dp else 17.dp)
    Row(
        modifier = modifier
            .height(if (compact) 86.dp else 94.dp)
            .background(SpoofColors.Surface.copy(alpha = 0.77f), shape)
            .border(0.75.dp, SpoofColors.CardBorder, shape)
            .padding(horizontal = 5.dp, vertical = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeatureItem(
            Icons.Outlined.VerifiedUser,
            t("Secure", "امن"),
            t("Encrypted", "رمزگذاری‌شده"),
            accent,
            compact,
            Modifier.weight(1f),
        )
        FeatureDivider()
        FeatureItem(
            Icons.Rounded.Bolt,
            t("Fast", "سریع"),
            t("Optimized", "بهینه"),
            accent,
            compact,
            Modifier.weight(1f),
        )
        FeatureDivider()
        FeatureItem(
            Icons.Rounded.Wifi,
            t("Stable", "پایدار"),
            t("Reliable", "قابل‌اعتماد"),
            accent,
            compact,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun FeatureItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    compact: Boolean,
    modifier: Modifier,
) {
    val localizedFont = homeLocalizedFont()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(if (compact) 21.dp else 23.dp),
        )
        Spacer(Modifier.height(if (compact) 3.dp else 4.dp))
        Text(
            text = title,
            color = SpoofColors.TextPrimary,
            fontSize = if (compact) 11.5.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = localizedFont,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = subtitle,
            color = SpoofColors.TextSecondary,
            fontSize = if (compact) 9.5.sp else 10.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = localizedFont,
        )
    }
}

@Composable
private fun FeatureDivider() {
    Box(
        modifier = Modifier
            .height(46.dp)
            .width(1.dp)
            .background(SpoofColors.Divider),
    )
}
