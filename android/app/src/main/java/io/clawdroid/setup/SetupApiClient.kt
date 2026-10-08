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
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException

class SetupApiClient(private val settingsStore: GatewaySettingsStore, context: Context) : Closeable {

    init { io.clawdroid.diagnostics.DiagnosticEvents.initialize(context) }

    private val vault = SecretVault(context)

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        engine { config {
            connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        } }
    }

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

    suspend fun connectOpenAi(key: String) = kotlinx.coroutines.withTimeout(60000L) {
        settingsStore.awaitLoaded()
        awaitLocalGateway()
        val transport = object : OpenAiSetupTransport {
            override suspend fun validate(key: String): Boolean {
                val response = client.post("$baseUrl/api/openai/validate") {
                    header("Authorization", "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("api_key", key) }.toString())
                }
                val raw = response.bodyAsText()
                if (!response.status.isSuccess()) throw openAiError(raw, response.status.value)
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
                if (!response.status.isSuccess()) {
                    io.clawdroid.diagnostics.DiagnosticEvents.record("openai", "save_failure", response.status.value)
                    throw OpenAiConnectionException("Ключ проверен OpenAI, но встроенный сервер не сохранил настройки. Повторите попытку и выгрузите тестовый файл", "save_failure")
                }
            }
        }
        val secrets = object : OpenAiSecretStore {
            override fun readKey() = vault.environment()["CLAWDROID_LLM_API_KEY"].orEmpty()
            override fun writeKey(key: String) = vault.saveEnvironment(mapOf("CLAWDROID_LLM_API_KEY" to key))
        }
        OpenAiKeySetup(transport, secrets).connect(key)
    }

    /** Retry only the local readiness probe; never repeat billable Responses requests. */
    private suspend fun awaitLocalGateway() {
        repeat(10) { attempt ->
            try {
                val ready = kotlinx.coroutines.withTimeout(2000L) {
                    val response = client.get("$baseUrl/api/config/schema") {
                        header("Authorization", "Bearer $apiKey")
                    }
                    response.bodyAsText()
                    response.status.value
                }
                if (ready == 200) return
                if (ready == 401 || ready == 403) {
                    throw OpenAiConnectionException("Встроенный сервер отклонил подключение. Перезапустите Джарвис и выгрузите тестовый файл", "local_gateway_auth")
                }
            } catch (error: OpenAiConnectionException) { throw error }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { /* No raw network errors or response bodies enter diagnostics. */ }
            if (attempt < 9) kotlinx.coroutines.delay(200)
        }
        throw OpenAiConnectionException("Встроенный сервер Джарвиса не запустился. Перезапустите приложение и выгрузите тестовый файл", "local_gateway_network")
    }

    private fun openAiError(raw: String, status: Int): OpenAiConnectionException {
        val code = runCatching { json.parseToJsonElement(raw).jsonObject["error_code"]?.jsonPrimitive?.content }.getOrNull()
        val failure = OpenAiFailure.from(code, status)
        io.clawdroid.diagnostics.DiagnosticEvents.record("openai", failure.code, status)
        return OpenAiConnectionException(failure.message, failure.code)
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
