package io.clawdroid.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpenAiSetupState(val key: String = "", val loading: Boolean = false, val error: String? = null)

class OpenAiSetupViewModel(private val api: SetupApiClient) : ViewModel() {
    private val state = MutableStateFlow(OpenAiSetupState())
    val uiState = state.asStateFlow()

    fun onKeyChange(key: String) {
        if (!state.value.loading) state.update { it.copy(key = key, error = null) }
    }

    fun connect(onConnected: () -> Unit) {
        val key = state.value.key.trim()
        if (state.value.loading) return
        if (key.isEmpty()) {
            state.update { it.copy(error = "Введите ключ API OpenAI") }
            return
        }
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                io.clawdroid.diagnostics.DiagnosticEvents.record("openai", "connection_started")
                api.connectOpenAi(key)
                io.clawdroid.diagnostics.DiagnosticEvents.record("openai", "connected")
                state.value = OpenAiSetupState()
                onConnected()
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                io.clawdroid.diagnostics.DiagnosticEvents.record("openai", "network")
                state.update { it.copy(loading = false, error = "Подключение заняло слишком много времени. Проверьте интернет и выгрузите тестовый файл") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                io.clawdroid.diagnostics.DiagnosticEvents.record("openai", (error as? OpenAiConnectionException)?.code ?: "local_gateway_network")
                state.update { it.copy(loading = false, error = (error as? OpenAiConnectionException)?.message ?: "Не удалось связаться со встроенным сервером Джарвиса. Перезапустите приложение и выгрузите тестовый файл") }
            }
        }
    }
}

class OpenAiConnectionException(message: String, val code: String = "unknown") : java.io.IOException(message)
