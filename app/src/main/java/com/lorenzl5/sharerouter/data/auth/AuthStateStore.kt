package com.lorenzl5.sharerouter.data.auth

import android.content.Context
import net.openid.appauth.AuthState
import org.json.JSONException

/**
 * Persists the AppAuth [AuthState] as its JSON serialization.
 *
 * Note: for a LAN/homelab tool this uses plain SharedPreferences. If you later expose
 * this beyond a trusted device, swap in androidx.security:security-crypto
 * (EncryptedSharedPreferences) here — the rest of the app is unaffected.
 */
class AuthStateStore(context: Context) {

    private val prefs = context.getSharedPreferences("auth_state", Context.MODE_PRIVATE)

    fun save(state: AuthState) {
        prefs.edit().putString(KEY, state.jsonSerializeString()).apply()
    }

    fun load(): AuthState? {
        val raw = prefs.getString(KEY, null) ?: return null
        return try {
            AuthState.jsonDeserialize(raw)
        } catch (e: JSONException) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "state"
    }
}
