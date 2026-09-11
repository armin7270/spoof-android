package com.armin7270.snispoof.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.armin7270.snispoof.core.proxy.ProxyProtocol
import com.armin7270.snispoof.state.VpnViewModel
import com.armin7270.snispoof.ui.theme.SpoofColors

/** Imported proxy configs (vless/trojan/vmess) — the UAC "Configs" screen. */
@Composable
internal fun ConfigsScreen(vm: VpnViewModel, onMenuClick: () -> Unit) {
    val configs by vm.configs.collectAsStateWithLifecycle()
    val selectedId by vm.selectedConfigId.collectAsStateWithLifecycle()
    val accent = SpoofColors.ConnectingCyan
    val clipboard = LocalClipboardManager.current
    var notice by remember { mutableStateOf<String?>(null) }
    val lblPaste = t("Paste config link(s) below", "لینک کانفیگ را اینجا بچسبانید")
    val lblImport = t("Import from clipboard or text", "ورود از کلیپ‌بورد یا متن")
    val hint = "vless://…  trojan://…"
    val okEmpty = t("No configs yet — the tunnel runs direct with DPI desync.",
        "هنوز کانفیگی نیست — تونل مستقیم با desync اجرا می‌شود.")
    val lblDirect = t("Use direct mode (no tunnel)", "حالت مستقیم (بدون تونل)")
    val msgDirect = tNoCompose("Direct mode (no config)", "حالت مستقیم (بدون کانفیگ)", L10nRuntime.language == "fa")

    ToolPageScaffold(
        accent = accent,
        header = {
            ToolPageHeader(
                title = t("Configs", "کانفیگ‌ها"),
                subtitle = t("VLESS · Trojan tunnel", "تونل VLESS · Trojan"),
                icon = Icons.Rounded.SwapHoriz,
                accent = accent,
                onMenuClick = onMenuClick,
            )
        },
    ) {
        item {
            ToolCard(accent = accent) {
                Text(lblPaste, color = SpoofColors.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                TextFieldRow(
                    label = lblImport,
                    value = "",
                    accent = accent,
                    onCommit = { text ->
                        val payload = if (text.isBlank())
                            clipboard.getText()?.text.orEmpty() else text
                        val res = vm.importConfigs(payload)
                        val fa = L10nRuntime.language == "fa"
                        notice = when {
                            res.imported > 0 && res.unsupported == 0 ->
                                tNoCompose("Imported ${res.imported} config(s)", "${res.imported} کانفیگ وارد شد", fa)
                            res.imported > 0 ->
                                tNoCompose(
                                    "Imported ${res.imported}; skipped ${res.unsupported} vmess link(s) — VMess is not supported yet",
                                    "${res.imported} کانفیگ وارد شد؛ ${res.unsupported} لینک vmess نادیده گرفته شد — VMess هنوز پشتیبانی نمی‌شود",
                                    fa,
                                )
                            res.unsupported > 0 ->
                                tNoCompose(
                                    "${res.unsupported} vmess link(s) found — VMess is not supported yet, use VLESS or Trojan",
                                    "${res.unsupported} لینک vmess پیدا شد — VMess هنوز پشتیبانی نمی‌شود؛ از VLESS یا Trojan استفاده کنید",
                                    fa,
                                )
                            else -> tNoCompose("No valid config found", "کانفیگ معتبری پیدا نشد", fa)
                        }
                    },
                    hint = hint,
                )
                if (notice != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(notice!!, color = accent, fontSize = 12.sp)
                }
            }
        }

        if (configs.isEmpty()) {
            item {
                ToolCard(accent = accent) {
                    Text(okEmpty, color = SpoofColors.TextSecondary, fontSize = 12.sp)
                }
            }
        }

        items(configs.size) { i ->
            val c = configs[i]
            val selected = c.id == selectedId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.selectConfig(c.id) }
                    .background(
                        if (selected) accent.copy(alpha = 0.10f) else Color.Transparent,
                        RoundedCornerShape(14.dp),
                    )
                    .border(
                        1.dp,
                        if (selected) accent.copy(alpha = 0.5f) else SpoofColors.CardBorder,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(
                            if (selected) accent else Color.Transparent,
                            RoundedCornerShape(50),
                        )
                        .border(
                            1.dp,
                            if (selected) accent else SpoofColors.TextSecondary,
                            RoundedCornerShape(50),
                        ),
                )
                Spacer(Modifier.size(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        c.name,
                        color = SpoofColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        "${c.protocol.uppercase()} · ${c.address}:${c.port} · ${c.net.id}${if (c.tls) "+tls" else ""} · ${c.sni.ifBlank { "-" }}",
                        color = SpoofColors.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { vm.deleteConfig(c.id) }) {
                    Icon(Icons.Rounded.Delete, null, tint = SpoofColors.ErrorRed, modifier = Modifier.size(17.dp))
                }
            }
        }

        item {
            TextButton(onClick = {
                vm.selectConfig(null)
                notice = msgDirect
            }) {
                Text(lblDirect, color = SpoofColors.TextSecondary)
            }
        }
    }
}
