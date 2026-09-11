package com.armin7270.snispoof.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.armin7270.snispoof.state.AppSettings
import com.armin7270.snispoof.state.ConnectionState
import com.armin7270.snispoof.state.EngineStats
import com.armin7270.snispoof.state.VpnViewModel
import com.armin7270.snispoof.ui.theme.SpoofColors
import com.armin7270.snispoof.ui.theme.colorsFor

@Composable
internal fun HomeScreenContent(
    vm: VpnViewModel,
    state: ConnectionState,
    onMenuClick: () -> Unit,
    onConfigClick: () -> Unit,
    onConnect: () -> Unit,
    onErrorClick: () -> Unit,
) {
    val stateColors = colorsFor(state)
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val configs by vm.configs.collectAsStateWithLifecycle()
    val selectedConfigId by vm.selectedConfigId.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val activeConfig = configs.firstOrNull { it.id == selectedConfigId }
    val selectedLabel = activeConfig?.name ?: t("Direct · DPI desync", "مستقیم · desync")

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        SpoofColors.BackgroundTop,
                        SpoofColors.BackgroundMiddle,
                        SpoofColors.BackgroundBottom,
                    ),
                ),
            ),
    ) {
        val innerHeight = maxHeight -
            safeDrawingPadding.calculateTopPadding() -
            safeDrawingPadding.calculateBottomPadding()
        val compact = innerHeight < 700.dp
        val tight = innerHeight < 620.dp
        val wide = LocalWideShell.current || WideShell.isWide(maxWidth, maxHeight)
        val contentMax = if (wide) WideShell.HomeContentMax else maxWidth
        val selectorMaxWidth = contentMax * 0.80f
        val headerPad = if (wide) WideShell.EdgePadding else if (compact) 20.dp else 24.dp
        val topSpacing = (innerHeight * 0.028f).coerceIn(
            if (tight) 6.dp else 10.dp,
            if (compact) 18.dp else 28.dp,
        )
        val motionEnabled = true

        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.width * 0.70f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        stateColors.accent.copy(alpha = 0.040f),
                        stateColors.accent.copy(alpha = 0.016f),
                        stateColors.accent.copy(alpha = 0f),
                    ),
                    center = Offset(size.width / 2f, size.height * 0.41f),
                    radius = radius,
                ),
                center = Offset(size.width / 2f, size.height * 0.41f),
                radius = radius,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(safeDrawingPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(topSpacing))
            HomeHeader(
                accent = stateColors.accent,
                compact = compact,
                onMenuClick = onMenuClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = headerPad),
            )
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (wide) Modifier.width(contentMax).fillMaxWidth() else Modifier.fillMaxWidth()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppTitle(compact = compact, accent = stateColors.accent)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            val halo = when {
                                tight || maxHeight < 280.dp -> 22.dp
                                compact -> 36.dp
                                else -> 54.dp
                            }
                            val diameter = minOf(
                                maxWidth * 0.54f,
                                (maxHeight - halo).coerceAtLeast(0.dp),
                                if (compact) 200.dp else 220.dp,
                            )
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (diameter > 0.dp) {
                                    ConnectButton(
                                        state = state,
                                        accent = stateColors.accent,
                                        diameter = diameter,
                                        halo = halo,
                                        onClick = {
                                            when (state) {
                                                ConnectionState.DISCONNECTED, ConnectionState.ERROR -> onConnect()
                                                else -> onErrorClick()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(if (tight) 4.dp else if (compact) 7.dp else 11.dp))
                        ConnectionStatus(state = state, accent = stateColors.accent)
                        Spacer(Modifier.height(if (tight) 2.dp else if (compact) 5.dp else 7.dp))
                        SelectedProfileRow(
                            label = selectedLabel,
                            onClick = onConfigClick,
                            maxWidth = selectorMaxWidth,
                        )
                    }
                    Spacer(Modifier.height(if (compact) 6.dp else 9.dp))
                    TrafficStatsRow(
                        accent = stateColors.accent,
                        compact = compact,
                        stats = stats,
                        modifier = Modifier.fillMaxWidth(0.86f),
                    )
                    Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                    FeatureCard(
                        accent = stateColors.accent,
                        compact = compact,
                        modifier = Modifier.fillMaxWidth(0.86f),
                    )
                    Spacer(Modifier.height(if (tight) 2.dp else if (compact) 3.dp else 6.dp))
                }
            }
            AnimatedDottedWave(
                accent = stateColors.accent,
                motionEnabled = motionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (tight) 14.dp else if (compact) 22.dp else 32.dp),
            )
        }
    }
}
