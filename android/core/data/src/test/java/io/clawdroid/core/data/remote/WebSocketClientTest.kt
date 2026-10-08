package io.clawdroid.core.data.remote

import android.content.Context
import io.clawdroid.core.data.remote.dto.WsIncoming
import io.ktor.client.HttpClient
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class WebSocketClientTest {
    @Test
    fun `send without connection reports failure instead of silently dropping message`() = runTest {
        val client = WebSocketClient(
            client = mockk<HttpClient>(), scope = backgroundScope,
            clientId = "test", context = mockk<Context>()
        )
        assertFalse(client.send(WsIncoming(content = "find contact")))
    }
}
