package io.clawdroid.assistant

/** Unknown actions fail closed: a model cannot classify its own action as safe. */
object ActionSafetyPolicy {
    private val safePreparationAndReading = setOf("search_apps", "app_info", "launch_app", "get_ui_tree", "search_contacts", "get_contact_detail", "show_alarms", "show_map", "search_nearby", "get_current_location", "web_search", "open_settings", "list_calendars", "list_events", "get_event", "query_events", "compose_sms", "compose_email", "dial")
    fun requiresConfirmation(action: String): Boolean = action !in safePreparationAndReading
}
