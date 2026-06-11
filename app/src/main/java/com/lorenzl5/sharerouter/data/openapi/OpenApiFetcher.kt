package com.lorenzl5.sharerouter.data.openapi

import com.lorenzl5.sharerouter.data.InputKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches an OpenAPI 3.x JSON document and reduces it to the [Endpoint]s that can
 * receive shared content.
 *
 * Each operation's accepted [InputKind]s are derived in priority order:
 *   1. `x-share-accepts: ["image", "url", ...]`  — explicit, authoritative.
 *   2. Schema inference from `requestBody` (binary field -> file/image, uri string -> url, ...).
 * Operations that yield no accepted kinds are dropped.
 *
 * Optional vendor extensions per operation:
 *   x-share-field         field/param that receives the payload
 *   x-share-style         multipart | json | text | query  (overrides inference)
 *   x-share-url-hosts     ["youtube.com", ...] host allowlist for URL payloads
 *   x-share-defaults      { "model": "sd_xl" } static fields always sent
 */
class OpenApiFetcher(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetch(specUrl: String, bearer: String?): ParsedSpec = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(specUrl)
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .header("Accept", "application/json")
            .build()

        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Spec fetch failed: HTTP ${resp.code}")
            resp.body?.string() ?: error("Empty spec body")
        }

        val root = json.parseToJsonElement(body).jsonObject
        parse(root, specUrl)
    }

    private fun parse(root: JsonObject, specUrl: String): ParsedSpec {
        val title = root["info"]?.jsonObject?.get("title")?.str() ?: "MasterAPI"
        // Swagger 2.0 (e.g. swaggo's doc.json) vs OpenAPI 3.x — affects base-url
        // resolution and request-body inference; the x-share-* contract is identical.
        val isV2 = root["swagger"]?.str()?.startsWith("2") == true
        val baseUrl = if (isV2) resolveBaseUrlV2(root, specUrl) else resolveBaseUrl(root, specUrl)
        val components = if (isV2)
            root["definitions"]?.jsonObject
        else
            root["components"]?.jsonObject?.get("schemas")?.jsonObject

        val endpoints = mutableListOf<Endpoint>()
        val paths = root["paths"]?.jsonObject ?: JsonObject(emptyMap())

        for ((path, pathItemEl) in paths) {
            val pathItem = pathItemEl as? JsonObject ?: continue
            for (method in HTTP_METHODS) {
                val op = pathItem[method]?.jsonObject ?: continue
                runCatching { parseOperation(path, method, op, components, isV2) }
                    .getOrNull()
                    ?.let { endpoints += it }
            }
        }
        return ParsedSpec(title = title, baseUrl = baseUrl, endpoints = endpoints)
    }

    private fun parseOperation(
        path: String,
        method: String,
        op: JsonObject,
        components: JsonObject?,
        isV2: Boolean = false,
    ): Endpoint? {
        val opId = op["operationId"]?.str() ?: "${method.uppercase()} $path"
        val summary = op["summary"]?.str()
        val description = op["description"]?.str()
        val tags = op["tags"]?.jsonArray?.mapNotNull { it.str() } ?: emptyList()

        val explicitAccepts = op["x-share-accepts"]?.jsonArray
            ?.mapNotNull { it.str()?.let(InputKind::fromString) }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }

        val urlHosts = op["x-share-url-hosts"]?.jsonArray?.mapNotNull { it.str() }
        val defaults = op["x-share-defaults"]?.jsonObject
            ?.mapValues { it.value.str() ?: "" } ?: emptyMap()
        val styleOverride = op["x-share-style"]?.str()?.let(::styleFromString)
        val fieldOverride = op["x-share-field"]?.str()

        // Inspect requestBody (OpenAPI 3) / parameters (Swagger 2) to infer style/field/accepts.
        val inferred = if (isV2) inferFromParametersV2(op, components) else inferFromRequestBody(op, components)

        val accepts = explicitAccepts ?: inferred?.accepts ?: emptySet()
        if (accepts.isEmpty()) return null

        val style = styleOverride
            ?: inferred?.style
            ?: if (accepts == setOf(InputKind.URL) || accepts == setOf(InputKind.TEXT))
                RequestStyle.JSON else RequestStyle.MULTIPART

        val payloadField = fieldOverride
            ?: inferred?.field
            ?: defaultFieldFor(accepts)

        return Endpoint(
            operationId = opId,
            method = method.uppercase(),
            path = path,
            summary = summary,
            description = description,
            tags = tags,
            accepts = accepts,
            style = style,
            payloadField = payloadField,
            defaults = defaults,
            urlHosts = urlHosts,
            annotated = explicitAccepts != null,
        )
    }

    private data class Inferred(
        val accepts: Set<InputKind>,
        val style: RequestStyle,
        val field: String?,
    )

    private fun inferFromRequestBody(op: JsonObject, components: JsonObject?): Inferred? {
        val content = op["requestBody"]?.jsonObject?.get("content")?.jsonObject ?: return null

        // Preference order of media types.
        val mediaType = listOf(
            "multipart/form-data",
            "application/octet-stream",
            "application/json",
            "text/plain",
        ).firstOrNull { content.containsKey(it) } ?: content.keys.firstOrNull() ?: return null

        val schema = content[mediaType]?.jsonObject?.get("schema")?.jsonObject
            ?.let { resolveRef(it, components) }

        return when {
            mediaType == "application/octet-stream" ->
                Inferred(setOf(InputKind.FILE), RequestStyle.MULTIPART, null)

            mediaType == "text/plain" ->
                Inferred(setOf(InputKind.TEXT), RequestStyle.TEXT_PLAIN, null)

            mediaType.startsWith("multipart/") -> {
                val (kinds, field) = inferFromProperties(schema, components, binaryAsFile = true)
                if (kinds.isEmpty()) null else Inferred(kinds, RequestStyle.MULTIPART, field)
            }

            mediaType == "application/json" -> {
                val (kinds, field) = inferFromProperties(schema, components, binaryAsFile = false)
                if (kinds.isEmpty()) null else Inferred(kinds, RequestStyle.JSON, field)
            }

            else -> null
        }
    }

    /**
     * Swagger 2.0: operations carry a `parameters` array instead of `requestBody`.
     * `in: formData, type: file` → multipart binary; `in: body` with a schema
     * ($ref into #/definitions) → JSON, inferred from the schema's properties.
     */
    private fun inferFromParametersV2(op: JsonObject, definitions: JsonObject?): Inferred? {
        val params = op["parameters"]?.jsonArray ?: return null

        val kinds = mutableSetOf<InputKind>()
        var fileField: String? = null
        for (p in params) {
            val param = p as? JsonObject ?: continue
            if (param["in"]?.str() != "formData") continue
            if (param["type"]?.str() != "file") continue
            val name = param["name"]?.str() ?: continue
            val lname = name.lowercase()
            kinds += when {
                lname.contains("image") || lname.contains("photo") || lname.contains("img") -> InputKind.IMAGE
                lname.contains("video") -> InputKind.VIDEO
                lname.contains("audio") || lname.contains("voice") -> InputKind.AUDIO
                else -> InputKind.FILE
            }
            if (fileField == null) fileField = name
        }
        if (fileField != null) return Inferred(kinds, RequestStyle.MULTIPART, fileField)

        val bodySchema = params
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it["in"]?.str() == "body" }
            ?.get("schema")?.jsonObject
            ?.let { resolveRef(it, definitions) }
            ?: return null
        val (jsonKinds, jsonField) = inferFromProperties(bodySchema, definitions, binaryAsFile = false)
        return if (jsonKinds.isEmpty()) null else Inferred(jsonKinds, RequestStyle.JSON, jsonField)
    }

    /** Scan an object schema's properties; return the detected kinds and the best payload field. */
    private fun inferFromProperties(
        schema: JsonObject?,
        components: JsonObject?,
        binaryAsFile: Boolean,
    ): Pair<Set<InputKind>, String?> {
        val props = schema?.get("properties")?.jsonObject
            ?: return emptySet<InputKind>() to null

        val kinds = mutableSetOf<InputKind>()
        var binaryField: String? = null
        var urlField: String? = null
        var textField: String? = null

        for ((name, propEl) in props) {
            val prop = (propEl as? JsonObject)?.let { resolveRef(it, components) } ?: continue
            val type = prop["type"]?.str()
            val format = prop["format"]?.str()
            val lname = name.lowercase()

            when {
                format == "binary" || type == "file" -> {
                    val kind = when {
                        lname.contains("image") || lname.contains("photo") || lname.contains("img") -> InputKind.IMAGE
                        lname.contains("video") -> InputKind.VIDEO
                        lname.contains("audio") || lname.contains("voice") -> InputKind.AUDIO
                        else -> InputKind.FILE
                    }
                    kinds += if (binaryAsFile) kind else InputKind.FILE
                    if (binaryField == null) binaryField = name
                }

                type == "string" && (format == "uri" || lname in URL_FIELD_NAMES) -> {
                    kinds += InputKind.URL
                    if (urlField == null) urlField = name
                }

                type == "string" && lname in TEXT_FIELD_NAMES -> {
                    kinds += InputKind.TEXT
                    if (textField == null) textField = name
                }
            }
        }
        val field = binaryField ?: urlField ?: textField
        return kinds to field
    }

    /** Resolve a local `$ref` (#/components/schemas/Name); returns the schema itself otherwise. */
    private fun resolveRef(schema: JsonObject, components: JsonObject?): JsonObject {
        val ref = schema["\$ref"]?.str() ?: return schema
        val name = ref.substringAfterLast('/')
        return components?.get(name)?.jsonObject ?: schema
    }

    /** Swagger 2.0: scheme://host + basePath; host omitted → origin of the spec URL. */
    private fun resolveBaseUrlV2(root: JsonObject, specUrl: String): String {
        val origin = Regex("^(https?://[^/]+)").find(specUrl)?.groupValues?.get(1) ?: ""
        val host = root["host"]?.str()
        val scheme = root["schemes"]?.jsonArray?.firstOrNull()?.str()
            ?: origin.substringBefore("://").ifBlank { "https" }
        val basePath = root["basePath"]?.str().orEmpty()
        val base = if (host.isNullOrBlank()) origin else "$scheme://$host"
        return (base + basePath).trimEnd('/')
    }

    private fun resolveBaseUrl(root: JsonObject, specUrl: String): String {
        val server = root["servers"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("url")?.str()
        return when {
            server == null -> specUrl.substringBefore("/openapi").substringBeforeLast('/')
            server.startsWith("http") -> server.trimEnd('/')
            else -> {
                // Relative server url -> resolve against spec origin.
                val origin = Regex("^(https?://[^/]+)").find(specUrl)?.groupValues?.get(1) ?: ""
                (origin + server).trimEnd('/')
            }
        }
    }

    private fun defaultFieldFor(accepts: Set<InputKind>): String = when {
        InputKind.IMAGE in accepts -> "image"
        InputKind.VIDEO in accepts -> "video"
        InputKind.AUDIO in accepts -> "audio"
        InputKind.FILE in accepts -> "file"
        InputKind.URL in accepts -> "url"
        else -> "text"
    }

    private fun styleFromString(s: String): RequestStyle? = when (s.trim().lowercase()) {
        "multipart", "multipart/form-data", "form" -> RequestStyle.MULTIPART
        "json", "application/json" -> RequestStyle.JSON
        "json-array", "jsonarray", "json_array" -> RequestStyle.JSON_ARRAY
        "text", "text/plain", "plain" -> RequestStyle.TEXT_PLAIN
        "query", "querystring", "param" -> RequestStyle.QUERY
        else -> null
    }

    private fun JsonElement.str(): String? = (this as? JsonElement)
        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    companion object {
        private val HTTP_METHODS = listOf("post", "put", "patch", "get", "delete")
        private val URL_FIELD_NAMES = setOf("url", "uri", "link", "src", "source", "href", "stream")
        private val TEXT_FIELD_NAMES = setOf("text", "prompt", "message", "content", "query", "body", "input")
    }
}
