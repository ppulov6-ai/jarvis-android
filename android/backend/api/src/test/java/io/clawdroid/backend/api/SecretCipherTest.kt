package io.clawdroid.backend.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class SecretCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `secrets round trip without plaintext and use fresh IV`() {
        val cipher = SecretCipher(key())
        val secret = "ключ API sk-sensitive-value"
        val first = cipher.encrypt(secret)
        val second = cipher.encrypt(secret)
        assertEquals(secret, cipher.decrypt(first))
        assertNotEquals(first, second)
        assertFalse(String(Base64.getDecoder().decode(first), Charsets.ISO_8859_1).contains("sk-sensitive-value"))
    }

    @Test
    fun `tampering and a different key fail authentication`() {
        val cipher = SecretCipher(key())
        val original = cipher.encrypt("sensitive")
        val bytes = Base64.getDecoder().decode(original)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(Base64.getEncoder().encodeToString(bytes)) }
        assertThrows(AEADBadTagException::class.java) { SecretCipher(key()).decrypt(original) }
    }
}
