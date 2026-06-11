package com.lorenzl5.sharerouter.data.net

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.lorenzl5.sharerouter.data.InputKind
import com.lorenzl5.sharerouter.data.SharedContent
import com.lorenzl5.sharerouter.data.SharedItem
import com.lorenzl5.sharerouter.data.openapi.Endpoint
import com.lorenzl5.sharerouter.data.openapi.RequestStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DispatchResult(val ok: Boolean, val code: Int, val message: String)

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
                val msg = resp.body?.string()?.take(280).orEmpty()
                DispatchResult(
                    ok = resp.isSuccessful,
                    code = resp.code,
                    message = if (resp.isSuccessful) "Sent — HTTP ${resp.code}" else "HTTP ${resp.code}: $msg",
                )
            }
        } catch (e: Exception) {
            DispatchResult(false, 0, e.message ?: "Network error")
        }
    }

    private fun multipartBody(endpoint: Endpoint, item: SharedItem): RequestBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        endpoint.defaults.forEach { (k, v) -> builder.addFormDataPart(k, v) }

        if (item.uri != null) {
            val bytes = readBytes(item.uri)
            val filename = displayName(item.uri) ?: "shared"
            val media = (item.mimeType ?: "application/octet-stream").toMediaType()
            builder.addFormDataPart(endpoint.payloadField, filename, bytes.toRequestBody(media))
        } else {
            builder.addFormDataPart(endpoint.payloadField, item.text ?: "")
        }
        return builder.build()
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

    private fun readBytes(uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not read shared file")

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
