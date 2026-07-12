package com.soma.storage.crypto

import java.nio.ByteBuffer

/**
 * Encrypts user-authored text before it crosses the Room boundary.
 *
 * Callers must supply stable additional authenticated data (AAD). The storage
 * repository binds every ciphertext to its entity id, field name, and crypto
 * version so swapping encrypted columns or rows is detected during decryption.
 */
interface TextCipher {
    fun encrypt(plaintext: String, aad: ByteArray): ByteArray

    @Throws(CryptoException::class)
    fun decrypt(ciphertext: ByteArray, aad: ByteArray): String
}

class CryptoException(message: String, cause: Throwable? = null) : SecurityException(message, cause)

internal object StorageAad {
    private val DOMAIN = "soma-storage".encodeToByteArray()

    fun forField(entityId: String, field: String, version: Int): ByteArray {
        require(entityId.isNotBlank()) { "Entity id must not be blank" }
        require(field.isNotBlank()) { "Field name must not be blank" }
        require(version > 0) { "Crypto version must be positive" }
        val idBytes = entityId.encodeToByteArray()
        val fieldBytes = field.encodeToByteArray()
        return ByteBuffer.allocate(
            Int.SIZE_BYTES + DOMAIN.size +
                Int.SIZE_BYTES + idBytes.size +
                Int.SIZE_BYTES + fieldBytes.size +
                Int.SIZE_BYTES,
        )
            .putInt(DOMAIN.size)
            .put(DOMAIN)
            .putInt(idBytes.size)
            .put(idBytes)
            .putInt(fieldBytes.size)
            .put(fieldBytes)
            .putInt(version)
            .array()
    }
}
