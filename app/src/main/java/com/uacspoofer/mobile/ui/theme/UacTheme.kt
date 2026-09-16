package com.uacspoofer.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.ui.ProvideFixedFontScale

object UacColors {
    var isDark: Boolean by mutableStateOf(true)
        internal set

    val BackgroundTop: Color get() = if (isDark) Color(0xFF070B16) else Color(0xFFF4F7FF)
    val BackgroundMiddle: Color get() = if (isDark) Color(0xFF0B1224) else Color(0xFFEBF1FD)
    val BackgroundBottom: Color get() = if (isDark) Color(0xFF090E1C) else Color(0xFFDFE9FC)
    val Surface: Color get() = if (isDark) Color(0xFF131D33) else Color(0xFFFFFFFF)
    val DisconnectedBlue: Color get() = if (isDark) Color(0xFF6366F1) else Color(0xFF4F46E5)
    val ConnectingCyan: Color get() = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7)
    val ConnectedGreen: Color get() = if (isDark) Color(0xFF10B981) else Color(0xFF059669)
    val DisconnectingAmber: Color get() = if (isDark) Color(0xFFF59E0B) else Color(0xFFD97706)
    val ErrorRed: Color get() = if (isDark) Color(0xFFF43F5E) else Color(0xFFE11D48)
    val TextPrimary: Color get() = if (isDark) Color(0xFFFFFFFF) else Color(0xFF0F172A)
    val TextSecondary: Color get() = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val CardBorder: Color get() = if (isDark) Color(0x35FFFFFF) else Color(0x20000000)
    val Divider: Color get() = if (isDark) Color(0x25FFFFFF) else Color(0x18000000)
    val ButtonCenter: Color get() = if (isDark) Color(0xFF1E2A44) else Color(0xFFFFFFFF)
    val ButtonEdge: Color get() = if (isDark) Color(0xFF0B1424) else Color(0xFFE2E8F0)
    val ButtonInnerRing: Color get() = if (isDark) Color(0xFF475569) else Color(0xFFCBD5E1)

    // Liquid glass properties
    val GlassCardBg: Color get() = if (isDark) Color(0x2A1E2B46) else Color(0xE6FFFFFF)
    val GlassCardBorder: Color get() = if (isDark) Color(0x40FFFFFF) else Color(0x80FFFFFF)
    val GlassIconBg: Color get() = if (isDark) Color(0x22FFFFFF) else Color(0x66FFFFFF)
}

data class UiStateColors(val accent: Color)

fun colorsFor(state: ConnectionState): UiStateColors = when (state) {
    ConnectionState.DISCONNECTED -> UiStateColors(UacColors.DisconnectedBlue)
    ConnectionState.CONNECTING -> UiStateColors(UacColors.ConnectingCyan)
    ConnectionState.CONNECTED -> UiStateColors(UacColors.ConnectedGreen)
    ConnectionState.DISCONNECTING -> UiStateColors(UacColors.DisconnectingAmber)
    ConnectionState.ERROR -> UiStateColors(UacColors.ErrorRed)
}

private val UacDarkColorScheme = darkColorScheme(
    primary = Color(0xFF10B981),
    secondary = Color(0xFF6366F1),
    background = Color(0xFF070B16),
    surface = Color(0xFF131D33),
    onPrimary = Color(0xFF070B16),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    error = Color(0xFFF43F5E),
)

private val UacLightColorScheme = lightColorScheme(
    primary = Color(0xFF059669),
    secondary = Color(0xFF4F46E5),
    background = Color(0xFFF4F7FF),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    error = Color(0xFFE11D48),
)

@Composable
fun UacSniSpooferTheme(
    isDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    SideEffect {
        UacColors.isDark = isDark
    }
    ProvideFixedFontScale {
        MaterialTheme(
            colorScheme = if (isDark) UacDarkColorScheme else UacLightColorScheme,
            content = content,
        )
    }
}
