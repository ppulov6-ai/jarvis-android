package io.clawdroid.assistant

import java.util.UUID

/** Local, short-lived, one-use observations; no screen content leaves this registry. */
class UiObservationRegistry(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val makeId: () -> String = { UUID.randomUUID().toString() }
) {
    private data class Observation(val id: String, val screen: ApprovedScreen, val paths: Set<String>, val targets: Map<String, String>, val timeframePaths: Set<String>, val created: Long)
    private var latest: Observation? = null
    @Synchronized fun record(screen: ApprovedScreen, paths: Set<String>, targets: Map<String, String> = emptyMap(), timeframePaths: Set<String> = emptySet()): String {
        val id = makeId()
        latest = Observation(id, screen, paths.toSet(), targets.toMap(), timeframePaths.toSet(), clock())
        return id
    }
    @Synchronized fun consume(id: String?, screen: ApprovedScreen?, path: String? = null, target: String? = null, timeframe: Boolean = false): Boolean {
        val current = latest ?: return false
        val age = clock() - current.created
        val expectedTarget = path?.let { current.targets[it] }
        val screenMatches = if (path in current.timeframePaths && timeframe)
            ScreenApprovalGuard.matchesContext(current.screen, screen) else ScreenApprovalGuard.matches(current.screen, screen)
        val matches = screenMatches && (expectedTarget == null || (target != null && target == expectedTarget))
        if (id != current.id || age !in 0..60_000 || !matches ||
            (path != null && path !in current.paths)) return false
        latest = null
        return true
    }
    @Synchronized fun clear() { latest = null }
}

internal fun coordinatesInDisplay(x: Float, y: Float, size: Pair<Int, Int>?): Boolean =
    size != null && x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < size.first && y < size.second
