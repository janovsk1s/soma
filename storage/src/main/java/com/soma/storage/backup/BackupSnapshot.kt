package com.soma.storage.backup

import com.soma.core.model.DailyNote
import com.soma.core.model.AudioAttachment
import com.soma.core.model.EntryKind
import com.soma.core.model.StillOpenDismissal
import com.soma.core.model.Todo
import com.soma.core.model.TodoSuggestion
import com.soma.core.model.TranscriptionJob
import java.time.Instant

/**
 * Versioned, portable representation of everything needed to rebuild Soma's stores.
 *
 * [audioContainers] contains portable WAV bytes protected by the backup's outer
 * passphrase encryption. It is empty when the user chooses a text-only export.
 * Importers must immediately wrap the PCM in a fresh destination-device audio key.
 */
data class BackupSnapshot(
    val payloadVersion: Int = CURRENT_PAYLOAD_VERSION,
    val exportedAt: Instant,
    val notes: List<DailyNote>,
    val todos: List<Todo>,
    val suggestions: List<TodoSuggestion>,
    val stillOpenDismissals: List<StillOpenDismissal> = emptyList(),
    val transcriptionJobs: List<TranscriptionJob> = emptyList(),
    val audioContainers: List<BackupAudioContainer> = emptyList(),
) {
    init {
        require(payloadVersion > 0) { "Payload version must be positive" }
        require(notes.map { it.date }.distinct().size == notes.size) {
            "A backup cannot contain two notes for the same day"
        }
        val entryIds = notes.flatMap { note -> note.entries.map { it.id } }
        require(entryIds.distinct().size == entryIds.size) {
            "A backup cannot contain duplicate entry ids"
        }
        require(todos.map { it.id }.distinct().size == todos.size) {
            "A backup cannot contain duplicate todo ids"
        }
        require(suggestions.map { it.id }.distinct().size == suggestions.size) {
            "A backup cannot contain duplicate suggestion ids"
        }
        require(stillOpenDismissals.map { it.date }.distinct().size == stillOpenDismissals.size) {
            "A backup cannot contain duplicate still-open dismissals"
        }
        require(transcriptionJobs.map { it.id }.distinct().size == transcriptionJobs.size) {
            "A backup cannot contain duplicate transcription job ids"
        }
        require(transcriptionJobs.map { it.entryId }.distinct().size == transcriptionJobs.size) {
            "A backup cannot contain multiple transcription jobs for one entry"
        }
        require(audioContainers.map { it.fileId }.distinct().size == audioContainers.size) {
            "A backup cannot contain duplicate audio file ids"
        }
        val entriesById = notes.flatMap { note -> note.entries.map { entry -> entry.id to entry } }.toMap()
        val attachmentIds = entriesById.values.mapNotNull { it.audio?.fileId }
        require(attachmentIds.distinct().size == attachmentIds.size) {
            "Each voice entry must own a distinct audio file"
        }
        require(attachmentIds.all { AudioAttachment.isValidFileId(it) }) {
            "A backup contains an unsafe audio file id"
        }
        require(audioContainers.all { it.fileId in attachmentIds }) {
            "A backup contains audio that is not referenced by an entry"
        }
        suggestions.forEach { suggestion ->
            require(suggestion.entryId in entriesById) { "A suggestion references a missing entry" }
        }
        transcriptionJobs.forEach { job ->
            require(entriesById[job.entryId]?.kind == EntryKind.VOICE) {
                "A transcription job must reference a voice entry"
            }
        }
        todos.forEach { todo ->
            todo.source?.let { source ->
                val entry = entriesById[source.entryId]
                require(entry != null && entry.noteDate == source.noteDate) {
                    "A todo source must reference its entry's note"
                }
            }
        }
    }

    companion object {
        const val CURRENT_PAYLOAD_VERSION: Int = 1
    }
}

/** Immutable-by-copy wrapper with content-based equality for a portable WAV payload. */
class BackupAudioContainer(
    val fileId: String,
    portableWavBytes: ByteArray,
) {
    private val bytes = portableWavBytes.copyOf()

    init {
        require(AudioAttachment.isValidFileId(fileId)) { "Audio file id is unsafe" }
        require(bytes.isNotEmpty()) { "Portable audio payload must not be empty" }
    }

    fun portableWavBytes(): ByteArray = bytes.copyOf()

    /** Best-effort erasure once an import no longer needs the portable audio. */
    fun clearPortableBytes() = bytes.fill(0)

    internal fun writeBytes(block: (ByteArray) -> Unit) = block(bytes)

    override fun equals(other: Any?): Boolean =
        other is BackupAudioContainer && fileId == other.fileId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * fileId.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "BackupAudioContainer(fileId=$fileId, wavByteCount=${bytes.size})"
}
