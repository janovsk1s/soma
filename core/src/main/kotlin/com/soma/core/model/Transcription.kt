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

enum class TranscriptionEngine {
    LOCAL_WHISPER_TINY,
    ELEVENLABS_SCRIBE_V2,
    GROQ_WHISPER_LARGE_V3_TURBO,
    GROQ_WHISPER_LARGE_V3,
    LOCAL_WHISPER_BASE,
}

/** Engines that run on the phone and never open the network. */
val TranscriptionEngine.isLocal: Boolean
    get() = this == TranscriptionEngine.LOCAL_WHISPER_TINY ||
        this == TranscriptionEngine.LOCAL_WHISPER_BASE

enum class TranscriptionFallbackReason {
    WIFI_REQUIRED,
    API_KEY_MISSING,
    PROVIDER_ERROR,
    AUTHENTICATION_ERROR,
    PERMISSION_ERROR,
    PAYMENT_REQUIRED,
    RATE_LIMITED,
    INVALID_REQUEST,
    NETWORK_ERROR,
}

/**
 * Records what Soma tried and what actually produced the saved transcript.
 * Provider error details stay sanitized; note text, audio, and API keys never belong here.
 */
data class TranscriptionProvenance(
    val requestedEngine: TranscriptionEngine,
    val usedEngine: TranscriptionEngine,
    val fallbackReason: TranscriptionFallbackReason? = null,
) {
    init {
        if (fallbackReason == null) {
            require(requestedEngine == usedEngine) {
                "Different requested and used engines require a fallback reason"
            }
        } else {
            require(!requestedEngine.isLocal) {
                "Local Whisper cannot be the source of a cloud fallback"
            }
            require(usedEngine.isLocal) {
                "Cloud fallback must finish with local Whisper"
            }
        }
    }

    companion object {
        fun local(engine: TranscriptionEngine = TranscriptionEngine.LOCAL_WHISPER_TINY): TranscriptionProvenance {
            require(engine.isLocal) { "Local provenance requires a local engine" }
            return TranscriptionProvenance(requestedEngine = engine, usedEngine = engine)
        }
    }
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
    val provenance: TranscriptionProvenance,
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
