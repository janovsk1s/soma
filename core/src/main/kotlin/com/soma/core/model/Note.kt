package com.soma.core.model

import java.time.Instant
import java.time.LocalDate

enum class EntryKind {
    TEXT,
    VOICE,
}

enum class AudioFormat {
    WAV,
    FLAC,
}

/**
 * An opaque reference to an encrypted audio file owned by the storage layer.
 *
 * [fileId] is deliberately not a path: callers must ask storage for a short-lived,
 * decrypted playback/transcription source instead of bypassing encryption.
 */
data class AudioAttachment(
    val fileId: String,
    val format: AudioFormat,
    val durationMillis: Long,
    val byteCount: Long,
    val sampleRateHz: Int = WHISPER_SAMPLE_RATE_HZ,
    val channelCount: Int = 1,
) {
    init {
        require(isValidFileId(fileId)) { "Audio fileId is not a safe opaque identifier" }
        require(durationMillis >= 0) { "Audio duration must not be negative" }
        require(byteCount >= 0) { "Audio byte count must not be negative" }
        require(sampleRateHz > 0) { "Audio sample rate must be positive" }
        require(channelCount > 0) { "Audio channel count must be positive" }
    }

    companion object {
        const val WHISPER_SAMPLE_RATE_HZ: Int = 16_000
        private val SAFE_FILE_ID = Regex("[A-Za-z0-9_-]{1,80}")

        fun isValidFileId(value: String): Boolean = SAFE_FILE_ID.matches(value)
    }
}

enum class EntryTranscriptionState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    DISABLED,
}

data class TranscriptionInfo(
    val state: EntryTranscriptionState,
    val attemptCount: Int = 0,
    val detectedLanguages: List<SupportedLanguage> = emptyList(),
    val provenance: TranscriptionProvenance? = null,
    val updatedAt: Instant,
    val failure: TranscriptionFailure? = null,
) {
    init {
        require(attemptCount >= 0) { "Transcription attempt count must not be negative" }
        require(failure == null || state == EntryTranscriptionState.FAILED) {
            "A transcription failure is only valid in FAILED state"
        }
        require(provenance == null || state == EntryTranscriptionState.SUCCEEDED) {
            "Transcription provenance is only valid after successful transcription"
        }
    }
}

/**
 * A persistence-friendly note entry. Voice transcript text uses the same [text] field
 * as typed entries, which keeps editing and future full-text indexing straightforward.
 */
data class NoteEntry(
    val id: String,
    val noteDate: LocalDate,
    val position: Int,
    val kind: EntryKind,
    val text: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Last deliberate user text edit; system updates such as transcription do not change it. */
    val lastUserEditedAt: Instant? = null,
    val returnLater: Boolean = false,
    val audio: AudioAttachment? = null,
    val transcription: TranscriptionInfo? = null,
) {
    init {
        require(id.isNotBlank()) { "Entry id must not be blank" }
        require(position >= 0) { "Entry position must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "Entry update cannot precede creation" }
        require(lastUserEditedAt == null || !lastUserEditedAt.isBefore(createdAt)) {
            "Entry edit cannot precede creation"
        }
        require(kind == EntryKind.VOICE || audio == null) { "Text entries cannot have audio" }
        require(kind == EntryKind.VOICE || transcription == null) {
            "Text entries cannot have transcription state"
        }
        require(kind != EntryKind.VOICE || audio != null) { "Voice entries require audio" }
    }

    val hasTranscript: Boolean
        get() = kind == EntryKind.VOICE && text.isNotBlank()

    companion object {
        fun text(
            id: String,
            noteDate: LocalDate,
            position: Int,
            text: String,
            createdAt: Instant,
        ): NoteEntry = NoteEntry(
            id = id,
            noteDate = noteDate,
            position = position,
            kind = EntryKind.TEXT,
            text = text,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

        fun voice(
            id: String,
            noteDate: LocalDate,
            position: Int,
            audio: AudioAttachment,
            createdAt: Instant,
            transcriptionEnabled: Boolean,
        ): NoteEntry = NoteEntry(
            id = id,
            noteDate = noteDate,
            position = position,
            kind = EntryKind.VOICE,
            text = "",
            createdAt = createdAt,
            updatedAt = createdAt,
            audio = audio,
            transcription = TranscriptionInfo(
                state = if (transcriptionEnabled) {
                    EntryTranscriptionState.QUEUED
                } else {
                    EntryTranscriptionState.DISABLED
                },
                updatedAt = createdAt,
            ),
        )
    }
}

/** One encrypted text snapshot created by a deliberate user edit. */
data class EntryRevision(
    val entryId: String,
    val revision: Long,
    val text: String,
    val editedAt: Instant,
) {
    init {
        require(entryId.isNotBlank()) { "Revision entry id must not be blank" }
        require(revision > 0) { "User revision numbers start at one" }
    }
}

/** One note per local calendar day. Repositories must enforce the date as a unique key. */
data class DailyNote(
    val date: LocalDate,
    val createdAt: Instant,
    val entries: List<NoteEntry> = emptyList(),
) {
    init {
        require(entries.all { it.noteDate == date }) { "Every entry must belong to the note date" }
        require(entries.zipWithNext().all { (first, second) -> first.position < second.position }) {
            "Entries must have unique positions and be ordered ascending"
        }
    }
}
