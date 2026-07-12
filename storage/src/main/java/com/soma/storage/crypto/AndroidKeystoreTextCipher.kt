package com.soma.storage.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM text encryption whose non-exportable key lives in Android Keystore. */
class AndroidKeystoreTextCipher(
    private val alias: String = DEFAULT_ALIAS,
) : TextCipher {
    init {
        require(alias.isNotBlank()) { "Keystore alias must not be blank" }
    }

    override fun encrypt(plaintext: String, aad: ByteArray): ByteArray = crypt {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext.encodeToByteArray())
        ByteBuffer.allocate(HEADER_BYTES + encrypted.size)
            .put(FORMAT_VERSION)
            .put(cipher.iv)
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
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            generator.generateKey()
        }
    }

    private inline fun <T> crypt(block: () -> T): T = try {
        block()
    } catch (error: GeneralSecurityException) {
        throw CryptoException("Android Keystore AES-GCM operation failed", error)
    }

    companion object {
        const val DEFAULT_ALIAS = "soma_storage_text_v1"
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val HEADER_BYTES = 1 + IV_BYTES
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private val KEY_LOCK = Any()
    }
}
