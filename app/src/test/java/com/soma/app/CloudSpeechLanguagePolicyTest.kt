package com.soma.app

import com.soma.core.model.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSpeechLanguagePolicyTest {
    @Test
    fun `one selected language is forced with provider-specific code`() {
        val policy = CloudSpeechLanguagePolicy.from(
            spoken = setOf(SupportedLanguage.SLOVAK),
            appLanguage = SupportedLanguage.ENGLISH,
        )

        assertEquals("sk", policy.requestCode(CloudSpeechProvider.GROQ))
        assertEquals("slk", policy.requestCode(CloudSpeechProvider.ELEVENLABS))
        assertEquals("sk", policy.resolved("slovak"))
        assertFalse(policy.accepts("de"))
    }

    @Test
    fun `several selected languages preserve provider auto detection`() {
        val policy = CloudSpeechLanguagePolicy.from(
            spoken = setOf(SupportedLanguage.ENGLISH, SupportedLanguage.LATVIAN),
            appLanguage = SupportedLanguage.LATVIAN,
        )

        assertNull(policy.requestCode(CloudSpeechProvider.GROQ))
        assertTrue(policy.accepts("eng"))
        assertTrue(policy.accepts("lav"))
        assertFalse(policy.accepts("ru"))
        assertEquals("lv", policy.resolved("und"))
    }

    @Test
    fun `ElevenLabs preserves full recording context while Groq keeps VAD chunks`() {
        assertTrue(CloudSpeechProvider.ELEVENLABS.preservesRecordingContext)
        assertFalse(CloudSpeechProvider.GROQ.preservesRecordingContext)
    }

    @Test
    fun `empty preference safely restores the full supported set`() {
        val policy = CloudSpeechLanguagePolicy.from(
            spoken = emptySet(),
            appLanguage = SupportedLanguage.GERMAN,
        )

        assertEquals(SupportedLanguage.entries.map { it.languageTag }.toSet(), policy.allowed)
        assertEquals("de", policy.resolved("unknown"))
    }
}
