package com.lorenzl5.sharerouter.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

data class AppSettings(
    val specUrl: String = "",
    val baseUrlOverride: String = "",
    val oidcIssuer: String = "",
    val oidcClientId: String = "",
    val oidcScopes: String = "openid profile email offline_access",
    /** Client ID of the forward-auth proxy provider guarding the API host.
     *  When set, access tokens are exchanged for proxy-provider tokens
     *  (Authentik JWT federation) before calling the MasterAPI. */
    val exchangeClientId: String = "",
) {
    val isConfigured: Boolean get() = specUrl.isNotBlank()
    val usesAuth: Boolean get() = oidcIssuer.isNotBlank() && oidcClientId.isNotBlank()
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val SPEC = stringPreferencesKey("spec_url")
        val BASE = stringPreferencesKey("base_url")
        val ISSUER = stringPreferencesKey("oidc_issuer")
        val CLIENT = stringPreferencesKey("oidc_client")
        val SCOPES = stringPreferencesKey("oidc_scopes")
        val EXCHANGE = stringPreferencesKey("exchange_client_id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            specUrl = p[Keys.SPEC] ?: "",
            baseUrlOverride = p[Keys.BASE] ?: "",
            oidcIssuer = p[Keys.ISSUER] ?: "",
            oidcClientId = p[Keys.CLIENT] ?: "",
            oidcScopes = p[Keys.SCOPES] ?: "openid profile email offline_access",
            exchangeClientId = p[Keys.EXCHANGE] ?: "",
        )
    }

    suspend fun update(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.SPEC] = s.specUrl.trim()
            p[Keys.BASE] = s.baseUrlOverride.trim()
            p[Keys.ISSUER] = s.oidcIssuer.trim()
            p[Keys.CLIENT] = s.oidcClientId.trim()
            p[Keys.SCOPES] = s.oidcScopes.trim().ifBlank { "openid profile email offline_access" }
            p[Keys.EXCHANGE] = s.exchangeClientId.trim()
        }
    }
}
