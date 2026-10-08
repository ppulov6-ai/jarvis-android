package io.clawdroid.core.domain.local

import io.clawdroid.core.domain.model.ImageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

data class LocalContact(val id: String, val name: String, val phone: String)
data class LocalCommandResult(val content: String, val images: List<ImageData> = emptyList())
/** A deliberately user-facing platform failure; never expose arbitrary exception messages. */
class LocalCommandFailure(message: String) : Exception(message)
interface LocalCommandPlatform {
    suspend fun searchContacts(query: String): List<LocalContact>
    suspend fun dial(phone: String)
    suspend fun screenshot(): ImageData
}
interface LocalCommandHandler {
    fun handles(text: String): Boolean
    suspend fun execute(text: String): LocalCommandResult
    fun reset()
}

/** All recognized commands, including errors and choices, remain on the device. */
class LocalCommandRouter(
    private val platform: LocalCommandPlatform,
    private val now: () -> Long = { System.currentTimeMillis() }
) : LocalCommandHandler {
    private data class Pending(val contacts: List<LocalContact>, val dial: Boolean, val expires: Long, val epoch: Long)
    private val mutex = Mutex()
    private val epoch = AtomicLong()
    @Volatile private var pending: Pending? = null

    override fun handles(text: String): Boolean = parse(text) != null ||
        (pending != null && (choice(text) != null || clean(text) in cancellation))

    override fun reset() { epoch.incrementAndGet(); pending = null }

    override suspend fun execute(text: String): LocalCommandResult {
        val token = epoch.get()
        return mutex.withLock {
            fun checkCurrent() {
                if (token != epoch.get()) throw CancellationException("Local command superseded")
            }
            try {
                currentCoroutineContext().ensureActive()
                checkCurrent()
                val normalized = clean(text)
                val selection = pending
                if (selection != null && normalized in cancellation) {
                    pending = null
                    return@withLock LocalCommandResult("Выбор контакта отменён.")
                }
                val number = choice(text)
                if (selection != null && number != null) {
                    if (selection.epoch != token || now() >= selection.expires) {
                        pending = null
                        return@withLock LocalCommandResult("Время выбора истекло. Повторите поиск контакта.")
                    }
                    val contact = selection.contacts.getOrNull(number - 1)
                        ?: return@withLock LocalCommandResult("Выберите номер от 1 до ${selection.contacts.size}.")
                    pending = null
                    return@withLock finish(contact, selection.dial, token)
                }
                val command = parse(text)
                    ?: return@withLock LocalCommandResult("Команда не относится к локальным действиям.")
                pending = null
                if (command.kind == Kind.SCREENSHOT) {
                    val image = platform.screenshot()
                    currentCoroutineContext().ensureActive(); checkCurrent()
                    return@withLock LocalCommandResult("Снимок экрана сохранён на телефоне.", listOf(image))
                }
                if (command.operand.isBlank()) return@withLock LocalCommandResult("Укажите имя контакта или номер телефона.")
                if (command.kind == Kind.DIAL && command.operand.any(Char::isDigit)) {
                    val phone = normalizePhone(command.operand)
                        ?: return@withLock LocalCommandResult("Укажите обычный номер телефона без добавочных команд.")
                    return@withLock finish(LocalContact("", phone, phone), true, token)
                }
                val contacts = platform.searchContacts(command.operand)
                    .distinctBy { it.id to it.phone }
                currentCoroutineContext().ensureActive(); checkCurrent()
                if (contacts.isEmpty()) return@withLock LocalCommandResult("Контакт «${command.operand}» не найден.")
                if (contacts.size == 1) return@withLock finish(contacts.single(), command.kind == Kind.DIAL, token)
                pending = Pending(contacts, command.kind == Kind.DIAL, now() + 120_000, token)
                LocalCommandResult("Найдено несколько вариантов. Назовите номер:\n" + contacts.mapIndexed { index, contact ->
                    "${index + 1}. ${contact.name}: ${contact.phone}"
                }.joinToString("\n"))
            } catch (error: CancellationException) { throw error }
            catch (error: LocalCommandFailure) { LocalCommandResult(error.message ?: "Не удалось выполнить действие на телефоне.") }
            catch (error: SecurityException) { LocalCommandResult("Нет разрешения для этого действия. Проверьте разрешения Джарвиса в настройках телефона.") }
            catch (error: Exception) { LocalCommandResult("Не удалось выполнить действие на телефоне. Повторите команду или проверьте разрешения.") }
        }
    }

    private suspend fun finish(contact: LocalContact, dial: Boolean, token: Long): LocalCommandResult {
        currentCoroutineContext().ensureActive()
        if (epoch.get() != token) throw CancellationException("Local command superseded")
        if (!dial) return LocalCommandResult("${contact.name}: ${contact.phone.ifBlank { "номер не указан" }}")
        val phone = normalizePhone(contact.phone)
            ?: return LocalCommandResult("У контакта нет подходящего номера телефона.")
        platform.dial(phone)
        currentCoroutineContext().ensureActive()
        if (epoch.get() != token) throw CancellationException("Local command superseded")
        return LocalCommandResult("Открыт набор номера: ${contact.name}, $phone.")
    }

    private enum class Kind { FIND, DIAL, SCREENSHOT }
    private data class Command(val kind: Kind, val operand: String = "")
    companion object {
        private val cancellation = setOf("отмена", "отмени", "стоп", "cancel", "stop")
        private fun clean(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?')
        private val screenshot = Regex("^(?:(?:сделай|сделать|сохрани|сохранить) )?(?:скриншот|скрин шот|снимок экрана)$|^(?:take |save )?(?:a )?screenshot$")
        private val find = Regex("^(?:найди|найти|покажи|показать|find|search|show) (?:контакт(?:ы)?|contact(?:s)?)(?: (.*))?$")
        private val dial = Regex("^(?:позвони|позвонить|набери|набрать|call|dial)(?: (.*))?$")
        private val findAndDial = Regex("^(?:найди|найти) контакт (.+) и (?:позвони|позвонить|набери|набрать)$")
        private val instruction = Regex("(?:[;\n]|(?:^| )(?:и|затем|потом|чтобы|как|and|then|because|напиши|объясни|расскажи|проанализируй|отправь)(?: |$))")
        private fun parse(text: String): Command? {
            val value = clean(text)
            if (screenshot.matches(value)) return Command(Kind.SCREENSHOT)
            findAndDial.matchEntire(value)?.let {
                val operand = it.groupValues[1].trim()
                if (operand.length <= 100 && !instruction.containsMatchIn(operand) && !operand.contains('?'))
                    return Command(Kind.DIAL, operand)
                return null
            }
            val match = find.matchEntire(value)
            val kind = if (match != null) Kind.FIND else Kind.DIAL
            val matched = match ?: dial.matchEntire(value) ?: return null
            val operand = matched.groupValues.getOrElse(1) { "" }.trim()
                .removePrefix("контакту ").removePrefix("контакт ").removePrefix("contact ")
                .removePrefix("по номеру ").removePrefix("номер ").removePrefix("number ")
            if (instruction.containsMatchIn(operand) || operand.length > 100 || operand.contains('?')) return null
            return Command(kind, operand)
        }
        fun isRoutine(text: String): Boolean = parse(text) != null
        private fun choice(text: String): Int? {
            val value = clean(text).removePrefix("номер ").removePrefix("вариант ").removePrefix("number ")
            return value.toIntOrNull() ?: mapOf("первый" to 1, "второй" to 2, "третий" to 3, "first" to 1, "second" to 2, "third" to 3)[value]
        }
        private fun normalizePhone(value: String): String? {
            if (!Regex("^\\+?[0-9 ()-]+$").matches(value.trim())) return null
            val result = value.trim().replace(Regex("[ ()-]"), "")
            return result.takeIf { Regex("^\\+?[0-9]{3,15}$").matches(it) }
        }
    }
}
