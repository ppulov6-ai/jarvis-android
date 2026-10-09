package io.clawdroid.assistant

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceControllerTargetTest {
    private val controller = DeviceController()
    private fun node(action: Int) = mockk<AccessibilityNodeInfo> {
        every { packageName } returns "com.example.editor"
        every { isVisibleToUser } returns true
        every { isEnabled } returns true
        every { isPassword } returns false
        every { isEditable } returns true
        every { actionList } returns listOf(mockk { every { id } returns action })
        every { parent } returns null
    }
    @AfterEach fun cleanup() { unmockkAll() }
    @Test fun `click acts on the selected node and never retries elsewhere`() {
        val target = node(AccessibilityNodeInfo.ACTION_CLICK)
        every { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns false
        assertFalse(controller.clickNode(target, "com.example.editor"))
        verify(exactly = 1) { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
    @Test fun `nonclickable label uses only its own clickable ancestor`() {
        val label = node(AccessibilityNodeInfo.ACTION_SET_TEXT)
        every { label.isEditable } returns false
        val button = node(AccessibilityNodeInfo.ACTION_CLICK)
        every { label.parent } returns button
        every { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        assertTrue(controller.clickNode(label, "com.example.editor"))
        verify(exactly = 1) { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
    @Test fun `foreign disabled and password nodes cannot be clicked`() {
        val target = node(AccessibilityNodeInfo.ACTION_CLICK)
        assertFalse(controller.clickNode(target, "com.other.app"))
        every { target.isEnabled } returns false
        assertFalse(controller.clickNode(target, "com.example.editor"))
        every { target.isEnabled } returns true
        every { target.isPassword } returns true
        assertFalse(controller.clickNode(target, "com.example.editor"))
        verify(exactly = 0) { target.performAction(any()) }
    }
    private fun textNode(): AccessibilityNodeInfo {
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putCharSequence(any(), any()) } just Runs
        return node(AccessibilityNodeInfo.ACTION_SET_TEXT).also {
            every { it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returns true
            every { it.refresh() } returns true
            every { it.text } returns "тест"
        }
    }
    @Test fun `direct text is verified in the selected editable field`() = runTest {
        val target = textNode()
        assertEquals(DeviceController.TextOutcome.VERIFIED, controller.writeText(target, "тест", "com.example.editor"))
        verify(exactly = 1) { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) }
        verify { anyConstructed<Bundle>().putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "тест") }
    }
    @Test fun `accepted text without visible confirmation is not reported as success or repeated`() = runTest {
        val target = textNode()
        every { target.text } returns "другое"
        assertEquals(DeviceController.TextOutcome.UNVERIFIED, controller.writeText(target, "тест", "com.example.editor"))
        verify(exactly = 1) { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) }
    }
    @Test fun `missing unsupported or password field is rejected`() = runTest {
        assertEquals(DeviceController.TextOutcome.REJECTED, controller.writeText(null, "тест", "com.example.editor"))
        val target = node(AccessibilityNodeInfo.ACTION_CLICK)
        assertEquals(DeviceController.TextOutcome.REJECTED, controller.writeText(target, "тест", "com.example.editor"))
        every { target.isPassword } returns true
        assertEquals(DeviceController.TextOutcome.REJECTED, controller.writeText(target, "тест", "com.example.editor"))
        verify(exactly = 0) { target.performAction(any(), any()) }
    }
}
