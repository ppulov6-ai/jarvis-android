package io.clawdroid.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel

@Composable
fun OpenAiSetupScreen(onConnected: () -> Unit, onBack: (() -> Unit)? = null, viewModel: OpenAiSetupViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var showKey by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().imePadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (onBack != null) TextButton(onClick = onBack, enabled = !state.loading) { Text("Назад") }
            Text("Подключение OpenAI", style = MaterialTheme.typography.headlineMedium)
            Text("Введите свой ключ API OpenAI. Джарвис проверит подключение и сохранит ключ в защищённом хранилище устройства.")
            OutlinedTextField(
                value = state.key,
                onValueChange = viewModel::onKeyChange,
                label = { Text("Ключ API OpenAI") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Скрыть" else "Показать") } },
                isError = state.error != null
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { viewModel.connect(onConnected) }, enabled = !state.loading && state.key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (state.loading) "Проверка подключения…" else "Подключить")
            }
            Text("Запросы оплачиваются по тарифам OpenAI API. Подписка ChatGPT не оплачивает работу API.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
