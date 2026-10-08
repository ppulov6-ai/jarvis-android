package io.clawdroid.diagnostics

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/** No free text input: only constrained categories and numeric metadata. */
object DiagnosticEvents {
    @Serializable data class Event(val time: Long, val component: String, val code: String, val status: Int? = null, val durationMs: Long? = null)
    private val events = ArrayDeque<Event>()
    private var store: AtomicFile? = null
    private val components = setOf("openai", "tool", "assistant", "websocket")
    private val codes = setOf("save_failure", "connection_started", "connected", "disconnected", "authentication", "quota", "network", "model", "region", "permission", "rate_limit", "local_gateway_auth", "local_gateway_network", "invalid_request", "context", "upstream", "unknown", "started", "stopped", "success", "error", "cancelled", "screenshot_started", "screenshot_success", "screenshot_error", "screenshot_cancelled", "ui_started", "ui_success", "ui_error", "ui_cancelled", "other_started", "other_success", "other_error", "other_cancelled")
    @Synchronized fun initialize(context: Context) {
        if (store != null) return
        store = AtomicFile(File(context.filesDir, "test-diagnostics.json"))
        val saved = runCatching { Json.decodeFromString<List<Event>>(store!!.openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }.getOrDefault(emptyList())
        val current = events.toList()
        events.clear()
        (saved + current).filter { valid(it) }.takeLast(200).forEach { events.addLast(it) }
        persist()
    }
    private fun valid(event: Event) = event.component in components && event.code in codes && (event.status == null || event.status in 100..599) && (event.durationMs == null || event.durationMs in 0..86400000)
    @Synchronized fun record(component: String, code: String, status: Int? = null, durationMs: Long? = null) {
        val event = Event(System.currentTimeMillis(), component, code, status, durationMs)
        require(valid(event)) { "Invalid diagnostic category" }
        events.addLast(event)
        while (events.size > 200) events.removeFirst()
        persist()
    }
    private fun persist() {
        val target = store ?: return
        runCatching {
            val stream = target.startWrite()
            try { stream.write(Json.encodeToString(events.toList()).toByteArray()); target.finishWrite(stream) }
            catch (error: Exception) { target.failWrite(stream); throw error }
        }
    }
    @Synchronized fun snapshot(): List<Event> = events.toList()
}
