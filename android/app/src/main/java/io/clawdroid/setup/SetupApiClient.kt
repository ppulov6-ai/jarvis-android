package io.clawdroid.setup

import io.clawdroid.backend.api.SecretVault
import android.content.Context
import io.clawdroid.backend.api.GatewaySettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
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

    suspend fun connectOpenAi(key: String) {
        settingsStore.awaitLoaded()
        val transport = object : OpenAiSetupTransport {
            override suspend fun validate(key: String): Boolean {
                val response = client.post("$baseUrl/api/openai/validate") {
                    header("Authorization", "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("api_key", key) }.toString())
                }
                val raw = response.bodyAsText()
                if (!response.status.isSuccess()) throw openAiError(raw)
                val result = json.parseToJsonElement(raw).jsonObject
                check(result["valid"]?.jsonPrimitive?.booleanOrNull == true) { "Проверка подключения не подтверждена" }
                return result["configured"]?.jsonPrimitive?.booleanOrNull
                    ?: throw OpenAiConnectionException("Сервер не сообщил состояние настройки. Обновите приложение")
            }
            override suspend fun initialize() {
                val settings = settingsStore.settings.value
                init(buildJsonObject {
                    put("gateway", buildJsonObject { put("port", settings.httpPort); put("api_key", settings.apiKey) })
                })
            }
            override suspend fun applyKey(key: String, configured: Boolean) {
                val route = if (configured) "config" else "setup/complete"
                val response = client.put("$baseUrl/api/$route") {
                    header("Authorization", "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("llm", buildJsonObject {
                            put("model", "openai/gpt-5.4-mini")
                            put("api_key", key)
                            put("base_url", "https://api.openai.com/v1")
                        })
                    }.toString())
                }
                if (!response.status.isSuccess()) throw OpenAiConnectionException("Не удалось сохранить подключение. Повторите попытку")
            }
        }
        val secrets = object : OpenAiSecretStore {
            override fun readKey() = vault.environment()["CLAWDROID_LLM_API_KEY"].orEmpty()
            override fun writeKey(key: String) = vault.saveEnvironment(mapOf("CLAWDROID_LLM_API_KEY" to key))
        }
        OpenAiKeySetup(transport, secrets).connect(key)
    }

    private fun openAiError(raw: String): OpenAiConnectionException {
        val code = runCatching { json.parseToJsonElement(raw).jsonObject["error_code"]?.jsonPrimitive?.content }.getOrNull()
        val message = when (code) {
            "authentication" -> "OpenAI отклонил ключ. Проверьте его или создайте новый"
            "quota" -> "В OpenAI API закончились средства или достигнут лимит. Проверьте баланс и ограничения"
            "network" -> "Не удалось связаться с OpenAI. Проверьте интернет и повторите попытку"
            "model" -> "Для этого ключа недоступна модель Джарвиса. Проверьте доступ в OpenAI"
            "invalid_request" -> "Не удалось проверить ключ OpenAI. Проверьте настройки проекта API"
            else -> "OpenAI временно недоступен. Повторите попытку позже"
        }
        return OpenAiConnectionException(message)
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
