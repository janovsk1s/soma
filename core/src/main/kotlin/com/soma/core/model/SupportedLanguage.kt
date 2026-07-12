package com.soma.core.model

import java.util.Locale

/** Languages supported by Paka and therefore expected by Soma's local transcription. */
enum class SupportedLanguage(val languageTag: String) {
    ENGLISH("en"),
    LATVIAN("lv"),
    ESTONIAN("et"),
    LITHUANIAN("lt"),
    FINNISH("fi"),
    SWEDISH("sv"),
    GERMAN("de"),
    SLOVAK("sk");

    companion object {
        /** Accepts a BCP-47 tag such as `lv` or `de-AT`. */
        fun fromLanguageTag(tag: String): SupportedLanguage? {
            val language = Locale.forLanguageTag(tag).language.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.languageTag == language }
        }
    }
}
