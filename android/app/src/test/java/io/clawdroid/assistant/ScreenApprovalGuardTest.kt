package io.clawdroid.assistant

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScreenApprovalGuardTest {
    private val approved = ApprovedScreen("com.example.messages", "original-digest", 25)

    @Test fun `changed app screen and missing root never dispatch a tap`() {
        var taps = 0
        val changed = listOf(null, approved.copy(packageName = "com.example.bank"),
            approved.copy(fingerprint = "new-recipient"), approved.copy(nodeCount = 26))
        changed.forEach { current ->
            assertNull(ScreenApprovalGuard.execute(approved, current) { ++taps })
        }
        assertNull(ScreenApprovalGuard.execute(null, approved) { ++taps })
        assertEquals(0, taps)
    }

    @Test fun `unchanged approved screen dispatches exactly once`() {
        var taps = 0
        assertEquals(1, ScreenApprovalGuard.execute(approved, approved.copy()) { ++taps })
        assertEquals(1, taps)
    }
}
