package com.soma.core.model

import java.util.Locale

/**
 * User-controlled spellings and short phrases that are easy for speech recognition to miss.
 *
 * The same deliberately small list can be used as a local Whisper initial prompt or sent as
 * provider keyterms. Terms are never learned silently: the user can always see and edit the
 * complete list, which keeps the behaviour and any provider surcharge predictable.
 */
object TranscriptionVocabulary {
    // Staying at 100 avoids ElevenLabs' minimum-duration rule for lists over 100.
    const val MAX_TERMS: Int = 100
    // ElevenLabs documents keyterm length as strictly less than 50 characters.
    const val MAX_TERM_CHARACTERS: Int = 49
    const val MAX_WORDS_PER_TERM: Int = 5

    private val whitespace = Regex("\\s+")
    private val separators = Regex("[\\r\\n,]+")
    private val forbiddenCharacters = setOf('<', '>', '{', '}', '[', ']', '\\')

    /** Parses newline- or comma-separated terms, preserving the user's spelling and order. */
    fun parse(raw: CharSequence): List<String> {
        val terms = raw.split(separators)
            .map { it.trim().replace(whitespace, " ") }
            .filter(String::isNotEmpty)
        require(terms.size <= MAX_TERMS) { "Too many transcription vocabulary terms" }

        val seen = mutableSetOf<String>()
        return buildList {
            terms.forEach { term ->
                require(term.length <= MAX_TERM_CHARACTERS) { "A transcription vocabulary term is too long" }
                require(term.split(' ').size <= MAX_WORDS_PER_TERM) { "A transcription vocabulary phrase has too many words" }
                require(term.none(Char::isISOControl)) { "A transcription vocabulary term contains a control character" }
                require(term.none(forbiddenCharacters::contains)) { "A transcription vocabulary term contains an unsupported character" }
                if (seen.add(term.lowercase(Locale.ROOT))) add(term)
            }
        }
    }

    fun asEditableText(terms: List<String>): String = terms.joinToString("\n")

    /** Kept short because tiny Whisper has a small useful prompt window. */
    fun asWhisperPrompt(terms: List<String>): String? = terms
        .take(MAX_TERMS)
        .joinToString(", ")
        .takeIf(String::isNotBlank)
}
