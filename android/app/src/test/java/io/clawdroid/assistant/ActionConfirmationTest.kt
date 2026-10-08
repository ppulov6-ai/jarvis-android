package io.clawdroid.assistant

import android.content.Context
import android.content.SharedPreferences
import io.clawdroid.core.data.remote.dto.ToolRequest
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActionConfirmationTest {
    @Test fun `remembered device grant skips activity and can be revoked`() = runTest {
        var granted = false
        val editor = mockk<SharedPreferences.Editor>()
        val prefs = mockk<SharedPreferences>()
        val context = mockk<Context>()
        every { context.getSharedPreferences("device_access_consent", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getBoolean("granted", false) } answers { granted }
        every { prefs.edit() } returns editor
        every { editor.putBoolean("granted", any()) } answers { granted = secondArg(); editor }
        every { editor.commit() } returns true
        assertFalse(ActionConfirmation.isDeviceAccessGranted(context))
        assertTrue(ActionConfirmation.grantDeviceAccess(context))
        for (action in listOf("screenshot", "tap", "swipe", "text", "keyevent")) {
            assertTrue(ActionConfirmation.ask(context, ToolRequest(requestId = action, action = action), {}))
        }
        verify(exactly = 0) { context.startActivity(any()) }
        ActionConfirmation.revokeDeviceAccess(context)
        assertFalse(ActionConfirmation.isDeviceAccessGranted(context))
        assertFalse(ActionConfirmation.canRemember("delete_event"))
        assertFalse(ActionConfirmation.canRemember("unknown"))
    }
}
