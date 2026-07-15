package com.soma.core.search

import java.text.Normalizer

/**
 * Case- and diacritic-insensitive text matching for all eight supported
 * languages: "janis" finds "Jānis" and "SKĒDE" finds "skēde". Folding is per
 * character (NFD, combining marks dropped, then lowercased), which preserves a
 * position map back into the original text so matches can be highlighted there.
 */
class FoldedText private constructor(
    val original: String,
    val folded: String,
    private val originalIndexByFoldedIndex: IntArray,
) {
    fun indexOf(foldedQuery: String, fromFoldedIndex: Int = 0): Int =
        folded.indexOf(foldedQuery, fromFoldedIndex)

    /** Maps a match in the folded form back to the original character range. */
    fun originalRange(foldedStart: Int, foldedLength: Int): IntRange {
        require(foldedLength > 0) { "A match cannot be empty" }
        val start = originalIndexByFoldedIndex[foldedStart]
        val endInclusive = originalIndexByFoldedIndex[foldedStart + foldedLength - 1]
        return start..endInclusive
    }

    companion object {
        fun of(text: String): FoldedText {
            val folded = StringBuilder(text.length)
            val map = ArrayList<Int>(text.length)
            text.forEachIndexed { index, char ->
                Normalizer.normalize(char.toString(), Normalizer.Form.NFD).forEach { part ->
                    if (Character.getType(part) != Character.NON_SPACING_MARK.toInt()) {
                        folded.append(part.lowercaseChar())
                        map.add(index)
                    }
                }
            }
            return FoldedText(text, folded.toString(), map.toIntArray())
        }

        /** Queries fold exactly like text so both sides compare in one alphabet. */
        fun foldQuery(query: String): String = of(query.trim()).folded
    }
}

/**
 * A one-line window around the first occurrence, with the match position
 * expressed in snippet coordinates so the caller can emphasize it.
 */
data class SearchMatch(
    val snippet: String,
    val highlightStart: Int,
    val highlightEndExclusive: Int,
    val leadingTruncated: Boolean,
    val trailingTruncated: Boolean,
)

object TextSearch {
    /**
     * Returns the first occurrence of [foldedQuery] in [text] as a display
     * snippet, or null when the text does not contain the query.
     */
    fun match(text: String, foldedQuery: String): SearchMatch? {
        if (foldedQuery.isEmpty() || text.isEmpty()) return null
        val foldedText = FoldedText.of(text)
        val at = foldedText.indexOf(foldedQuery)
        if (at < 0) return null
        val range = foldedText.originalRange(at, foldedQuery.length)
        val windowStart = (range.first - SNIPPET_CONTEXT_CHARS).coerceAtLeast(0)
        val windowEnd = (range.last + 1 + SNIPPET_CONTEXT_CHARS).coerceAtMost(text.length)
        val snippet = text.substring(windowStart, windowEnd).replace('\n', ' ')
        return SearchMatch(
            snippet = snippet,
            highlightStart = range.first - windowStart,
            highlightEndExclusive = range.last + 1 - windowStart,
            leadingTruncated = windowStart > 0,
            trailingTruncated = windowEnd < text.length,
        )
    }

    private const val SNIPPET_CONTEXT_CHARS = 44
}
