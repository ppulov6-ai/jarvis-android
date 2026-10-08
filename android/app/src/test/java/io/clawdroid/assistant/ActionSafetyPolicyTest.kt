package io.clawdroid.assistant

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionSafetyPolicyTest {
    @Test fun `indirect routes cannot bypass confirmation`() {
        listOf("tap", "swipe", "text", "keyevent", "intent", "broadcast", "open_url", "screenshot", "compose_sms", "compose_email", "dial", "delete_event", "update_event", "add_contact", "clipboard_read", "future_unknown_action").forEach {
            assertTrue(ActionSafetyPolicy.requiresConfirmation(it), it)
        }
    }
    @Test fun `ordinary app discovery remains available`() {
        listOf("search_apps", "app_info", "launch_app", "get_ui_tree").forEach {
            assertFalse(ActionSafetyPolicy.requiresConfirmation(it), it)
        }
    }
}
