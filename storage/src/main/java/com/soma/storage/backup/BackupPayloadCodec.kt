package com.soma.storage.backup

import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.DailyNote
import com.soma.core.model.EntryKind
import com.soma.core.model.EntrySource
import com.soma.core.model.EntryTranscriptionState
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
import com.soma.core.model.TranscriptionInfo
import com.soma.core.model.TranscriptionJob
import com.soma.core.model.TranscriptionJobState
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
        if (payloadVersion != expectedVersion || payloadVersion != BackupSnapshot.CURRENT_PAYLOAD_VERSION) {
            throw BackupFormatException("Unsupported backup payload version: $payloadVersion")
        }
        val snapshot = BackupSnapshot(
            payloadVersion = payloadVersion,
            exportedAt = input.readInstant(),
            notes = input.readList(::readNote),
            todos = input.readList(::readTodo),
            suggestions = input.readList(::readSuggestion),
            stillOpenDismissals = input.readList(::readDismissal),
            transcriptionJobs = input.readList(::readJob),
            audioContainers = input.readList(::readAudioContainer),
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

    private fun readNote(input: DataInput): DailyNote {
        val date = LocalDate.ofEpochDay(input.readLong())
        val createdAt = input.readInstant()
        val count = input.readCount()
        val entries = ArrayList<NoteEntry>(count)
        repeat(count) { entries += readEntry(input, date) }
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

    private fun readEntry(input: DataInput, noteDate: LocalDate): NoteEntry = NoteEntry(
        id = input.readString(),
        noteDate = noteDate,
        position = input.readInt(),
        kind = input.readEnum<EntryKind>(),
        text = input.readString(),
        createdAt = input.readInstant(),
        updatedAt = input.readInstant(),
        returnLater = input.readBooleanByte(),
        audio = input.readNullable(::readAudioAttachment),
        transcription = input.readNullable(::readTranscriptionInfo),
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
    }

    private fun readTranscriptionInfo(input: DataInput) = TranscriptionInfo(
        state = input.readEnum<EntryTranscriptionState>(),
        attemptCount = input.readInt(),
        detectedLanguages = input.readList { it.readEnum<SupportedLanguage>() },
        updatedAt = input.readInstant(),
        failure = input.readNullable(::readFailure),
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
    }

    private fun readTodo(input: DataInput) = Todo(
        id = input.readString(),
        text = input.readString(),
        createdAt = input.readInstant(),
        updatedAt = input.readInstant(),
        lastTouchedAt = input.readInstant(),
        state = input.readEnum<TodoState>(),
        source = input.readNullable(::readSource),
        closedAt = input.readNullable { it.readInstant() },
        stalePromptShownAt = input.readNullable { it.readInstant() },
    )

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
    }

    private fun readSuggestion(input: DataInput) = TodoSuggestion(
        id = input.readString(),
        entryId = input.readString(),
        suggestedText = input.readString(),
        language = input.readEnum<SupportedLanguage>(),
        reason = input.readEnum<TodoSuggestionReason>(),
        matchedRule = input.readString(),
        state = input.readEnum<TodoSuggestionState>(),
        createdAt = input.readInstant(),
        resolvedAt = input.readNullable { it.readInstant() },
    )

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
