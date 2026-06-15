package com.lorenzl5.sharerouter.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.lorenzl5.sharerouter.container
import com.lorenzl5.sharerouter.data.export.ResponseExport
import com.lorenzl5.sharerouter.data.export.mimeFor
import com.lorenzl5.sharerouter.data.export.suggestedFileName

/** Handles the Copy / Save action buttons on the response notification. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Notifications.EXTRA_RECORD_ID) ?: return
        val record = context.container.history.get(id) ?: return

        when (intent.action) {
            Notifications.ACTION_COPY -> {
                ResponseExport.copyToClipboard(context, "API response", record.body)
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            Notifications.ACTION_SAVE -> {
                val name = ResponseExport.saveToDownloads(
                    context, suggestedFileName(record), record.body, mimeFor(record),
                )
                val msg = if (name != null) "Saved to Downloads/$name" else "Save failed"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
