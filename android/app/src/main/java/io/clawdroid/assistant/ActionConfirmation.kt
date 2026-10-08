package io.clawdroid.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.clawdroid.core.data.remote.dto.ToolRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Pending requests are immutable; device access can be explicitly remembered and revoked. */
object ActionConfirmation {
    data class Pending(val request: ToolRequest, val answer: CompletableDeferred<Boolean>, val stop: () -> Unit, val screenContext: String?, val closed: CompletableDeferred<Unit>)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val deviceActions = setOf("screenshot", "tap", "swipe", "text", "keyevent")
    private fun preferences(context: Context) = context.getSharedPreferences("device_access_consent", Context.MODE_PRIVATE)
    fun isDeviceAccessGranted(context: Context): Boolean = preferences(context).getBoolean("granted", false)
    fun revokeDeviceAccess(context: Context) { preferences(context).edit().putBoolean("granted", false).commit() }
    fun canRemember(action: String): Boolean = action in deviceActions
    fun grantDeviceAccess(context: Context): Boolean = preferences(context).edit().putBoolean("granted", true).commit()
    fun lookup(id: String): Pending? = pending[id]
    suspend fun ask(context: Context, request: ToolRequest, stop: () -> Unit, screenContext: String? = null): Boolean {
        if (canRemember(request.action) && isDeviceAccessGranted(context)) return true
        val id = UUID.randomUUID().toString()
        val item = Pending(request, CompletableDeferred(), stop, screenContext, CompletableDeferred())
        pending[id] = item
        try {
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(context, ActionConfirmationActivity::class.java)
                    .putExtra("approval_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val approved = withTimeoutOrNull(60_000) { item.answer.await() } == true
            if (!approved) return false
            // Never recheck the screen while the approval Activity still owns foreground.
            return withTimeoutOrNull(2_000) { item.closed.await(); true } == true
        } finally {
            pending.remove(id)
            item.answer.complete(false)
        }
    }
}

class ActionConfirmationActivity : Activity() {
    private var item: ActionConfirmation.Pending? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        item = intent.getStringExtra("approval_id")?.let(ActionConfirmation::lookup)
        val approval = item ?: run { finish(); return }
        val request = approval.request
        val title = when (request.action) {
            "tap" -> "Нажатие на экран"
            "swipe" -> "Жест на экране"
            "text" -> "Ввод текста"
            "keyevent" -> "Нажатие клавиши"
            "intent", "broadcast" -> "Команда другому приложению"
            "screenshot" -> if (request.params?.get("local_only")?.toString() == "true") "Сохранение снимка на телефоне без передачи модели" else "Передача снимка экрана модели"
            "compose_sms" -> "Подготовка SMS"
            "compose_email" -> "Подготовка письма"
            "dial" -> "Открытие набора номера"
            "delete_event" -> "Удаление события"
            "clipboard_read" -> "Чтение буфера обмена"
            else -> "Команда ${request.action}"
        }
        val remember = ActionConfirmation.canRemember(request.action)
        val explanation = if (remember)
            "Разрешить Джарвису читать и передавать экран подключённой модели, выполнять нажатия, жесты и ввод текста по вашим командам. Разрешение сохраняется. Отключить его можно в настройках Джарвиса → Разрешения."
        else "Проверьте параметры перед подтверждением этого действия."
        val dialog = AlertDialog.Builder(this)
            .setTitle("Подтвердите действие Джарвиса")
            .setMessage("Действие: $title\n${approval.screenContext.orEmpty()}\n\nПараметры:\n${request.params ?: "нет"}\n\n$explanation")
            .setPositiveButton(if (remember) "Разрешить и запомнить" else "Разрешить") { _, _ ->
                val saved = !remember || ActionConfirmation.grantDeviceAccess(this)
                approval.answer.complete(saved)
                finish()
            }
            .setNeutralButton("Стоп") { _, _ -> approval.stop(); approval.answer.complete(false); finish() }
            .setNegativeButton("Отменить") { _, _ -> approval.answer.complete(false); finish() }
            .setOnCancelListener { approval.answer.complete(false); finish() }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
        approval.answer.invokeOnCompletion { runOnUiThread { if (!isFinishing) finish() } }
    }
    override fun onDestroy() {
        item?.answer?.complete(false)
        item?.closed?.complete(Unit)
        super.onDestroy()
    }
}
