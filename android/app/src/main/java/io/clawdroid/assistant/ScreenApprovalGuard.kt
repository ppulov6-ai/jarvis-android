package io.clawdroid.assistant

/** Kept locally; the screen digest never enters the model's context. */
data class ApprovedScreen(val packageName: String, val fingerprint: String, val nodeCount: Int, val structureFingerprint: String = fingerprint, val windowId: Int = -1, val contextFingerprint: String = fingerprint) {
    val description: String get() = "Приложение: $packageName. Элементов на экране: $nodeCount."
}

object ScreenApprovalGuard {
    fun matches(approved: ApprovedScreen?, current: ApprovedScreen?): Boolean =
        approved != null && current != null && approved == current

    fun matchesStructure(approved: ApprovedScreen?, current: ApprovedScreen?): Boolean =
        approved != null && current != null && approved.packageName == current.packageName &&
            approved.windowId == current.windowId && approved.nodeCount == current.nodeCount &&
            approved.structureFingerprint == current.structureFingerprint

    fun matchesContext(approved: ApprovedScreen?, current: ApprovedScreen?): Boolean =
        matchesStructure(approved, current) && approved!!.contextFingerprint == current!!.contextFingerprint

    inline fun <T> execute(approved: ApprovedScreen?, current: ApprovedScreen?, action: () -> T): T? {
        if (!matches(approved, current)) return null
        return action()
    }
}

/** Narrow decimal quote form: integers, dates, phone numbers and words stay in context. */
internal fun isLiveNumericQuote(value: String): Boolean = value.length in 1..120 &&
    Regex("^[+-]?[$€£₽¥]?[0-9]+[.,][0-9]{1,8}%?$").matches(value.trim())
