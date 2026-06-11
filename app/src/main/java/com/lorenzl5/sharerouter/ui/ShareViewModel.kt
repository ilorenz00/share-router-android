package com.lorenzl5.sharerouter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lorenzl5.sharerouter.AppContainer
import com.lorenzl5.sharerouter.data.SharedContent
import com.lorenzl5.sharerouter.data.net.DispatchResult
import com.lorenzl5.sharerouter.data.openapi.Endpoint
import com.lorenzl5.sharerouter.data.openapi.EndpointMatcher
import com.lorenzl5.sharerouter.data.settings.AppSettings
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
                _state.value = ShareUiState.Done(endpoint, result)
            } catch (e: Exception) {
                _state.value = ShareUiState.Error(e.message ?: "Send failed")
            }
        }
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
