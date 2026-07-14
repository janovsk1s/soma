package com.soma.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "daily_notes",
    indices = [Index(value = ["epoch_day"], unique = true)],
)
data class DailyNoteEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
)

@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = DailyNoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["note_id"]),
        Index(value = ["note_id", "position"], unique = true),
        Index(value = ["audio_file_id"], unique = true),
    ],
)
data class EntryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "text_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val textCiphertext: ByteArray?,
    @ColumnInfo(name = "crypto_version") val cryptoVersion: Int,
    @ColumnInfo(name = "audio_file_id") val audioFileId: String?,
    @ColumnInfo(name = "audio_format") val audioFormat: String?,
    @ColumnInfo(name = "audio_duration_millis") val audioDurationMillis: Long?,
    @ColumnInfo(name = "audio_byte_count") val audioByteCount: Long?,
    @ColumnInfo(name = "audio_sample_rate_hz") val audioSampleRateHz: Int?,
    @ColumnInfo(name = "audio_channel_count") val audioChannelCount: Int?,
    @ColumnInfo(name = "transcription_state") val transcriptionState: String,
    @ColumnInfo(name = "transcription_attempt_count") val transcriptionAttemptCount: Int,
    @ColumnInfo(name = "detected_languages") val detectedLanguages: String?,
    @ColumnInfo(name = "transcription_requested_engine") val transcriptionRequestedEngine: String?,
    @ColumnInfo(name = "transcription_used_engine") val transcriptionUsedEngine: String?,
    @ColumnInfo(name = "transcription_fallback_reason") val transcriptionFallbackReason: String?,
    @ColumnInfo(name = "transcription_updated_at_millis") val transcriptionUpdatedAtMillis: Long?,
    @ColumnInfo(name = "transcription_failure_code") val transcriptionFailureCode: String?,
    @ColumnInfo(name = "transcription_failure_retryable") val transcriptionFailureRetryable: Boolean?,
    @ColumnInfo(name = "transcription_failure_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val transcriptionFailureCiphertext: ByteArray?,
    @ColumnInfo(name = "return_later") val returnLater: Boolean,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "last_user_edited_at_millis") val lastUserEditedAtMillis: Long?,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "deleted_at_millis") val deletedAtMillis: Long? = null,
    @ColumnInfo(name = "audio_deleted_at_millis") val audioDeletedAtMillis: Long? = null,
)

@Entity(
    tableName = "entry_revisions",
    primaryKeys = ["entry_id", "revision"],
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["entry_id"])],
)
data class EntryRevisionEntity(
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "text_ciphertext", typeAffinity = ColumnInfo.BLOB) val textCiphertext: ByteArray,
    @ColumnInfo(name = "crypto_version") val cryptoVersion: Int,
    @ColumnInfo(name = "edited_at_millis") val editedAtMillis: Long,
)

@Entity(
    tableName = "todos",
    foreignKeys = [
        ForeignKey(
            entity = DailyNoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_note_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_entry_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["status", "created_at_millis"]),
        Index(value = ["source_note_id"]),
        Index(value = ["source_entry_id"]),
    ],
)
data class TodoEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "text_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val textCiphertext: ByteArray,
    @ColumnInfo(name = "crypto_version") val cryptoVersion: Int,
    @ColumnInfo(name = "created_epoch_day") val createdEpochDay: Long,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "last_touched_at_millis") val lastTouchedAtMillis: Long,
    @ColumnInfo(name = "source_note_id") val sourceNoteId: String?,
    @ColumnInfo(name = "source_entry_id") val sourceEntryId: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "kind", defaultValue = "'ACTION'") val kind: String = ImportantKindValue.ACTION,
    @ColumnInfo(name = "completed_at_millis") val completedAtMillis: Long?,
    @ColumnInfo(name = "review_prompted_at_millis") val reviewPromptedAtMillis: Long?,
    @ColumnInfo(name = "resurface_epoch_day") val resurfaceEpochDay: Long? = null,
)

@Entity(
    tableName = "todo_suggestions",
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["entry_id"]),
        Index(value = ["state", "created_at_millis"]),
    ],
)
data class TodoSuggestionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "candidate_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val candidateCiphertext: ByteArray,
    @ColumnInfo(name = "matched_rule_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val matchedRuleCiphertext: ByteArray,
    @ColumnInfo(name = "crypto_version") val cryptoVersion: Int,
    @ColumnInfo(name = "sentence_start") val sentenceStart: Int,
    @ColumnInfo(name = "sentence_end") val sentenceEnd: Int,
    @ColumnInfo(name = "language") val language: String,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "suggested_kind", defaultValue = "'ACTION'")
    val suggestedKind: String = ImportantKindValue.ACTION,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "decided_at_millis") val decidedAtMillis: Long?,
)

@Entity(tableName = "still_open_dismissals")
data class StillOpenDismissalEntity(
    @PrimaryKey @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "dismissed_at_millis") val dismissedAtMillis: Long,
)

@Entity(
    tableName = "transcription_jobs",
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["entry_id"], unique = true),
        Index(value = ["state", "not_before_millis", "enqueued_at_millis"]),
        Index(value = ["lease_owner"]),
    ],
)
data class TranscriptionJobEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "enqueued_at_millis") val enqueuedAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "not_before_millis") val notBeforeMillis: Long,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String?,
    @ColumnInfo(name = "lease_expires_at_millis") val leaseExpiresAtMillis: Long?,
    @ColumnInfo(name = "last_error_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val lastErrorCiphertext: ByteArray?,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String?,
    @ColumnInfo(name = "last_error_retryable") val lastErrorRetryable: Boolean?,
    @ColumnInfo(name = "crypto_version") val cryptoVersion: Int,
)

object EntryTypeValue {
    const val TEXT = "TEXT"
    const val VOICE = "VOICE"
}

object TranscriptionStateValue {
    const val NOT_APPLICABLE = "NOT_APPLICABLE"
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val DISABLED = "DISABLED"
}

object TodoStatusValue {
    const val OPEN = "OPEN"
    const val DONE = "DONE"
    const val ARCHIVED = "ARCHIVED"
}

object ImportantKindValue {
    const val ACTION = "ACTION"
    const val LIST = "LIST"
    const val EXCERPT = "EXCERPT"
}

object SuggestionStateValue {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val DISMISSED = "DISMISSED"
}
