package com.soma.core.model

import java.util.Locale

/**
 * One day of an imported workbook: a heading, an optional quote, reflection
 * questions, and an optional practical exercise. Order in the file is the
 * programme order; any numbering inside the heading is display text only.
 */
data class WorkbookSection(
    val heading: String,
    val quote: String? = null,
    val questions: List<String> = emptyList(),
    val exercise: String? = null,
) {
    init {
        require(heading.isNotBlank()) { "A workbook section needs a heading" }
        require(heading.length <= Workbook.MAX_HEADING_CHARACTERS) { "A workbook heading is too long" }
        require(quote == null || quote.isNotBlank()) { "A workbook quote must not be blank" }
        require(exercise == null || exercise.isNotBlank()) { "A workbook exercise must not be blank" }
        require(questions.none(String::isBlank)) { "A workbook question must not be blank" }
        require(quote != null || questions.isNotEmpty() || exercise != null) {
            "A workbook section needs at least one prompt line"
        }
    }
}

/**
 * A user-imported guided journaling programme, parsed from pasted plain text.
 *
 * Progress is never stored: an answer is an ordinary entry carrying a metadata
 * TAG link whose target is [sectionTag] and whose relation is [LINK_RELATION],
 * so position derives from the notes themselves and survives backup restore.
 */
data class Workbook(
    val title: String,
    val sections: List<WorkbookSection>,
) {
    val slug: String = slugOf(title)

    init {
        require(title.isNotBlank()) { "A workbook needs a title" }
        require(sections.isNotEmpty()) { "A workbook needs at least one section" }
        require(sections.size <= MAX_SECTIONS) { "A workbook has too many sections" }
        val widest = sectionTag(sections.size)
        require(normalizeMetadataTag(widest) == widest) { "Workbook slug does not normalize" }
    }

    /** Stable per-section anchor used as a metadata tag target; [position] is 1-based. */
    fun sectionTag(position: Int): String {
        require(position in 1..sections.size) { "Workbook section position is out of range" }
        return "wb-$slug-" + position.toString().padStart(2, '0')
    }

    companion object {
        /** Matches the LAN form ceiling; a pasted workbook is a small text file. */
        const val MAX_TEXT_BYTES: Int = 64 * 1024
        const val MAX_SECTIONS: Int = 64
        const val MAX_HEADING_CHARACTERS: Int = 120
        const val MAX_LINES_PER_SECTION: Int = 12
        const val MAX_LINE_CHARACTERS: Int = 300

        /** "wb-" (3) + slug + "-NN" (3) must fit the 48-character metadata tag limit. */
        const val MAX_SLUG_CHARACTERS: Int = 42
        const val LINK_RELATION: String = "workbook"

        private const val TITLE_MARKER = "# "
        private const val SECTION_MARKER = "## "
        private const val QUOTE_MARKER = "> "
        private const val QUESTION_MARKER = "- "
        private const val EXERCISE_MARKER = "* "

        /**
         * Parses the documented workbook text format: one `# title`, then
         * `## heading` sections holding an optional `> quote`, `- question`
         * lines, and an optional `* exercise`. Anything else is rejected so a
         * wrong paste fails loudly instead of importing garbage.
         */
        fun parse(raw: CharSequence): Workbook {
            require(raw.toString().toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) {
                "A workbook is too large"
            }
            var title: String? = null
            val sections = mutableListOf<WorkbookSection>()
            var heading: String? = null
            var quote: String? = null
            var exercise: String? = null
            val questions = mutableListOf<String>()
            var sectionLines = 0

            fun closeSection() {
                val open = heading ?: return
                sections += WorkbookSection(open, quote, questions.toList(), exercise)
                heading = null
                quote = null
                exercise = null
                questions.clear()
                sectionLines = 0
            }

            fun content(line: String, marker: String): String {
                val value = line.removePrefix(marker).trim()
                require(value.isNotEmpty()) { "A workbook line has a marker but no text" }
                require(value.length <= MAX_LINE_CHARACTERS) { "A workbook line is too long" }
                return value
            }

            fun promptLine() {
                requireNotNull(heading) { "A workbook prompt line appears before any section" }
                sectionLines += 1
                require(sectionLines <= MAX_LINES_PER_SECTION) { "A workbook section has too many lines" }
            }

            raw.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                require(line.none(Char::isISOControl)) { "A workbook contains a control character" }
                when {
                    line.startsWith(SECTION_MARKER) -> {
                        requireNotNull(title) { "A workbook must start with its title" }
                        closeSection()
                        heading = content(line, SECTION_MARKER)
                    }
                    line.startsWith(TITLE_MARKER) -> {
                        require(title == null) { "A workbook has exactly one title" }
                        require(heading == null && sections.isEmpty()) {
                            "A workbook title must come first"
                        }
                        title = content(line, TITLE_MARKER)
                    }
                    line.startsWith(QUOTE_MARKER) -> {
                        promptLine()
                        require(quote == null) { "A workbook section has at most one quote" }
                        quote = content(line, QUOTE_MARKER)
                    }
                    line.startsWith(QUESTION_MARKER) -> {
                        promptLine()
                        questions += content(line, QUESTION_MARKER)
                    }
                    line.startsWith(EXERCISE_MARKER) -> {
                        promptLine()
                        require(exercise == null) { "A workbook section has at most one exercise" }
                        exercise = content(line, EXERCISE_MARKER)
                    }
                    else -> throw IllegalArgumentException("A workbook line is not recognised")
                }
            }
            closeSection()
            return Workbook(requireNotNull(title) { "A workbook must start with its title" }, sections)
        }

        /** Lowercased letters and digits with single dashes; never empty, never dash-edged. */
        fun slugOf(title: String): String {
            val collapsed = buildString {
                title.trim().lowercase(Locale.ROOT).forEach { character ->
                    when {
                        character.isLetterOrDigit() -> append(character)
                        isNotEmpty() && last() != '-' -> append('-')
                    }
                }
            }.trim('-').take(MAX_SLUG_CHARACTERS).trim('-')
            return collapsed.ifEmpty { "workbook" }
        }

        /**
         * The 1-based position of the first section without an answer link in
         * [layers], or null when every section is answered. Skipped days stay
         * next — there is no calendar and no concept of a missed day.
         */
        fun nextSection(workbook: Workbook, layers: List<EntryMetadata>): Int? {
            val answered = layers.asSequence()
                .flatMap { layer -> layer.links.asSequence() }
                .filter { link -> link.kind == EntryLinkKind.TAG && link.relation == LINK_RELATION }
                .mapTo(hashSetOf(), EntryLink::target)
            return (1..workbook.sections.size).firstOrNull { position ->
                workbook.sectionTag(position) !in answered
            }
        }
    }
}
