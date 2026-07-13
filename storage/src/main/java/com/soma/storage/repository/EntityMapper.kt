package com.soma.storage.repository

import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryRevision
import com.soma.core.model.EntrySource
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.ImportantKind
import com.soma.core.model.NoteEntry
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
import com.soma.storage.crypto.StorageAad
import com.soma.storage.crypto.TextCipher
import com.soma.storage.db.EntryEntity
import com.soma.storage.db.EntryRevisionEntity
import com.soma.storage.db.TodoEntity
import com.soma.storage.db.TodoSuggestionEntity
import com.soma.storage.db.TranscriptionJobEntity
import com.soma.storage.db.TranscriptionStateValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal class EntityMapper(
    private val cipher: TextCipher,
) {
    fun entryToEntity(entry: NoteEntry, noteId: String, revision: Long): EntryEntity {
        val audio = entry.audio
        val transcription = entry.transcription
        val failure = transcription?.failure
        return EntryEntity(
            id = entry.id,
            noteId = noteId,
            position = entry.position,
            type = entry.kind.name,
            textCiphertext = encrypt(entry.id, ENTRY_TEXT, entry.text),
            cryptoVersion = CRYPTO_VERSION,
            audioFileId = audio?.fileId,
            audioFormat = audio?.format?.name,
            audioDurationMillis = audio?.durationMillis,
            audioByteCount = audio?.byteCount,
            audioSampleRateHz = audio?.sampleRateHz,
            audioChannelCount = audio?.channelCount,
            transcriptionState = transcription?.state?.name ?: TranscriptionStateValue.NOT_APPLICABLE,
            transcriptionAttemptCount = transcription?.attemptCount ?: 0,
            detectedLanguages = transcription?.detectedLanguages?.joinToString(",") { it.name },
            transcriptionRequestedEngine = transcription?.provenance?.requestedEngine?.name,
            transcriptionUsedEngine = transcription?.provenance?.usedEngine?.name,
            transcriptionFallbackReason = transcription?.provenance?.fallbackReason?.name,
            transcriptionUpdatedAtMillis = transcription?.updatedAt?.toEpochMilli(),
            transcriptionFailureCode = failure?.code?.name,
            transcriptionFailureRetryable = failure?.retryable,
            transcriptionFailureCiphertext = failure?.diagnostic?.let {
                encrypt(entry.id, ENTRY_TRANSCRIPTION_FAILURE, it)
            },
            returnLater = entry.returnLater,
            createdAtMillis = entry.createdAt.toEpochMilli(),
            updatedAtMillis = entry.updatedAt.toEpochMilli(),
            lastUserEditedAtMillis = entry.lastUserEditedAt?.toEpochMilli(),
            revision = revision,
        )
    }

    fun entryFromEntity(entity: EntryEntity, noteDate: LocalDate): NoteEntry {
        val kind = EntryKind.valueOf(entity.type)
        val audio = entity.audioFileId?.let { fileId ->
            AudioAttachment(
                fileId = fileId,
                format = AudioFormat.valueOf(requireNotNull(entity.audioFormat)),
                durationMillis = requireNotNull(entity.audioDurationMillis),
                byteCount = requireNotNull(entity.audioByteCount),
                sampleRateHz = requireNotNull(entity.audioSampleRateHz),
                channelCount = requireNotNull(entity.audioChannelCount),
            )
        }
        val transcription = if (kind == EntryKind.VOICE) {
            val state = EntryTranscriptionState.valueOf(entity.transcriptionState)
            val failure = entity.transcriptionFailureCode?.let { code ->
                TranscriptionFailure(
                    code = TranscriptionFailureCode.valueOf(code),
                    retryable = requireNotNull(entity.transcriptionFailureRetryable),
                    diagnostic = entity.transcriptionFailureCiphertext?.let {
                        decrypt(entity.id, ENTRY_TRANSCRIPTION_FAILURE, entity.cryptoVersion, it)
                    },
                )
            }
            val provenance = entity.transcriptionRequestedEngine?.let { requested ->
                TranscriptionProvenance(
                    requestedEngine = TranscriptionEngine.valueOf(requested),
                    usedEngine = TranscriptionEngine.valueOf(requireNotNull(entity.transcriptionUsedEngine)),
                    fallbackReason = entity.transcriptionFallbackReason?.let(TranscriptionFallbackReason::valueOf),
                )
            }
            TranscriptionInfo(
                state = state,
                attemptCount = entity.transcriptionAttemptCount,
                detectedLanguages = decodeLanguages(entity.detectedLanguages),
                provenance = provenance,
                updatedAt = Instant.ofEpochMilli(
                    entity.transcriptionUpdatedAtMillis ?: entity.updatedAtMillis,
                ),
                failure = failure,
            )
        } else {
            null
        }
        return NoteEntry(
            id = entity.id,
            noteDate = noteDate,
            position = entity.position,
            kind = kind,
            text = entity.textCiphertext?.let {
                decrypt(entity.id, ENTRY_TEXT, entity.cryptoVersion, it)
            }.orEmpty(),
            createdAt = Instant.ofEpochMilli(entity.createdAtMillis),
            updatedAt = Instant.ofEpochMilli(entity.updatedAtMillis),
            lastUserEditedAt = entity.lastUserEditedAtMillis?.let(Instant::ofEpochMilli),
            returnLater = entity.returnLater,
            audio = audio,
            transcription = transcription,
        )
    }

    fun todoToEntity(todo: Todo, existing: TodoEntity? = null): TodoEntity = TodoEntity(
        id = todo.id,
        textCiphertext = encrypt(todo.id, TODO_TEXT, todo.text),
        cryptoVersion = CRYPTO_VERSION,
        createdEpochDay = existing?.createdEpochDay
            ?: todo.createdAt.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(),
        createdAtMillis = todo.createdAt.toEpochMilli(),
        updatedAtMillis = todo.updatedAt.toEpochMilli(),
        lastTouchedAtMillis = todo.lastTouchedAt.toEpochMilli(),
        sourceNoteId = todo.source?.noteDate?.let(NoteIds::fromDate),
        sourceEntryId = todo.source?.entryId,
        status = todo.state.name,
        kind = todo.kind.name,
        completedAtMillis = todo.closedAt?.toEpochMilli(),
        reviewPromptedAtMillis = todo.stalePromptShownAt?.toEpochMilli(),
    )

    fun todoFromEntity(entity: TodoEntity): Todo = Todo(
        id = entity.id,
        text = decrypt(entity.id, TODO_TEXT, entity.cryptoVersion, entity.textCiphertext),
        createdAt = Instant.ofEpochMilli(entity.createdAtMillis),
        updatedAt = Instant.ofEpochMilli(entity.updatedAtMillis),
        lastTouchedAt = Instant.ofEpochMilli(entity.lastTouchedAtMillis),
        state = TodoState.valueOf(entity.status),
        kind = ImportantKind.valueOf(entity.kind),
        source = entity.sourceEntryId?.let { entryId ->
            val noteEpochDay = entity.sourceNoteId?.let(NoteIds::toEpochDay)
                ?: error("Todo ${entity.id} has a source entry without a source note")
            EntrySource(LocalDate.ofEpochDay(noteEpochDay), entryId)
        },
        closedAt = entity.completedAtMillis?.let(Instant::ofEpochMilli),
        stalePromptShownAt = entity.reviewPromptedAtMillis?.let(Instant::ofEpochMilli),
    )

    fun suggestionToEntity(suggestion: TodoSuggestion): TodoSuggestionEntity = TodoSuggestionEntity(
        id = suggestion.id,
        entryId = suggestion.entryId,
        candidateCiphertext = encrypt(suggestion.id, SUGGESTION_TEXT, suggestion.suggestedText),
        matchedRuleCiphertext = encrypt(suggestion.id, SUGGESTION_MATCHED_RULE, suggestion.matchedRule),
        cryptoVersion = CRYPTO_VERSION,
        sentenceStart = 0,
        sentenceEnd = suggestion.suggestedText.length,
        language = suggestion.language.name,
        reason = suggestion.reason.name,
        suggestedKind = suggestion.suggestedKind.name,
        state = suggestion.state.name,
        createdAtMillis = suggestion.createdAt.toEpochMilli(),
        decidedAtMillis = suggestion.resolvedAt?.toEpochMilli(),
    )

    fun suggestionFromEntity(entity: TodoSuggestionEntity): TodoSuggestion = TodoSuggestion(
        id = entity.id,
        entryId = entity.entryId,
        suggestedText = decrypt(
            entity.id,
            SUGGESTION_TEXT,
            entity.cryptoVersion,
            entity.candidateCiphertext,
        ),
        language = SupportedLanguage.valueOf(entity.language),
        reason = TodoSuggestionReason.valueOf(entity.reason),
        suggestedKind = ImportantKind.valueOf(entity.suggestedKind),
        matchedRule = decrypt(
            entity.id,
            SUGGESTION_MATCHED_RULE,
            entity.cryptoVersion,
            entity.matchedRuleCiphertext,
        ),
        state = TodoSuggestionState.valueOf(entity.state),
        createdAt = Instant.ofEpochMilli(entity.createdAtMillis),
        resolvedAt = entity.decidedAtMillis?.let(Instant::ofEpochMilli),
    )

    fun jobToEntity(job: TranscriptionJob, enqueuedAtMillis: Long = job.updatedAt.toEpochMilli()) =
        TranscriptionJobEntity(
            id = job.id,
            entryId = job.entryId,
            state = job.state.name,
            enqueuedAtMillis = enqueuedAtMillis,
            updatedAtMillis = job.updatedAt.toEpochMilli(),
            attemptCount = job.attemptCount,
            notBeforeMillis = job.availableAt.toEpochMilli(),
            leaseOwner = job.leaseOwner,
            leaseExpiresAtMillis = job.leaseExpiresAt?.toEpochMilli(),
            lastErrorCiphertext = job.lastFailure?.diagnostic?.let {
                encrypt(job.id, JOB_LAST_FAILURE, it)
            },
            lastErrorCode = job.lastFailure?.code?.name,
            lastErrorRetryable = job.lastFailure?.retryable,
            cryptoVersion = CRYPTO_VERSION,
        )

    fun jobFromEntity(entity: TranscriptionJobEntity): TranscriptionJob = TranscriptionJob(
        id = entity.id,
        entryId = entity.entryId,
        state = TranscriptionJobState.valueOf(entity.state),
        attemptCount = entity.attemptCount,
        availableAt = Instant.ofEpochMilli(entity.notBeforeMillis),
        leaseOwner = entity.leaseOwner,
        leaseExpiresAt = entity.leaseExpiresAtMillis?.let(Instant::ofEpochMilli),
        lastFailure = entity.lastErrorCode?.let { code ->
            TranscriptionFailure(
                code = TranscriptionFailureCode.valueOf(code),
                retryable = requireNotNull(entity.lastErrorRetryable),
                diagnostic = entity.lastErrorCiphertext?.let {
                    decrypt(entity.id, JOB_LAST_FAILURE, entity.cryptoVersion, it)
                },
            )
        },
        updatedAt = Instant.ofEpochMilli(entity.updatedAtMillis),
    )

    fun encryptEntryText(entryId: String, text: String): ByteArray = encrypt(entryId, ENTRY_TEXT, text)

    fun revisionToEntity(revision: EntryRevision): EntryRevisionEntity {
        val revisionId = revisionId(revision.entryId, revision.revision)
        return EntryRevisionEntity(
            entryId = revision.entryId,
            revision = revision.revision,
            textCiphertext = encrypt(revisionId, ENTRY_REVISION_TEXT, revision.text),
            cryptoVersion = CRYPTO_VERSION,
            editedAtMillis = revision.editedAt.toEpochMilli(),
        )
    }

    fun revisionFromEntity(entity: EntryRevisionEntity): EntryRevision = EntryRevision(
        entryId = entity.entryId,
        revision = entity.revision,
        text = decrypt(
            revisionId(entity.entryId, entity.revision),
            ENTRY_REVISION_TEXT,
            entity.cryptoVersion,
            entity.textCiphertext,
        ),
        editedAt = Instant.ofEpochMilli(entity.editedAtMillis),
    )

    fun encryptEntryFailure(entryId: String, diagnostic: String): ByteArray =
        encrypt(entryId, ENTRY_TRANSCRIPTION_FAILURE, diagnostic)

    fun encryptJobFailure(jobId: String, diagnostic: String): ByteArray =
        encrypt(jobId, JOB_LAST_FAILURE, diagnostic)

    private fun encrypt(id: String, field: String, plaintext: String): ByteArray =
        cipher.encrypt(plaintext, StorageAad.forField(id, field, CRYPTO_VERSION))

    private fun decrypt(id: String, field: String, version: Int, ciphertext: ByteArray): String =
        cipher.decrypt(ciphertext, StorageAad.forField(id, field, version))

    private fun decodeLanguages(encoded: String?): List<SupportedLanguage> = encoded
        ?.takeIf(String::isNotBlank)
        ?.split(',')
        ?.map(SupportedLanguage::valueOf)
        .orEmpty()

    private fun revisionId(entryId: String, revision: Long): String = "$entryId:$revision"

    companion object {
        const val CRYPTO_VERSION = 1
        private const val ENTRY_TEXT = "entry.text"
        private const val ENTRY_REVISION_TEXT = "entryRevision.text"
        private const val ENTRY_TRANSCRIPTION_FAILURE = "entry.transcriptionFailure"
        private const val TODO_TEXT = "todo.text"
        private const val SUGGESTION_TEXT = "suggestion.text"
        private const val SUGGESTION_MATCHED_RULE = "suggestion.matchedRule"
        private const val JOB_LAST_FAILURE = "transcription.lastFailure"
    }
}

internal object NoteIds {
    private const val PREFIX = "note:"

    fun fromDate(date: LocalDate): String = PREFIX + date.toEpochDay()

    fun toEpochDay(id: String): Long {
        require(id.startsWith(PREFIX)) { "Unsupported note id: $id" }
        return id.removePrefix(PREFIX).toLong()
    }
}
