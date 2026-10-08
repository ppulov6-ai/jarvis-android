package io.clawdroid.core.domain.local

import io.clawdroid.core.domain.model.ImageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalCommandRouterTest {
    private class Platform : LocalCommandPlatform {
        var contacts = listOf(LocalContact("1", "Мама", "+79990001122"))
        var dialed: String? = null
        var searches = 0
        var failure: Exception? = null
        var wait: CompletableDeferred<Unit>? = null
        override suspend fun searchContacts(query: String): List<LocalContact> {
            searches++; wait?.await(); failure?.let { throw it }; return contacts
        }
        override suspend fun dial(phone: String) { dialed = phone }
        override suspend fun screenshot() = ImageData("Pictures/Jarvis/test.jpg", 100, 100)
    }

    @Test fun `routine boundaries do not consume requests for explanation or compound work`() {
        listOf("найди контакт Мама", "позвони Мама", "call +7 (999) 000-11-22", "сделай снимок экрана", "найди контакт", "позвони").forEach {
            assertTrue(LocalCommandRouter.isRoutine(it), it)
        }
        listOf("как позвонить маме", "найди контакт Мама и напиши ей", "позвони маме затем отправь сообщение", "объясни скриншот", "расскажи про контакт", "найди рецепт").forEach {
            assertFalse(LocalCommandRouter.isRoutine(it), it)
        }
    }

    @Test fun `single contact search does not dial and call opens validated number`() = runTest {
        val p = Platform(); val router = LocalCommandRouter(p)
        assertTrue(router.execute("найди контакт Мама").content.contains("Мама"))
        assertNull(p.dialed)
        router.execute("позвони Мама")
        assertEquals("+79990001122", p.dialed)
    }

    @Test fun `ambiguous contacts and phone entries require local selection`() = runTest {
        val p = Platform(); p.contacts = listOf(LocalContact("1", "Мама", "111"), LocalContact("1", "Мама", "222"))
        val router = LocalCommandRouter(p)
        assertTrue(router.execute("позвони Мама").content.contains("2. Мама"))
        assertNull(p.dialed); assertTrue(router.handles("2"))
        router.execute("2"); assertEquals("222", p.dialed)
        assertFalse(router.handles("2"))
    }

    @Test fun `selection expires and cancellation removes pending action`() = runTest {
        val p = Platform(); p.contacts = p.contacts + LocalContact("2", "Мама работа", "222")
        var time = 0L; val router = LocalCommandRouter(p) { time }
        router.execute("позвони Мама"); time = 120_000
        assertTrue(router.execute("1").content.contains("истекло")); assertNull(p.dialed)
        router.execute("позвони Мама"); router.execute("отмена")
        assertFalse(router.handles("1")); assertNull(p.dialed)
    }

    @Test fun `unsafe phone payload and missing operand remain local clarifications`() = runTest {
        val p = Platform(); val router = LocalCommandRouter(p)
        assertTrue(router.handles("позвони *100#"))
        assertTrue(router.execute("позвони *100#").content.contains("без добавочных"))
        assertTrue(router.execute("позвони").content.contains("Укажите"))
        assertNull(p.dialed); assertEquals(0, p.searches)
    }

    @Test fun `permission error is local and cancellation propagates`() = runTest {
        val p = Platform(); val router = LocalCommandRouter(p)
        p.failure = SecurityException()
        assertTrue(router.execute("найди контакт Мама").content.contains("Нет разрешения"))
        p.failure = CancellationException("cancelled")
        try { router.execute("найди контакт Мама"); fail<Unit>("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun `reset prevents deferred lookup from dialing after stop`() = runTest {
        val p = Platform(); p.wait = CompletableDeferred()
        val router = LocalCommandRouter(p)
        val task = async { router.execute("позвони Мама") }
        testScheduler.runCurrent(); router.reset(); p.wait!!.complete(Unit)
        try { task.await(); fail<Unit>("Stale action must be cancelled") }
        catch (_: CancellationException) { }
        assertNull(p.dialed)
    }

    @Test fun `screenshot returns saved local image`() = runTest {
        val result = LocalCommandRouter(Platform()).execute("сделай скриншот")
        assertEquals("Pictures/Jarvis/test.jpg", result.images.single().path)
    }

    @Test fun `find and dial is a single anchored local pipeline`() = runTest {
        val p = Platform(); val router = LocalCommandRouter(p)
        assertTrue(router.handles("найди контакт Мама и позвони"))
        router.execute("найди контакт Мама и позвони")
        assertEquals("+79990001122", p.dialed)
        assertFalse(router.handles("найди контакт Мама и позвони и отправь сообщение"))
    }

    @Test fun `expected platform failure preserves useful message but unexpected details stay private`() = runTest {
        val p = Platform(); val router = LocalCommandRouter(p)
        p.failure = LocalCommandFailure("Слишком много совпадений. Уточните имя контакта.")
        assertEquals(p.failure!!.message, router.execute("найди контакт Мама").content)
        p.failure = IllegalStateException("private stack implementation detail")
        assertFalse(router.execute("найди контакт Мама").content.contains("private"))
    }

    @Test fun `no results is a local completion without dial`() = runTest {
        val p = Platform(); p.contacts = emptyList()
        val result = LocalCommandRouter(p).execute("позвони Неизвестный")
        assertTrue(result.content.contains("не найден")); assertNull(p.dialed)
    }
}
