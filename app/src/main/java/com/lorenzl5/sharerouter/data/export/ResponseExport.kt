package com.lorenzl5.sharerouter.data.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.lorenzl5.sharerouter.data.history.ResponseRecord
import org.json.JSONObject
import java.io.File

/** Clipboard + "save to Downloads" helpers, shared by the notification actions and the UI. */
object ResponseExport {

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /**
     * Writes [text] into the public Downloads directory. Uses MediaStore on API 29+
     * (no permission needed) and a legacy file write on API 26–28
     * (WRITE_EXTERNAL_STORAGE, capped at maxSdk 28 in the manifest).
     * Returns the saved file name on success, or null on failure.
     */
    fun saveToDownloads(context: Context, fileName: String, text: String, mime: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                null
            } else {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                fileName
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeText(text)
            fileName
        }
    } catch (_: Exception) {
        null
    }

    /** Like [saveToDownloads] but for raw bytes (e.g. a decoded result image). */
    fun saveBytesToDownloads(context: Context, fileName: String, bytes: ByteArray, mime: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                null
            } else {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                fileName
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeBytes(bytes)
            fileName
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Best-effort extraction of an image from a job result string. Handles the common
 * shapes returned by GPU queues today (Stable Diffusion's `{"images":["<base64>"]}`,
 * a bare base64 string, or a `data:image/...;base64,` URI). Returns decoded bytes,
 * or null when the result isn't an image (then it's shown as text). New queue result
 * types just need their own extractor here.
 */
fun extractImageBytes(result: String): ByteArray? {
    val trimmed = result.trim()
    if (trimmed.isEmpty()) return null

    val b64: String = when {
        trimmed.startsWith("{") -> try {
            JSONObject(trimmed).optJSONArray("images")?.let { arr ->
                if (arr.length() > 0) arr.optString(0).ifBlank { null } else null
            }
        } catch (_: Exception) {
            null
        }
        trimmed.startsWith("data:image") -> trimmed.substringAfter("base64,", "").ifBlank { null }
        // A bare, sufficiently long base64 blob (no JSON, no spaces).
        trimmed.length > 256 && trimmed.matches(Regex("[A-Za-z0-9+/=\\s]+")) -> trimmed
        else -> null
    } ?: return null

    return try {
        Base64.decode(b64.substringAfter("base64,").replace("\\s".toRegex(), ""), Base64.DEFAULT)
            .takeIf { it.size > 64 }
    } catch (_: Exception) {
        null
    }
}

/** Downloads file name derived from the response content type + timestamp. */
fun suggestedFileName(record: ResponseRecord): String {
    val ext = when {
        record.contentType?.contains("json") == true -> "json"
        record.contentType?.contains("xml") == true -> "xml"
        record.contentType?.contains("html") == true -> "html"
        record.contentType?.contains("csv") == true -> "csv"
        else -> "txt"
    }
    return "sharerouter-${record.timestamp}.$ext"
}

/** MIME type for the saved file (content type without parameters, defaulting to text/plain). */
fun mimeFor(record: ResponseRecord): String =
    record.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() } ?: "text/plain"
