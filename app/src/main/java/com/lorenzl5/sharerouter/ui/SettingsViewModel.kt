package com.lorenzl5.sharerouter.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lorenzl5.sharerouter.AppContainer
import com.lorenzl5.sharerouter.data.settings.AppSettings
import kotlinx.coroutines.flow.Flow

/** Backs the settings screen: persist config, run OIDC login, test the spec. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: Flow<AppSettings> = container.settings.settings

    suspend fun save(s: AppSettings) = container.settings.update(s)

    fun isAuthorized(): Boolean = container.auth.isAuthorized()

    fun logout() = container.auth.logout()

    /** Build the browser intent that begins the OIDC flow (after saving config). */
    suspend fun buildLoginIntent(s: AppSettings): Intent {
        container.settings.update(s)
        return container.auth.buildAuthIntent(s.oidcIssuer, s.oidcClientId, s.oidcScopes)
    }

    suspend fun handleLoginResult(data: Intent) = container.auth.handleAuthResponse(data)

    /** Fetch the spec and summarize what would be exposed. */
    suspend fun test(s: AppSettings): String {
        container.settings.update(s)
        val bearer = if (s.usesAuth) container.auth.bearerToken(s.exchangeClientId) else null
        val spec = container.fetcher.fetch(s.specUrl, bearer)
        if (spec.endpoints.isEmpty()) {
            return "Connected to “${spec.title}”, but no operation accepts shareable input.\n" +
                "Annotate operations with x-share-accepts (see README)."
        }
        val lines = spec.endpoints.joinToString("\n") { ep ->
            "• ${ep.title} — ${ep.accepts.joinToString { k -> k.name.lowercase() }} (${ep.method} ${ep.path})"
        }
        return "“${spec.title}” → base ${spec.baseUrl}\n${spec.endpoints.size} endpoint(s):\n$lines"
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
