package com.soma.storage.backup

import com.soma.core.model.DailyNote
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryRevision
import com.soma.core.model.ImportantKind
import com.soma.core.model.NoteEntry
import com.soma.core.model.Todo
import com.soma.core.model.TodoState
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A one-way, plaintext export intended to remain useful in Obsidian, Logseq, or a text editor.
 *
 * This deliberately does not attempt to be a restorable Soma backup. Stable hashed anchors keep
 * source links working without allowing old or imported entry ids to become ZIP paths.
 */
class MarkdownVaultExporter(
    private val zoneId: ZoneId = ZoneOffset.UTC,
) {
    fun encode(snapshot: BackupSnapshot): ByteArray {
        val visible = snapshot.withoutDeletedContent()
        val revisions = visible.entryRevisions.groupBy(EntryRevision::entryId)
        val historyPaths = revisions.keys.associateWith { entryId ->
            val entry = visible.entry(entryId)
            "history/${entry.noteDate}-${entryToken(entryId)}.md"
        }
        val includedAudioIds = visible.audioContainers.mapTo(hashSetOf(), BackupAudioContainer::fileId)
        val audioPaths = visible.notes.asSequence()
            .flatMap { it.entries.asSequence() }
            .mapNotNull { entry ->
                entry.activeAudio?.fileId?.let { fileId ->
                    if (fileId in includedAudioIds) {
                        fileId to "media/${entry.noteDate}-$fileId.wav"
                    } else {
                        null
                    }
                }
            }
            .toMap()
        val includedImageIds = visible.imageContainers.mapTo(hashSetOf(), BackupImageContainer::fileId)
        val imagePaths = visible.notes.asSequence()
            .flatMap { it.entries.asSequence() }
            .mapNotNull { entry ->
                entry.activeImage?.fileId?.let { fileId ->
                    if (fileId in includedImageIds) {
                        fileId to "media/${entry.noteDate}-$fileId.jpg"
                    } else {
                        null
                    }
                }
            }
            .toMap()

        val output = ByteArrayOutputStream()
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
            zip.putText("README.md", readme(visible))
            zip.putText(".soma/manifest.json", manifest(visible))
            zip.putText("Important.md", importantMarkdown(visible.todos))
            visible.notes.sortedBy(DailyNote::date).forEach { note ->
                zip.putText(
                    "${note.date}.md",
                    noteMarkdown(note, revisions, historyPaths, audioPaths, imagePaths),
                )
                note.entries.forEach { entry ->
                    val earlier = revisions[entry.id].orEmpty().sortedBy(EntryRevision::revision)
                    if (earlier.isNotEmpty()) {
                        zip.putText(
                            historyPaths.getValue(entry.id),
                            historyMarkdown(entry, earlier),
                        )
                    }
                }
            }
            visible.audioContainers.sortedBy(BackupAudioContainer::fileId).forEach { audio ->
                val path = audioPaths[audio.fileId] ?: return@forEach
                val bytes = audio.portableWavBytes()
                try {
                    zip.putBytes(path, bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            visible.imageContainers.sortedBy(BackupImageContainer::fileId).forEach { image ->
                val path = imagePaths[image.fileId] ?: return@forEach
                val bytes = image.portableJpegBytes()
                try {
                    zip.putBytes(path, bytes)
                } finally {
                    bytes.fill(0)
                }
            }
        }
        return output.toByteArray()
    }

    private fun readme(snapshot: BackupSnapshot): String = buildString {
        appendLine("# Soma Markdown vault")
        appendLine()
        appendLine("Created: ${snapshot.exportedAt}")
        appendLine("Time zone used for headings: $zoneId")
        appendLine()
        appendLine("Daily notes are the `YYYY-MM-DD.md` files in this folder.")
        appendLine("`Important.md` is a portable checklist linked back to source entries.")
        appendLine("`history/` preserves earlier wordings after deliberate edits.")
        appendLine("`media/` contains standard WAV recordings and JPEG originals when media was included.")
        appendLine()
        appendLine("Unzip this folder and open it as an Obsidian vault, a Logseq graph, or plain text.")
        appendLine("This is a one-way export: Soma does not import Markdown vaults.")
        appendLine("It is not encrypted. Store it somewhere you trust.")
    }

    private fun manifest(snapshot: BackupSnapshot): String = buildString {
        appendLine("{")
        appendLine("  \"format\": \"soma-markdown-vault\",")
        appendLine("  \"version\": $FORMAT_VERSION,")
        appendLine("  \"exportedAt\": ${quoted(snapshot.exportedAt.toString())},")
        appendLine("  \"timeZone\": ${quoted(zoneId.id)},")
        appendLine("  \"noteCount\": ${snapshot.notes.size},")
        appendLine("  \"entryCount\": ${snapshot.notes.sumOf { it.entries.size }},")
        appendLine("  \"importantCount\": ${snapshot.todos.size},")
        appendLine("  \"revisionCount\": ${snapshot.entryRevisions.size},")
        appendLine("  \"audioCount\": ${snapshot.audioContainers.size},")
        appendLine("  \"imageCount\": ${snapshot.imageContainers.size}")
        appendLine("}")
    }

    private fun noteMarkdown(
        note: DailyNote,
        revisions: Map<String, List<EntryRevision>>,
        historyPaths: Map<String, String>,
        audioPaths: Map<String, String>,
        imagePaths: Map<String, String>,
    ): String = buildString {
        val lastEdited = note.entries.mapNotNull(NoteEntry::lastUserEditedAt).maxOrNull()
        appendLine("---")
        appendLine("date: ${quoted(note.date.toString())}")
        appendLine("created: ${quoted(note.createdAt.toString())}")
        appendLine("last_edited: ${lastEdited?.let { quoted(it.toString()) } ?: "null"}")
        appendLine("tags: []")
        appendLine("soma_timezone: ${quoted(zoneId.id)}")
        appendLine("---")
        appendLine()
        appendLine("# ${note.date}")
        appendLine()
        if (note.entries.isEmpty()) appendLine("_No entries._")
        note.entries.sortedBy(NoteEntry::position).forEach { entry ->
            append("## ${LOCAL_TIME.format(entry.createdAt.atZone(zoneId))}")
            if (entry.kind == EntryKind.VOICE) append(" · voice")
            if (entry.kind == EntryKind.IMAGE) append(" · photo")
            appendLine()
            appendLine()
            appendLine(entry.text.ifBlank {
                if (entry.kind == EntryKind.IMAGE) "_(photo)_" else "_(no transcript)_"
            })
            appendLine()
            appendLine("^soma-${entryToken(entry.id)}")
            entry.lastUserEditedAt?.let {
                appendLine()
                appendLine("_Edited ${it}_")
            }
            if (entry.returnLater) {
                appendLine()
                appendLine("_Return later_")
            }
            entry.transcription?.provenance?.let { provenance ->
                appendLine()
                appendLine("_Transcription: ${provenance.usedEngine.name.lowercase()}_")
            }
            if (revisions[entry.id].orEmpty().isNotEmpty()) {
                appendLine()
                appendLine("[[${historyPaths.getValue(entry.id).removeSuffix(".md")}|Earlier wordings]]")
            }
            entry.activeAudio?.fileId?.let { fileId ->
                audioPaths[fileId]?.let { path ->
                    appendLine()
                    appendLine("![[${path}]]")
                }
            }
            entry.activeImage?.fileId?.let { fileId ->
                imagePaths[fileId]?.let { path ->
                    appendLine()
                    appendLine("![[${path}]]")
                }
            }
            appendLine()
        }
    }

    private fun historyMarkdown(entry: NoteEntry, revisions: List<EntryRevision>): String = buildString {
        appendLine("---")
        appendLine("date: ${quoted(entry.noteDate.toString())}")
        appendLine("created: ${quoted(entry.createdAt.toString())}")
        appendLine("soma_entry_id: ${quoted(entry.id)}")
        appendLine("tags: []")
        appendLine("---")
        appendLine()
        appendLine("# Earlier wordings")
        appendLine()
        appendLine("[[${entry.noteDate}#^soma-${entryToken(entry.id)}|Back to entry]]")
        appendLine()
        revisions.forEachIndexed { index, revision ->
            appendLine("## ${if (index == 0) "Original" else "Version ${revision.revision}"}")
            appendLine()
            appendLine("_Replaced ${revision.editedAt}_")
            appendLine()
            appendLine(revision.text)
            appendLine()
        }
        appendLine("## Current")
        appendLine()
        appendLine(entry.text)
    }

    private fun importantMarkdown(todos: List<Todo>): String = buildString {
        appendLine("---")
        appendLine("tags: []")
        appendLine("---")
        appendLine()
        appendLine("# Important")
        appendLine()
        importantSection("Open", todos.filter { it.state == TodoState.OPEN }, checked = false)
        importantSection("Done", todos.filter { it.state == TodoState.DONE }, checked = true)
        importantSection("Let go", todos.filter { it.state == TodoState.ARCHIVED }, checked = true)
    }

    private fun StringBuilder.importantSection(title: String, todos: List<Todo>, checked: Boolean) {
        appendLine("## $title")
        appendLine()
        if (todos.isEmpty()) {
            appendLine("_None._")
            appendLine()
            return
        }
        todos.sortedWith(compareBy(Todo::createdAt, Todo::id)).forEach { todo ->
            val lines = todo.text.lines().ifEmpty { listOf("") }
            append("- [${if (checked) "x" else " "}] ${lines.first()}")
            if (todo.kind != ImportantKind.ACTION) append(" · _${todo.kind.name.lowercase()}_")
            todo.source?.let { source ->
                append(" · [[${source.noteDate}#^soma-${entryToken(source.entryId)}|source]]")
            }
            todo.resurfaceOn?.let { append(" · _show again ${it}_") }
            appendLine()
            lines.drop(1).forEach { appendLine("    $it") }
        }
        appendLine()
    }

    private fun BackupSnapshot.entry(id: String): NoteEntry = notes.asSequence()
        .flatMap { it.entries.asSequence() }
        .first { it.id == id }

    private fun entryToken(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8))
        .take(TOKEN_BYTES)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun quoted(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun ZipOutputStream.putText(path: String, value: String) =
        putBytes(path, value.toByteArray(Charsets.UTF_8))

    private fun ZipOutputStream.putBytes(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path).apply { time = FIXED_ZIP_TIME_MILLIS })
        write(bytes)
        closeEntry()
    }

    private companion object {
        const val FORMAT_VERSION = 2
        const val TOKEN_BYTES = 8
        // 1980-01-01T00:00:00Z stays inside the DOS timestamp range understood by old ZIP tools.
        const val FIXED_ZIP_TIME_MILLIS = 315_532_800_000L
        val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
