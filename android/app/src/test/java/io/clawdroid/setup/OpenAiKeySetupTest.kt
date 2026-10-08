package io.clawdroid.setup

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class OpenAiKeySetupTest {
    private class Secrets(var key: String = "previous-working-key") : OpenAiSecretStore {
        val writes = mutableListOf<String>()
        override fun readKey() = key
        override fun writeKey(key: String) { writes.add(key); this.key = key }
    }
    private class Transport(val configured: Boolean, val calls: MutableList<String>) : OpenAiSetupTransport {
        var validationFailure: Exception? = null
        var saveFailure: Exception? = null
        override suspend fun validate(key: String): Boolean {
            calls.add("validate")
            validationFailure?.let { throw it }
            return configured
        }
        override suspend fun initialize() { calls.add("initialize") }
        override suspend fun applyKey(key: String, configured: Boolean) {
            calls.add(if (configured) "replace" else "complete")
            saveFailure?.let { throw it }
        }
    }

    @Test fun `first setup validates before initialization and saves only accepted key`() = runTest {
        val calls = mutableListOf<String>()
        val secrets = Secrets()
        OpenAiKeySetup(Transport(false, calls), secrets).connect("accepted-key")
        assertEquals(listOf("validate", "initialize", "complete"), calls)
        assertEquals("accepted-key", secrets.key)
    }

    @Test fun `replacement does not reinitialize existing configuration`() = runTest {
        val calls = mutableListOf<String>()
        val secrets = Secrets()
        OpenAiKeySetup(Transport(true, calls), secrets).connect("replacement-key")
        assertEquals(listOf("validate", "replace"), calls)
        assertEquals("replacement-key", secrets.key)
    }

    @Test fun `authentication quota and network errors cannot overwrite working key`() = runTest {
        for (code in listOf("authentication", "quota", "network", "model")) {
            val calls = mutableListOf<String>()
            val secrets = Secrets()
            val transport = Transport(true, calls).apply { validationFailure = IOException(code) }
            try { OpenAiKeySetup(transport, secrets).connect("invalid-key"); throw AssertionError("must reject") } catch (_: IOException) {}
            assertEquals(listOf("validate"), calls)
            assertEquals("previous-working-key", secrets.key)
            assertTrue(secrets.writes.isEmpty())
        }
    }

    @Test fun `failed config save restores encrypted vault key`() = runTest {
        val secrets = Secrets()
        val transport = Transport(true, mutableListOf()).apply { saveFailure = IOException("failed") }
        try { OpenAiKeySetup(transport, secrets).connect("new-key"); throw AssertionError("must reject") } catch (_: IOException) {}
        assertEquals(listOf("new-key", "previous-working-key"), secrets.writes)
        assertEquals("previous-working-key", secrets.key)
    }
}
