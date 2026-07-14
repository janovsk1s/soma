package com.soma.storage.repository

import com.soma.core.model.AudioAttachment
import com.soma.core.model.AudioFormat
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryLink
import com.soma.core.model.EntryLinkKind
import com.soma.core.model.EntryMetadata
import com.soma.core.model.EntryRevision
import com.soma.core.model.EntrySource
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.ImportantKind
import com.soma.core.model.MetadataSource
import com.soma.core.model.LogRecord
import com.soma.core.model.LogRevision
import com.soma.core.model.ImageAttachment
import com.soma.core.model.ImageFormat
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
import com.soma.storage.db.EntryMetadataEntity
import com.soma.storage.db.EntryRevisionEntity
import com.soma.storage.db.TrackingLogEntity
import com.soma.storage.db.TrackingLogRevisionEntity
import com.soma.storage.db.TodoEntity
import com.soma.storage.db.TodoSuggestionEntity
import com.soma.storage.db.TranscriptionJobEntity
import com.soma.storage.db.TranscriptionStateValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

internal class EntityMapper(
    private val cipher: TextCipher,
) {
    fun entryToEntity(entry: NoteEntry, noteId: String, revision: Long): EntryEntity {
        val audio = entry.audio
        val transcription = entry.transcription
        val image = entry.image
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
            deletedAtMillis = entry.deletedAt?.toEpochMilli(),
            audioDeletedAtMillis = entry.audioDeletedAt?.toEpochMilli(),
            imageFileId = image?.fileId,
            imageFormat = image?.format?.name,
            imageWidth = image?.width,
            imageHeight = image?.height,
            imageRotationDegrees = image?.rotationDegrees,
            imageByteCount = image?.byteCount,
            imageDeletedAtMillis = entry.imageDeletedAt?.toEpochMilli(),
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
        val transcription = if (audio != null) {
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
        val image = entity.imageFileId?.let { fileId ->
            ImageAttachment(
                fileId = fileId,
                format = ImageFormat.valueOf(requireNotNull(entity.imageFormat)),
                width = requireNotNull(entity.imageWidth),
                height = requireNotNull(entity.imageHeight),
                rotationDegrees = requireNotNull(entity.imageRotationDegrees),
                byteCount = requireNotNull(entity.imageByteCount),
            )
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
            image = image,
            transcription = transcription,
            deletedAt = entity.deletedAtMillis?.let(Instant::ofEpochMilli),
            audioDeletedAt = entity.audioDeletedAtMillis?.let(Instant::ofEpochMilli),
            imageDeletedAt = entity.imageDeletedAtMillis?.let(Instant::ofEpochMilli),
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
        resurfaceEpochDay = todo.resurfaceOn?.toEpochDay(),
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
        resurfaceOn = entity.resurfaceEpochDay?.let(LocalDate::ofEpochDay),
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

    fun metadataToEntity(metadata: EntryMetadata): EntryMetadataEntity {
        val id = metadataId(metadata.entryId, metadata.source)
        val tags = metadata.tags.joinToString("\n")
        val links = metadata.links.joinToString("\n") { link ->
            listOf(
                link.kind.name,
                BASE64_ENCODER.encodeToString(link.target.encodeToByteArray()),
                link.relation?.let { BASE64_ENCODER.encodeToString(it.encodeToByteArray()) }.orEmpty(),
            ).joinToString("\t")
        }
        return EntryMetadataEntity(
            entryId = metadata.entryId,
            source = metadata.source.name,
            tagsCiphertext = encrypt(id, ENTRY_METADATA_TAGS, tags),
            linksCiphertext = encrypt(id, ENTRY_METADATA_LINKS, links),
            cryptoVersion = CRYPTO_VERSION,
            derivedAtMillis = metadata.derivedAt.toEpochMilli(),
        )
    }

    fun metadataFromEntity(entity: EntryMetadataEntity): EntryMetadata {
        val source = MetadataSource.valueOf(entity.source)
        val id = metadataId(entity.entryId, source)
        val tags = decrypt(id, ENTRY_METADATA_TAGS, entity.cryptoVersion, entity.tagsCiphertext)
            .takeIf(String::isNotEmpty)?.split('\n').orEmpty()
        val links = decrypt(id, ENTRY_METADATA_LINKS, entity.cryptoVersion, entity.linksCiphertext)
            .takeIf(String::isNotEmpty)?.split('\n').orEmpty().map { encoded ->
                val parts = encoded.split('\t')
                require(parts.size == 3) { "Invalid encrypted entry link" }
                EntryLink(
                    kind = EntryLinkKind.valueOf(parts[0]),
                    target = BASE64_DECODER.decode(parts[1]).decodeToString(),
                    relation = parts[2].takeIf(String::isNotEmpty)
                        ?.let(BASE64_DECODER::decode)
                        ?.decodeToString(),
                )
            }
        return EntryMetadata(
            entryId = entity.entryId,
            tags = tags,
            links = links,
            derivedAt = Instant.ofEpochMilli(entity.derivedAtMillis),
            source = source,
        )
    }

    fun trackingLogToEntity(log: LogRecord): TrackingLogEntity = TrackingLogEntity(
        id = log.id,
        kind = log.kind.name,
        payloadCiphertext = encrypt(log.id, TRACKING_LOG_PAYLOAD, TrackingPayloadCodec.encode(log)),
        cryptoVersion = CRYPTO_VERSION,
        occurredAtMillis = log.occurredAt.toEpochMilli(),
        createdAtMillis = log.createdAt.toEpochMilli(),
        updatedAtMillis = log.updatedAt.toEpochMilli(),
        sourceNoteEpochDay = log.source?.noteDate?.toEpochDay(),
        sourceEntryId = log.source?.entryId,
        revision = log.revision,
        archivedAtMillis = log.archivedAt?.toEpochMilli(),
    )

    fun trackingLogFromEntity(entity: TrackingLogEntity): LogRecord {
        val log = TrackingPayloadCodec.decode(
            decrypt(entity.id, TRACKING_LOG_PAYLOAD, entity.cryptoVersion, entity.payloadCiphertext),
        )
        require(log.id == entity.id) { "Tracking payload id does not match its row" }
        require(log.kind.name == entity.kind) { "Tracking payload kind does not match its row" }
        require(log.occurredAt.toEpochMilli() == entity.occurredAtMillis) {
            "Tracking payload occurrence does not match its row"
        }
        require(log.createdAt.toEpochMilli() == entity.createdAtMillis) {
            "Tracking payload creation does not match its row"
        }
        require(log.updatedAt.toEpochMilli() == entity.updatedAtMillis) {
            "Tracking payload update does not match its row"
        }
        require(log.source?.noteDate?.toEpochDay() == entity.sourceNoteEpochDay) {
            "Tracking payload source day does not match its row"
        }
        require(log.source?.entryId == entity.sourceEntryId) {
            "Tracking payload source entry does not match its row"
        }
        require(log.revision == entity.revision) { "Tracking payload revision does not match its row" }
        require(log.archivedAt?.toEpochMilli() == entity.archivedAtMillis) {
            "Tracking payload archive does not match its row"
        }
        return log
    }

    fun trackingRevisionToEntity(revision: LogRevision): TrackingLogRevisionEntity {
        val id = trackingRevisionId(revision.logId, revision.revision)
        return TrackingLogRevisionEntity(
            logId = revision.logId,
            revision = revision.revision,
            payloadCiphertext = encrypt(
                id,
                TRACKING_LOG_REVISION_PAYLOAD,
                TrackingPayloadCodec.encode(revision.snapshot),
            ),
            cryptoVersion = CRYPTO_VERSION,
            editedAtMillis = revision.editedAt.toEpochMilli(),
        )
    }

    fun trackingRevisionFromEntity(entity: TrackingLogRevisionEntity): LogRevision {
        val id = trackingRevisionId(entity.logId, entity.revision)
        val snapshot = TrackingPayloadCodec.decode(
            decrypt(
                id,
                TRACKING_LOG_REVISION_PAYLOAD,
                entity.cryptoVersion,
                entity.payloadCiphertext,
            ),
        )
        return LogRevision(
            logId = entity.logId,
            revision = entity.revision,
            snapshot = snapshot,
            editedAt = Instant.ofEpochMilli(entity.editedAtMillis),
        )
    }

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

    private fun metadataId(entryId: String, source: MetadataSource): String = "$entryId:${source.name}"

    private fun trackingRevisionId(logId: String, revision: Long): String = "$logId:$revision"

    companion object {
        const val CRYPTO_VERSION = 1
        private const val ENTRY_TEXT = "entry.text"
        private const val ENTRY_REVISION_TEXT = "entryRevision.text"
        private const val ENTRY_TRANSCRIPTION_FAILURE = "entry.transcriptionFailure"
        private const val ENTRY_METADATA_TAGS = "entryMetadata.tags"
        private const val ENTRY_METADATA_LINKS = "entryMetadata.links"
        private const val TRACKING_LOG_PAYLOAD = "trackingLog.payload"
        private const val TRACKING_LOG_REVISION_PAYLOAD = "trackingLogRevision.payload"
        private const val TODO_TEXT = "todo.text"
        private const val SUGGESTION_TEXT = "suggestion.text"
        private const val SUGGESTION_MATCHED_RULE = "suggestion.matchedRule"
        private const val JOB_LAST_FAILURE = "transcription.lastFailure"
        private val BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val BASE64_DECODER = Base64.getUrlDecoder()
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
