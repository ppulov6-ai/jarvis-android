package io.clawdroid.backend.api

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption; each write uses a fresh IV. */
class SecretCipher(private val key: SecretKey) {
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size >= 28) { "Повреждено защищённое хранилище" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
