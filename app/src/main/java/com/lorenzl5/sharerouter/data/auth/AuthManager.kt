package com.lorenzl5.sharerouter.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OIDC Authorization-Code + PKCE against Authentik via AppAuth.
 *
 * Redirect URI is "sharerouter:/oauth2redirect" (the appAuthRedirectScheme manifest
 * placeholder). Register it on the Authentik OAuth2 provider as a valid redirect URI.
 */
const val REDIRECT_URI = "sharerouter:/oauth2redirect"

class AuthManager(context: Context, private val http: OkHttpClient) {

    private val appContext = context.applicationContext
    private val store = AuthStateStore(appContext)
    private val service = AuthorizationService(appContext)

    /** Cached result of the proxy-provider token exchange (see [bearerToken]). */
    private data class ExchangedToken(val clientId: String, val token: String, val expiresAtMs: Long)

    @Volatile
    private var exchanged: ExchangedToken? = null

    fun isAuthorized(): Boolean = store.load()?.isAuthorized == true

    fun logout() {
        exchanged = null
        store.clear()
    }

    fun dispose() = service.dispose()

    /** Build the browser intent that starts the auth flow. */
    suspend fun buildAuthIntent(issuer: String, clientId: String, scopes: String): Intent {
        val config = fetchConfig(issuer)
        val request = AuthorizationRequest.Builder(
            config,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        ).setScope(scopes).build()
        // Persist the fresh (unauthorized) state so the redirect handler can update it.
        store.save(AuthState(config))
        return service.getAuthorizationRequestIntent(request)
    }

    /** Handle the redirect intent: validate the response and exchange the code for tokens. */
    suspend fun handleAuthResponse(data: Intent) {
        val response = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (response == null) throw (ex ?: RuntimeException("No authorization response"))

        val state = store.load() ?: AuthState()
        state.update(response, ex)
        store.save(state)

        val token = exchangeToken(response)
        state.update(token, null)
        store.save(state)
    }

    /**
     * Discovers the forward-auth proxy provider's client_id without any login:
     * an unauthenticated probe of the API host gets redirected by the Authentik
     * outpost (… → /outpost.goauthentik.io/start → /application/o/authorize/
     * ?client_id=<proxy-provider>…); the authorize URL carries the client_id.
     * Returns null if the host never redirects to an authorize URL (e.g. not
     * behind forward-auth at all).
     */
    suspend fun discoverProxyClientId(probeUrl: String): String? = withContext(Dispatchers.IO) {
        val noRedirect = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var url = probeUrl
        repeat(5) {
            val resp = try {
                noRedirect.newCall(
                    Request.Builder().url(url).header("Accept", "text/html").build()
                ).execute()
            } catch (e: Exception) {
                return@withContext null
            }
            resp.use {
                if (it.code !in 300..399) return@withContext null
                val location = it.header("Location") ?: return@withContext null
                val resolved = it.request.url.resolve(location)?.toString()
                    ?: return@withContext null
                Uri.parse(resolved).getQueryParameter("client_id")
                    ?.takeIf { id -> id.isNotBlank() }
                    ?.let { id -> return@withContext id }
                url = resolved
            }
        }
        null
    }

    /**
     * Returns the token to send as `Authorization: Bearer` to the MasterAPI.
     *
     * Authentik's forward-auth outpost only accepts access tokens issued by the
     * *proxy provider* guarding the host (introspection checks the issuing provider).
     * With [exchangeClientId] set (= the masterapi proxy provider's client ID, see
     * docs/manual-setup-steps.md in the cluster repo), our own OIDC access token is
     * exchanged at the token endpoint via client_credentials + client_assertion —
     * possible because the share-router provider is listed in the proxy provider's
     * jwt_federation_providers. Without it, the raw access token is returned.
     */
    suspend fun bearerToken(exchangeClientId: String?): String? {
        val access = freshAccessToken() ?: return null
        if (exchangeClientId.isNullOrBlank()) return access

        val now = System.currentTimeMillis()
        exchanged?.let {
            if (it.clientId == exchangeClientId && now < it.expiresAtMs - 60_000) return it.token
        }

        val tokenEndpoint = store.load()?.authorizationServiceConfiguration?.tokenEndpoint?.toString()
            ?: throw IllegalStateException("No token endpoint known — log in first")

        return withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", exchangeClientId)
                .add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                .add("client_assertion", access)
                // Without scopes the issued token has an empty claim set — the
                // outpost then writes an empty X-authentik-username and the API
                // rejects the request. ak_proxy is the proxy provider's own scope.
                .add("scope", "openid profile email ak_proxy")
                .build()
            val req = Request.Builder().url(tokenEndpoint).post(form).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Token exchange failed: HTTP ${resp.code} ${body.take(200)}")
                }
                val json = JSONObject(body)
                val token = json.getString("access_token")
                val expiresIn = json.optLong("expires_in", 300)
                exchanged = ExchangedToken(exchangeClientId, token, now + expiresIn * 1000)
                token
            }
        }
    }

    /** Returns a valid access token, refreshing if necessary; null if not logged in. */
    suspend fun freshAccessToken(): String? {
        val state = store.load() ?: return null
        if (!state.isAuthorized) return null
        return suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(service) { accessToken, _, ex ->
                store.save(state)
                if (ex != null) cont.resumeWithException(ex)
                else cont.resume(accessToken)
            }
        }
    }

    private suspend fun fetchConfig(issuer: String): AuthorizationServiceConfiguration =
        suspendCancellableCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(issuer)) { config, ex ->
                if (config != null) cont.resume(config)
                else cont.resumeWithException(ex ?: RuntimeException("OIDC discovery failed"))
            }
        }

    private suspend fun exchangeToken(response: AuthorizationResponse): TokenResponse =
        suspendCancellableCoroutine { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { token, ex ->
                if (token != null) cont.resume(token)
                else cont.resumeWithException(ex ?: RuntimeException("Token exchange failed"))
            }
        }
}
