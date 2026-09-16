package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.faketcp.FakeTcpEngineStore
import com.uacspoofer.mobile.engine.faketcp.FakeTcpSettings
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.launch

@Composable
internal fun FakeTcpSettingsScreen(onBack: () -> Unit) {
    val accent = Color(0xFFFFB74D)
    ToolPageScaffold(
        accent = accent,
        header = {
            ToolPageHeader(
                title = homeText("SNI Spoofing 1.0", "جعل SNI ۱.۰"),
                subtitle = homeText("Fake TLS ClientHello & TCP Injection", "تزریق پکت جعلی TLS و فریب DPI"),
                icon = Icons.Outlined.Tune,
                accent = accent,
                onMenuClick = onBack,
                navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                navigationDescription = homeText("Back", "برگشت"),
            )
        },
    ) {
        item { FakeTcpSettingsPanel() }
    }
}

@Composable
internal fun HomeFakeTcpDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(22.dp))
                .background(UacColors.Surface)
                .border(1.dp, UacColors.CardBorder, RoundedCornerShape(22.dp))
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = homeText("SNI Spoofing Settings", "تنظیمات جعل SNI"),
                            color = UacColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                        )
                        Text(
                            text = homeText("Engine 1.0 (Fake TCP)", "هسته ۱.۰ (Fake TCP)"),
                            color = UacColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFB74D).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = FakeTcpShieldIcon,
                            contentDescription = null,
                            tint = Color(0xFFFFB74D),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                FakeTcpSettingsPanel(onSaved = onDismissRequest)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FakeTcpSettingsPanel(
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember(context) { FakeTcpEngineStore.get(context) }
    val currentSettings by store.settings.collectAsStateWithLifecycle()

    var edgeIp by remember(currentSettings) { mutableStateOf(currentSettings.edgeIp) }
    var edgePort by remember(currentSettings) { mutableStateOf(currentSettings.edgePort.toString()) }
    var fakeSni by remember(currentSettings) { mutableStateOf(currentSettings.fakeSni) }
    var localPort by remember(currentSettings) { mutableStateOf(currentSettings.localPort.toString()) }
    var savedFeedback by remember { mutableStateOf(false) }

    val accent = Color(0xFFFFB74D)
    val presets = listOf(
        "auth.vercel.com",
        "speed.cloudflare.com",
        "gateway.icloud.com",
        "cp.cloudflare.com",
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.08f))
                .border(0.8.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp),
                )
                Text(
                    text = homeText(
                        "SNI Spoofing 1.0 injects a forged TLS 1.3 ClientHello with a whitelisted SNI before the payload stream. This bypasses DPI firewalls on Cloudflare Edge IPs.",
                        "متد جعل SNI نسخه ۱.۰ یک پکت ساختگی TLS 1.3 با SNI مجاز به سمت لبه ارسال می‌کند تا فیلترینگ DPI دور زده شود و ترافیک برقرار گردد.",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }

        Text(
            text = homeText("Fake SNI Domain", "دامنه جعلی (Fake SNI)"),
            color = UacColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = fakeSni,
            onValueChange = { fakeSni = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = UacColors.CardBorder,
                focusedTextColor = UacColors.TextPrimary,
                unfocusedTextColor = UacColors.TextPrimary,
            ),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { preset ->
                val isSelected = fakeSni.equals(preset, ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) accent.copy(alpha = 0.25f) else UacColors.BackgroundMiddle,
                    border = androidx.compose.foundation.BorderStroke(
                        0.5.dp,
                        if (isSelected) accent else UacColors.CardBorder,
                    ),
                    modifier = Modifier.clickable { fakeSni = preset },
                ) {
                    Text(
                        text = preset,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSelected) accent else UacColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(0.65f)) {
                Text(
                    text = homeText("Edge IP", "آی‌پی سرور Edge"),
                    color = UacColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = edgeIp,
                    onValueChange = { edgeIp = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = UacColors.CardBorder,
                        focusedTextColor = UacColors.TextPrimary,
                        unfocusedTextColor = UacColors.TextPrimary,
                    ),
                )
            }
            Column(modifier = Modifier.weight(0.35f)) {
                Text(
                    text = homeText("Port", "پورت"),
                    color = UacColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = edgePort,
                    onValueChange = { edgePort = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = UacColors.CardBorder,
                        focusedTextColor = UacColors.TextPrimary,
                        unfocusedTextColor = UacColors.TextPrimary,
                    ),
                )
            }
        }

        Text(
            text = homeText("Local Bridge Port (127.0.0.1)", "پورت بریج داخلی (127.0.0.1)"),
            color = UacColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = localPort,
            onValueChange = { localPort = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = UacColors.CardBorder,
                focusedTextColor = UacColors.TextPrimary,
                unfocusedTextColor = UacColors.TextPrimary,
            ),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    edgeIp = FakeTcpSettings.DEFAULT_EDGE_IP
                    edgePort = FakeTcpSettings.DEFAULT_EDGE_PORT.toString()
                    fakeSni = FakeTcpSettings.DEFAULT_FAKE_SNI
                    localPort = FakeTcpSettings.DEFAULT_LOCAL_PORT.toString()
                },
                modifier = Modifier.weight(0.4f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = UacColors.TextSecondary,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(homeText("Reset", "پیش‌فرض"), fontSize = 13.sp)
            }

            Button(
                onClick = {
                    val portNum = edgePort.toIntOrNull() ?: 443
                    val localPortNum = localPort.toIntOrNull() ?: 40443
                    store.save(
                        FakeTcpSettings(
                            edgeIp = edgeIp.ifBlank { FakeTcpSettings.DEFAULT_EDGE_IP },
                            edgePort = portNum,
                            fakeSni = fakeSni.ifBlank { FakeTcpSettings.DEFAULT_FAKE_SNI },
                            localPort = localPortNum,
                        ),
                    )
                    savedFeedback = true
                    onSaved()
                },
                modifier = Modifier.weight(0.6f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color(0xFF1B1200),
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(homeText("Save Settings", "ذخیره تنظیمات"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}
