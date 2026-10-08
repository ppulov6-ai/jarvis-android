package io.clawdroid.assistant

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OrbPlacementTest {
    @Test fun `expanding at bottom right keeps Stop reachable`() {
        val position = clampOrbPosition(1000, 2000, 320, 480, 1080, 2400, 72, 96)
        assertEquals(760, position.x)
        assertEquals(1824, position.y)
        assertTrue(position.y + 480 <= 2400 - 96)
    }
    @Test fun `landscape controls fit visible viewport and can be scrolled`() {
        val size = fitOrbSize(960, 1440, 2200, 800)
        assertEquals(OrbSize(960, 800), size)
        val position = clampOrbPosition(2200, 1400, size.width, size.height, 2400, 1080, 80, 200)
        assertTrue(position.y >= 80)
        assertTrue(position.y + size.height <= 1080 - 200)
    }
    @Test fun `drag cannot move orb outside screen or under status bar`() {
        assertEquals(OrbPosition(0, 72), clampOrbPosition(-100, -100, 96, 118, 1080, 2400, 72, 96))
    }
}
