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
fun OpenAiSetupScreen(onConnected: () -> Unit, onBack: (() -> Unit)? = null, onExport: (() -> Unit)? = null, viewModel: OpenAiSetupViewModel = koinViewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var exportError by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(false) }
    val activity = remember(context) {
        var current: android.content.Context = context
        while (current is android.content.ContextWrapper && current !is android.app.Activity) current = current.baseContext
        current as? android.app.Activity
    }
    // Screenshots remain available while the key is masked; protect visible secrets only.
    DisposableEffect(activity, showKey) {
        if (showKey) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
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
            OutlinedButton(onClick = {
                showKey = false
                if (onExport != null) onExport() else try { io.clawdroid.diagnostics.DiagnosticsExporter.share(context) }
                catch (_: Exception) { exportError = "Не удалось выгрузить файл. Повторите попытку." }
            }, enabled = !state.loading) { Text("Выгрузить тестовый файл") }
            exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Запросы оплачиваются по тарифам OpenAI API. Подписка ChatGPT не оплачивает работу API.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
