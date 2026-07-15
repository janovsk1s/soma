package com.soma.core.search

import com.soma.core.model.DailyNote
import com.soma.core.model.EntryKind
import com.soma.core.model.LogKind
import com.soma.core.model.LogRecord
import com.soma.core.model.NoteEntry
import com.soma.core.model.Todo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun `results merge all sources newest first`() {
        val results = SearchResults.searchAll(
            notes = listOf(
                note(
                    LocalDate.parse("2026-07-10"),
                    entry("piens ir beidzies", at = "2026-07-10T08:00:00Z"),
                ),
            ),
            todos = listOf(todo("nopirkt pienu", at = "2026-07-12T09:00:00Z")),
            logs = listOf(log("Piena zupa", at = "2026-07-11T12:00:00Z")),
            query = "pien",
            zone = zone,
        )

        assertEquals(
            listOf(SearchResultKind.IMPORTANT, SearchResultKind.LOG, SearchResultKind.ENTRY),
            results.map(SearchResult::kind),
        )
        assertTrue(results.all { it.date.year == 2026 })
    }

    @Test
    fun `deleted entries never surface`() {
        val results = SearchResults.searchAll(
            notes = listOf(
                note(
                    LocalDate.parse("2026-07-10"),
                    entry("slepens teksts", at = "2026-07-10T08:00:00Z"),
                    entry("slepens izdzēsts", at = "2026-07-10T09:00:00Z", deleted = true),
                ),
            ),
            todos = emptyList(),
            logs = emptyList(),
            query = "slepens",
            zone = zone,
        )

        assertEquals(1, results.size)
        assertEquals("slepens teksts", results.single().entry?.text)
    }

    @Test
    fun `log notes are searched alongside titles`() {
        val results = SearchResults.searchAll(
            notes = emptyList(),
            todos = emptyList(),
            logs = listOf(log("Vakariņas", at = "2026-07-11T19:00:00Z", note = "ar biezpienu")),
            query = "biezpien",
            zone = zone,
        )

        assertEquals(1, results.size)
    }

    @Test
    fun `a blank query returns nothing`() {
        val results = SearchResults.searchAll(
            notes = listOf(note(LocalDate.parse("2026-07-10"), entry("teksts", at = "2026-07-10T08:00:00Z"))),
            todos = emptyList(),
            logs = emptyList(),
            query = "   ",
            zone = zone,
        )
        assertTrue(results.isEmpty())
    }

    private fun note(date: LocalDate, vararg entries: NoteEntry): DailyNote = DailyNote(
        date = date,
        createdAt = entries.minOf(NoteEntry::createdAt),
        entries = entries.mapIndexed { index, entry ->
            entry.copy(noteDate = date, position = index)
        },
    )

    private fun entry(text: String, at: String, deleted: Boolean = false): NoteEntry {
        val created = Instant.parse(at)
        return NoteEntry(
            id = "entry-$at-$text".take(36),
            noteDate = LocalDate.parse("2026-07-10"),
            position = 0,
            kind = EntryKind.TEXT,
            text = text,
            createdAt = created,
            updatedAt = created,
            deletedAt = if (deleted) created.plusSeconds(60) else null,
        )
    }

    private fun todo(text: String, at: String): Todo {
        val created = Instant.parse(at)
        return Todo(id = "todo-$at", text = text, createdAt = created, updatedAt = created)
    }

    private fun log(title: String, at: String, note: String = ""): LogRecord {
        val occurred = Instant.parse(at)
        return LogRecord(
            id = "log-$at",
            kind = LogKind.MEAL,
            title = title,
            note = note,
            occurredAt = occurred,
            createdAt = occurred,
            updatedAt = occurred,
        )
    }
}
