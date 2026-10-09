package io.clawdroid.assistant

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceControllerInputTest {
    private val controller = DeviceController()
    private val field = mockk<AccessibilityNodeInfo> {
        every { packageName } returns "com.example.editor"
        every { isVisibleToUser } returns true
        every { isEnabled } returns true
        every { isEditable } returns true
        every { isPassword } returns false
        every { actionList } returns listOf(mockk { every { id } returns AccessibilityNodeInfo.ACTION_SET_TEXT })
    }
    private val root = mockk<AccessibilityNodeInfo> {
        every { packageName } returns "com.example.editor"
        every { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns field
    }
    @Test fun `focused editable field is found in active root`() {
        assertSame(field, controller.findInputField(root, "ru.pulat.jarvis", "com.example.editor"))
    }
    @Test fun `own or unexpected application cannot receive text`() {
        assertNull(controller.findInputField(root, "com.example.editor", null))
        assertNull(controller.findInputField(root, "ru.pulat.jarvis", "com.other.app"))
    }
    @Test fun `missing focus does not choose an arbitrary field`() {
        every { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns null
        assertNull(controller.findInputField(root, "ru.pulat.jarvis", null))
    }
    @Test fun `password hidden disabled and noneditable fields are rejected`() {
        every { field.isPassword } returns true
        assertNull(controller.findInputField(root, "ru.pulat.jarvis", null))
        every { field.isPassword } returns false
        every { field.isVisibleToUser } returns false
        assertNull(controller.findInputField(root, "ru.pulat.jarvis", null))
        every { field.isVisibleToUser } returns true
        every { field.isEnabled } returns false
        assertNull(controller.findInputField(root, "ru.pulat.jarvis", null))
        every { field.isEnabled } returns true
        every { field.isEditable } returns false
        assertNull(controller.findInputField(root, "ru.pulat.jarvis", null))
    }
    @Test fun `unsupported or foreign node is rejected`() {
        every { field.actionList } returns emptyList()
        assertNull(controller.findInputField(root, "ru.pulat.jarvis", null))
        every { field.actionList } returns listOf(mockk { every { id } returns AccessibilityNodeInfo.ACTION_SET_TEXT })
        every { field.packageName } returns "com.other.app"
        assertNull(controller.findInputField(root, "ru.pulat.jarvis", null))
    }
}
