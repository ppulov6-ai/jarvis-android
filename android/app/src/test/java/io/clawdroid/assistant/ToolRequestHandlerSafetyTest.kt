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
    private fun controller() = mockk<DeviceController> {
        every { isAvailable } returns true
        every { screenSize() } returns (1080 to 2400)
        every { captureApprovalScreen() } returns approved
        coEvery { tap(any(), any()) } returns true
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

}
