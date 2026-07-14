package com.soma.app

import com.soma.core.model.EntryRevision
import com.soma.core.model.NoteEntry
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryHistoryTest {
    @Test
    fun `history preserves original and orders every wording by when it became current`() {
        val created = Instant.parse("2026-07-14T08:00:00Z")
        val firstEdit = created.plusSeconds(60)
        val secondEdit = created.plusSeconds(120)
        val entry = NoteEntry.text("entry", LocalDate.parse("2026-07-14"), 0, "third", created)
            .copy(updatedAt = secondEdit, lastUserEditedAt = secondEdit)
        val history = buildEntryHistory(
            entry,
            listOf(
                EntryRevision(entry.id, 2, "second", secondEdit),
                EntryRevision(entry.id, 1, "first", firstEdit),
            ),
        )

        assertEquals(listOf("first", "second", "third"), history.map(EntryHistoryVersion::text))
        assertEquals(listOf(created, firstEdit, secondEdit), history.map(EntryHistoryVersion::becameCurrentAt))
        assertTrue(history.first().isOriginal)
        assertFalse(history.first().isCurrent)
        assertTrue(history.last().isCurrent)
    }

    @Test
    fun `unedited entry is both original and current`() {
        val created = Instant.parse("2026-07-14T08:00:00Z")
        val entry = NoteEntry.text("entry", LocalDate.parse("2026-07-14"), 0, "only", created)

        val version = buildEntryHistory(entry, emptyList()).single()

        assertTrue(version.isOriginal)
        assertTrue(version.isCurrent)
        assertEquals(created, version.becameCurrentAt)
    }
}
