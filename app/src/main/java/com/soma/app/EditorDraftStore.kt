package com.soma.app

import android.content.Context

internal enum class EditorDraftKind(val field: String) {
    CAPTURE("capture"),
    IMPORTANT("important"),
}

/**
 * Small device-local store for unfinished editor text.
 *
 * Drafts are application data, not saved-instance state: they can be larger than
 * Android's Bundle limit and must follow Soma's encryption-at-rest promise. This
 * store has its own non-exportable Keystore key and is deliberately excluded from
 * backups because a draft is cleared as soon as its entry is saved.
 */
internal class EditorDraftStore(context: Context) {
    private val encrypted = EncryptedTextPreference(
        context = context.applicationContext,
        preferencesName = PREFERENCES,
        keyAlias = KEY_ALIAS,
    )

    fun read(kind: EditorDraftKind): String = encrypted.read(kind.field).orEmpty()

    fun write(kind: EditorDraftKind, value: String) {
        val chars = value.toCharArray()
        try {
            encrypted.write(kind.field, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    private companion object {
        const val PREFERENCES = "soma_editor_drafts"
        const val KEY_ALIAS = "soma.editor.drafts.v1"
    }
}
