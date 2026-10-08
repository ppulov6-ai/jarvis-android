package io.clawdroid.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import io.clawdroid.core.data.remote.dto.ToolRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Approval exists only in this process and is bound to one immutable request. */
object ActionConfirmation {
    data class Pending(val request: ToolRequest, val answer: CompletableDeferred<Boolean>, val stop: () -> Unit)
    private val pending = ConcurrentHashMap<String, Pending>()
    fun lookup(id: String): Pending? = pending[id]
    suspend fun ask(context: Context, request: ToolRequest, stop: () -> Unit): Boolean {
        val id = UUID.randomUUID().toString()
        val item = Pending(request, CompletableDeferred(), stop)
        pending[id] = item
        try {
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(context, ActionConfirmationActivity::class.java)
                    .putExtra("approval_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return withTimeoutOrNull(60_000) { item.answer.await() } == true
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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
            "screenshot" -> "Передача снимка экрана модели"
            "compose_sms" -> "Подготовка SMS"
            "compose_email" -> "Подготовка письма"
            "dial" -> "Открытие набора номера"
            "delete_event" -> "Удаление события"
            "clipboard_read" -> "Чтение буфера обмена"
            else -> "Команда ${request.action}"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Подтвердите действие Джарвиса")
            .setMessage("Действие: $title\n\nПараметры:\n${request.params ?: "нет"}\n\nРазрешение действует только один раз. Проверьте получателя, сумму и содержимое перед подтверждением.")
            .setPositiveButton("Разрешить один раз") { _, _ -> approval.answer.complete(true); finish() }
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
        super.onDestroy()
    }
}
