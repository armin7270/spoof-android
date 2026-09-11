package com.armin7270.snispoof.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armin7270.snispoof.ui.theme.SpoofColors
import com.armin7270.snispoof.ui.theme.VazirmatnUiFd

enum class DrawerDestination(val icon: ImageVector) {
    HOME(Icons.Rounded.Home),
    CONFIGS(Icons.Rounded.SwapHoriz),
    SCANNER(Icons.Rounded.Radar),
    APPS(Icons.Rounded.Dns),
    LOGS(Icons.Rounded.BugReport),
    SETTINGS(Icons.Rounded.Settings),
}

@Composable
internal fun AppDrawerContent(
    selected: DrawerDestination,
    onDestination: (DrawerDestination) -> Unit,
    persian: Boolean,
    onToggleLanguage: (Boolean) -> Unit,
) {
    val localizedFont = VazirmatnUiFd
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF030B15), Color(0xFF071421)),
                ),
            )
            .padding(18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "SNI SPOOFING",
            color = SpoofColors.DisconnectedBlue,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = localizedFont,
            letterSpacing = 0.55.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (persian) "دورزدن DPI با دستکاری TLS" else "DPI bypass with TLS manipulation",
            color = SpoofColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = localizedFont,
        )
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SpoofColors.Divider),
        )
        Spacer(Modifier.height(14.dp))

        DrawerItem(DrawerDestination.HOME, t("Home", "خانه"), Icons.Rounded.Home, selected, localizedFont, onDestination)
        DrawerItem(DrawerDestination.CONFIGS, t("Configs", "کانفیگ‌ها"), Icons.Rounded.SwapHoriz, selected, localizedFont, onDestination)
        DrawerItem(DrawerDestination.SCANNER, t("Scanner", "اسکنر"), Icons.Rounded.Radar, selected, localizedFont, onDestination)
        DrawerItem(DrawerDestination.APPS, t("Per-app", "برنامه‌ها"), Icons.Rounded.Dns, selected, localizedFont, onDestination)
        DrawerItem(DrawerDestination.LOGS, t("Live logs", "لاگ زنده"), Icons.Rounded.BugReport, selected, localizedFont, onDestination)
        DrawerItem(DrawerDestination.SETTINGS, t("Settings", "تنظیمات"), Icons.Rounded.Settings, selected, localizedFont, onDestination)

        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SpoofColors.Divider),
        )
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    t("Persian interface", "رابط فارسی"),
                    color = SpoofColors.TextPrimary,
                    fontSize = 14.sp,
                    fontFamily = localizedFont,
                )
                Text(
                    t("English UI", "رابط انگلیسی"),
                    color = SpoofColors.TextSecondary,
                    fontSize = 10.5.sp,
                    fontFamily = localizedFont,
                )
            }
            Switch(
                checked = persian,
                onCheckedChange = { onToggleLanguage(it) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = SpoofColors.DisconnectedBlue,
                    checkedThumbColor = SpoofColors.BackgroundTop,
                ),
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            text = "github.com/armin7270",
            color = SpoofColors.TextSecondary.copy(alpha = 0.7f),
            fontSize = 10.5.sp,
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DrawerItem(
    destination: DrawerDestination,
    label: String,
    icon: ImageVector,
    selected: DrawerDestination,
    font: androidx.compose.ui.text.font.FontFamily,
    onDestination: (DrawerDestination) -> Unit,
) {
    val isActive = selected == destination
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                if (isActive) SpoofColors.DisconnectedBlue.copy(alpha = 0.14f) else Color.Transparent,
                RoundedCornerShape(14.dp),
            )
            .border(
                1.dp,
                if (isActive) SpoofColors.DisconnectedBlue.copy(alpha = 0.35f) else Color.Transparent,
                RoundedCornerShape(14.dp),
            )
            .clickable { onDestination(destination) }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isActive) SpoofColors.DisconnectedBlue else SpoofColors.TextSecondary,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            label,
            color = if (isActive) SpoofColors.TextPrimary else SpoofColors.TextSecondary,
            fontSize = 14.5.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = font,
        )
    }
}
