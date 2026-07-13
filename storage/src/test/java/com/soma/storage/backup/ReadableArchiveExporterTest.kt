package com.soma.storage.backup

import com.soma.core.model.DailyNote
import com.soma.core.model.EntryRevision
import com.soma.core.model.NoteEntry
import com.soma.core.model.Todo
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableArchiveExporterTest {
    @Test
    fun `archive preserves readable notes todos and edit history`() {
        val date = LocalDate.of(2026, 7, 13)
        val created = Instant.parse("2026-07-13T08:12:00Z")
        val edited = Instant.parse("2026-07-13T10:45:00Z")
        val entry = NoteEntry.text("entry-1", date, 0, "buy milk", created).copy(
            text = "buy oat milk",
            updatedAt = edited,
            lastUserEditedAt = edited,
        )
        val snapshot = BackupSnapshot(
            exportedAt = Instant.parse("2026-07-13T11:00:00Z"),
            notes = listOf(DailyNote(date, created, listOf(entry))),
            entryRevisions = listOf(EntryRevision(entry.id, 1, "buy milk", edited)),
            todos = listOf(Todo("todo-1", "call Ada", created, created)),
            suggestions = emptyList(),
        )

        val files = unzip(ReadableArchiveExporter().encode(snapshot))

        assertEquals(
            setOf("README.txt", "manifest.json", "notes/2026-07-13.md", "todos.csv", "data/notes.json", "data/history.jsonl"),
            files.keys,
        )
        assertTrue(files.getValue("notes/2026-07-13.md").contains("buy oat milk"))
        assertTrue(files.getValue("todos.csv").contains("call Ada"))
        assertTrue(files.getValue("data/history.jsonl").contains("buy milk"))
        assertTrue(files.getValue("data/notes.json").contains("2026-07-13T10:45:00Z"))
    }

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
