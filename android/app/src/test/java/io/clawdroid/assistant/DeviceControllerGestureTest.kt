package io.clawdroid.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import io.mockk.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceControllerGestureTest {
    private val controller = DeviceController()
    private val service = mockk<AccessibilityService>()
    private val gesture = mockk<GestureDescription>()
    @Test fun `rejected dispatch returns immediately without a callback`() = runTest {
        every { service.dispatchGesture(gesture, any(), null) } returns false
        assertFalse(controller.dispatchGesture(service, gesture))
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun `completed and cancelled gestures produce distinct results`() = runTest {
        every { service.dispatchGesture(gesture, any(), null) } answers {
            secondArg<AccessibilityService.GestureResultCallback>().onCompleted(gesture)
            true
        }
        assertTrue(controller.dispatchGesture(service, gesture))
        every { service.dispatchGesture(gesture, any(), null) } answers {
            secondArg<AccessibilityService.GestureResultCallback>().onCancelled(gesture)
            true
        }
        assertFalse(controller.dispatchGesture(service, gesture))
    }
    @Test fun `missing callback is bounded by eight seconds`() = runTest {
        every { service.dispatchGesture(gesture, any(), null) } returns true
        assertFalse(controller.dispatchGesture(service, gesture))
        assertEquals(8000L, testScheduler.currentTime)
    }
    @Test fun `Stop cancellation cannot be revived by a late callback`() = runTest {
        val callback = slot<AccessibilityService.GestureResultCallback>()
        every { service.dispatchGesture(gesture, capture(callback), null) } returns true
        val job = launch { controller.dispatchGesture(service, gesture) }
        runCurrent()
        job.cancel()
        job.join()
        callback.captured.onCompleted(gesture)
        assertTrue(job.isCancelled)
    }
}
