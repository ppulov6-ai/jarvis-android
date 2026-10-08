package io.clawdroid.assistant

/** Kept locally; the screen digest never enters the model's context. */
data class ApprovedScreen(val packageName: String, val fingerprint: String, val nodeCount: Int) {
    val description: String get() = "Приложение: $packageName. Элементов на экране: $nodeCount."
}

object ScreenApprovalGuard {
    fun matches(approved: ApprovedScreen?, current: ApprovedScreen?): Boolean =
        approved != null && current != null && approved == current

    inline fun <T> execute(approved: ApprovedScreen?, current: ApprovedScreen?, action: () -> T): T? {
        if (!matches(approved, current)) return null
        return action()
    }
}
