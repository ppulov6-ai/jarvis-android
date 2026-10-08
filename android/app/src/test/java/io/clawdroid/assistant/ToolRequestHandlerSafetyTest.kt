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
    private fun request() = ToolRequest("one", "tap", buildJsonObject { put("x", 10); put("y", 20) })
    private fun controller() = mockk<DeviceController> {
        every { isAvailable } returns true
        coEvery { tap(any(), any()) } returns true
    }
    @Test fun `rejected approval cannot execute a screen action`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        coEvery { ActionConfirmation.ask(any(), any(), any()) } returns false
        val device = controller()
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), {}, {})
        val response = handler.handle(request())
        assertFalse(response.success)
        coVerify(exactly = 1) { ActionConfirmation.ask(any(), request(), any()) }
        coVerify(exactly = 0) { device.tap(any(), any()) }
    }
    @Test fun `Stop while approval is pending cancels action and restores overlay`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(ActionConfirmation)
        val approval = CompletableDeferred<Boolean>()
        coEvery { ActionConfirmation.ask(any(), any(), any()) } coAnswers { approval.await() }
        val device = controller()
        val visibility = mutableListOf<Boolean>()
        val handler = ToolRequestHandler(mockk<Context>(), device, mockk<ScreenshotSource>(), { visibility.add(it) }, {})
        val job = launch { handler.handle(request()) }
        runCurrent()
        job.cancelAndJoin()
        approval.complete(true)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(listOf(false, true), visibility)
        coVerify(exactly = 0) { device.tap(any(), any()) }
    }
}
