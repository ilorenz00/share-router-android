package com.lorenzl5.sharerouter.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lorenzl5.sharerouter.HistoryActivity
import com.lorenzl5.sharerouter.R
import com.lorenzl5.sharerouter.data.history.ResponseRecord

/** Builds and posts the "API response" notification, with Copy / Save actions. */
object Notifications {

    const val CHANNEL_ID = "api_responses"
    const val ACTION_COPY = "com.lorenzl5.sharerouter.action.COPY"
    const val ACTION_SAVE = "com.lorenzl5.sharerouter.action.SAVE"
    const val EXTRA_RECORD_ID = "record_id"

    fun ensureChannel(context: Context) {
        // minSdk is 26, so NotificationChannel always applies.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "API responses",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Responses returned by the MasterAPI after a share" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyResponse(context: Context, record: ResponseRecord) {
        ensureChannel(context)

        val openPending = PendingIntent.getActivity(
            context,
            record.id.hashCode(),
            Intent(context, HistoryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingFlags(),
        )
        val copyPending = actionPending(context, ACTION_COPY, record.id, record.id.hashCode() + 1)
        val savePending = actionPending(context, ACTION_SAVE, record.id, record.id.hashCode() + 2)

        val title = (if (record.ok) "✓ " else "✗ ") +
            record.endpointTitle + " · HTTP " + record.httpCode
        val body = record.body.ifBlank { "(empty response)" }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(4000)))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .addAction(0, "Copy", copyPending)
            .addAction(0, "Save", savePending)
            .build()

        post(context, record.id.hashCode(), notification)
    }

    /** Ongoing low-priority notification shown while an async job is queued/running. */
    fun notifyProgress(context: Context, recordId: String, title: String, text: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .setContentIntent(openHistoryPending(context, recordId))
            .build()
        post(context, recordId.hashCode(), notification)
    }

    /** Result notification carrying the generated image (BigPicture) + a Save action. */
    fun notifyImageResult(context: Context, record: ResponseRecord, image: Bitmap) {
        ensureChannel(context)
        val savePending = actionPending(context, ACTION_SAVE, record.id, record.id.hashCode() + 2)
        val title = (if (record.ok) "✓ " else "✗ ") + record.endpointTitle + " · Bild fertig"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(record.body.ifBlank { "Ergebnis verfügbar" }.take(120))
            .setLargeIcon(image)
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(image)
                    .bigLargeIcon(null as Bitmap?),
            )
            .setContentIntent(openHistoryPending(context, record.id))
            .setAutoCancel(true)
            .addAction(0, "Save", savePending)
            .build()
        post(context, record.id.hashCode(), notification)
    }

    fun cancel(context: Context, recordId: String) {
        NotificationManagerCompat.from(context).cancel(recordId.hashCode())
    }

    private fun openHistoryPending(context: Context, recordId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            recordId.hashCode(),
            Intent(context, HistoryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingFlags(),
        )

    /** Post only if allowed (POST_NOTIFICATIONS on API 33+); otherwise the in-app history holds it. */
    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canPost) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    private fun actionPending(
        context: Context,
        action: String,
        recordId: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_RECORD_ID, recordId)
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
