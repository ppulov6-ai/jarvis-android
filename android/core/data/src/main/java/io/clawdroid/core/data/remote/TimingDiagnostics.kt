package io.clawdroid.core.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One process collector, shared by main and assistant sockets. No payload text. */
object TimingDiagnostics {
    @Serializable data class Event(val event_id: String, val turn_id: String, val phase: String, val call: Int, val duration_ms: Long)
    private val json = Json { ignoreUnknownKeys = false }
    private val phases = setOf("turn_started", "turn_finished", "turn_cancelled", "llm_started", "llm_success", "llm_error", "llm_cancelled")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val seen = LinkedHashSet<String>()
    @Volatile var collector: ((Event) -> Unit)? = null
    fun accept(content: String) {
        if (content.length > 512) return
        val event = runCatching { json.decodeFromString<Event>(content) }.getOrNull() ?: return
        if (event.phase !in phases || !uuid.matches(event.event_id) || !uuid.matches(event.turn_id) || event.duration_ms !in 0..86400000 || event.call !in 0..300000) return
        if ((event.phase.startsWith("turn_") && event.call != 0) || (event.phase.startsWith("llm_") && event.call == 0)) return
        val callback = collector ?: return
        synchronized(seen) {
            if (!seen.add(event.event_id)) return
            while (seen.size > 400) seen.remove(seen.first())
        }
        callback(event)
    }
}
