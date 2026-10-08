package io.clawdroid.setup

import io.clawdroid.backend.api.SecretVault
import android.content.Context
import io.clawdroid.backend.api.GatewaySettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.IOException

class SetupApiClient(private val settingsStore: GatewaySettingsStore, context: Context) : Closeable {

    private val vault = SecretVault(context)

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp)

    private val baseUrl: String get() = settingsStore.settings.value.httpBaseUrl
    private val apiKey: String get() = settingsStore.settings.value.apiKey

    suspend fun init(body: JsonObject) {
        val response = client.post("$baseUrl/api/setup/init") {
            if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            val errorMsg = parseError(response.bodyAsText())
            throw IOException("HTTP ${response.status.value}: $errorMsg")
        }
    }

    suspend fun complete(body: JsonObject, overrideApiKey: String? = null) {
        val modelKey = body["llm"]?.jsonObject?.get("api_key")?.jsonPrimitive?.content
        val previous = vault.environment()["CLAWDROID_LLM_API_KEY"].orEmpty()
        if (modelKey != null) vault.saveEnvironment(mapOf("CLAWDROID_LLM_API_KEY" to modelKey))
        try {
            val key = overrideApiKey ?: apiKey
            val response = client.put("$baseUrl/api/setup/complete") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
                if (key.isNotEmpty()) header("Authorization", "Bearer $key")
            }
            if (!response.status.isSuccess()) {
                val errorMsg = parseError(response.bodyAsText())
                throw IOException("HTTP ${response.status.value}: $errorMsg")
            }
        } catch (error: Exception) {
            if (modelKey != null) vault.saveEnvironment(mapOf("CLAWDROID_LLM_API_KEY" to previous))
            throw error
        }
    }

    override fun close() {
        client.close()
    }

    private fun parseError(responseBody: String): String {
        return try {
            json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonPrimitive?.content
                ?: "request failed"
        } catch (_: Exception) {
            "request failed"
        }
    }
}
