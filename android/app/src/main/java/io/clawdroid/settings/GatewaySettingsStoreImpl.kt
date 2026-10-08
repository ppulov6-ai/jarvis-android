package io.clawdroid.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.clawdroid.backend.api.GatewaySettings
import io.clawdroid.backend.api.SecretVault
import io.clawdroid.backend.api.GatewaySettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.gatewayDataStore by preferencesDataStore(name = "gateway_settings")

class GatewaySettingsStoreImpl(
    private val context: Context,
    scope: CoroutineScope,
) : GatewaySettingsStore {

    private val loaded = CompletableDeferred<GatewaySettings>()
    private val vault = SecretVault(context)

    private object Keys {
        val HTTP_PORT = intPreferencesKey("http_port")
        val API_KEY = stringPreferencesKey("api_key")
        val ENCRYPTED_API_KEY = stringPreferencesKey("api_key_encrypted_v1")
    }

    override val settings: StateFlow<GatewaySettings> =
        context.gatewayDataStore.data.onStart {
            context.gatewayDataStore.edit { prefs ->
                prefs[Keys.API_KEY]?.let { legacy ->
                    if (prefs[Keys.ENCRYPTED_API_KEY] == null) prefs[Keys.ENCRYPTED_API_KEY] = vault.encrypt(legacy)
                    prefs.remove(Keys.API_KEY)
                }
            }
        }.map { prefs ->
            val result = GatewaySettings(
                httpPort = prefs[Keys.HTTP_PORT] ?: DEFAULT.httpPort,
                apiKey = prefs[Keys.ENCRYPTED_API_KEY]?.let(vault::decrypt) ?: prefs[Keys.API_KEY] ?: DEFAULT.apiKey,
            )
            loaded.complete(result)
            result
        }.catch { error ->
            loaded.completeExceptionally(error)
            throw error
        }.stateIn(scope, SharingStarted.Eagerly, GatewaySettings(httpPort = -1))

    companion object {
        private val DEFAULT = GatewaySettings()
    }

    override suspend fun awaitLoaded() {
        loaded.await()
        settings.first { it.httpPort > 0 }
    }

    override suspend fun update(settings: GatewaySettings) {
        context.gatewayDataStore.edit { prefs ->
            prefs[Keys.HTTP_PORT] = settings.httpPort
            prefs[Keys.ENCRYPTED_API_KEY] = vault.encrypt(settings.apiKey)
            prefs.remove(Keys.API_KEY)
        }
    }
}
