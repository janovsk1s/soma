package com.soma.storage.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM AES-256-GCM implementation used by tests and non-Android tooling.
 * Production Android code should use [AndroidKeystoreTextCipher].
 */
class AesGcmCipher(
    secretKey: ByteArray,
    private val secureRandom: SecureRandom = SecureRandom(),
) : TextCipher {
    private val key = SecretKeySpec(secretKey.copyOf(), ALGORITHM)

    init {
        require(secretKey.size == KEY_BYTES) { "AES-256 requires a 32-byte key" }
    }

    override fun encrypt(plaintext: String, aad: ByteArray): ByteArray = crypt {
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext.encodeToByteArray())
        ByteBuffer.allocate(HEADER_BYTES + encrypted.size)
            .put(FORMAT_VERSION)
            .put(iv)
            .put(encrypted)
            .array()
    }

    override fun decrypt(ciphertext: ByteArray, aad: ByteArray): String = crypt {
        require(ciphertext.size >= HEADER_BYTES + TAG_BYTES) { "Ciphertext is truncated" }
        val buffer = ByteBuffer.wrap(ciphertext)
        val version = buffer.get()
        require(version == FORMAT_VERSION) { "Unsupported ciphertext format: $version" }
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        cipher.doFinal(encrypted).decodeToString(throwOnInvalidSequence = true)
    }

    private inline fun <T> crypt(block: () -> T): T = try {
        block()
    } catch (error: GeneralSecurityException) {
        throw CryptoException("AES-GCM operation failed", error)
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw CryptoException("Decrypted bytes are not valid UTF-8", error)
    }

    companion object {
        const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val HEADER_BYTES = 1 + IV_BYTES
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1

        fun randomKey(secureRandom: SecureRandom = SecureRandom()): ByteArray =
            ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
    }
}
