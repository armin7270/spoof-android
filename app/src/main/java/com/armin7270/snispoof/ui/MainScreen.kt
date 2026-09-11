package com.armin7270.snispoof.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.armin7270.snispoof.state.VpnViewModel
import com.armin7270.snispoof.ui.theme.SpoofColors
import kotlinx.coroutines.launch

@Composable
internal fun MainScreen(
    vm: VpnViewModel,
    onConnect: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val persian = settings.language == "fa"
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var selected by remember { mutableStateOf(DrawerDestination.HOME) }

    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (selected != DrawerDestination.HOME) {
            selected = DrawerDestination.HOME
        }
    }

    CompositionLocalProvider(LocalHomePersian provides persian) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = SpoofColors.BackgroundMiddle) {
                    AppDrawerContent(
                        selected = selected,
                        onDestination = { dest ->
                            selected = dest
                            scope.launch { drawerState.close() }
                        },
                        persian = persian,
                        onToggleLanguage = { fa -> vm.setLanguage(if (fa) "fa" else "en") },
                    )
                }
            },
        ) {
            Scaffold(containerColor = SpoofColors.BackgroundTop) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (selected) {
                        DrawerDestination.HOME ->
                            HomeScreenContent(
                                vm = vm,
                                state = state,
                                onMenuClick = { scope.launch { drawerState.open() } },
                                onConfigClick = { selected = DrawerDestination.CONFIGS },
                                onConnect = onConnect,
                                onErrorClick = { vm.disconnect() },
                            )
                        DrawerDestination.CONFIGS ->
                            ConfigsScreen(vm, onMenuClick = { scope.launch { drawerState.open() } })
                        DrawerDestination.SCANNER ->
                            ScannerScreen(vm, onMenuClick = { scope.launch { drawerState.open() } })
                        DrawerDestination.APPS ->
                            AppsScreen(vm, onMenuClick = { scope.launch { drawerState.open() } })
                        DrawerDestination.LOGS ->
                            LogsScreen(vm, onMenuClick = { scope.launch { drawerState.open() } })
                        DrawerDestination.SETTINGS ->
                            SettingsScreen(
                                vm,
                                onMenuClick = { scope.launch { drawerState.open() } },
                                onOpenApps = { selected = DrawerDestination.APPS },
                            )
                    }
                }
            }
        }
    }
}
