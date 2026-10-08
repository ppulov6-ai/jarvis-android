package io.clawdroid.diagnostics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiagnosticEventsTest {
    @Test fun `diagnostics rejects arbitrary payload and invalid HTTP codes`() {
        assertThrows(IllegalArgumentException::class.java) { DiagnosticEvents.record("openai", "sk_secret") }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticEvents.record("user_prompt", "started") }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticEvents.record("openai", "network", 999) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticEvents.record("tool", "success", durationMs = -1) }
    }
    @Test fun `journal keeps only latest 200 structured events`() {
        repeat(205) { DiagnosticEvents.record("tool", "ui_success", durationMs = it.toLong()) }
        val events = DiagnosticEvents.snapshot()
        assertEquals(200, events.size)
        assertEquals(5L, events.first().durationMs)
        assertEquals(204L, events.last().durationMs)
    }
}
