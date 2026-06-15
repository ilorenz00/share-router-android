package com.lorenzl5.sharerouter.data.net

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import com.lorenzl5.sharerouter.data.InputKind
import com.lorenzl5.sharerouter.data.SharedContent
import com.lorenzl5.sharerouter.data.SharedItem
import com.lorenzl5.sharerouter.data.openapi.Endpoint
import com.lorenzl5.sharerouter.data.openapi.RequestStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DispatchResult(
    val ok: Boolean,
    val code: Int,
    /** Short human-readable status for the share sheet. */
    val message: String,
    /** Full response body, surfaced in the notification and history. */
    val body: String = "",
    val contentType: String? = null,
)

/** Builds and sends the chosen shared content to a matched [Endpoint]. */
class Dispatcher(
    private val client: OkHttpClient,
    private val resolver: ContentResolver,
) {
    suspend fun send(
        endpoint: Endpoint,
        content: SharedContent,
        baseUrl: String,
        bearer: String?,
    ): DispatchResult = withContext(Dispatchers.IO) {
        val item = content.firstItemOfKind(endpoint.accepts)
            ?: return@withContext DispatchResult(false, 0, "No shareable item matched this endpoint")

        val (url, body) = when (endpoint.style) {
            RequestStyle.MULTIPART -> joinUrl(baseUrl, endpoint.path) to multipartBody(endpoint, item)
            RequestStyle.JSON -> joinUrl(baseUrl, endpoint.path) to jsonBody(endpoint, item, wrapInArray = false)
            RequestStyle.JSON_ARRAY -> joinUrl(baseUrl, endpoint.path) to jsonBody(endpoint, item, wrapInArray = true)
            RequestStyle.TEXT_PLAIN -> joinUrl(baseUrl, endpoint.path) to
                (item.text ?: "").toRequestBody("text/plain".toMediaType())
            RequestStyle.QUERY -> queryUrl(baseUrl, endpoint, item) to
                "".toRequestBody(null)
        }

        val request = Request.Builder()
            .url(url)
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .method(endpoint.method, if (endpoint.method == "GET") null else body)
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                val contentType = resp.header("Content-Type")
                DispatchResult(
                    ok = resp.isSuccessful,
                    code = resp.code,
                    message = if (resp.isSuccessful) "Sent — HTTP ${resp.code}"
                    else "HTTP ${resp.code}: ${bodyStr.take(280)}",
                    body = bodyStr,
                    contentType = contentType,
                )
            }
        } catch (e: Exception) {
            DispatchResult(false, 0, e.message ?: "Network error", body = e.message ?: "Network error")
        }
    }

    private fun multipartBody(endpoint: Endpoint, item: SharedItem): RequestBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        endpoint.defaults.forEach { (k, v) -> builder.addFormDataPart(k, v) }

        if (item.uri != null) {
            val bytes = readBytes(item.uri)
            val mime = concreteMime(item)
            val filename = fileNameFor(item.uri, mime)
            val media = (mime ?: "application/octet-stream").toMediaTypeOrNull()
                ?: "application/octet-stream".toMediaType()
            builder.addFormDataPart(endpoint.payloadField, filename, bytes.toRequestBody(media))
        } else {
            builder.addFormDataPart(endpoint.payloadField, item.text ?: "")
        }
        return builder.build()
    }

    /**
     * A concrete MIME type — never null/blank and never a wildcard subtype (an
     * "image" type with a star subtype), which okhttp's toMediaType() rejects.
     * Falls back to the ContentResolver, which returns the precise type for
     * MediaStore / gallery URIs.
     */
    private fun concreteMime(item: SharedItem): String? {
        val declared = item.mimeType?.substringBefore(';')?.trim()
        if (declared != null && declared.contains('/') && !declared.endsWith("/*")) return declared
        val uri = item.uri ?: return declared
        return resolver.getType(uri)?.takeIf { it.contains('/') && !it.endsWith("/*") } ?: declared
    }

    /**
     * A filename that carries a real extension. The MasterAPI re-forwards uploads as
     * `application/octet-stream` (Go's `CreateFormFile`), so the downstream OCR/vision
     * service detects the image from the **filename extension**. A gallery URI whose
     * DISPLAY_NAME lacks an extension (Google Photos etc.) would otherwise be rejected
     * as "no image" — so derive the extension from the MIME type when missing.
     */
    private fun fileNameFor(uri: Uri, mime: String?): String {
        val raw = displayName(uri)?.trim().orEmpty()
        val hasExt = raw.substringAfterLast('.', "").length in 1..5
        if (raw.isNotEmpty() && hasExt) return raw
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return raw.ifEmpty { "shared" } + (ext?.let { ".$it" } ?: "")
    }

    private fun jsonBody(endpoint: Endpoint, item: SharedItem, wrapInArray: Boolean): RequestBody {
        val json = JSONObject()
        endpoint.defaults.forEach { (k, v) -> json.put(k, coerce(v)) }
        val value: String = when {
            item.uri != null && item.kind in BINARY_KINDS ->
                Base64.encodeToString(readBytes(item.uri), Base64.NO_WRAP)
            item.text != null -> item.text
            item.uri != null -> item.uri.toString()
            else -> ""
        }
        json.put(endpoint.payloadField, if (wrapInArray) JSONArray().put(value) else value)
        return json.toString().toRequestBody("application/json".toMediaType())
    }

    /**
     * x-share-defaults values arrive as strings; JSON endpoints often expect real
     * numbers/booleans (e.g. SD's denoising_strength). Coerce when unambiguous.
     */
    private fun coerce(v: String): Any = when {
        v == "true" -> true
        v == "false" -> false
        v.matches(Regex("-?\\d+")) -> v.toLongOrNull() ?: v
        v.matches(Regex("-?\\d*\\.\\d+")) -> v.toDoubleOrNull() ?: v
        else -> v
    }

    private fun queryUrl(baseUrl: String, endpoint: Endpoint, item: SharedItem): String {
        val value = item.text ?: item.uri?.toString() ?: ""
        val httpUrl = joinUrl(baseUrl, endpoint.path).toHttpUrl().newBuilder()
            .addQueryParameter(endpoint.payloadField, value)
            .apply { endpoint.defaults.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return httpUrl.toString()
    }

    /**
     * Read the shared content's bytes. Primary path is openInputStream; some gallery
     * and cloud document providers return an *empty* stream there but read fine via an
     * asset file descriptor, so fall back to that. Never returns empty bytes — an
     * empty/unreadable share throws (so the user gets a clear error instead of the
     * MasterAPI silently receiving a 0-byte file and replying "no image").
     */
    private fun readBytes(uri: Uri): ByteArray {
        runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.createInputStream().use { it.readBytes() }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        error("Geteilte Datei konnte nicht gelesen werden oder war leer ($uri)")
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun joinUrl(base: String, path: String): String =
        base.trimEnd('/') + "/" + path.trimStart('/')

    private companion object {
        val BINARY_KINDS = setOf(InputKind.IMAGE, InputKind.VIDEO, InputKind.AUDIO, InputKind.FILE)
    }
}
