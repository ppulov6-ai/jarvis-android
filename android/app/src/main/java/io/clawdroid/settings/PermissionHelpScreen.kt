package io.clawdroid.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.clawdroid.assistant.ActionConfirmation
import io.clawdroid.assistant.DeviceController
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionHelpScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller: DeviceController = koinInject()
    var active by remember { mutableStateOf(controller.isAvailable) }
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var mic by remember { mutableStateOf(false) }
    var deviceAccess by remember { mutableStateOf(ActionConfirmation.isDeviceAccessGranted(context)) }
    var launchError by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        deviceAccess = ActionConfirmation.isDeviceAccessGranted(context)
        active = controller.isAvailable
        overlay = Settings.canDrawOverlays(context)
        mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    fun open(action: String, withPackage: Boolean = false) {
        try {
            context.startActivity(Intent(action).apply {
                if (withPackage) data = Uri.parse("package:${context.packageName}")
            })
            launchError = null
        } catch (_: Exception) {
            launchError = "Не удалось открыть этот раздел. Откройте его вручную в настройках телефона."
        }
    }
    DisposableEffect(lifecycleOwner) {
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Разрешения Джарвиса") }, navigationIcon = {
            TextButton(onClick = onNavigateBack) { Text("Назад") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Управление экраном: ${if (deviceAccess) "разрешено без повторных запросов" else "потребуется подтверждение"}")
            if (deviceAccess) {
                OutlinedButton(onClick = { ActionConfirmation.revokeDeviceAccess(context); refresh() }) { Text("Отозвать разрешение управления экраном") }
            }
            Text("Состояние разрешений", style = MaterialTheme.typography.titleMedium)
            Text("Специальные возможности: ${if (active) "служба работает" else "служба не подключена"}")
            Text("Поверх приложений: ${if (overlay) "разрешено" else "не разрешено"}")
            Text("Микрофон: ${if (mic) "разрешён" else "не разрешён"}")
            Text("Специальные возможности позволяют читать содержимое экрана, делать снимки и выполнять нажатия, ввод текста и жесты по вашим командам. Данные экрана могут отправляться подключённой модели для выполнения задачи.")
            Text("Если Android пишет «Доступ для приложения запрещён»", style = MaterialTheme.typography.titleMedium)
            Text("1. Откройте сведения о Джарвисе кнопкой ниже.\n2. Нажмите меню ⋮ в правом верхнем углу.\n3. Выберите «Разрешить ограниченные настройки» и подтвердите действие на телефоне.\n4. Вернитесь в специальные возможности, откройте «Установленные приложения» → «Джарвис» и включите службу.")
            Button(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }, modifier = Modifier.fillMaxWidth()) { Text("Сведения о Джарвисе") }
            Button(onClick = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text("Специальные возможности") }
            Text("Это системное ограничение для некоторых приложений, установленных из APK. Приложение не может снять его самостоятельно. Если пункта нет, проверьте ограничения рабочего профиля или администратора устройства. Названия пунктов зависят от версии Android.")
            OutlinedButton(onClick = { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить поверх приложений") }
            OutlinedButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }, modifier = Modifier.fillMaxWidth()) { Text("Настроить микрофон") }
            Text("Микрофон включается в разделе «Разрешения» сведений о приложении.")
            TextButton(onClick = { refresh() }) { Text("Обновить состояние") }
            TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://support.google.com/android/answer/12623953"))) }
                catch (_: Exception) { launchError = "Не удалось открыть инструкцию Google." }
            }) { Text("Инструкция Google") }
            launchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
