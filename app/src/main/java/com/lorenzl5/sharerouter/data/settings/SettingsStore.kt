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

/** Production defaults — a fresh install is fully configured for the
 *  lorenzl5 cluster; only Login + Test connection remain. */
object Defaults {
    const val SPEC_URL = "https://api.lorenzl5.com/swagger/doc.json"
    const val OIDC_ISSUER = "https://auth.lorenzl5.com/application/o/share-router/"
    const val OIDC_CLIENT_ID = "share-router"
    const val OIDC_SCOPES = "openid profile email offline_access"
}

data class AppSettings(
    val specUrl: String = Defaults.SPEC_URL,
    val baseUrlOverride: String = "",
    val oidcIssuer: String = Defaults.OIDC_ISSUER,
    val oidcClientId: String = Defaults.OIDC_CLIENT_ID,
    val oidcScopes: String = Defaults.OIDC_SCOPES,
    /** Client ID of the forward-auth proxy provider guarding the API host.
     *  When set, access tokens are exchanged for proxy-provider tokens
     *  (Authentik JWT federation) before calling the MasterAPI.
     *  Blank = auto-discovered from the forwardAuth redirect chain. */
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
            specUrl = p[Keys.SPEC] ?: Defaults.SPEC_URL,
            baseUrlOverride = p[Keys.BASE] ?: "",
            oidcIssuer = p[Keys.ISSUER] ?: Defaults.OIDC_ISSUER,
            oidcClientId = p[Keys.CLIENT] ?: Defaults.OIDC_CLIENT_ID,
            oidcScopes = p[Keys.SCOPES] ?: Defaults.OIDC_SCOPES,
            exchangeClientId = p[Keys.EXCHANGE] ?: "",
        )
    }

    suspend fun update(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.SPEC] = s.specUrl.trim()
            p[Keys.BASE] = s.baseUrlOverride.trim()
            p[Keys.ISSUER] = s.oidcIssuer.trim()
            p[Keys.CLIENT] = s.oidcClientId.trim()
            p[Keys.SCOPES] = s.oidcScopes.trim().ifBlank { Defaults.OIDC_SCOPES }
            p[Keys.EXCHANGE] = s.exchangeClientId.trim()
        }
    }
}
