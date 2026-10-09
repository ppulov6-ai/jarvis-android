package io.clawdroid.assistant

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.Callable

class UiObservationRegistryTest {
    private val screen = ApprovedScreen("com.example.editor", "digest", 10)
    @Test fun `an observed node is usable once`() {
        val registry = UiObservationRegistry()
        val id = registry.record(screen, setOf("0", "0.1"))
        assertFalse(registry.consume(id, screen, "0.9"))
        assertTrue(registry.consume(id, screen, "0.1"))
        assertFalse(registry.consume(id, screen, "0.1"))
    }
    @Test fun `changed expired missing and replaced observations are rejected`() {
        var time = 100L
        val registry = UiObservationRegistry(clock = { time })
        val id = registry.record(screen, setOf("0"))
        assertFalse(registry.consume(null, screen))
        assertFalse(registry.consume(id, null))
        assertFalse(registry.consume(id, screen.copy(packageName = "com.other.app")))
        assertFalse(registry.consume(id, screen.copy(fingerprint = "changed")))
        time = 60101L
        assertFalse(registry.consume(id, screen))
        val newer = registry.record(screen, setOf("0"))
        assertFalse(registry.consume(id, screen))
        registry.clear()
        assertFalse(registry.consume(newer, screen))
    }
    @Test fun `parallel requests cannot consume an observation twice`() {
        val registry = UiObservationRegistry()
        val id = registry.record(screen, setOf("0"))
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((0 until 100).map { Callable { registry.consume(id, screen) } })
            assertEquals(1, results.count { it.get() })
        } finally { pool.shutdownNow() }
    }
    @Test fun `coordinates must be finite and inside the full display`() {
        val size = 1080 to 2400
        assertTrue(coordinatesInDisplay(0f, 2399f, size))
        assertFalse(coordinatesInDisplay(1080f, 20f, size))
        assertFalse(coordinatesInDisplay(-1f, 20f, size))
        assertFalse(coordinatesInDisplay(Float.NaN, 20f, size))
        assertFalse(coordinatesInDisplay(20f, Float.POSITIVE_INFINITY, size))
        assertFalse(coordinatesInDisplay(1f, 1f, null))
    }
}
