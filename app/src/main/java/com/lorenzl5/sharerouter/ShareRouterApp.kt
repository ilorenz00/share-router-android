package com.lorenzl5.sharerouter

import android.app.Application
import android.content.Context
import com.lorenzl5.sharerouter.data.auth.AuthManager
import com.lorenzl5.sharerouter.data.net.Dispatcher
import com.lorenzl5.sharerouter.data.openapi.OpenApiFetcher
import com.lorenzl5.sharerouter.data.settings.SettingsStore
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Tiny manual DI container — avoids pulling in a DI framework for a single-screen app. */
class AppContainer(context: Context) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val settings = SettingsStore(context)
    val auth = AuthManager(context, http)
    val fetcher = OpenApiFetcher(http, json)
    val dispatcher = Dispatcher(http, context.contentResolver)
}

class ShareRouterApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as ShareRouterApp).container
