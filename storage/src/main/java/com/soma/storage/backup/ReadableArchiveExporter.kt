package com.soma.storage.backup

import com.soma.core.model.DailyNote
import com.soma.core.model.EntryKind
import com.soma.core.model.NoteEntry
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a deliberately boring, dependency-free ZIP that remains useful without Soma.
 *
 * Markdown and CSV are for people; JSON/JSONL preserve exact timestamps, ids, and edit
 * history for a future importer. All timestamps are ISO-8601 UTC instants so exports do
 * not silently change meaning when opened in another timezone.
 */
class ReadableArchiveExporter {
    fun encode(snapshot: BackupSnapshot): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
            zip.putText("README.txt", readme(snapshot))
            zip.putText("manifest.json", manifest(snapshot))
            snapshot.notes.sortedBy { it.date }.forEach { note ->
                zip.putText("notes/${note.date}.md", noteMarkdown(note))
            }
            zip.putText("todos.csv", todosCsv(snapshot))
            zip.putText("data/notes.json", notesJson(snapshot))
            zip.putText("data/history.jsonl", historyJsonl(snapshot))
            snapshot.audioContainers.sortedBy { it.fileId }.forEach { audio ->
                val date = snapshot.notes.asSequence()
                    .flatMap { note -> note.entries.asSequence() }
                    .firstOrNull { it.audio?.fileId == audio.fileId }
                    ?.noteDate
                    ?.toString()
                    ?: "unknown-date"
                val bytes = audio.portableWavBytes()
                try {
                    zip.putNextEntry(ZipEntry("audio/$date-${audio.fileId}.wav"))
                    zip.write(bytes)
                    zip.closeEntry()
                } finally {
                    bytes.fill(0)
                }
            }
        }
        return output.toByteArray()
    }

    private fun readme(snapshot: BackupSnapshot): String = buildString {
        appendLine("Soma readable archive")
        appendLine("=====================")
        appendLine()
        appendLine("Created: ${snapshot.exportedAt}")
        appendLine("Timestamps: ISO-8601 UTC")
        appendLine()
        appendLine("notes/ contains one human-readable Markdown file per day.")
        appendLine("todos.csv opens in spreadsheet applications.")
        appendLine("data/notes.json preserves complete structured note data.")
        appendLine("data/history.jsonl preserves previous text after user edits.")
        appendLine("audio/ contains standard 16 kHz mono WAV files when included.")
        appendLine()
        appendLine("This ZIP is intentionally not encrypted. Store it somewhere you trust.")
        appendLine("It is designed to remain readable even if Soma no longer exists.")
    }

    private fun manifest(snapshot: BackupSnapshot): String = buildString {
        append('{')
        jsonField("format", "soma-readable-archive")
        append(',')
        jsonNumberField("version", READABLE_FORMAT_VERSION)
        append(',')
        jsonField("exportedAt", snapshot.exportedAt.toString())
        append(',')
        jsonNumberField("noteCount", snapshot.notes.size)
        append(',')
        jsonNumberField("entryCount", snapshot.notes.sumOf { it.entries.size })
        append(',')
        jsonNumberField("todoCount", snapshot.todos.size)
        append(',')
        jsonNumberField("revisionCount", snapshot.entryRevisions.size)
        append(',')
        jsonNumberField("audioCount", snapshot.audioContainers.size)
        append('}')
        appendLine()
    }

    private fun noteMarkdown(note: DailyNote): String = buildString {
        appendLine("# ${note.date}")
        appendLine()
        if (note.entries.isEmpty()) appendLine("_No entries._")
        note.entries.sortedBy(NoteEntry::position).forEach { entry ->
            val kind = if (entry.kind == EntryKind.VOICE) "voice" else "text"
            append("- **${TIME.format(entry.createdAt)}**")
            if (kind == "voice") append(" · voice")
            if (entry.returnLater) append(" · return later")
            appendLine()
            appendLine("  ${markdownText(entry.text.ifBlank { "_(no transcript)_" })}")
            entry.lastUserEditedAt?.let { appendLine("  _edited ${TIME.format(it)}_") }
            entry.audio?.let { appendLine("  _audio id: `${it.fileId}`_") }
            appendLine()
        }
    }

    private fun todosCsv(snapshot: BackupSnapshot): String = buildString {
        appendLine("id,text,state,created_at,updated_at,closed_at,source_date,source_entry_id")
        snapshot.todos.sortedBy { it.createdAt }.forEach { todo ->
            appendLine(
                listOf(
                    todo.id,
                    todo.text,
                    todo.state.name.lowercase(),
                    todo.createdAt.toString(),
                    todo.updatedAt.toString(),
                    todo.closedAt?.toString().orEmpty(),
                    todo.source?.noteDate?.toString().orEmpty(),
                    todo.source?.entryId.orEmpty(),
                ).joinToString(",", transform = ::csv),
            )
        }
    }

    private fun notesJson(snapshot: BackupSnapshot): String = buildString {
        append("{\"format\":\"soma-notes\",\"version\":1,\"exportedAt\":")
        appendJson(snapshot.exportedAt.toString())
        append(",\"notes\":[")
        snapshot.notes.sortedBy { it.date }.forEachIndexed { noteIndex, note ->
            if (noteIndex > 0) append(',')
            append("{\"date\":")
            appendJson(note.date.toString())
            append(",\"createdAt\":")
            appendJson(note.createdAt.toString())
            append(",\"entries\":[")
            note.entries.sortedBy(NoteEntry::position).forEachIndexed { entryIndex, entry ->
                if (entryIndex > 0) append(',')
                entryJson(entry)
            }
            append("]}")
        }
        append("]}\n")
    }

    private fun StringBuilder.entryJson(entry: NoteEntry) {
        append('{')
        jsonField("id", entry.id)
        append(',')
        jsonNumberField("position", entry.position)
        append(',')
        jsonField("kind", entry.kind.name.lowercase())
        append(',')
        jsonField("text", entry.text)
        append(',')
        jsonField("createdAt", entry.createdAt.toString())
        append(',')
        jsonField("updatedAt", entry.updatedAt.toString())
        append(",\"lastUserEditedAt\":")
        appendNullableJson(entry.lastUserEditedAt?.toString())
        append(",\"returnLater\":${entry.returnLater}")
        append(",\"audio\":")
        val audio = entry.audio
        if (audio == null) {
            append("null")
        } else {
            append('{')
            jsonField("fileId", audio.fileId)
            append(',')
            jsonField("format", audio.format.name.lowercase())
            append(',')
            jsonNumberField("durationMillis", audio.durationMillis)
            append(',')
            jsonNumberField("byteCount", audio.byteCount)
            append(',')
            jsonNumberField("sampleRateHz", audio.sampleRateHz)
            append(',')
            jsonNumberField("channelCount", audio.channelCount)
            append('}')
        }
        append('}')
    }

    private fun historyJsonl(snapshot: BackupSnapshot): String = buildString {
        snapshot.entryRevisions.sortedWith(compareBy({ it.entryId }, { it.revision })).forEach { revision ->
            append('{')
            jsonField("entryId", revision.entryId)
            append(',')
            jsonNumberField("revision", revision.revision)
            append(',')
            jsonField("text", revision.text)
            append(',')
            jsonField("editedAt", revision.editedAt.toString())
            appendLine('}')
        }
    }

    private fun markdownText(value: String): String = value.replace("\n", "\n  ")

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun StringBuilder.jsonField(name: String, value: String) {
        appendJson(name)
        append(':')
        appendJson(value)
    }

    private fun StringBuilder.jsonNumberField(name: String, value: Number) {
        appendJson(name)
        append(':')
        append(value)
    }

    private fun StringBuilder.appendNullableJson(value: String?) {
        if (value == null) append("null") else appendJson(value)
    }

    private fun StringBuilder.appendJson(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun ZipOutputStream.putText(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private companion object {
        const val READABLE_FORMAT_VERSION = 1
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
    }
}
