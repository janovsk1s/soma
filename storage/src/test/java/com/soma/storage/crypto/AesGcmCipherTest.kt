package com.soma.storage.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test

class AesGcmCipherTest {
    private val cipher = AesGcmCipher(ByteArray(AesGcmCipher.KEY_BYTES) { it.toByte() })

    @Test
    fun `unicode text round trips`() {
        val aad = StorageAad.forField("entry-1", "entry.text", 1)
        val text = "Jāatceras piezvanīt — Привіт — こんにちは"

        assertEquals(text, cipher.decrypt(cipher.encrypt(text, aad), aad))
    }

    @Test
    fun `encryption uses a fresh nonce`() {
        val aad = StorageAad.forField("todo-1", "todo.text", 1)

        val first = cipher.encrypt("same text", aad)
        val second = cipher.encrypt("same text", aad)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `tampering is rejected`() {
        val aad = StorageAad.forField("entry-1", "entry.text", 1)
        val encrypted = cipher.encrypt("private thought", aad)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()

        assertThrows(CryptoException::class.java) {
            cipher.decrypt(encrypted, aad)
        }
    }

    @Test
    fun `moving ciphertext to another row or field is rejected`() {
        val originalAad = StorageAad.forField("entry-1", "entry.text", 1)
        val encrypted = cipher.encrypt("bound text", originalAad)

        assertThrows(CryptoException::class.java) {
            cipher.decrypt(encrypted, StorageAad.forField("entry-2", "entry.text", 1))
        }
        assertThrows(CryptoException::class.java) {
            cipher.decrypt(encrypted, StorageAad.forField("entry-1", "todo.text", 1))
        }
        assertThrows(CryptoException::class.java) {
            cipher.decrypt(encrypted, StorageAad.forField("entry-1", "entry.text", 2))
        }
    }

    @Test
    fun `only 256 bit keys are accepted`() {
        assertThrows(IllegalArgumentException::class.java) {
            AesGcmCipher(ByteArray(16))
        }
        assertEquals(AesGcmCipher.KEY_BYTES, AesGcmCipher.randomKey().size)
    }
}
