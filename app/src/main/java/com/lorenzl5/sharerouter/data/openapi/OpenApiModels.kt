package com.lorenzl5.sharerouter.data.openapi

import com.lorenzl5.sharerouter.data.InputKind

/** How the chosen payload is delivered to an endpoint. */
enum class RequestStyle {
    /** multipart/form-data with a file part (binary) or form fields. */
    MULTIPART,

    /** application/json body; payload goes into [Endpoint.payloadField]. */
    JSON,

    /** Like [JSON], but the payload is wrapped in a single-element array
     *  (e.g. Stable Diffusion's `init_images: [base64]`). */
    JSON_ARRAY,

    /** text/plain body = the raw payload. */
    TEXT_PLAIN,

    /** No body; payload appended as a query parameter named [Endpoint.payloadField]. */
    QUERY,
}

/** A single MasterAPI operation that can receive shared content. */
data class Endpoint(
    val operationId: String,
    val method: String,            // GET, POST, ...
    val path: String,              // /sd/img2img
    val summary: String?,
    val description: String?,
    val tags: List<String>,
    val accepts: Set<InputKind>,
    val style: RequestStyle,
    /** Name of the field/param that receives the payload (form field, json key, query param). */
    val payloadField: String,
    /** Static extra form/json fields to always include (from x-share-defaults). */
    val defaults: Map<String, String>,
    /** Optional host allowlist for URL payloads (from x-share-url-hosts). */
    val urlHosts: List<String>?,
    /** True when accepts came from explicit x-share-accepts rather than schema inference. */
    val annotated: Boolean,
) {
    /** Human label for the share sheet. */
    val title: String
        get() = summary?.takeIf { it.isNotBlank() }
            ?: tags.firstOrNull()
            ?: operationId
}

data class ParsedSpec(
    val title: String,
    val baseUrl: String,
    val endpoints: List<Endpoint>,
)
