package com.soma.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.soma.core.model.TranscriptionVocabulary
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local encrypted storage for user vocabulary. This uses its own Keystore key rather than
 * reusing note, audio, backup, or cloud-credential keys. Portable/readable exports include the
 * terms explicitly; Android cloud backup remains disabled.
 */
class TranscriptionVocabularyStore(context: Context) {
    private val encrypted = EncryptedTextPreference(
        context = context.applicationContext,
        preferencesName = PREFERENCES,
        keyAlias = KEY_ALIAS,
    )

    fun read(): List<String> = encrypted.read(FIELD)
        ?.let { runCatching { TranscriptionVocabulary.parse(it) }.getOrNull() }
        .orEmpty()

    fun write(raw: CharArray): List<String> {
        val terms = TranscriptionVocabulary.parse(String(raw))
        writeTerms(terms)
        return terms
    }

    fun writeTerms(terms: List<String>) {
        val normalized = TranscriptionVocabulary.parse(TranscriptionVocabulary.asEditableText(terms))
        val text = TranscriptionVocabulary.asEditableText(normalized).toCharArray()
        try {
            encrypted.write(FIELD, text)
        } finally {
            text.fill('\u0000')
        }
    }

    private companion object {
        const val PREFERENCES = "soma_transcription_vocabulary"
        const val KEY_ALIAS = "soma.transcription.vocabulary.v1"
        const val FIELD = "terms"
    }
}

private class EncryptedTextPreference(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun read(field: String): String? {
        val encoded = preferences.getString(field, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES))
            String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    fun write(field: String, value: CharArray) {
        if (value.isEmpty()) {
            check(preferences.edit().remove(field).commit()) { "Could not clear encrypted preference" }
            return
        }
        val plain = String(value).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(plain)
            val packed = ByteArray(cipher.iv.size + encrypted.size)
            try {
                cipher.iv.copyInto(packed)
                encrypted.copyInto(packed, cipher.iv.size)
                check(
                    preferences.edit()
                        .putString(field, Base64.encodeToString(packed, Base64.NO_WRAP))
                        .commit(),
                ) { "Could not save encrypted preference" }
            } finally {
                encrypted.fill(0)
                packed.fill(0)
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
