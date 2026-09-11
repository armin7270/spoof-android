package com.armin7270.snispoof.ui

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.armin7270.snispoof.state.VpnViewModel
import com.armin7270.snispoof.ui.theme.SpoofTheme

class MainActivity : ComponentActivity() {

    private val vm: VpnViewModel by viewModels { VpnViewModel.factory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.language) {
                L10nRuntime.language = settings.language
            }
            SpoofTheme {
                Root(vm)
            }
        }
    }

    @Composable
    private fun Root(vm: VpnViewModel) {
        val context = LocalContext.current

        val vpnLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) vm.connect()
        }
        val notifLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val requestConnect: () -> Unit = {
            val prepare: Intent? = VpnService.prepare(context)
            if (prepare != null) vpnLauncher.launch(prepare) else vm.connect()
        }

        androidx.compose.runtime.CompositionLocalProvider(LocalWideShell provides false) {
            MainScreen(
                vm = vm,
                onConnect = requestConnect,
            )
        }
    }
}
