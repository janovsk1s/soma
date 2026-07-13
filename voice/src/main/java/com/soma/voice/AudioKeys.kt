package com.soma.voice

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Supplies the non-exportable key used only to wrap per-recording data keys. */
fun interface AudioWrappingKeyProvider {
    fun getOrCreate(): SecretKey
}

/**
 * Uses a dedicated Keystore alias; note/text encryption deliberately uses a
 * different key domain in the storage module.
 */
class AndroidAudioWrappingKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
) : AudioWrappingKeyProvider {
    @Volatile
    private var cachedKey: SecretKey? = null

    override fun getOrCreate(): SecretKey = cachedKey ?: synchronized(KEY_LOCK) {
        cachedKey ?: loadOrCreate().also { cachedKey = it }
    }

    private fun loadOrCreate(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }

        fun generate(strongBox: Boolean): SecretKey {
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(strongBox)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(builder.build())
                generateKey()
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { generate(strongBox = true) }.getOrElse { generate(strongBox = false) }
        } else {
            generate(strongBox = false)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_BITS = 256
        const val DEFAULT_ALIAS = "soma_audio_wrap_v1"
        val KEY_LOCK = Any()
    }
}
