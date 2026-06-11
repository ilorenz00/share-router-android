package com.lorenzl5.sharerouter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
                SettingsScreen(viewModel)
            }
        }
    }
}
