package io.clawdroid.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
                api.connectOpenAi(key)
                state.value = OpenAiSetupState()
                onConnected()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = (error as? OpenAiConnectionException)?.message ?: "Не удалось подключиться. Проверьте интернет и повторите попытку") }
            }
        }
    }
}

class OpenAiConnectionException(message: String) : java.io.IOException(message)
