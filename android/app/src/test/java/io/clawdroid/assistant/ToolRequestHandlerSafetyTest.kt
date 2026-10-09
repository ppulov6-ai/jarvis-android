package io.clawdroid.assistant

import android.content.Context
import io.clawdroid.core.data.remote.dto.ToolRequest
import io.clawdroid.feature.chat.voice.ScreenshotSource
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolRequestHandlerSafetyTest {
    @AfterEach fun cleanup() {
        unmockkAll()
        Dispatchers.resetMain()
    }
    private fun request() = ToolRequest(requestId = "one", action = "tap", params = buildJsonObject { put("x", 10); put("y", 20); put("observation_id", "observation-one") })
    private val approved = ApprovedScreen("com.example.messages", "screen-one", 25)
    private fun controller(): DeviceController {
        every { ActionConfirmation.isDeviceAccessGranted(any()) } returns false
        return mockk<DeviceController> {
        every { isAvailable } returns true
        every { screenSize() } returns (1080 to 2400)
        every { resolveNode(any(), any()) } returns null
        every { captureApprovalScreen() } returns approved
        every { captureTarget(any<String>(), any()) } returns "target-one"
        every { captureTarget(any<android.view.accessibility.AccessibilityNodeInfo>(), any()) } returns "target-one"
        every { isTimeframeTarget(any<String>(), any()) } returns false
        every { isTimeframeTarget(any<android.view.accessibility.AccessibilityNodeInfo>(), any()) } returns false
        coEvery { tap(any(), any()) } returns true
        }
    }
    @Test fun `rejected approval cannot execute a screen action`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        coEvery { ActionConfirmation.ask(any(), any(), any(), any()) } returns false
        val device = controller()
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), {}, {})
        val response = handler.handle(request())
        assertFalse(response.success)
        coVerify(exactly = 1) { ActionConfirmation.ask(any(), request(), any(), any()) }
        coVerify(exactly = 0) { device.tap(any(), any()) }
    }
    @Test fun `Stop while approval is pending cancels action and restores overlay`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        val approval = CompletableDeferred<Boolean>()
        coEvery { ActionConfirmation.ask(any(), any(), any(), any()) } coAnswers { approval.await() }
        val device = controller()
        val visibility = mutableListOf<Boolean>()
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), { visibility.add(it) }, {})
        val job = launch { handler.handle(request()) }
        advanceTimeBy(150)
        runCurrent()
        coVerify(exactly = 1) { ActionConfirmation.ask(any(), request(), any(), any()) }
        job.cancelAndJoin()
        approval.complete(true)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(listOf(false, true, false, true), visibility)
        coVerify(exactly = 0) { device.tap(any(), any()) }
    }
    @Test fun `approved tap is blocked when the foreground screen changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        coEvery { ActionConfirmation.ask(any(), any(), any(), any()) } returns true
        val device = controller()
        every { device.captureApprovalScreen() } returnsMany listOf(approved, approved.copy(fingerprint = "another-recipient"))
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), {}, {})
        val response = handler.handle(request())
        assertFalse(response.success)
        assertTrue(response.error.orEmpty().contains("Экран приложения изменился"))
        coVerify(exactly = 1) { ActionConfirmation.ask(any(), request(), any(), approved.description) }
        coVerify(exactly = 0) { device.tap(any(), any()) }
    }

    @Test fun `approved tap executes on the unchanged foreground screen`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        coEvery { ActionConfirmation.ask(any(), any(), any(), any()) } returns true
        val device = controller()
        val observations = UiObservationRegistry(makeId = { "observation-one" })
        observations.record(approved, setOf("0"))
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), {}, {}, observations = observations)
        assertTrue(handler.handle(request()).success)
        coVerify(exactly = 1) { device.tap(10f, 20f) }
    }

    @Test fun `observed node is clicked without coordinate guessing and cannot be reused`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        coEvery { ActionConfirmation.ask(any(), any(), any(), any()) } returns true
        val device = controller()
        val node = mockk<android.view.accessibility.AccessibilityNodeInfo>()
        every { device.resolveNode("0.2", approved.packageName) } returns node
        every { device.clickNode(node, approved.packageName) } returns true
        val observations = UiObservationRegistry(makeId = { "observation-one" })
        observations.record(approved, setOf("0.2"))
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), {}, {}, observations = observations)
        val req = ToolRequest(requestId = "node", action = "tap", params = buildJsonObject {
            put("observation_id", "observation-one"); put("node_id", "0.2")
        })
        assertTrue(handler.handle(req).success)
        assertFalse(handler.handle(req).success)
        verify(exactly = 1) { device.clickNode(node, approved.packageName) }
        coVerify(exactly = 0) { device.tap(any(), any()) }
    }
    @Test fun `unobserved target is rejected without clicking another node`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        coEvery { ActionConfirmation.ask(any(), any(), any(), any()) } returns true
        val device = controller()
        val observations = UiObservationRegistry(makeId = { "observation-one" })
        observations.record(approved, setOf("0.2"))
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), {}, {}, observations = observations)
        val req = ToolRequest(requestId = "wrong", action = "tap", params = buildJsonObject {
            put("observation_id", "observation-one"); put("node_id", "0.9")
        })
        assertFalse(handler.handle(req).success)
        verify(exactly = 0) { device.clickNode(any(), any()) }
        coVerify(exactly = 0) { device.tap(any(), any()) }
    }
    @Test fun `unverified text never becomes a successful or repeated write`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        coEvery { ActionConfirmation.ask(any(), any(), any(), any()) } returns true
        val device = controller()
        coEvery { device.inputTextAt("тест", approved.packageName, "0.2", any()) } returns DeviceController.TextOutcome.UNVERIFIED
        val observations = UiObservationRegistry(makeId = { "observation-one" })
        observations.record(approved, setOf("0.2"))
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), {}, {}, observations = observations)
        val req = ToolRequest(requestId = "text", action = "text", params = buildJsonObject {
            put("observation_id", "observation-one"); put("node_id", "0.2"); put("text", "тест")
        })
        val response = handler.handle(req)
        assertFalse(response.success)
        assertTrue(response.error.orEmpty().contains("Не повторяйте ввод"))
        assertFalse(handler.handle(req).success)
        coVerify(exactly = 1) { device.inputTextAt(any(), any(), any(), any()) }
    }

    @Test fun `live price changes allow observed timeframe target without restoration polling`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        coEvery { ActionConfirmation.ask(any(), any(), any(), any()) } returns true
        val device = controller()
        every { ActionConfirmation.isDeviceAccessGranted(any()) } returns true
        val stable = approved.copy(structureFingerprint = "unchanged-layout", windowId = 4)
        every { device.captureApprovalScreen() } returns stable.copy(fingerprint = "price-101")
        val node = mockk<android.view.accessibility.AccessibilityNodeInfo>()
        every { device.resolveNode("0.2", approved.packageName) } returns node
        every { device.clickNode(node, approved.packageName) } returns true
        val observations = UiObservationRegistry(makeId = { "observation-one" })
        observations.record(stable.copy(fingerprint = "price-100"), setOf("0.2"), mapOf("0.2" to "target-one"), setOf("0.2"))
        every { device.isTimeframeTarget(any<String>(), any()) } returns true
        every { device.isTimeframeTarget(any<android.view.accessibility.AccessibilityNodeInfo>(), any()) } returns true
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), {}, {}, observations = observations)
        val req = ToolRequest(requestId = "timeframe", action = "tap", params = buildJsonObject {
            put("observation_id", "observation-one"); put("node_id", "0.2")
        })
        val started = testScheduler.currentTime
        assertTrue(handler.handle(req).success)
        assertEquals(300L, testScheduler.currentTime - started)
        verify(exactly = 2) { device.captureApprovalScreen() }
        verify(exactly = 1) { device.clickNode(node, approved.packageName) }
        coVerify(exactly = 0) { device.tap(any(), any()) }
    }

    @Test fun `changed target label is rejected even when layout stays unchanged`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        coEvery { ActionConfirmation.ask(any(), any(), any(), any()) } returns true
        val device = controller()
        every { ActionConfirmation.isDeviceAccessGranted(any()) } returns true
        every { device.captureTarget(any<String>(), any()) } returns "changed-target"
        every { device.captureTarget(any<android.view.accessibility.AccessibilityNodeInfo>(), any()) } returns "changed-target"
        every { device.resolveNode("0.2", approved.packageName) } returns mockk()
        val observations = UiObservationRegistry(makeId = { "observation-one" })
        observations.record(approved, setOf("0.2"), mapOf("0.2" to "target-one"))
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), {}, {}, observations = observations)
        val req = ToolRequest(requestId = "changed", action = "tap", params = buildJsonObject {
            put("observation_id", "observation-one"); put("node_id", "0.2")
        })
        assertFalse(handler.handle(req).success)
        verify(exactly = 0) { device.clickNode(any(), any()) }
    }

    @Test fun `changed chat recipient blocks both send tap and input despite same target`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        coEvery { ActionConfirmation.ask(any(), any(), any(), any()) } returns true
        val device = controller()
        every { ActionConfirmation.isDeviceAccessGranted(any()) } returns true
        val original = approved.copy(structureFingerprint = "same-layout")
        every { device.captureApprovalScreen() } returns original.copy(fingerprint = "different-recipient")
        every { device.resolveNode("0.2", approved.packageName) } returns mockk()
        for (action in listOf("tap", "text")) {
            val observations = UiObservationRegistry(makeId = { "observation-one" })
            observations.record(original, setOf("0.2"), mapOf("0.2" to "target-one"))
            val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), {}, {}, observations = observations)
            val req = ToolRequest(requestId = action, action = action, params = buildJsonObject {
                put("observation_id", "observation-one"); put("node_id", "0.2")
                if (action == "text") put("text", "сообщение")
            })
            assertFalse(handler.handle(req).success)
        }
        verify(exactly = 0) { device.clickNode(any(), any()) }
        coVerify(exactly = 0) { device.inputTextAt(any(), any(), any(), any()) }
    }

}
