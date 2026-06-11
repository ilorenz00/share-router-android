package com.lorenzl5.sharerouter

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lorenzl5.sharerouter.data.IntentParser
import com.lorenzl5.sharerouter.data.SharedContent
import com.lorenzl5.sharerouter.ui.ShareSheetScreen
import com.lorenzl5.sharerouter.ui.ShareViewModel
import com.lorenzl5.sharerouter.ui.theme.ShareRouterTheme

/** Entry point for SEND / SEND_MULTIPLE shares. Presents the matched-endpoint sheet. */
class ShareActivity : ComponentActivity() {

    private lateinit var content: SharedContent

    private val viewModel: ShareViewModel by viewModels {
        ShareViewModel.Factory(container, content)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        content = IntentParser.parse(intent, contentResolver)
        if (content.isEmpty) {
            Toast.makeText(this, "Nothing shareable in that intent", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            ShareRouterTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ShareSheetScreen(
                        vm = viewModel,
                        onOpenSettings = {
                            startActivity(Intent(this@ShareActivity, MainActivity::class.java))
                            finish()
                        },
                        onDismiss = { finish() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
