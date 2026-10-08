package io.clawdroid.setup

interface OpenAiSetupTransport {
    /** Validates a real Responses API call, and reports whether configuration exists. */
    suspend fun validate(key: String): Boolean
    suspend fun initialize()
    suspend fun applyKey(key: String, configured: Boolean)
}

interface OpenAiSecretStore {
    fun readKey(): String
    fun writeKey(key: String)
}

/** A rejected key never replaces the working key; failed saves restore the vault. */
class OpenAiKeySetup(private val transport: OpenAiSetupTransport, private val secrets: OpenAiSecretStore) {
    suspend fun connect(key: String) {
        require(key.isNotBlank()) { "Введите ключ API OpenAI" }
        val configured = transport.validate(key)
        val previous = secrets.readKey()
        secrets.writeKey(key)
        try {
            if (!configured) transport.initialize()
            transport.applyKey(key, configured)
        } catch (error: Exception) {
            secrets.writeKey(previous)
            throw error
        }
    }
}
