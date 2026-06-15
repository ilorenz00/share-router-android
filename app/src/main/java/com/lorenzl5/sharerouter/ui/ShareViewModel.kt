package com.lorenzl5.sharerouter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lorenzl5.sharerouter.AppContainer
import com.lorenzl5.sharerouter.data.SharedContent
import com.lorenzl5.sharerouter.data.history.ResponseRecord
import com.lorenzl5.sharerouter.data.net.DispatchResult
import com.lorenzl5.sharerouter.data.openapi.Endpoint
import com.lorenzl5.sharerouter.data.openapi.EndpointMatcher
import com.lorenzl5.sharerouter.data.settings.AppSettings
import com.lorenzl5.sharerouter.notify.Notifications
import com.lorenzl5.sharerouter.work.JobTicketWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface ShareUiState {
    data object Loading : ShareUiState
    data object NeedsConfig : ShareUiState
    data class Ready(val specTitle: String, val endpoints: List<Endpoint>) : ShareUiState
    data class NoMatch(val specTitle: String, val kinds: String) : ShareUiState
    data class Error(val message: String) : ShareUiState
    data class Sending(val endpoint: Endpoint) : ShareUiState
    data class Done(val endpoint: Endpoint, val result: DispatchResult) : ShareUiState

    /** Async job accepted: the result will arrive later via notification + history. */
    data class Queued(val endpoint: Endpoint, val ticket: String) : ShareUiState
}

class ShareViewModel(
    private val container: AppContainer,
    private val content: SharedContent,
) : ViewModel() {

    private val _state = MutableStateFlow<ShareUiState>(ShareUiState.Loading)
    val state: StateFlow<ShareUiState> = _state

    /** Base URL resolved from the spec during load(), reused for dispatch. */
    private var cachedBaseUrl: String? = null

    val sharedContent: SharedContent get() = content

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = ShareUiState.Loading
            try {
                val settings = container.settings.settings.first()
                if (!settings.isConfigured) {
                    _state.value = ShareUiState.NeedsConfig
                    return@launch
                }
                val bearer = tokenIfNeeded(settings)
                val spec = container.fetcher.fetch(settings.specUrl, bearer)
                cachedBaseUrl = settings.baseUrlOverride.ifBlank { spec.baseUrl }
                val matches = EndpointMatcher.match(spec, content)
                _state.value = if (matches.isEmpty())
                    ShareUiState.NoMatch(spec.title, content.kinds.joinToString { it.name.lowercase() })
                else
                    ShareUiState.Ready(spec.title, matches)
            } catch (e: Exception) {
                _state.value = ShareUiState.Error(e.message ?: "Failed to load endpoints")
            }
        }
    }

    fun dispatch(endpoint: Endpoint) {
        viewModelScope.launch {
            _state.value = ShareUiState.Sending(endpoint)
            try {
                val settings = container.settings.settings.first()
                val bearer = tokenIfNeeded(settings)
                val base = cachedBaseUrl ?: settings.baseUrlOverride.ifBlank {
                    container.fetcher.fetch(settings.specUrl, bearer).baseUrl
                }
                val result = container.dispatcher.send(endpoint, content, base, bearer)
                val ticket = ticketOf(result)
                if (ticket != null) {
                    enqueueTicket(endpoint, result, ticket, base)
                    _state.value = ShareUiState.Queued(endpoint, ticket)
                } else {
                    recordResponse(endpoint, result)
                    _state.value = ShareUiState.Done(endpoint, result)
                }
            } catch (e: Exception) {
                _state.value = ShareUiState.Error(e.message ?: "Send failed")
            }
        }
    }

    /**
     * Detects an async job ticket in the response. Generic across queue kinds: any
     * 2xx body shaped like {"ticket":"…","status":"queued|running",…} is treated as
     * async. Returns the ticket id, or null for a direct response.
     */
    private fun ticketOf(result: DispatchResult): String? {
        if (!result.ok) return null
        val body = result.body.trim()
        if (!body.startsWith("{")) return null
        return try {
            val obj = JSONObject(body)
            val ticket = obj.optString("ticket")
            if (ticket.isNotBlank() && obj.has("status")) ticket else null
        } catch (_: Exception) {
            null
        }
    }

    /** Persist a pending record and schedule the background poll for the job result. */
    private fun enqueueTicket(endpoint: Endpoint, result: DispatchResult, ticket: String, base: String) {
        val recordId = UUID.randomUUID().toString()
        val record = ResponseRecord(
            id = recordId,
            timestamp = System.currentTimeMillis(),
            endpointTitle = endpoint.title,
            requestSummary = content.primaryText?.take(120)
                ?: content.kinds.joinToString { it.name.lowercase() },
            httpCode = result.code,
            ok = true,
            body = "In Bearbeitung… (Ticket $ticket)",
            pending = true,
            ticket = ticket,
        )
        container.history.add(record)

        val request = OneTimeWorkRequestBuilder<JobTicketWorker>()
            .setInputData(JobTicketWorker.inputData(pollUrlFor(base, ticket), recordId, ticket))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(container.appContext)
            .enqueueUniqueWork("ticket-$ticket", ExistingWorkPolicy.KEEP, request)

        Notifications.notifyProgress(
            container.appContext, recordId, "⏳ GPU-Job eingereiht", "queued · $ticket",
        )
    }

    /** Job-status endpoint on the same host as the dispatch base, regardless of basePath. */
    private fun pollUrlFor(base: String, ticket: String): String =
        runCatching { base.toHttpUrl().resolve("/api/v2/gpu/jobs/$ticket")?.toString() }
            .getOrNull() ?: "https://api.lorenzl5.com/api/v2/gpu/jobs/$ticket"

    /** Persist the response to history and raise the notification (Copy / Save actions). */
    private fun recordResponse(endpoint: Endpoint, result: DispatchResult) {
        val record = ResponseRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            endpointTitle = endpoint.title,
            requestSummary = content.primaryText?.take(120)
                ?: content.kinds.joinToString { it.name.lowercase() },
            httpCode = result.code,
            ok = result.ok,
            body = result.body.ifBlank { result.message },
            contentType = result.contentType,
        )
        container.history.add(record)
        Notifications.notifyResponse(container.appContext, record)
    }

    private suspend fun tokenIfNeeded(settings: AppSettings): String? {
        if (!settings.usesAuth) return null
        var exchangeId = settings.exchangeClientId
        if (exchangeId.isBlank()) {
            // Auto-discover the proxy provider's client_id from the forwardAuth
            // redirect chain and persist it for subsequent shares.
            exchangeId = container.auth.discoverProxyClientId(settings.specUrl).orEmpty()
            if (exchangeId.isNotBlank()) {
                container.settings.update(settings.copy(exchangeClientId = exchangeId))
            }
        }
        return container.auth.bearerToken(exchangeId)
    }

    class Factory(
        private val container: AppContainer,
        private val content: SharedContent,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ShareViewModel(container, content) as T
    }
}
