package com.soma.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkbookTest {
    @Test
    fun `parses a workbook with quotes, questions, and exercises in order`() {
        val workbook = Workbook.parse(
            """
            # Mein kleines Übungsheft

            ## Tag 1 · Ankommen — Wo stehe ich?
            > Kleine Schritte zählen.
            - Was läuft gerade gut?
            - Was kostet Kraft?
            * Schreibe drei ehrliche Sätze.

            ## Tag 2 · Warum
            - Wofür lohnt sich das?
            """.trimIndent(),
        )

        assertEquals("Mein kleines Übungsheft", workbook.title)
        assertEquals(2, workbook.sections.size)
        val first = workbook.sections.first()
        assertEquals("Tag 1 · Ankommen — Wo stehe ich?", first.heading)
        assertEquals("Kleine Schritte zählen.", first.quote)
        assertEquals(listOf("Was läuft gerade gut?", "Was kostet Kraft?"), first.questions)
        assertEquals("Schreibe drei ehrliche Sätze.", first.exercise)
        val second = workbook.sections[1]
        assertNull(second.quote)
        assertNull(second.exercise)
    }

    @Test
    fun `rejects text without a leading title`() {
        assertThrows(IllegalArgumentException::class.java) {
            Workbook.parse("## Tag 1\n- Frage?")
        }
    }

    @Test
    fun `rejects a second title and an unrecognised line`() {
        assertThrows(IllegalArgumentException::class.java) {
            Workbook.parse("# Eins\n# Zwei\n## Tag 1\n- Frage?")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Workbook.parse("# Titel\n## Tag 1\nnur Text ohne Marker")
        }
    }

    @Test
    fun `rejects prompt lines before any section and bare sections`() {
        assertThrows(IllegalArgumentException::class.java) {
            Workbook.parse("# Titel\n- Frage vor dem ersten Tag?")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Workbook.parse("# Titel\n## Tag 1")
        }
    }

    @Test
    fun `rejects duplicate quotes, oversized sections, and long lines`() {
        assertThrows(IllegalArgumentException::class.java) {
            Workbook.parse("# T\n## Tag 1\n> eins\n> zwei\n- Frage?")
        }
        val tooManyLines = buildString {
            appendLine("# T")
            appendLine("## Tag 1")
            repeat(Workbook.MAX_LINES_PER_SECTION + 1) { appendLine("- Frage $it?") }
        }
        assertThrows(IllegalArgumentException::class.java) { Workbook.parse(tooManyLines) }
        assertThrows(IllegalArgumentException::class.java) {
            Workbook.parse("# T\n## Tag 1\n- " + "x".repeat(Workbook.MAX_LINE_CHARACTERS + 1))
        }
    }

    @Test
    fun `rejects too many sections`() {
        val text = buildString {
            appendLine("# T")
            repeat(Workbook.MAX_SECTIONS + 1) {
                appendLine("## Tag $it")
                appendLine("- Frage?")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { Workbook.parse(text) }
    }

    @Test
    fun `slug keeps unicode letters, collapses punctuation, and never edges on a dash`() {
        assertEquals("dein-neues-ich", Workbook.slugOf("Dein neues Ich"))
        assertEquals("übungsheft-für-25-tage", Workbook.slugOf("Übungsheft für 25 Tage!"))
        assertEquals("a-b", Workbook.slugOf("  a — b  "))
        assertEquals("workbook", Workbook.slugOf("§*!"))
        val trimmed = Workbook.slugOf("a".repeat(Workbook.MAX_SLUG_CHARACTERS) + "-b")
        assertEquals(Workbook.MAX_SLUG_CHARACTERS, trimmed.length)
        assertEquals("a".repeat(Workbook.MAX_SLUG_CHARACTERS), trimmed)
    }

    @Test
    fun `section tags are zero padded, normalized, and exactly fit the tag limit`() {
        val longTitle = "t".repeat(Workbook.MAX_SLUG_CHARACTERS + 10)
        val workbook = Workbook.parse(
            buildString {
                appendLine("# $longTitle")
                repeat(25) {
                    appendLine("## Tag ${it + 1}")
                    appendLine("- Frage?")
                }
            },
        )
        assertEquals("wb-" + "t".repeat(Workbook.MAX_SLUG_CHARACTERS) + "-01", workbook.sectionTag(1))
        assertEquals(48, workbook.sectionTag(25).length)
        assertEquals(workbook.sectionTag(25), normalizeMetadataTag(workbook.sectionTag(25)))
    }

    @Test
    fun `next section skips answered days and honors gaps`() {
        val workbook = Workbook.parse(
            "# Heft\n## Tag 1\n- A?\n## Tag 2\n- B?\n## Tag 3\n- C?",
        )
        fun answer(position: Int) = EntryMetadata(
            entryId = "entry-$position",
            tags = listOf(workbook.sectionTag(position)),
            links = listOf(
                EntryLink(EntryLinkKind.TAG, workbook.sectionTag(position), Workbook.LINK_RELATION),
            ),
            derivedAt = Instant.EPOCH,
            source = MetadataSource.MANUAL,
        )

        assertEquals(1, Workbook.nextSection(workbook, emptyList()))
        assertEquals(2, Workbook.nextSection(workbook, listOf(answer(1))))
        // Answering ahead leaves the skipped day as the quiet next step.
        assertEquals(2, Workbook.nextSection(workbook, listOf(answer(1), answer(3))))
        assertNull(Workbook.nextSection(workbook, listOf(answer(1), answer(2), answer(3))))
    }

    @Test
    fun `links from other relations or kinds never count as answers`() {
        val workbook = Workbook.parse("# Heft\n## Tag 1\n- A?")
        val unrelated = EntryMetadata(
            entryId = "entry-x",
            tags = emptyList(),
            links = listOf(
                EntryLink(EntryLinkKind.TAG, workbook.sectionTag(1), relation = "topic"),
                EntryLink(EntryLinkKind.ENTRY, workbook.sectionTag(1), Workbook.LINK_RELATION),
            ),
            derivedAt = Instant.EPOCH,
            source = MetadataSource.LOCAL,
        )

        assertEquals(1, Workbook.nextSection(workbook, listOf(unrelated)))
    }
}
