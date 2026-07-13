package com.soma.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptionVocabularyTest {
    @Test
    fun `parses, trims, and de-duplicates terms without changing spelling`() {
        assertEquals(
            listOf("Milchreis", "Rīga", "Light Phone"),
            TranscriptionVocabulary.parse("  Milchreis\nRīga, milchreis\nLight   Phone  "),
        )
    }

    @Test
    fun `empty input disables adaptable terms`() {
        assertEquals(emptyList<String>(), TranscriptionVocabulary.parse(" \n "))
        assertEquals(null, TranscriptionVocabulary.asWhisperPrompt(emptyList<String>()))
    }

    @Test
    fun `rejects provider-unsupported and oversized terms`() {
        assertThrows(IllegalArgumentException::class.java) { TranscriptionVocabulary.parse("bad{term") }
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptionVocabulary.parse("one two three four five six")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptionVocabulary.parse("x".repeat(TranscriptionVocabulary.MAX_TERM_CHARACTERS + 1))
        }
    }
}
