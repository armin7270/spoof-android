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

    val BackgroundTop: Color get() = if (isDark) Color(0xFF020913) else Color(0xFFF8FAFC)
    val BackgroundMiddle: Color get() = if (isDark) Color(0xFF04101C) else Color(0xFFF1F5F9)
    val BackgroundBottom: Color get() = if (isDark) Color(0xFF071421) else Color(0xFFE2E8F0)
    val Surface: Color get() = if (isDark) Color(0xFF101C29) else Color(0xFFFFFFFF)
    val DisconnectedBlue: Color get() = if (isDark) Color(0xFF299EFF) else Color(0xFF0284C7)
    val ConnectingCyan: Color get() = if (isDark) Color(0xFF27D7FF) else Color(0xFF0284C7)
    val ConnectedGreen: Color get() = if (isDark) Color(0xFF25F58A) else Color(0xFF059669)
    val DisconnectingAmber: Color get() = if (isDark) Color(0xFFFFB44A) else Color(0xFFD97706)
    val ErrorRed: Color get() = if (isDark) Color(0xFFFF3344) else Color(0xFFDC2626)
    val TextPrimary: Color get() = if (isDark) Color(0xFFFFFFFF) else Color(0xFF0F172A)
    val TextSecondary: Color get() = if (isDark) Color(0xFF8D99A6) else Color(0xFF64748B)
    val CardBorder: Color get() = if (isDark) Color(0x20FFFFFF) else Color(0x18000000)
    val Divider: Color get() = if (isDark) Color(0x24FFFFFF) else Color(0x1E000000)
    val ButtonCenter: Color get() = if (isDark) Color(0xFF172536) else Color(0xFFE2E8F0)
    val ButtonEdge: Color get() = if (isDark) Color(0xFF07111D) else Color(0xFFCBD5E1)
    val ButtonInnerRing: Color get() = if (isDark) Color(0xFF40536A) else Color(0xFF94A3B8)
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
    primary = Color(0xFF25F58A),
    secondary = Color(0xFF299EFF),
    background = Color(0xFF020913),
    surface = Color(0xFF101C29),
    onPrimary = Color(0xFF020913),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    error = Color(0xFFFF3344),
)

private val UacLightColorScheme = lightColorScheme(
    primary = Color(0xFF059669),
    secondary = Color(0xFF0284C7),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    error = Color(0xFFDC2626),
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
