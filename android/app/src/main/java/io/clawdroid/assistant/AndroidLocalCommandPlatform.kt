package io.clawdroid.assistant

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Base64
import io.clawdroid.core.data.remote.dto.ToolRequest
import io.clawdroid.core.domain.local.LocalCommandPlatform
import io.clawdroid.core.domain.local.LocalContact
import io.clawdroid.core.domain.local.LocalCommandFailure
import io.clawdroid.core.domain.model.ImageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** Routine commands never send contacts or screenshots to the gateway or model. */
class AndroidLocalCommandPlatform(
    private val context: Context,
    private val tools: ToolRequestHandler
) : LocalCommandPlatform {
    private val permissions = PermissionRequester(context)

    override suspend fun searchContacts(query: String): List<LocalContact> {
        io.clawdroid.diagnostics.DiagnosticEvents.record("assistant", "local_contacts")
        if (!permissions.request(Manifest.permission.READ_CONTACTS)) throw LocalCommandFailure(
            "Нет разрешения на чтение контактов. Разрешите доступ в настройках телефона.")
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            fun normalized(value: String) = value.lowercase(java.util.Locale.ROOT).replace('ё', 'е')
            val terms = normalized(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val resolver = context.contentResolver
            val cursor = resolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC")
                ?: throw LocalCommandFailure("Телефон не предоставил список контактов. Повторите запрос.")
            cursor.use {
                val results = mutableListOf<LocalContact>()
                var matches = 0
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = it.getString(0)
                    val name = it.getString(1).orEmpty()
                    if (!terms.all { term -> normalized(name).contains(term) }) continue
                    matches++
                    if (matches > 200) throw LocalCommandFailure("Слишком много совпадений. Уточните имя контакта.")
                    val numbers = resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id), null)
                        ?: throw LocalCommandFailure("Телефон не предоставил номера контакта.")
                    val phones = numbers.use { rows ->
                        buildList {
                            while (rows.moveToNext()) {
                                currentCoroutineContext().ensureActive()
                                rows.getString(0)?.takeIf(String::isNotBlank)?.let { add(it) }
                            }
                        }
                    }
                    if (phones.isEmpty()) results += LocalContact(id, name, "")
                    else phones.forEach { number -> results += LocalContact(id, name, number) }
                    if (results.size > 200) throw LocalCommandFailure("Слишком много номеров. Уточните имя контакта.")
                }
                results.distinctBy { contact -> contact.id to contact.phone.filter(Char::isDigit) }
            }
        }
    }

    override suspend fun dial(phone: String) = withContext(Dispatchers.Main) {
        currentCoroutineContext().ensureActive()
        io.clawdroid.diagnostics.DiagnosticEvents.record("assistant", "local_dial")
        // ACTION_DIAL only prepares the number. The user starts the actual call.
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun screenshot(): ImageData {
        io.clawdroid.diagnostics.DiagnosticEvents.record("assistant", "local_screenshot")
        val response = tools.handle(ToolRequest(requestId = UUID.randomUUID().toString(), action = "screenshot",
            params = buildJsonObject { put("local_only", JsonPrimitive(true)) }))
        if (!response.success) throw LocalCommandFailure("Не удалось сделать снимок. Проверьте доступ Джарвиса к экрану; защищённые окна Android снимать запрещает.")
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val bytes = Base64.decode(response.result ?: throw LocalCommandFailure("Снимок экрана пуст."), Base64.NO_WRAP)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Телефон вернул повреждённый снимок." }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Jarvisjon-${UUID.randomUUID()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Jarvisjon")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw LocalCommandFailure("Не удалось создать файл снимка.")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw LocalCommandFailure("Не удалось записать снимок.")
                currentCoroutineContext().ensureActive()
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
                    "Не удалось завершить сохранение снимка."
                }
                ImageData(uri.toString(), bounds.outWidth, bounds.outHeight)
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }
    }
}
