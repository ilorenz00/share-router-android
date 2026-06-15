package com.lorenzl5.sharerouter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.lorenzl5.sharerouter.ui.HistoryScreen
import com.lorenzl5.sharerouter.ui.HistoryViewModel
import com.lorenzl5.sharerouter.ui.theme.ShareRouterTheme

/** Shows the persisted history of MasterAPI responses (opened from the app or a notification). */
class HistoryActivity : ComponentActivity() {

    private val viewModel: HistoryViewModel by viewModels {
        HistoryViewModel.Factory(container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShareRouterTheme {
                HistoryScreen(viewModel, onBack = { finish() })
            }
        }
    }
}
