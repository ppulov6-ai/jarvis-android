package io.clawdroid.core.data.repository

import android.content.Context
import io.clawdroid.core.data.remote.WebSocketClient
import io.clawdroid.core.data.remote.dto.WsIncoming
import io.clawdroid.core.data.remote.dto.WsOutgoing
import io.clawdroid.core.domain.local.LocalCommandHandler
import io.clawdroid.core.domain.local.LocalCommandResult
import io.clawdroid.core.domain.model.ConnectionState
import io.ktor.client.HttpClient
import io.mockk.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AssistantLocalCommandsTest {
    @Test fun `overlay routine emits spoken reply offline without sending images or contacting gateway`() = runTest {
        val socket = mockk<WebSocketClient>(relaxed = true)
        every { socket.incomingMessages } returns MutableSharedFlow<WsOutgoing>()
        every { socket.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        val connection = AssistantConnectionImpl(mockk<HttpClient>(), mockk<Context>(), socket = socket, connectionScope = backgroundScope)
        val local = mockk<LocalCommandHandler>()
        every { local.handles(any()) } returns true
        coEvery { local.execute(any()) } returns LocalCommandResult("Мама: 111")
        connection.localCommands = local
        val reply = async(start = CoroutineStart.UNDISPATCHED) { connection.messages.first() }
        connection.send("найди контакт Мама", listOf("automatic screen capture"))
        assertEquals("Мама: 111", reply.await().content)
        coVerify(exactly = 0) { socket.send(any<WsIncoming>()) }
        verify(exactly = 0) { socket.disconnect() }
    }

    @Test fun `overlay stop cancels local wait and prevents stale reply`() = runTest {
        val socket = mockk<WebSocketClient>(relaxed = true)
        every { socket.incomingMessages } returns MutableSharedFlow<WsOutgoing>()
        every { socket.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        val connection = AssistantConnectionImpl(mockk<HttpClient>(), mockk<Context>(), socket = socket, connectionScope = backgroundScope)
        val local = mockk<LocalCommandHandler>(relaxed = true)
        every { local.handles(any()) } returns true
        val wait = CompletableDeferred<Unit>()
        coEvery { local.execute(any()) } coAnswers { wait.await(); LocalCommandResult("stale") }
        connection.localCommands = local
        val replies = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.messages.collect { replies += it.content } }
        val task = launch { connection.send("позвони Мама") }
        testScheduler.runCurrent()
        connection.stop(); testScheduler.runCurrent(); wait.complete(Unit); task.join()
        assertEquals(emptyList<String>(), replies)
        verify { local.reset() }
        coVerify(exactly = 0) { socket.send(match<WsIncoming> { it.type != "cancel" }) }
    }

    @Test fun `non routine overlay request retains model route and automatic reconnection settings`() = runTest {
        val socket = mockk<WebSocketClient>(relaxed = true)
        every { socket.incomingMessages } returns MutableSharedFlow<WsOutgoing>()
        every { socket.connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        coEvery { socket.send(any<WsIncoming>()) } returns true
        val connection = AssistantConnectionImpl(mockk<HttpClient>(), mockk<Context>(), socket = socket, connectionScope = backgroundScope)
        val local = mockk<LocalCommandHandler>(relaxed = true)
        every { local.handles(any()) } returns false
        connection.localCommands = local
        connection.send("Объясни изображение", listOf("image"))
        coVerify { socket.send(match<WsIncoming> { it.content == "Объясни изображение" && it.images == listOf("image") }) }
        coVerify(exactly = 0) { local.execute(any()) }
        verify(exactly = 0) { socket.disconnect() }
    }
}
