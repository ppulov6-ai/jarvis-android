package io.clawdroid.core.data.repository

import android.content.Context
import android.util.Log
import io.clawdroid.core.domain.local.LocalCommandHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.ktor.client.HttpClient
import io.clawdroid.core.data.remote.WebSocketClient
import io.clawdroid.core.data.remote.dto.ToolRequest
import io.clawdroid.core.data.remote.dto.WsIncoming
import io.clawdroid.core.domain.model.AssistantMessage
import io.clawdroid.core.domain.model.ConnectionState
import io.clawdroid.core.domain.repository.AssistantConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

typealias ToolRequestCallback = suspend (ToolRequest) -> String

class AssistantConnectionImpl(
    private val httpClient: HttpClient,
    private val context: Context,
    apiKeyProvider: () -> String = { "" },
    socket: WebSocketClient? = null,
    connectionScope: CoroutineScope? = null
) : AssistantConnection {

    private val scope = connectionScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientId = UUID.randomUUID().toString()
    private val wsClient = socket ?: WebSocketClient(httpClient, scope, clientId, "assistant", context = context, apiKeyProvider = apiKeyProvider)
    private val requestLock = Any()
    private val generation = AtomicLong(0)
    private val toolJobs = ConcurrentHashMap<String, Job>()

    private val json = Json { ignoreUnknownKeys = true }

    private val _messages = MutableSharedFlow<AssistantMessage>(extraBufferCapacity = 64)
    override val messages: SharedFlow<AssistantMessage> = _messages.asSharedFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    override val statusText: StateFlow<String?> = _statusText.asStateFlow()

    override val connectionState: StateFlow<ConnectionState> = wsClient.connectionState

    var localCommands: LocalCommandHandler? = null
    private val localJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    @Volatile private var remoteActive = false
    override fun isLocalCommand(text: String) = localCommands?.handles(text) == true

    var onToolRequest: ToolRequestCallback? = null
    var onExit: ((String?) -> Unit)? = null

    init {
        scope.launch {
            wsClient.incomingMessages.collect { dto ->
                if (generation.get() > 0L && dto.generation != generation.get() && dto.type != "setup_required") return@collect
                when (dto.type) {
                    "status" -> _statusText.value = dto.content
                    "cancelled" -> _statusText.value = null
                    "status_end" -> _statusText.value = null
                    "tool_cancel" -> {
                        val request = json.decodeFromString<ToolRequest>(dto.content)
                        toolJobs.remove(request.requestId)?.cancel()
                    }
                    "tool_request" -> handleToolRequest(dto.content)
                    "exit" -> onExit?.invoke(dto.content)
                    else -> {
                        _messages.emit(AssistantMessage(content = dto.content, type = dto.type))
                    }
                }
            }
        }
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
                    "error: tool request handler not configured"
                }

                val response = WsIncoming(
                    content = resultContent,
                    type = "tool_response",
                    requestId = request.requestId,
                    generation = request.generation
                )
                if (request.generation == generation.get()) wsClient.send(response)
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

    override fun connect(wsUrl: String) {
        wsClient.wsUrl = wsUrl
        wsClient.connect()
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
        _statusText.value = null
        scope.launch { wsClient.send(WsIncoming(content = "", type = "cancel", generation = next)) }
    }

    override fun disconnect() {
        stop()
        wsClient.disconnect()
        scope.cancel()
    }

    override suspend fun send(text: String, images: List<String>, inputMode: String) {
        val next = synchronized(requestLock) {
            localJob.getAndSet(null)?.cancel()
            generation.incrementAndGet()
        }
        toolJobs.values.forEach { it.cancel() }
        val local = localCommands
        if (local?.handles(text) == true) {
            if (remoteActive) wsClient.send(WsIncoming(content = "", type = "cancel", generation = next))
            remoteActive = false
            _statusText.value = "Выполняю на телефоне"
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
                if (generation.get() == next) _messages.emit(AssistantMessage(content = result.content))
            } finally {
                if (generation.get() == next) { _statusText.value = null }
            }
            return
        }
        local?.reset()
        remoteActive = true
        val dto = WsIncoming(
            generation = next,
            content = text,
            images = images.ifEmpty { null },
            inputMode = inputMode
        )
        check(wsClient.send(dto)) { "Нет подключения к локальному серверу Джарвиса. Дождитесь подключения и повторите запрос." }
    }

    companion object {
        private const val TAG = "AssistantConnectionImpl"
    }
}
