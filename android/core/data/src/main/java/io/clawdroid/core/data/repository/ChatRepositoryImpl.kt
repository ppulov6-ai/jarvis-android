package io.clawdroid.core.data.repository

import android.util.Log
import io.clawdroid.core.domain.local.LocalCommandHandler
import io.clawdroid.core.domain.model.MessageSender
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.clawdroid.core.data.local.ImageFileStorage
import io.clawdroid.core.data.local.dao.MessageDao
import io.clawdroid.core.data.mapper.MessageMapper
import io.clawdroid.core.data.remote.WebSocketClient
import io.clawdroid.core.data.remote.dto.ToolRequest
import io.clawdroid.core.data.remote.dto.WsIncoming
import io.clawdroid.core.domain.model.ChatMessage
import io.clawdroid.core.domain.model.ConnectionState
import io.clawdroid.core.domain.model.ImageAttachment
import io.clawdroid.core.domain.model.MessageStatus
import io.clawdroid.core.domain.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class ChatRepositoryImpl(
    private val webSocketClient: WebSocketClient,
    private val messageDao: MessageDao,
    private val scope: CoroutineScope,
    private val imageFileStorage: ImageFileStorage
) : ChatRepository {

    private val requestLock = Any()
    private val generation = AtomicLong(0)
    private val toolJobs = ConcurrentHashMap<String, Job>()

    private val json = Json { ignoreUnknownKeys = true }
    var localCommands: LocalCommandHandler? = null
    private val localJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    @Volatile private var remoteActive = false

    override fun isLocalCommand(text: String) = localCommands?.handles(text) == true

    var onToolRequest: (suspend (ToolRequest) -> String)? = null

    private val _displayLimit = MutableStateFlow(INITIAL_LOAD_COUNT)
    private val _statusLabel = MutableStateFlow<String?>(null)

    @Suppress("OPT_IN_USAGE")
    override val messages: StateFlow<List<ChatMessage>> =
        _displayLimit.flatMapLatest { limit ->
            messageDao.getRecentMessages(limit)
        }.map { entities ->
            entities.map { MessageMapper.toDomain(it) }
        }.stateIn(scope, SharingStarted.Lazily, emptyList())

    override val connectionState: StateFlow<ConnectionState> = webSocketClient.connectionState

    override val statusLabel: StateFlow<String?> = _statusLabel.asStateFlow()

    init {
        scope.launch {
            webSocketClient.incomingMessages.collect { dto ->
                if (dto.type == "diagnostic") {
                    io.clawdroid.core.data.remote.TimingDiagnostics.accept(dto.content)
                    return@collect
                }
                if (generation.get() > 0L && dto.generation != generation.get() && dto.type != "setup_required") return@collect
                when (dto.type) {
                    "status" -> _statusLabel.value = dto.content
                    "cancelled" -> _statusLabel.value = null
                    "status_end" -> _statusLabel.value = null
                    "tool_cancel" -> {
                        val request = json.decodeFromString<ToolRequest>(dto.content)
                        toolJobs.remove(request.requestId)?.cancel()
                    }
                    "tool_request" -> handleToolRequest(dto.content)
                    "exit", "setup_required" -> { /* ignored in chat mode */ }
                    else -> {
                        _statusLabel.value = null
                        val entity = MessageMapper.toEntity(dto)
                        messageDao.insert(entity)
                    }
                }
            }
        }
    }

    override suspend fun sendMessage(text: String, images: List<ImageAttachment>, inputMode: String?) {
        val next = synchronized(requestLock) {
            localJob.getAndSet(null)?.cancel()
            generation.incrementAndGet()
        }
        toolJobs.values.forEach { it.cancel() }
        val local = localCommands
        if (images.isEmpty() && local?.handles(text) == true) {
            if (remoteActive) webSocketClient.send(WsIncoming(content = "", type = "cancel", generation = next))
            remoteActive = false
            _statusLabel.value = "Выполняю на телефоне"
            messageDao.insert(MessageMapper.toEntity(text, emptyList(), MessageStatus.SENT))
            try {
                val result = coroutineScope {
                    val job = async(start = CoroutineStart.LAZY) { local.execute(text) }
                    val registered = synchronized(requestLock) {
                        if (generation.get() != next) false else {
                            localJob.set(job)
                            true
                        }
                    }
                    if (registered) job.start() else job.cancel()
                    try { job.await() } finally { localJob.compareAndSet(job, null) }
                }
                currentCoroutineContext().ensureActive()
                if (generation.get() != next) return
                val reply = MessageMapper.toEntity(result.content, result.images, MessageStatus.RECEIVED)
                    .copy(sender = MessageSender.AGENT.name)
                messageDao.insert(reply)
            } finally {
                if (generation.get() == next) { _statusLabel.value = null }
            }
            return
        }
        local?.reset()
        remoteActive = true
        val results = images.map { imageFileStorage.saveFromUri(it.uri) }
        val entity = MessageMapper.toEntity(text, results.map { it.imageData }, MessageStatus.SENDING)
        messageDao.insert(entity)
        val wsDto = MessageMapper.toWsIncoming(text, results.map { it.base64 }, inputMode).copy(generation = next)
        val success = webSocketClient.send(wsDto)
        messageDao.update(entity.copy(status = if (success) MessageStatus.SENT.name else MessageStatus.FAILED.name))
        check(success) { "Нет подключения к локальному серверу Джарвиса. Дождитесь подключения и повторите запрос." }
    }

    override fun loadMore() {
        _displayLimit.update { it + PAGE_SIZE }
    }

    override fun connect() {
        webSocketClient.connect()
    }

    override fun stop() {
        val next = synchronized(requestLock) {
            localJob.getAndSet(null)?.cancel()
            localCommands?.reset()
            remoteActive = false
            generation.incrementAndGet()
        }
        toolJobs.values.forEach { it.cancel() }
        toolJobs.clear()
        _statusLabel.value = null
        scope.launch { webSocketClient.send(WsIncoming(content = "", type = "cancel", generation = next)) }
    }

    override fun disconnect() {
        stop()
        webSocketClient.disconnect()
    }

    private fun handleToolRequest(content: String) {
        val request = try { json.decodeFromString<ToolRequest>(content) } catch (e: Exception) { return }
        if (request.generation != generation.get()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (request.generation != generation.get()) return@launch
                val callback = onToolRequest
                val resultContent = if (callback != null) {
                    callback(request)
                } else {
                    "tool request handler not configured"
                }
                val response = WsIncoming(
                    content = resultContent,
                    type = "tool_response",
                    requestId = request.requestId,
                    generation = request.generation
                )
                if (request.generation == generation.get()) webSocketClient.send(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle tool request", e)
            } finally {
                toolJobs.remove(request.requestId)
            }
        }
        toolJobs[request.requestId] = job
        if (request.generation != generation.get()) job.cancel() else job.start()
    }

    companion object {
        private const val TAG = "ChatRepositoryImpl"
        const val INITIAL_LOAD_COUNT = 50
        const val PAGE_SIZE = 30
    }
}
