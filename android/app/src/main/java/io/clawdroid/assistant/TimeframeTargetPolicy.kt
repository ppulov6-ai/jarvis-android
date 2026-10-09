package io.clawdroid.assistant

/** Deliberately limited to standard chart interval labels; never inferred from numbers alone. */
internal object TimeframeTargetPolicy {
    fun canonical(label: String): String? {
        val match = Regex("^([1-9][0-9]?)\\s*(m|min|мин|м|h|hr|ч|час|d|д|дн|day|w|н|нед|wk|mo|мес)\\.?$", RegexOption.IGNORE_CASE)
            .matchEntire(label.trim()) ?: return null
        val number = match.groupValues[1].toInt()
        val unit = if (match.groupValues[2] == "M") "mo" else when (match.groupValues[2].lowercase()) {
            "m", "min", "мин", "м" -> "m"
            "h", "hr", "ч", "час" -> "h"
            "d", "д", "дн", "day" -> "d"
            "w", "н", "нед", "wk" -> "w"
            else -> "mo"
        }
        val allowed = when (unit) {
            "m" -> setOf(1, 3, 5, 10, 15, 30, 45, 60, 90)
            "h" -> setOf(1, 2, 3, 4, 6, 8, 12)
            "d" -> setOf(1, 2, 3)
            "w" -> setOf(1)
            else -> setOf(1, 3, 6, 12)
        }
        return if (number in allowed) "$number$unit" else null
    }
    fun labels(text: String?, description: String?): String? {
        val nonempty = listOfNotNull(text, description).map { it.trim() }.filter { it.isNotEmpty() }
        if (nonempty.isEmpty()) return null
        val intervals = nonempty.map { canonical(it) ?: return null }
        return intervals.first().takeIf { intervals.all { value -> value == it } }
    }
}
