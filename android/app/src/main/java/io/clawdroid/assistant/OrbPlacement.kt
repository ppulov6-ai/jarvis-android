package io.clawdroid.assistant

/** Keep every control reachable when the orb expands near a screen edge. */
data class OrbPosition(val x: Int, val y: Int)
fun clampOrbPosition(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int, topInset: Int, bottomInset: Int): OrbPosition {
    val maxX = (screenWidth - width).coerceAtLeast(0)
    val minY = topInset.coerceIn(0, screenHeight.coerceAtLeast(0))
    val maxY = (screenHeight - bottomInset - height).coerceAtLeast(minY)
    return OrbPosition(x.coerceIn(0, maxX), y.coerceIn(minY, maxY))
}

/** A landscape viewport may be shorter than the expanded controls. */
data class OrbSize(val width: Int, val height: Int)
fun fitOrbSize(requestedWidth: Int, requestedHeight: Int, availableWidth: Int, availableHeight: Int): OrbSize =
    OrbSize(requestedWidth.coerceIn(1, availableWidth.coerceAtLeast(1)), requestedHeight.coerceIn(1, availableHeight.coerceAtLeast(1)))
