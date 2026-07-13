package com.soma.storage.backup

import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.DailyNote
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryRevision
import com.soma.core.model.EntrySource
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.ImportantKind
import com.soma.core.model.NoteEntry
import com.soma.core.model.StillOpenDismissal
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.Todo
import com.soma.core.model.TodoState
import com.soma.core.model.TodoSuggestion
import com.soma.core.model.TodoSuggestionReason
import com.soma.core.model.TodoSuggestionState
import com.soma.core.model.TranscriptionFailure
import com.soma.core.model.TranscriptionFailureCode
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionFallbackReason
import com.soma.core.model.TranscriptionInfo
import com.soma.core.model.TranscriptionJob
import com.soma.core.model.TranscriptionJobState
import com.soma.core.model.TranscriptionProvenance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.time.LocalDate
import java.util.Arrays

/** Deterministic, dependency-free serializer for the plaintext inside a backup. */
internal object BackupPayloadCodec {
    fun encode(snapshot: BackupSnapshot): ByteArray {
        require(snapshot.payloadVersion == BackupSnapshot.CURRENT_PAYLOAD_VERSION) {
            "Cannot encode payload version ${snapshot.payloadVersion}"
        }
        val buffer = WipeableByteArrayOutputStream()
        return try {
            DataOutputStream(buffer).use { output ->
                output.writeInt(snapshot.payloadVersion)
                output.writeInstant(snapshot.exportedAt)
                output.writeList(snapshot.notes, ::writeNote)
                output.writeList(snapshot.todos, ::writeTodo)
                output.writeList(snapshot.suggestions, ::writeSuggestion)
                output.writeList(snapshot.stillOpenDismissals, ::writeDismissal)
                output.writeList(snapshot.transcriptionJobs, ::writeJob)
                output.writeList(snapshot.audioContainers, ::writeAudioContainer)
                output.writeList(snapshot.entryRevisions, ::writeEntryRevision)
                output.writeList(snapshot.transcriptionVocabulary) { target, term -> target.writeString(term) }
            }
            buffer.copyBytes()
        } finally {
            buffer.wipe()
        }
    }

    fun decode(plaintext: ByteArray, expectedVersion: Int): BackupSnapshot = try {
        val byteInput = ByteArrayInputStream(plaintext)
        val input = DataInputStream(byteInput)
        val payloadVersion = input.readInt()
        if (payloadVersion != expectedVersion || payloadVersion !in BackupSnapshot.SUPPORTED_PAYLOAD_VERSIONS) {
            throw BackupFormatException("Unsupported backup payload version: $payloadVersion")
        }
        val exportedAt = input.readInstant()
        val rawNotes = input.readList { source -> readNote(source, payloadVersion) }
        val todos = input.readList { source -> readTodo(source, payloadVersion) }
        val suggestions = input.readList { source -> readSuggestion(source, payloadVersion) }
        val dismissals = input.readList(::readDismissal)
        val jobs = input.readList(::readJob)
        val audio = input.readList(::readAudioContainer)
        val revisions = if (payloadVersion >= 2) input.readList(::readEntryRevision) else emptyList()
        val transcriptionVocabulary = if (payloadVersion >= 4) input.readList { it.readString() } else emptyList()
        val lastEdits = revisions.groupBy(EntryRevision::entryId)
            .mapValues { (_, values) -> values.maxBy(EntryRevision::editedAt).editedAt }
        val notes = rawNotes.map { note ->
            note.copy(entries = note.entries.map { entry ->
                entry.copy(lastUserEditedAt = lastEdits[entry.id])
            })
        }
        val snapshot = BackupSnapshot(
            payloadVersion = payloadVersion,
            exportedAt = exportedAt,
            notes = notes,
            entryRevisions = revisions,
            todos = todos,
            suggestions = suggestions,
            stillOpenDismissals = dismissals,
            transcriptionJobs = jobs,
            audioContainers = audio,
            transcriptionVocabulary = transcriptionVocabulary,
        )
        if (byteInput.available() != 0) {
            throw BackupFormatException("Backup payload has trailing bytes")
        }
        snapshot
    } catch (error: BackupException) {
        throw error
    } catch (error: EOFException) {
        throw BackupFormatException("Backup payload is truncated", error)
    } catch (error: CharacterCodingException) {
        throw BackupFormatException("Backup payload contains invalid UTF-8", error)
    } catch (error: IllegalArgumentException) {
        throw BackupFormatException("Backup payload contains invalid domain data", error)
    }

    private fun writeNote(output: DataOutput, note: DailyNote) {
        output.writeLong(note.date.toEpochDay())
        output.writeInstant(note.createdAt)
        output.writeInt(note.entries.size)
        note.entries.forEach { writeEntry(output, it) }
    }

    private fun readNote(input: DataInput, payloadVersion: Int): DailyNote {
        val date = LocalDate.ofEpochDay(input.readLong())
        val createdAt = input.readInstant()
        val count = input.readCount()
        val entries = ArrayList<NoteEntry>(count)
        repeat(count) { entries += readEntry(input, date, payloadVersion) }
        return DailyNote(date, createdAt, entries)
    }

    private fun writeEntry(output: DataOutput, entry: NoteEntry) {
        output.writeString(entry.id)
        output.writeInt(entry.position)
        output.writeEnum(entry.kind)
        output.writeString(entry.text)
        output.writeInstant(entry.createdAt)
        output.writeInstant(entry.updatedAt)
        output.writeBooleanByte(entry.returnLater)
        output.writeNullable(entry.audio, ::writeAudioAttachment)
        output.writeNullable(entry.transcription, ::writeTranscriptionInfo)
    }

    private fun readEntry(input: DataInput, noteDate: LocalDate, payloadVersion: Int): NoteEntry = NoteEntry(
        id = input.readString(),
        noteDate = noteDate,
        position = input.readInt(),
        kind = input.readEnum<EntryKind>(),
        text = input.readString(),
        createdAt = input.readInstant(),
        updatedAt = input.readInstant(),
        returnLater = input.readBooleanByte(),
        audio = input.readNullable(::readAudioAttachment),
        transcription = input.readNullable { source -> readTranscriptionInfo(source, payloadVersion) },
    )

    private fun writeEntryRevision(output: DataOutput, revision: EntryRevision) {
        output.writeString(revision.entryId)
        output.writeLong(revision.revision)
        output.writeString(revision.text)
        output.writeInstant(revision.editedAt)
    }

    private fun readEntryRevision(input: DataInput): EntryRevision = EntryRevision(
        entryId = input.readString(),
        revision = input.readLong(),
        text = input.readString(),
        editedAt = input.readInstant(),
    )

    private fun writeAudioAttachment(output: DataOutput, audio: AudioAttachment) {
        output.writeString(audio.fileId)
        output.writeEnum(audio.format)
        output.writeLong(audio.durationMillis)
        output.writeLong(audio.byteCount)
        output.writeInt(audio.sampleRateHz)
        output.writeInt(audio.channelCount)
    }

    private fun readAudioAttachment(input: DataInput) = AudioAttachment(
        fileId = input.readString(),
        format = input.readEnum<AudioFormat>(),
        durationMillis = input.readLong(),
        byteCount = input.readLong(),
        sampleRateHz = input.readInt(),
        channelCount = input.readInt(),
    )

    private fun writeTranscriptionInfo(output: DataOutput, info: TranscriptionInfo) {
        output.writeEnum(info.state)
        output.writeInt(info.attemptCount)
        output.writeList(info.detectedLanguages) { target, language -> target.writeEnum(language) }
        output.writeInstant(info.updatedAt)
        output.writeNullable(info.failure, ::writeFailure)
        output.writeNullable(info.provenance, ::writeProvenance)
    }

    private fun readTranscriptionInfo(input: DataInput, payloadVersion: Int) = TranscriptionInfo(
        state = input.readEnum<EntryTranscriptionState>(),
        attemptCount = input.readInt(),
        detectedLanguages = input.readList { it.readEnum<SupportedLanguage>() },
        updatedAt = input.readInstant(),
        failure = input.readNullable(::readFailure),
        provenance = if (payloadVersion >= 3) input.readNullable(::readProvenance) else null,
    )

    private fun writeProvenance(output: DataOutput, provenance: TranscriptionProvenance) {
        output.writeEnum(provenance.requestedEngine)
        output.writeEnum(provenance.usedEngine)
        output.writeNullable(provenance.fallbackReason) { target, reason -> target.writeEnum(reason) }
    }

    private fun readProvenance(input: DataInput) = TranscriptionProvenance(
        requestedEngine = input.readEnum<TranscriptionEngine>(),
        usedEngine = input.readEnum<TranscriptionEngine>(),
        fallbackReason = input.readNullable { it.readEnum<TranscriptionFallbackReason>() },
    )

    private fun writeTodo(output: DataOutput, todo: Todo) {
        output.writeString(todo.id)
        output.writeString(todo.text)
        output.writeInstant(todo.createdAt)
        output.writeInstant(todo.updatedAt)
        output.writeInstant(todo.lastTouchedAt)
        output.writeEnum(todo.state)
        output.writeNullable(todo.source, ::writeSource)
        output.writeNullable(todo.closedAt) { target, value -> target.writeInstant(value) }
        output.writeNullable(todo.stalePromptShownAt) { target, value -> target.writeInstant(value) }
        output.writeEnum(todo.kind)
    }

    private fun readTodo(input: DataInput, payloadVersion: Int): Todo {
        val id = input.readString()
        val text = input.readString()
        val createdAt = input.readInstant()
        val updatedAt = input.readInstant()
        val lastTouchedAt = input.readInstant()
        val state = input.readEnum<TodoState>()
        val source = input.readNullable(::readSource)
        val closedAt = input.readNullable { it.readInstant() }
        val stalePromptShownAt = input.readNullable { it.readInstant() }
        val kind = if (payloadVersion >= 5) input.readEnum<ImportantKind>() else ImportantKind.ACTION
        return Todo(
            id = id,
            text = text,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastTouchedAt = lastTouchedAt,
            state = state,
            kind = kind,
            source = source,
            closedAt = closedAt,
            stalePromptShownAt = stalePromptShownAt,
        )
    }

    private fun writeSource(output: DataOutput, source: EntrySource) {
        output.writeLong(source.noteDate.toEpochDay())
        output.writeString(source.entryId)
    }

    private fun readSource(input: DataInput) = EntrySource(
        noteDate = LocalDate.ofEpochDay(input.readLong()),
        entryId = input.readString(),
    )

    private fun writeSuggestion(output: DataOutput, suggestion: TodoSuggestion) {
        output.writeString(suggestion.id)
        output.writeString(suggestion.entryId)
        output.writeString(suggestion.suggestedText)
        output.writeEnum(suggestion.language)
        output.writeEnum(suggestion.reason)
        output.writeString(suggestion.matchedRule)
        output.writeEnum(suggestion.state)
        output.writeInstant(suggestion.createdAt)
        output.writeNullable(suggestion.resolvedAt) { target, value -> target.writeInstant(value) }
        output.writeEnum(suggestion.suggestedKind)
    }

    private fun readSuggestion(input: DataInput, payloadVersion: Int): TodoSuggestion {
        val id = input.readString()
        val entryId = input.readString()
        val suggestedText = input.readString()
        val language = input.readEnum<SupportedLanguage>()
        val reason = input.readEnum<TodoSuggestionReason>()
        val matchedRule = input.readString()
        val state = input.readEnum<TodoSuggestionState>()
        val createdAt = input.readInstant()
        val resolvedAt = input.readNullable { it.readInstant() }
        val suggestedKind = if (payloadVersion >= 5) {
            input.readEnum<ImportantKind>()
        } else {
            ImportantKind.ACTION
        }
        return TodoSuggestion(
            id = id,
            entryId = entryId,
            suggestedText = suggestedText,
            suggestedKind = suggestedKind,
            language = language,
            reason = reason,
            matchedRule = matchedRule,
            state = state,
            createdAt = createdAt,
            resolvedAt = resolvedAt,
        )
    }

    private fun writeDismissal(output: DataOutput, dismissal: StillOpenDismissal) {
        output.writeLong(dismissal.date.toEpochDay())
        output.writeInstant(dismissal.dismissedAt)
    }

    private fun readDismissal(input: DataInput) = StillOpenDismissal(
        date = LocalDate.ofEpochDay(input.readLong()),
        dismissedAt = input.readInstant(),
    )

    private fun writeJob(output: DataOutput, job: TranscriptionJob) {
        output.writeString(job.id)
        output.writeString(job.entryId)
        output.writeEnum(job.state)
        output.writeInt(job.attemptCount)
        output.writeInstant(job.availableAt)
        output.writeNullable(job.leaseOwner) { target, value -> target.writeString(value) }
        output.writeNullable(job.leaseExpiresAt) { target, value -> target.writeInstant(value) }
        output.writeNullable(job.lastFailure, ::writeFailure)
        output.writeInstant(job.updatedAt)
    }

    private fun readJob(input: DataInput) = TranscriptionJob(
        id = input.readString(),
        entryId = input.readString(),
        state = input.readEnum<TranscriptionJobState>(),
        attemptCount = input.readInt(),
        availableAt = input.readInstant(),
        leaseOwner = input.readNullable { it.readString() },
        leaseExpiresAt = input.readNullable { it.readInstant() },
        lastFailure = input.readNullable(::readFailure),
        updatedAt = input.readInstant(),
    )

    private fun writeFailure(output: DataOutput, failure: TranscriptionFailure) {
        output.writeEnum(failure.code)
        output.writeBooleanByte(failure.retryable)
        output.writeNullable(failure.diagnostic) { target, value -> target.writeString(value) }
    }

    private fun readFailure(input: DataInput) = TranscriptionFailure(
        code = input.readEnum<TranscriptionFailureCode>(),
        retryable = input.readBooleanByte(),
        diagnostic = input.readNullable { it.readString() },
    )

    private fun writeAudioContainer(output: DataOutput, audio: BackupAudioContainer) {
        output.writeString(audio.fileId)
        audio.writeBytes { bytes ->
            require(bytes.size <= MAX_AUDIO_BYTES) { "Encrypted audio container is too large" }
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }

    private fun readAudioContainer(input: DataInput): BackupAudioContainer {
        val fileId = input.readString()
        val size = input.readBoundedLength(MAX_AUDIO_BYTES, "audio container")
        val bytes = ByteArray(size)
        return try {
            input.readFully(bytes)
            BackupAudioContainer(fileId, bytes)
        } finally {
            Arrays.fill(bytes, 0)
        }
    }

    private fun DataOutput.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInput.readInstant(): Instant {
        val seconds = readLong()
        val nanos = readInt()
        require(nanos in 0..999_999_999) { "Invalid nanosecond value" }
        return Instant.ofEpochSecond(seconds, nanos.toLong())
    }

    private fun DataOutput.writeString(value: String) {
        val encoded = value.encodeToByteArray()
        try {
            require(encoded.size <= MAX_STRING_BYTES) { "Backup string is too large" }
            writeInt(encoded.size)
            write(encoded)
        } finally {
            Arrays.fill(encoded, 0)
        }
    }

    @Throws(CharacterCodingException::class)
    private fun DataInput.readString(): String {
        val length = readBoundedLength(MAX_STRING_BYTES, "string")
        val encoded = ByteArray(length)
        return try {
            readFully(encoded)
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded)).toString()
        } finally {
            Arrays.fill(encoded, 0)
        }
    }

    private fun DataOutput.writeBooleanByte(value: Boolean) = writeByte(if (value) 1 else 0)

    private fun DataInput.readBooleanByte(): Boolean = when (val encoded = readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw BackupFormatException("Invalid boolean value: $encoded")
    }

    private fun DataOutput.writeEnum(value: Enum<*>) = writeString(value.name)

    private inline fun <reified T : Enum<T>> DataInput.readEnum(): T {
        val encoded = readString()
        return enumValues<T>().firstOrNull { it.name == encoded }
            ?: throw BackupFormatException("Unknown ${T::class.java.simpleName}: $encoded")
    }

    private fun <T> DataOutput.writeNullable(
        value: T?,
        writeValue: (DataOutput, T) -> Unit,
    ) {
        writeBooleanByte(value != null)
        if (value != null) writeValue(this, value)
    }

    private fun <T> DataInput.readNullable(readValue: (DataInput) -> T): T? =
        if (readBooleanByte()) readValue(this) else null

    private fun <T> DataOutput.writeList(values: List<T>, writeValue: (DataOutput, T) -> Unit) {
        require(values.size <= MAX_COLLECTION_SIZE) { "Backup collection is too large" }
        writeInt(values.size)
        values.forEach { writeValue(this, it) }
    }

    private fun <T> DataInput.readList(readValue: (DataInput) -> T): List<T> {
        val count = readCount()
        return List(count) { readValue(this) }
    }

    private fun DataInput.readCount(): Int = readBoundedLength(MAX_COLLECTION_SIZE, "collection")

    private fun DataInput.readBoundedLength(maximum: Int, label: String): Int {
        val value = readInt()
        if (value !in 0..maximum) throw BackupFormatException("Invalid $label length: $value")
        return value
    }

    private class WipeableByteArrayOutputStream : ByteArrayOutputStream() {
        fun copyBytes(): ByteArray = buf.copyOf(count)

        fun wipe() {
            Arrays.fill(buf, 0)
            reset()
        }
    }

    private const val MAX_COLLECTION_SIZE = 100_000
    private const val MAX_STRING_BYTES = 4 * 1024 * 1024
    private const val MAX_AUDIO_BYTES = 128 * 1024 * 1024
}
