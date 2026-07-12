package com.soma.core.model

import java.time.Duration
import java.time.Instant

enum class TranscriptionFailureCode {
    AUDIO_UNAVAILABLE,
    ENGINE_UNAVAILABLE,
    MODEL_ERROR,
    OUT_OF_MEMORY,
    CANCELLED,
    UNKNOWN,
}

/** A sanitized failure; do not put transcript or note contents in [diagnostic]. */
data class TranscriptionFailure(
    val code: TranscriptionFailureCode,
    val retryable: Boolean,
    val diagnostic: String? = null,
)

enum class TranscriptionJobState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class TranscriptionJob(
    val id: String,
    val entryId: String,
    val state: TranscriptionJobState,
    /** Incremented when a worker successfully claims the job. */
    val attemptCount: Int,
    val availableAt: Instant,
    val leaseOwner: String? = null,
    val leaseExpiresAt: Instant? = null,
    val lastFailure: TranscriptionFailure? = null,
    val updatedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "Transcription job id must not be blank" }
        require(entryId.isNotBlank()) { "Transcription entry id must not be blank" }
        require(attemptCount >= 0) { "Transcription attempt count must not be negative" }
        if (state == TranscriptionJobState.RUNNING) {
            require(!leaseOwner.isNullOrBlank() && leaseExpiresAt != null) {
                "Running transcription jobs require a lease"
            }
        } else {
            require(leaseOwner == null && leaseExpiresAt == null) {
                "Only running transcription jobs may hold a lease"
            }
        }
    }

    companion object {
        fun queued(id: String, entryId: String, at: Instant): TranscriptionJob = TranscriptionJob(
            id = id,
            entryId = entryId,
            state = TranscriptionJobState.QUEUED,
            attemptCount = 0,
            availableAt = at,
            updatedAt = at,
        )
    }
}

data class TranscriptSegment(
    val startMillis: Long,
    val endMillis: Long,
    val text: String,
    val language: SupportedLanguage,
) {
    init {
        require(startMillis >= 0) { "Segment start must not be negative" }
        require(endMillis >= startMillis) { "Segment end must not precede its start" }
        require(text.isNotBlank()) { "Transcript segment must not be blank" }
    }
}

data class TranscriptionResult(
    val segments: List<TranscriptSegment>,
) {
    val text: String
        get() = segments.joinToString(separator = " ") { it.text.trim() }

    val detectedLanguages: List<SupportedLanguage>
        get() = segments.map(TranscriptSegment::language).distinct()
}

data class TranscriptionPreferences(
    val enabled: Boolean = true,
    val allowInBatterySaver: Boolean = false,
)

data class TranscriptionEnvironment(
    val isBatterySaver: Boolean,
    /** Kept explicit because one-at-a-time is a product rule, not an implementation accident. */
    val anotherJobIsRunning: Boolean,
)

const val DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS: Int = 3

val DEFAULT_TRANSCRIPTION_LEASE_DURATION: Duration = Duration.ofMinutes(10)
