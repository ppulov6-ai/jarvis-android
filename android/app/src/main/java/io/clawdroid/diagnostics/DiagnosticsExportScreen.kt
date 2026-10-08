package io.clawdroid.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticsExportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val archive = DiagnosticsExporter.create(context)
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: throw java.io.IOException("Output unavailable")
                        stream.use { output -> archive.inputStream().use { it.copyTo(output) } }
                    }
                    message = "Тестовый файл сохранён. Пришлите его вместе с описанием проблемы"
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { message = "Не удалось сохранить файл. Выберите другую папку и повторите" }
                finally { busy = false }
            }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack) { Text("Назад") }
            Text("Тестовый файл", style = MaterialTheme.typography.headlineMedium)
            Text("Архив содержит версию приложения, разрешения и последние 200 событий: подключение, действия, остановку и ошибки. Ключи, переписка и снимки экрана в него не входят.")
            Button(onClick = { message = null; save.launch("Jarvisjon-test-${System.currentTimeMillis()}.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Сохранение…" else "Сохранить ZIP на телефон") }
            OutlinedButton(onClick = {
                message = null
                runCatching { DiagnosticsExporter.share(context) }.onFailure { message = "Не удалось отправить архив. Сохраните ZIP на телефон" }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Отправить ZIP") }
            message?.let { Text(it) }
        }
    }
}
