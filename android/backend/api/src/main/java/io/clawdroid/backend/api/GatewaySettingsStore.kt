package io.clawdroid.backend.api

import kotlinx.coroutines.flow.StateFlow

interface GatewaySettingsStore {
    val settings: StateFlow<GatewaySettings>
    suspend fun awaitLoaded() {}
    suspend fun update(settings: GatewaySettings)
}
