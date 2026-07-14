package com.soma.storage.backup

import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.DailyNote
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.EntryRevision
import com.soma.core.model.EntryLink
import com.soma.core.model.EntryLinkKind
import com.soma.core.model.EntryMetadata
import com.soma.core.model.ImportantKind
import com.soma.core.model.MetadataSource
import com.soma.core.model.NoteEntry
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.Todo
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionInfo
import com.soma.core.model.TranscriptionProvenance
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
        val voice = NoteEntry.voice(
            id = "voice-1",
            noteDate = date,
            position = 1,
            audio = AudioAttachment("audio-voice-1", AudioFormat.WAV, 1_000, 32_044),
            createdAt = created.plusSeconds(1),
            transcriptionEnabled = true,
        ).copy(
            text = "remember the milk",
            transcription = TranscriptionInfo(
                state = EntryTranscriptionState.SUCCEEDED,
                detectedLanguages = listOf(SupportedLanguage.ENGLISH),
                provenance = TranscriptionProvenance(
                    requestedEngine = TranscriptionEngine.ELEVENLABS_SCRIBE_V2,
                    usedEngine = TranscriptionEngine.ELEVENLABS_SCRIBE_V2,
                ),
                updatedAt = edited,
            ),
            updatedAt = edited,
            audioDeletedAt = edited.plusSeconds(1),
        )
        val deleted = NoteEntry.text(
            "entry-deleted",
            date,
            2,
            "SHOULD-NOT-EXPORT",
            created.plusSeconds(2),
        ).copy(deletedAt = edited.plusSeconds(2))
        val snapshot = BackupSnapshot(
            exportedAt = Instant.parse("2026-07-13T11:00:00Z"),
            notes = listOf(DailyNote(date, created, listOf(entry, voice, deleted))),
            entryRevisions = listOf(
                EntryRevision(entry.id, 1, "buy milk", edited),
                EntryRevision(deleted.id, 1, "deleted original", edited),
            ),
            entryMetadata = listOf(
                EntryMetadata(
                    entryId = entry.id,
                    tags = listOf("groceries"),
                    links = listOf(EntryLink(EntryLinkKind.DATE, date.toString(), "captured-on")),
                    derivedAt = edited,
                    source = MetadataSource.MANUAL,
                ),
                EntryMetadata(
                    entryId = deleted.id,
                    tags = listOf("deleted-tag"),
                    links = emptyList(),
                    derivedAt = edited,
                    source = MetadataSource.AI,
                ),
            ),
            todos = listOf(
                Todo("todo-1", "call Ada", created, created, kind = ImportantKind.EXCERPT),
            ),
            suggestions = emptyList(),
            transcriptionVocabulary = listOf("Milchreis", "Rīga"),
        )

        val files = unzip(ReadableArchiveExporter().encode(snapshot))

        assertEquals(
            setOf(
                "README.txt",
                "manifest.json",
                "notes/2026-07-13.md",
                "todos.csv",
                "data/notes.json",
                "data/history.jsonl",
                "data/metadata.json",
                "settings/transcription-vocabulary.txt",
            ),
            files.keys,
        )
        assertTrue(files.getValue("notes/2026-07-13.md").contains("buy oat milk"))
        assertTrue(files.getValue("notes/2026-07-13.md").contains("#groceries"))
        assertTrue(files.getValue("data/metadata.json").contains("captured-on"))
        assertTrue(!files.getValue("data/metadata.json").contains("deleted-tag"))
        assertTrue(files.getValue("todos.csv").contains("call Ada"))
        assertTrue(files.getValue("todos.csv").contains("excerpt"))
        assertTrue(files.getValue("data/history.jsonl").contains("buy milk"))
        assertTrue(files.getValue("data/notes.json").contains("2026-07-13T10:45:00Z"))
        assertTrue(files.getValue("data/notes.json").contains("elevenlabs_scribe_v2"))
        assertTrue(files.getValue("notes/2026-07-13.md").contains("ElevenLabs Scribe v2"))
        assertTrue(files.getValue("notes/2026-07-13.md").contains("remember the milk"))
        assertTrue(!files.getValue("notes/2026-07-13.md").contains("SHOULD-NOT-EXPORT"))
        assertTrue(!files.getValue("notes/2026-07-13.md").contains("audio-voice-1"))
        assertTrue(!files.getValue("data/history.jsonl").contains("deleted original"))
        assertEquals("Milchreis\nRīga\n", files.getValue("settings/transcription-vocabulary.txt"))
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
