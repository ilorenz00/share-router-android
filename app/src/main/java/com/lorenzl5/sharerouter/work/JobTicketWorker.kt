package com.lorenzl5.sharerouter.work

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.lorenzl5.sharerouter.container
import com.lorenzl5.sharerouter.data.export.extractImageBytes
import com.lorenzl5.sharerouter.data.history.ResponseRecord
import com.lorenzl5.sharerouter.notify.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Polls an async job ticket (`GET /api/v2/gpu/jobs/{ticket}`) until it reaches a
 * terminal status, then posts the result as a notification and updates the history
 * record. Generic over queue kinds: any result that decodes to an image is shown as
 * an image, everything else as text. Survives the share sheet closing and process
 * death (WorkManager); on the ~10-min worker cap it returns retry() and resumes.
 */
class JobTicketWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pollUrl = inputData.getString(KEY_POLL_URL) ?: return Result.failure()
        val recordId = inputData.getString(KEY_RECORD_ID) ?: return Result.failure()
        val ticket = inputData.getString(KEY_TICKET).orEmpty()

        val container = applicationContext.container
        val settings = container.settings.settings.first()
        val bearer = if (settings.usesAuth) {
            runCatching { container.auth.bearerToken(settings.exchangeClientId) }.getOrNull()
        } else {
            null
        }

        val deadline = System.currentTimeMillis() + RUN_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            val poll = runCatching { fetch(pollUrl, bearer) }.getOrNull()
            if (poll != null) {
                val status = poll.optString("status")
                when (status) {
                    "done" -> {
                        finalizeDone(recordId, poll.optString("result"))
                        return Result.success()
                    }
                    "failed", "cancelled" -> {
                        finalizeText(
                            recordId,
                            ok = false,
                            body = "Job $status" + poll.optString("error").let { if (it.isBlank()) "" else ": $it" },
                        )
                        return Result.success()
                    }
                    else -> {
                        val pos = poll.optInt("position", 0)
                        val extra = if (pos > 0) " · Position $pos" else ""
                        Notifications.notifyProgress(
                            applicationContext, recordId,
                            "⏳ GPU-Job läuft", "${status.ifBlank { "queued" }}$extra · $ticket",
                        )
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        // Hit the worker time cap before the job finished — reschedule and resume.
        return Result.retry()
    }

    private suspend fun fetch(url: String, bearer: String?): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .get()
            .build()
        applicationContext.container.http.newCall(request).execute().use { resp ->
            JSONObject(resp.body?.string().orEmpty())
        }
    }

    private fun finalizeDone(recordId: String, result: String) {
        val imageBytes = extractImageBytes(result)
        if (imageBytes != null) {
            val dir = File(applicationContext.filesDir, "images").apply { mkdirs() }
            val file = File(dir, "$recordId.png")
            runCatching { file.writeBytes(imageBytes) }
            val record = baseRecord(recordId).copy(
                ok = true,
                body = "Bild empfangen (${imageBytes.size / 1024} KB)",
                imagePath = file.absolutePath,
                pending = false,
            )
            applicationContext.container.history.upsert(record)
            val bitmap = runCatching { BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) }.getOrNull()
            if (bitmap != null) {
                Notifications.notifyImageResult(applicationContext, record, bitmap)
            } else {
                Notifications.notifyResponse(applicationContext, record)
            }
        } else {
            finalizeText(recordId, ok = true, body = result.ifBlank { "Job done" })
        }
    }

    private fun finalizeText(recordId: String, ok: Boolean, body: String) {
        val record = baseRecord(recordId).copy(ok = ok, body = body, pending = false)
        applicationContext.container.history.upsert(record)
        Notifications.notifyResponse(applicationContext, record)
    }

    /** The pending record created at dispatch time, or a minimal fallback. */
    private fun baseRecord(recordId: String): ResponseRecord =
        applicationContext.container.history.get(recordId)
            ?: ResponseRecord(
                id = recordId,
                timestamp = System.currentTimeMillis(),
                endpointTitle = "GPU job",
                requestSummary = "",
                httpCode = 200,
                ok = true,
                body = "",
            )

    companion object {
        const val KEY_POLL_URL = "poll_url"
        const val KEY_RECORD_ID = "record_id"
        const val KEY_TICKET = "ticket"

        private const val POLL_INTERVAL_MS = 5_000L
        private const val RUN_BUDGET_MS = 8L * 60 * 1000 // stay under WorkManager's ~10-min cap

        fun inputData(pollUrl: String, recordId: String, ticket: String): Data =
            Data.Builder()
                .putString(KEY_POLL_URL, pollUrl)
                .putString(KEY_RECORD_ID, recordId)
                .putString(KEY_TICKET, ticket)
                .build()
    }
}
