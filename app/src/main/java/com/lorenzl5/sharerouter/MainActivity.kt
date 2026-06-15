package com.lorenzl5.sharerouter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.lorenzl5.sharerouter.ui.SettingsScreen
import com.lorenzl5.sharerouter.ui.SettingsViewModel
import com.lorenzl5.sharerouter.ui.theme.ShareRouterTheme

/** Launcher screen — configure the MasterAPI spec URL and OIDC login. */
class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShareRouterTheme {
                // Ask for POST_NOTIFICATIONS once (API 33+) so API responses can notify.
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* result ignored — history still works without it */ }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                SettingsScreen(
                    vm = viewModel,
                    onOpenHistory = {
                        startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
                    },
                )
            }
        }
    }
}
