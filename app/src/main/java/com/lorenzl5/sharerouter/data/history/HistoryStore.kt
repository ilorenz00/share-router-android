package com.lorenzl5.sharerouter.data.history

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One stored MasterAPI response — surfaced in the notification and the in-app history. */
@Serializable
data class ResponseRecord(
    val id: String,
    val timestamp: Long,
    val endpointTitle: String,
    /** Short description of what was sent (primary text or the shared kinds). */
    val requestSummary: String,
    val httpCode: Int,
    val ok: Boolean,
    /** Full response body returned by the API (or job result / status text). */
    val body: String,
    val contentType: String? = null,
    /** Absolute path of a decoded result image in filesDir, if the result is an image. */
    val imagePath: String? = null,
    /** True while an async job (ticket) is still queued/running — final result pending. */
    val pending: Boolean = false,
    /** Ticket id of the async job this record tracks, if any. */
    val ticket: String? = null,
)

/**
 * File-backed history of API responses (JSON in filesDir). Deliberately no Room/KSP:
 * the list is small and rewritten whole. Exposes a [StateFlow] for the UI plus
 * synchronous [get]/[add] usable from a BroadcastReceiver (notification actions).
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private val _records = MutableStateFlow(readFromDisk())
    val records: StateFlow<List<ResponseRecord>> = _records.asStateFlow()

    fun get(id: String): ResponseRecord? = _records.value.firstOrNull { it.id == id }

    fun add(record: ResponseRecord): Unit = synchronized(lock) {
        val updated = (listOf(record) + _records.value).take(MAX_ENTRIES)
        _records.value = updated
        writeToDisk(updated)
    }

    /** Replace an existing record (same id) in place, or insert it at the front. */
    fun upsert(record: ResponseRecord): Unit = synchronized(lock) {
        val current = _records.value
        val updated = if (current.any { it.id == record.id }) {
            current.map { if (it.id == record.id) record else it }
        } else {
            (listOf(record) + current).take(MAX_ENTRIES)
        }
        _records.value = updated
        writeToDisk(updated)
    }

    fun delete(id: String): Unit = synchronized(lock) {
        val updated = _records.value.filterNot { it.id == id }
        _records.value = updated
        writeToDisk(updated)
    }

    fun clear(): Unit = synchronized(lock) {
        _records.value = emptyList()
        writeToDisk(emptyList())
    }

    private fun readFromDisk(): List<ResponseRecord> = try {
        if (file.exists()) json.decodeFromString<List<ResponseRecord>>(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeToDisk(list: List<ResponseRecord>) {
        try {
            file.writeText(json.encodeToString(list))
        } catch (_: Exception) {
            // Best-effort persistence — the in-memory StateFlow stays authoritative.
        }
    }

    private companion object {
        const val FILE_NAME = "response_history.json"
        const val MAX_ENTRIES = 200
    }
}
