package io.clawdroid.assistant

import java.util.UUID

/** Local, short-lived, one-use observations; no screen content leaves this registry. */
class UiObservationRegistry(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val makeId: () -> String = { UUID.randomUUID().toString() }
) {
    private data class Observation(val id: String, val screen: ApprovedScreen, val paths: Set<String>, val created: Long)
    private var latest: Observation? = null
    @Synchronized fun record(screen: ApprovedScreen, paths: Set<String>): String {
        val id = makeId()
        latest = Observation(id, screen, paths.toSet(), clock())
        return id
    }
    @Synchronized fun consume(id: String?, screen: ApprovedScreen?, path: String? = null): Boolean {
        val current = latest ?: return false
        val age = clock() - current.created
        if (id != current.id || age !in 0..60_000 || !ScreenApprovalGuard.matches(current.screen, screen) ||
            (path != null && path !in current.paths)) return false
        latest = null
        return true
    }
    @Synchronized fun clear() { latest = null }
}

internal fun coordinatesInDisplay(x: Float, y: Float, size: Pair<Int, Int>?): Boolean =
    size != null && x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < size.first && y < size.second
