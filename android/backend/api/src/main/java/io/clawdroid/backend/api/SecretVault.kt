package io.clawdroid.backend.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Only ciphertext is persisted. The encryption key cannot leave Android Keystore. */
class SecretVault(context: Context) {
    private val preferences = context.getSharedPreferences("jarvis_secret_vault", Context.MODE_PRIVATE)
    private val cipher: SecretCipher by lazy {
        synchronized(KEY_LOCK) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build())
                }.generateKey()
            }
            SecretCipher(key)
        }
    }

    fun encrypt(value: String): String = cipher.encrypt(value)
    fun decrypt(value: String): String = cipher.decrypt(value)

    fun environment(): Map<String, String> = synchronized(KEY_LOCK) {
        val encrypted = preferences.getString("environment", null) ?: return@synchronized emptyMap()
        val values = JSONObject(cipher.decrypt(encrypted))
        values.keys().asSequence().associateWith { values.getString(it) }
    }

    fun saveEnvironment(updates: Map<String, String>) = synchronized(KEY_LOCK) {
        val values = environment().toMutableMap()
        updates.forEach { (name, value) ->
            require(name.startsWith("CLAWDROID_")) { "Недопустимое имя секрета" }
            if (value.isEmpty()) values.remove(name) else values[name] = value
        }
        val encrypted = cipher.encrypt(JSONObject(values as Map<*, *>).toString())
        check(preferences.edit().putString("environment", encrypted).commit()) { "Не удалось сохранить защищённые ключи" }
    }

    /** Migrate credentials left by earlier versions before the backend starts. */
    fun migrateLegacyConfig(file: File) = synchronized(KEY_LOCK) {
        if (!file.exists()) return@synchronized
        val config = JSONObject(file.readText())
        val mcp = config.optJSONObject("tools")?.optJSONObject("mcp")
        mcp?.keys()?.asSequence()?.forEach { name ->
            val server = mcp?.optJSONObject(name)
            check((server?.optJSONObject("env")?.length() ?: 0) == 0 &&
                (server?.optJSONObject("headers")?.length() ?: 0) == 0) {
                "В первой версии Android секреты MCP в env и headers не поддерживаются. Уберите их перед запуском"
            }
        }
        val found = mutableMapOf<String, String>()
        fun visit(node: JSONObject, path: List<String>) {
            for (key in node.keys().asSequence().toList()) {
                val value = node.opt(key)
                if (value is JSONObject) visit(value, path + key)
                else if (key in SECRET_KEYS && value is String && value.isNotEmpty()) {
                    found["CLAWDROID_" + (path + key).joinToString("_").uppercase()] = value
                    node.put(key, "")
                }
            }
        }
        visit(config, emptyList())
        if (found.isEmpty()) return@synchronized
        saveEnvironment(found)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(config.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    companion object {
        private val SECRET_KEYS = setOf("api_key", "token", "bot_token", "app_token", "channel_secret", "channel_access_token", "password", "secret", "authorization")
        private const val KEY_ALIAS = "jarvis.secrets.aes.v1"
        private val KEY_LOCK = Any()
    }
}
