package io.clawdroid.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.clawdroid.BuildConfig
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticsExporter {
    fun create(context: Context): File {
        DiagnosticEvents.initialize(context)
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000 }?.forEach { it.delete() }
        val file = File.createTempFile("Jarvisjon-test-${System.currentTimeMillis()}-", ".zip", directory)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { it.substringBefore('/') == context.packageName }
        val report = JSONObject().put("schema", 2).put("created_utc_ms", System.currentTimeMillis())
            .put("app_version", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
            .put("build_type", BuildConfig.BUILD_TYPE).put("android_sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER).put("device_model", Build.MODEL)
            .put("microphone_allowed", androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            .put("accessibility_enabled", enabled).put("overlay_allowed", Settings.canDrawOverlays(context))
            .put("events", JSONArray(DiagnosticEvents.snapshot().map { event ->
                JSONObject().put("time_utc_ms", event.time).put("component", event.component).put("code", event.code).apply { event.status?.let { put("http_status", it) }; event.durationMs?.let { put("duration_ms", it) }; event.turnId?.let { put("turn_id", it) }; event.call?.let { put("call", it) } }
            }))
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.json")); zip.write(report.toString(2).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt")); zip.write("Диагностика Джарвиса. Содержит состояние разрешений, коды событий и время запросов модели последних тестов. Идентификаторы turn_id и call связывают начало и завершение обработки. Ключи, переписка, снимки экрана, файлы пользователя и сырые журналы не включены. Сохраняются последние 200 событий. Опишите шаги воспроизведения вместе с этим архивом.".toByteArray()); zip.closeEntry()
        }
        return file
    }
    fun share(context: Context) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", create(context))
        val intent = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = android.content.ClipData.newRawUri("Диагностика", uri) }
        context.startActivity(Intent.createChooser(intent, "Выгрузить тестовый файл").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
