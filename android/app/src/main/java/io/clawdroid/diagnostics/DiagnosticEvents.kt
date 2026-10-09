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
    @Serializable data class Event(val time: Long, val component: String, val code: String, val status: Int? = null, val durationMs: Long? = null, val turnId: String? = null, val call: Int? = null)
    private val events = ArrayDeque<Event>()
    private var store: AtomicFile? = null
    private val components = setOf("openai", "tool", "assistant", "websocket", "agent")
    private val timingCodes = setOf("turn_started", "turn_finished", "turn_cancelled", "llm_started", "llm_success", "llm_error", "llm_cancelled")
    private val codes = setOf("local_contacts", "local_dial", "local_screenshot", "save_failure", "connection_started", "connected", "disconnected", "authentication", "quota", "network", "model", "region", "permission", "rate_limit", "local_gateway_auth", "local_gateway_network", "invalid_request", "context", "upstream", "unknown", "started", "stopped", "success", "error", "cancelled", "screenshot_secure_window", "screenshot_interval", "screenshot_access_denied", "screenshot_capture_error", "screenshot_started", "screenshot_success", "screenshot_error", "screenshot_cancelled", "ui_started", "ui_success", "ui_error", "ui_cancelled", "other_started", "other_success", "other_error", "other_cancelled")
    private val uiCodes = setOf("tap", "swipe", "text", "keyevent", "get_ui_tree").flatMap { action ->
        setOf("started", "success", "error", "cancelled").map { "ui_${action}_$it" }
    }.toSet() + setOf("ui_observation_rejected", "ui_screen_changed")
    @Synchronized fun initialize(context: Context) {
        if (store != null) return
        store = AtomicFile(File(context.filesDir, "test-diagnostics.json"))
        val saved = runCatching { Json.decodeFromString<List<Event>>(store!!.openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }.getOrDefault(emptyList())
        val current = events.toList()
        events.clear()
        (saved + current).filter { valid(it) }.takeLast(200).forEach { events.addLast(it) }
        persist()
    }
    private fun valid(event: Event) = event.component in components && (event.code in codes || event.code in uiCodes || event.code in timingCodes) && (event.status == null || event.status in 100..599) && (event.durationMs == null || event.durationMs in 0..86400000) && (event.turnId == null || event.turnId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) && (event.call == null || event.call in 0..300000)
    @Synchronized fun record(component: String, code: String, status: Int? = null, durationMs: Long? = null, turnId: String? = null, call: Int? = null) {
        val event = Event(System.currentTimeMillis(), component, code, status, durationMs, turnId, call)
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
