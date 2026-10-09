package io.clawdroid.core.data.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class TimingDiagnosticsTest {
    private fun event(id: String = UUID.randomUUID().toString(), phase: String = "llm_success", duration: Long = 25, call: Int = 3) = """{"event_id":"$id","turn_id":"00000000-0000-0000-0000-000000000001","phase":"$phase","call":$call,"duration_ms":$duration}"""
    @Test fun `one process collector accepts timing once and rejects arbitrary data`() {
        val received = mutableListOf<TimingDiagnostics.Event>()
        TimingDiagnostics.collector = { received.add(it) }
        try {
            val good = event()
            TimingDiagnostics.accept(good); TimingDiagnostics.accept(good)
            TimingDiagnostics.accept("not JSON")
            TimingDiagnostics.accept(event(phase = "sk_secret"))
            TimingDiagnostics.accept(event(duration = -1))
            TimingDiagnostics.accept(event(duration = 86400001))
            TimingDiagnostics.accept(event(call = 0))
            TimingDiagnostics.accept(event(id = "private text"))
            TimingDiagnostics.accept(event().dropLast(1) + ",\"prompt\":\"secret\"}")
            assertEquals(1, received.size)
            assertEquals(25L, received.single().duration_ms)
        } finally { TimingDiagnostics.collector = null }
    }
    @Test fun `late cancelled call remains correlated and does not enter chat history`() {
        val received = mutableListOf<TimingDiagnostics.Event>()
        TimingDiagnostics.collector = { received.add(it) }
        try {
            TimingDiagnostics.accept(event(phase = "llm_started", duration = 0))
            TimingDiagnostics.accept(event(phase = "llm_cancelled", duration = 17))
            assertEquals(listOf("llm_started", "llm_cancelled"), received.map { it.phase })
            assertEquals(received[0].turn_id, received[1].turn_id)
            assertEquals(received[0].call, received[1].call)
        } finally { TimingDiagnostics.collector = null }
    }
}
