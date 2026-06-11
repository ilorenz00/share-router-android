package com.lorenzl5.sharerouter.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.URLUtil

/** Turns an incoming SEND / SEND_MULTIPLE intent into classified [SharedContent]. */
object IntentParser {

    fun parse(intent: Intent, resolver: ContentResolver): SharedContent {
        val rawItems = mutableListOf<Pair<Uri?, String?>>() // uri, text

        when (intent.action) {
            Intent.ACTION_SEND -> {
                streamExtra(intent)?.let { rawItems += it to null }
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { rawItems += null to it }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                streamListExtra(intent).forEach { rawItems += it to null }
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { rawItems += null to it }
            }
        }

        val items = rawItems.mapNotNull { (uri, text) ->
            when {
                uri != null -> {
                    val mime = intent.type?.takeIf { it != "*/*" } ?: resolver.getType(uri)
                    SharedItem(uri = uri, mimeType = mime, kind = kindForMime(mime))
                }
                text != null -> {
                    val trimmed = text.trim()
                    val isUrl = URLUtil.isNetworkUrl(trimmed) && !trimmed.contains(' ')
                    SharedItem(
                        text = text,
                        mimeType = "text/plain",
                        kind = if (isUrl) InputKind.URL else InputKind.TEXT,
                    )
                }
                else -> null
            }
        }

        val urlHost = items.firstOrNull { it.kind == InputKind.URL }?.text
            ?.let { runCatching { Uri.parse(it.trim()).host }.getOrNull() }

        return SharedContent(
            items = items,
            kinds = items.map { it.kind }.toSet(),
            urlHost = urlHost,
            primaryText = items.firstOrNull { it.text != null }?.text,
        )
    }

    private fun kindForMime(mime: String?): InputKind = when {
        mime == null -> InputKind.FILE
        mime.startsWith("image/") -> InputKind.IMAGE
        mime.startsWith("video/") -> InputKind.VIDEO
        mime.startsWith("audio/") -> InputKind.AUDIO
        mime == "text/plain" -> InputKind.TEXT
        else -> InputKind.FILE
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else
            intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
}
