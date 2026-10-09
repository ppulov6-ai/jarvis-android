package io.clawdroid.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceControllerSnapshotTest {
    @AfterEach fun cleanup() { unmockkAll() }
    private fun node(label: String, click: Boolean = false): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { packageName } returns "com.example.exchange"
        every { className } returns "android.widget.TextView"
        every { text } returns label
        every { isVisibleToUser } returns true
        every { isEnabled } returns true
        every { windowId } returns 7
        every { childCount } returns 0
        every { parent } returns null
        every { actionList } returns if (click) listOf(mockk { every { id } returns AccessibilityNodeInfo.ACTION_CLICK }) else emptyList()
    }
    @Test fun `price tick changes full digest while timeframe target and layout remain stable`() {
        mockkConstructor(Rect::class)
        every { anyConstructed<Rect>().toString() } returns "bounds"
        val root = node("")
        val price = node("100.00")
        val timeframe = node("15m", true)
        every { root.childCount } returns 2
        every { root.getChild(0) } returns price
        every { root.getChild(1) } returns timeframe
        val service = mockk<AccessibilityService> {
            every { packageName } returns "ru.pulat.jarvis"
            every { rootInActiveWindow } returns root
        }
        val controller = DeviceController().apply { setService(service) }
        val before = controller.captureApprovalScreen()!!
        val target = controller.captureTarget("0.1", before.packageName)!!
        every { price.text } returns "100.01"
        val after = controller.captureApprovalScreen()!!
        assertFalse(ScreenApprovalGuard.matches(before, after))
        assertTrue(ScreenApprovalGuard.matchesStructure(before, after))
        assertTrue(ScreenApprovalGuard.matchesContext(before, after))
        assertEquals(target, controller.captureTarget("0.1", before.packageName))
        val registry = UiObservationRegistry()
        val observation = registry.record(before, setOf("0.1"), mapOf("0.1" to target), setOf("0.1"))
        assertTrue(registry.consume(observation, after, "0.1", controller.captureTarget("0.1", before.packageName), controller.isTimeframeTarget("0.1", before.packageName)))
        every { root.text } returns "ETHUSDT"
        assertFalse(ScreenApprovalGuard.matchesContext(before, controller.captureApprovalScreen()))
        every { root.text } returns ""
        every { timeframe.text } returns "1h"
        assertNotEquals(target, controller.captureTarget("0.1", before.packageName))
        every { timeframe.isEnabled } returns false
        assertNull(controller.captureTarget("0.1", before.packageName))
    }
    @Test fun `click ancestor labels geometry window and capabilities participate in identity`() {
        mockkConstructor(Rect::class)
        every { anyConstructed<Rect>().toString() } returns "bounds"
        val button = node("period", true)
        val label = node("15m")
        every { label.parent } returns button
        val controller = DeviceController()
        val target = controller.captureTarget(label, "com.example.exchange")!!
        every { button.text } returns "trade"
        assertNotEquals(target, controller.captureTarget(label, "com.example.exchange"))
        every { button.text } returns "period"
        every { button.windowId } returns 8
        assertNotEquals(target, controller.captureTarget(label, "com.example.exchange"))
        every { button.windowId } returns 7
        every { anyConstructed<Rect>().toString() } returns "moved"
        assertNotEquals(target, controller.captureTarget(label, "com.example.exchange"))
        every { anyConstructed<Rect>().toString() } returns "bounds"
        every { button.actionList } returns emptyList()
        assertNull(controller.captureTarget(label, "com.example.exchange"))
    }
    @Test fun `re-resolved text field must retain expected target before any write`() = runTest {
        mockkConstructor(Rect::class)
        every { anyConstructed<Rect>().toString() } returns "bounds"
        val root = node("")
        val field = node("original")
        every { field.isEditable } returns true
        every { field.actionList } returns listOf(mockk { every { id } returns AccessibilityNodeInfo.ACTION_SET_TEXT })
        every { root.childCount } returns 1
        every { root.getChild(0) } returns field
        val service = mockk<AccessibilityService> {
            every { packageName } returns "ru.pulat.jarvis"
            every { rootInActiveWindow } returns root
        }
        val controller = DeviceController().apply { setService(service) }
        val expected = controller.captureTarget("0.0", "com.example.exchange")!!
        every { field.text } returns "changed"
        assertEquals(DeviceController.TextOutcome.REJECTED,
            controller.inputTextAt("message", "com.example.exchange", "0.0", expected))
        verify(exactly = 0) { field.performAction(any(), any()) }
    }
}
