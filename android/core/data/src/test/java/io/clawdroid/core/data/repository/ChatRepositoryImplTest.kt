package io.clawdroid.core.data.repository

import io.clawdroid.core.data.local.ImageFileStorage
import io.clawdroid.core.data.local.dao.MessageDao
import io.clawdroid.core.data.local.entity.MessageEntity
import io.clawdroid.core.data.remote.WebSocketClient
import io.clawdroid.core.data.remote.dto.WsIncoming
import io.clawdroid.core.data.remote.dto.WsOutgoing
import io.clawdroid.core.domain.model.ConnectionState
import io.clawdroid.core.domain.model.ImageAttachment
import io.clawdroid.core.domain.model.ImageData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryImplTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repoScope: kotlinx.coroutines.CoroutineScope
    private lateinit var incomingMessages: MutableSharedFlow<WsOutgoing>
    private lateinit var connectionStateFlow: MutableStateFlow<ConnectionState>
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var messageDao: MessageDao
    private lateinit var imageFileStorage: ImageFileStorage
    private lateinit var repository: ChatRepositoryImpl

    @BeforeEach
    fun setup() {
        repoScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        incomingMessages = MutableSharedFlow()
        connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)

        webSocketClient = mockk<WebSocketClient>(relaxed = true)
        every { webSocketClient.incomingMessages } returns incomingMessages
        every { webSocketClient.connectionState } returns connectionStateFlow

        messageDao = mockk<MessageDao>(relaxed = true)
        every { messageDao.getRecentMessages(any()) } returns flowOf(emptyList())

        imageFileStorage = mockk<ImageFileStorage>()

        repository = ChatRepositoryImpl(webSocketClient, messageDao, repoScope, imageFileStorage)
    }

    @AfterEach
    fun tearDown() {
        repoScope.cancel()
    }

    @Test
    fun `connect delegates to webSocketClient`() {
        repository.connect()

        verify { webSocketClient.connect() }
    }

    @Test
    fun `disconnect delegates to webSocketClient`() {
        repository.disconnect()

        verify { webSocketClient.disconnect() }
    }

    @Test
    fun `loadMore increases display limit`() {
        // Trigger collection so the Lazily-started StateFlow subscribes to DAO
        repoScope.launch { repository.messages.collect {} }

        verify { messageDao.getRecentMessages(ChatRepositoryImpl.INITIAL_LOAD_COUNT) }

        repository.loadMore()
        verify { messageDao.getRecentMessages(ChatRepositoryImpl.INITIAL_LOAD_COUNT + ChatRepositoryImpl.PAGE_SIZE) }

        repository.loadMore()
        verify { messageDao.getRecentMessages(ChatRepositoryImpl.INITIAL_LOAD_COUNT + 2 * ChatRepositoryImpl.PAGE_SIZE) }
    }

    @Test
    fun `connectionState returns webSocketClient connectionState`() {
        assertEquals(connectionStateFlow, repository.connectionState)
    }

    @Test
    fun `local contacts work while disconnected without websocket or image upload`() = runTest {
        val local = mockk<io.clawdroid.core.domain.local.LocalCommandHandler>()
        every { local.handles("найди контакт Мама") } returns true
        coEvery { local.execute(any()) } returns io.clawdroid.core.domain.local.LocalCommandResult("Мама: 111")
        repository.localCommands = local
        repository.sendMessage("найди контакт Мама", emptyList(), "voice")
        coVerify(exactly = 0) { webSocketClient.send(any<WsIncoming>()) }
        coVerify(exactly = 0) { imageFileStorage.saveFromUri(any()) }
        coVerify { messageDao.insert(match { it.sender == "AGENT" && it.content == "Мама: 111" && it.status == "RECEIVED" }) }
        verify(exactly = 0) { webSocketClient.disconnect() }
    }

    @Test
    fun `recognized local failure does not fall back to the model`() = runTest {
        val local = mockk<io.clawdroid.core.domain.local.LocalCommandHandler>()
        every { local.handles(any()) } returns true
        coEvery { local.execute(any()) } returns io.clawdroid.core.domain.local.LocalCommandResult("Нет разрешения")
        repository.localCommands = local
        repository.sendMessage("найди контакт Мама")
        coVerify(exactly = 0) { webSocketClient.send(any<WsIncoming>()) }
    }

    @Test
    fun `explicitly attached image keeps analysis path instead of being silently discarded`() = runTest {
        val local = mockk<io.clawdroid.core.domain.local.LocalCommandHandler>(relaxed = true)
        every { local.handles(any()) } returns true
        repository.localCommands = local
        coEvery { imageFileStorage.saveFromUri(any()) } returns ImageFileStorage.SaveResult(ImageData("/test.jpg", 10, 10), "image")
        coEvery { webSocketClient.send(any<WsIncoming>()) } returns true
        repository.sendMessage("найди контакт Мама", listOf(ImageAttachment("content://test")))
        coVerify(exactly = 0) { local.execute(any()) }
        coVerify { webSocketClient.send(match<WsIncoming> { it.content == "найди контакт Мама" && it.images == listOf("image") }) }
    }

    @Test
    fun `stop cancels local permission wait and suppresses stale result`() = runTest {
        val local = mockk<io.clawdroid.core.domain.local.LocalCommandHandler>(relaxed = true)
        val wait = kotlinx.coroutines.CompletableDeferred<Unit>()
        every { local.handles(any()) } returns true
        coEvery { local.execute(any()) } coAnswers { wait.await(); io.clawdroid.core.domain.local.LocalCommandResult("stale") }
        repository.localCommands = local
        val task = launch { repository.sendMessage("позвони Мама") }
        testScheduler.runCurrent()
        repository.stop()
        testScheduler.runCurrent()
        wait.complete(Unit)
        task.join()
        coVerify(exactly = 0) { messageDao.insert(match { it.content == "stale" }) }
        verify { local.reset() }
        coVerify(exactly = 0) { webSocketClient.send(match<WsIncoming> { it.type != "cancel" }) }
    }

    @Nested
    inner class SendMessage {

        @Test
        fun `sendMessage inserts entity and sends via websocket`() = runTest {
            val imageData = ImageData("/path/img.jpg", 100, 200)
            val saveResult = ImageFileStorage.SaveResult(imageData, "base64data")
            coEvery { imageFileStorage.saveFromUri("content://photo") } returns saveResult
            coEvery { webSocketClient.send(any<WsIncoming>()) } returns true

            repository.sendMessage("Hello", listOf(ImageAttachment("content://photo")))

            coVerify { messageDao.insert(any()) }
            coVerify { webSocketClient.send(any<WsIncoming>()) }
            coVerify { messageDao.update(match { it.status == "SENT" }) }
        }

        @Test
        fun `sendMessage marks as FAILED when websocket send fails`() = runTest {
            coEvery { webSocketClient.send(any<WsIncoming>()) } returns false

            var sendFailed = false
            try {
                repository.sendMessage("fail")
            } catch (expected: IllegalStateException) {
                sendFailed = true
            }
            assertEquals(true, sendFailed)

            coVerify { messageDao.update(match { it.status == "FAILED" }) }
        }

        @Test
        fun `sendMessage with no images sends empty base64 list`() = runTest {
            coEvery { webSocketClient.send(any<WsIncoming>()) } returns true

            repository.sendMessage("text only")

            val wsSlot = slot<WsIncoming>()
            coVerify { webSocketClient.send(capture(wsSlot)) }
            assertNull(wsSlot.captured.images)
        }
    }

    @Nested
    inner class IncomingMessageHandling {

        @Test
        fun `late diagnostic survives Stop without displaying or persisting a message`() = runTest {
            val received = mutableListOf<io.clawdroid.core.data.remote.TimingDiagnostics.Event>()
            io.clawdroid.core.data.remote.TimingDiagnostics.collector = { received.add(it) }
            try {
                repository.stop()
                val content = """{"event_id":"${java.util.UUID.randomUUID()}","turn_id":"00000000-0000-0000-0000-000000000001","phase":"llm_cancelled","call":3,"duration_ms":17}"""
                incomingMessages.emit(WsOutgoing(content = content, type = "diagnostic", generation = 0))
                assertEquals(1, received.size)
                coVerify(exactly = 0) { messageDao.insert(any()) }
                assertNull(repository.statusLabel.value)
            } finally { io.clawdroid.core.data.remote.TimingDiagnostics.collector = null }
        }

        @Test
        fun `status message updates statusLabel`() = runTest {
            incomingMessages.emit(WsOutgoing(content = "Thinking...", type = "status"))

            assertEquals("Thinking...", repository.statusLabel.value)
        }

        @Test
        fun `status_end clears statusLabel`() = runTest {
            incomingMessages.emit(WsOutgoing(content = "Thinking...", type = "status"))
            incomingMessages.emit(WsOutgoing(content = "", type = "status_end"))

            assertNull(repository.statusLabel.value)
        }

        @Test
        fun `normal message inserts entity and clears status`() = runTest {
            val entitySlot = slot<MessageEntity>()
            coEvery { messageDao.insert(capture(entitySlot)) } returns Unit

            incomingMessages.emit(WsOutgoing(content = "Hello!", type = null))

            assertEquals("Hello!", entitySlot.captured.content)
            assertEquals("AGENT", entitySlot.captured.sender)
            assertEquals("RECEIVED", entitySlot.captured.status)
        }

        @Test
        fun `exit type is ignored`() = runTest {
            incomingMessages.emit(WsOutgoing(content = "bye", type = "exit"))

            coVerify(exactly = 0) { messageDao.insert(any()) }
        }

        @Test
        fun `setup_required type is ignored`() = runTest {
            incomingMessages.emit(WsOutgoing(content = "", type = "setup_required"))

            coVerify(exactly = 0) { messageDao.insert(any()) }
        }

        @Test
        fun `tool_request invokes callback and sends response`() = runTest {
            val toolRequestJson = """{"request_id":"r1","action":"screenshot"}"""
            repository.onToolRequest = { "screenshot_result" }
            coEvery { webSocketClient.send(any<WsIncoming>()) } returns true

            incomingMessages.emit(WsOutgoing(content = toolRequestJson, type = "tool_request"))

            coVerify {
                webSocketClient.send(match<WsIncoming> {
                    it.type == "tool_response" && it.requestId == "r1" && it.content == "screenshot_result"
                })
            }
        }

        @Test
        fun `tool_request without callback sends fallback message`() = runTest {
            val toolRequestJson = """{"request_id":"r2","action":"tap"}"""
            repository.onToolRequest = null
            coEvery { webSocketClient.send(any<WsIncoming>()) } returns true

            incomingMessages.emit(WsOutgoing(content = toolRequestJson, type = "tool_request"))

            coVerify {
                webSocketClient.send(match<WsIncoming> {
                    it.content == "tool request handler not configured"
                })
            }
        }
    }
}
