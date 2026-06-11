package com.lorenzl5.sharerouter.data

import android.net.Uri

/**
 * The logical kind of a piece of shared content. Both the incoming share and every
 * MasterAPI operation are reduced to a set of these, and matching is set intersection.
 */
enum class InputKind {
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    URL,
    TEXT;

    companion object {
        fun fromString(s: String): InputKind? = when (s.trim().lowercase()) {
            "image", "img", "picture", "photo" -> IMAGE
            "video", "movie" -> VIDEO
            "audio", "sound", "voice" -> AUDIO
            "file", "binary", "document", "doc" -> FILE
            "url", "link", "uri" -> URL
            "text", "string", "prompt", "message" -> TEXT
            else -> null
        }
    }
}

/** A single shared payload: either a content Uri (binary) or a text string. */
data class SharedItem(
    val uri: Uri? = null,
    val text: String? = null,
    val mimeType: String? = null,
    val kind: InputKind,
)

/** The fully parsed and classified result of an incoming share Intent. */
data class SharedContent(
    val items: List<SharedItem>,
    val kinds: Set<InputKind>,
    /** Host of the first URL item, if any (used for x-share-url-hosts filtering). */
    val urlHost: String? = null,
    /** Convenience: the first text/url payload, if any. */
    val primaryText: String? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    fun firstItemOfKind(accepts: Set<InputKind>): SharedItem? =
        items.firstOrNull { it.kind in accepts }
}
