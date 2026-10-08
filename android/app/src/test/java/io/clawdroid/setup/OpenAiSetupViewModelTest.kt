package io.clawdroid.setup

import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiSetupViewModelTest {
    @AfterEach fun cleanup() { Dispatchers.resetMain(); unmockkAll() }

    @Test fun `successful connection clears key from UI memory`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = mockk<SetupApiClient>()
        coEvery { api.connectOpenAi("accepted-key") } returns Unit
        val model = OpenAiSetupViewModel(api)
        model.onKeyChange("accepted-key")
        var connected = false
        model.connect { connected = true }
        advanceUntilIdle()
        assertTrue(connected)
        assertEquals("", model.uiState.value.key)
    }

    @Test fun `connection error is visible in Russian and does not navigate`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = mockk<SetupApiClient>()
        coEvery { api.connectOpenAi(any()) } throws OpenAiConnectionException("OpenAI отклонил ключ")
        val model = OpenAiSetupViewModel(api)
        model.onKeyChange("bad-key")
        var connected = false
        model.connect { connected = true }
        advanceUntilIdle()
        assertFalse(connected)
        assertEquals("OpenAI отклонил ключ", model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
    }
    @Test fun `bounded connection timeout clears loading and exposes useful error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = mockk<SetupApiClient>()
        coEvery { api.connectOpenAi(any()) } coAnswers {
            kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.delay(2L) }
        }
        val model = OpenAiSetupViewModel(api)
        model.onKeyChange("test-key")
        var connected = false
        model.connect { connected = true }
        advanceUntilIdle()
        assertFalse(connected)
        assertFalse(model.uiState.value.loading)
        assertTrue(model.uiState.value.error!!.contains("слишком много времени"))
    }
}
