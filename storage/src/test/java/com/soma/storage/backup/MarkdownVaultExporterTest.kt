package com.soma.storage.backup

import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.DailyNote
import com.soma.core.model.EntryRevision
import com.soma.core.model.EntryLink
import com.soma.core.model.EntryLinkKind
import com.soma.core.model.EntryMetadata
import com.soma.core.model.EntrySource
import com.soma.core.model.ImportantKind
import com.soma.core.model.ImageAttachment
import com.soma.core.model.ImageFormat
import com.soma.core.model.MetadataSource
import com.soma.core.model.NoteEntry
import com.soma.core.model.LogKind
import com.soma.core.model.LogRecord
import com.soma.core.model.LogRevision
import com.soma.core.model.Todo
import com.soma.core.model.WorkoutExercise
import com.soma.core.model.WorkoutSet
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownVaultExporterTest {
    @Test
    fun `vault is portable linked and excludes recoverable deletions`() {
        val date = LocalDate.of(2026, 7, 14)
        val created = Instant.parse("2026-07-14T08:12:00Z")
        val edited = Instant.parse("2026-07-14T10:45:00Z")
        val entry = NoteEntry.text("../../entry", date, 0, "buy milk", created).copy(
            text = "buy oat milk",
            updatedAt = edited,
            lastUserEditedAt = edited,
        )
        val voice = NoteEntry.voice(
            id = "voice-1",
            noteDate = date,
            position = 1,
            audio = AudioAttachment("audio-voice-1", AudioFormat.WAV, 1_000, 32_044),
            createdAt = created.plusSeconds(60),
            transcriptionEnabled = false,
        ).copy(text = "Milchreis and milk")
        val deleted = NoteEntry.text(
            id = "deleted-entry",
            noteDate = date,
            position = 2,
            text = "SHOULD-NOT-EXPORT",
            createdAt = created.plusSeconds(120),
        ).copy(deletedAt = edited.plusSeconds(1))
        val photo = NoteEntry.image(
            id = "photo-entry",
            noteDate = date,
            position = 3,
            image = ImageAttachment("image-1", ImageFormat.JPEG, 1280, 960, 0, 5),
            createdAt = created.plusSeconds(180),
            caption = "train window",
        )
        val open = Todo(
            id = "open",
            text = "buy oats\nand cinnamon",
            createdAt = created,
            updatedAt = created,
            kind = ImportantKind.LIST,
            source = EntrySource(date, entry.id),
            resurfaceOn = date.plusWeeks(1),
        )
        val done = Todo("done", "call Ada", created, created).markDone(edited)
        val archived = Todo("archived", "old reference", created, created).archive(edited)
        val audioBytes = byteArrayOf(1, 2, 3, 4)
        val originalWorkout = LogRecord(
            id = "workout-1",
            kind = LogKind.WORKOUT,
            title = "Leg press",
            occurredAt = created,
            createdAt = created,
            updatedAt = created,
            source = EntrySource(date, entry.id),
            exercises = listOf(WorkoutExercise("Leg press", sets = listOf(WorkoutSet(10, 80.0)))),
        )
        val currentWorkout = originalWorkout.revise(
            exercises = listOf(WorkoutExercise("Leg press", sets = listOf(WorkoutSet(12, 80.0)))),
            at = edited,
        )
        val snapshot = BackupSnapshot(
            exportedAt = Instant.parse("2026-07-14T11:00:00Z"),
            notes = listOf(DailyNote(date, created, listOf(entry, voice, deleted, photo))),
            entryRevisions = listOf(
                EntryRevision(entry.id, 1, "buy milk", edited),
                EntryRevision(deleted.id, 1, "deleted original", edited),
            ),
            entryMetadata = listOf(
                EntryMetadata(
                    entryId = entry.id,
                    tags = listOf("groceries", "milk-rice"),
                    links = listOf(
                        EntryLink(EntryLinkKind.DATE, date.minusDays(1).toString()),
                        EntryLink(EntryLinkKind.ENTRY, voice.id, "related-voice"),
                    ),
                    derivedAt = edited,
                    source = MetadataSource.AI,
                ),
            ),
            trackingLogs = listOf(currentWorkout),
            trackingLogRevisions = listOf(
                LogRevision(originalWorkout.id, 0, originalWorkout, edited),
            ),
            todos = listOf(open, done, archived),
            suggestions = emptyList(),
            audioContainers = listOf(BackupAudioContainer("audio-voice-1", audioBytes)),
            imageContainers = listOf(
                BackupImageContainer("image-1", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())),
            ),
        )

        val exporter = MarkdownVaultExporter(ZoneId.of("Europe/Vienna"))
        val encoded = exporter.encode(snapshot)
        val files = unzip(encoded)
        assertArrayEquals(encoded, exporter.encode(snapshot))

        val historyPath = files.keys.single { it.startsWith("history/2026-07-14-") }
        val logHistoryPath = files.keys.single { it.startsWith("history/log-") }
        assertEquals(
            setOf(
                "README.md",
                ".soma/manifest.json",
                "Important.md",
                "Logs.md",
                "2026-07-14.md",
                historyPath,
                logHistoryPath,
                "media/2026-07-14-audio-voice-1.wav",
                "media/2026-07-14-image-1.jpg",
            ),
            files.keys,
        )
        assertFalse(files.keys.any { ".." in it })

        val day = files.getValue("2026-07-14.md").toString(Charsets.UTF_8)
        assertTrue(day.contains("date: \"2026-07-14\""))
        assertTrue(day.contains("created: \"2026-07-14T08:12:00Z\""))
        assertTrue(day.contains("last_edited: \"2026-07-14T10:45:00Z\""))
        assertTrue(day.contains("tags: [\"groceries\", \"milk-rice\"]"))
        assertTrue(day.contains("soma_timezone: \"Europe/Vienna\""))
        assertTrue(day.contains("## 10:12"))
        assertTrue(day.contains("buy oat milk"))
        assertTrue(day.contains("Milchreis and milk"))
        assertTrue(day.contains("[[${historyPath.removeSuffix(".md")}|Earlier wordings]]"))
        assertTrue(day.contains("![[media/2026-07-14-audio-voice-1.wav]]"))
        assertTrue(day.contains("![[media/2026-07-14-image-1.jpg]]"))
        assertTrue(day.contains("train window"))
        assertTrue(day.contains("#groceries #milk-rice"))
        assertTrue(day.contains("[[2026-07-13]]"))
        assertTrue(day.contains("|related-voice]]"))
        assertFalse(day.contains("SHOULD-NOT-EXPORT"))

        val manifest = files.getValue(".soma/manifest.json").toString(Charsets.UTF_8)
        assertTrue(manifest.contains("\"version\": 5"))
        assertTrue(manifest.contains("\"metadataLayerCount\": 1"))

        val important = files.getValue("Important.md").toString(Charsets.UTF_8)
        assertTrue(important.contains("- [ ] buy oats · _list_"))
        assertTrue(important.contains("    and cinnamon"))
        assertTrue(important.contains("[[2026-07-14#^soma-"))
        assertTrue(important.contains("_show again 2026-07-21_"))
        assertTrue(important.contains("- [x] call Ada"))
        assertTrue(important.contains("- [x] old reference"))

        val history = files.getValue(historyPath).toString(Charsets.UTF_8)
        assertTrue(history.contains("## Original"))
        assertTrue(history.contains("buy milk"))
        assertTrue(history.contains("## Current"))
        assertTrue(history.contains("buy oat milk"))
        assertFalse(history.contains("deleted original"))
        assertArrayEquals(audioBytes, files.getValue("media/2026-07-14-audio-voice-1.wav"))

        val logs = files.getValue("Logs.md").toString(Charsets.UTF_8)
        assertTrue(logs.contains("Leg press"))
        assertTrue(logs.contains("12 reps · 80 kg"))
        assertTrue(logs.contains("|Source entry]]"))
        val logHistory = files.getValue(logHistoryPath).toString(Charsets.UTF_8)
        assertTrue(logHistory.contains("## Original"))
        assertTrue(logHistory.contains("10 reps · 80 kg"))
    }

    @Test
    fun `text only vault keeps transcript without a dead audio link`() {
        val date = LocalDate.of(2026, 7, 14)
        val created = Instant.parse("2026-07-14T08:12:00Z")
        val voice = NoteEntry.voice(
            id = "voice-1",
            noteDate = date,
            position = 0,
            audio = AudioAttachment("audio-voice-1", AudioFormat.WAV, 1_000, 32_044),
            createdAt = created,
            transcriptionEnabled = false,
        ).copy(text = "keep this transcript")
        val snapshot = BackupSnapshot(
            exportedAt = created,
            notes = listOf(DailyNote(date, created, listOf(voice))),
            todos = emptyList(),
            suggestions = emptyList(),
        )

        val files = unzip(MarkdownVaultExporter().encode(snapshot))

        assertFalse(files.keys.any { it.startsWith("media/") })
        val day = files.getValue("2026-07-14.md").toString(Charsets.UTF_8)
        assertTrue(day.contains("keep this transcript"))
        assertFalse(day.contains("![[media/"))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
                zip.closeEntry()
            }
        }
    }
}
