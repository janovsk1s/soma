package com.soma.core.policy

import com.soma.core.model.DEFAULT_TRANSCRIPTION_LEASE_DURATION
import com.soma.core.model.DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS
import com.soma.core.model.TranscriptionEnvironment
import com.soma.core.model.TranscriptionFailure
import com.soma.core.model.TranscriptionJob
import com.soma.core.model.TranscriptionJobState
import com.soma.core.model.TranscriptionPreferences
import java.time.Clock
import java.time.Duration
import java.time.Instant

enum class TranscriptionRunDecision(val canRun: Boolean) {
    READY(true),
    DISABLED(false),
    BATTERY_SAVER(false),
    ANOTHER_JOB_RUNNING(false),
    NOT_YET_AVAILABLE(false),
    ACTIVE_LEASE(false),
    EXPIRED_LEASE(false),
    ATTEMPTS_EXHAUSTED(false),
    TERMINAL(false),
}

data class TranscriptionFailureDecision(
    /** Null means the failure is terminal. */
    val retryAt: Instant?,
) {
    val isTerminal: Boolean
        get() = retryAt == null
}

/** Pure policy for WorkManager constraints, leases, retries and terminal failures. */
class TranscriptionPolicy(
    private val clock: Clock,
    val maxAttempts: Int = DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS,
    private val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
) {
    init {
        require(maxAttempts > 0) { "Max attempts must be positive" }
        require(retryDelays.isNotEmpty()) { "At least one retry delay is required" }
        require(retryDelays.all { !it.isNegative && !it.isZero }) {
            "Retry delays must be positive"
        }
    }

    fun evaluate(
        job: TranscriptionJob,
        preferences: TranscriptionPreferences,
        environment: TranscriptionEnvironment,
        now: Instant = clock.instant(),
    ): TranscriptionRunDecision {
        if (!preferences.enabled) return TranscriptionRunDecision.DISABLED
        if (job.state == TranscriptionJobState.SUCCEEDED || job.state == TranscriptionJobState.FAILED) {
            return TranscriptionRunDecision.TERMINAL
        }
        if (environment.anotherJobIsRunning) return TranscriptionRunDecision.ANOTHER_JOB_RUNNING
        if (environment.isBatterySaver && !preferences.allowInBatterySaver) {
            return TranscriptionRunDecision.BATTERY_SAVER
        }
        if (job.state == TranscriptionJobState.RUNNING) {
            return if (job.leaseExpiresAt!!.isAfter(now)) {
                TranscriptionRunDecision.ACTIVE_LEASE
            } else {
                TranscriptionRunDecision.EXPIRED_LEASE
            }
        }
        if (job.attemptCount >= maxAttempts) return TranscriptionRunDecision.ATTEMPTS_EXHAUSTED
        if (job.availableAt.isAfter(now)) return TranscriptionRunDecision.NOT_YET_AVAILABLE
        return TranscriptionRunDecision.READY
    }

    /** Transition used by a transactional repository claim. */
    fun claim(
        job: TranscriptionJob,
        leaseOwner: String,
        at: Instant = clock.instant(),
        leaseDuration: Duration = DEFAULT_TRANSCRIPTION_LEASE_DURATION,
    ): TranscriptionJob {
        require(job.state == TranscriptionJobState.QUEUED) { "Only queued jobs can be claimed" }
        require(job.attemptCount < maxAttempts) { "Transcription attempts are exhausted" }
        require(!job.availableAt.isAfter(at)) { "Transcription job is not available yet" }
        require(leaseOwner.isNotBlank()) { "Lease owner must not be blank" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) {
            "Lease duration must be positive"
        }
        return job.copy(
            state = TranscriptionJobState.RUNNING,
            attemptCount = job.attemptCount + 1,
            leaseOwner = leaseOwner,
            leaseExpiresAt = at.plus(leaseDuration),
            updatedAt = at,
        )
    }

    fun failureDecision(
        job: TranscriptionJob,
        failure: TranscriptionFailure,
        failedAt: Instant = clock.instant(),
    ): TranscriptionFailureDecision {
        require(job.state == TranscriptionJobState.RUNNING) { "Only running jobs can fail" }
        if (!failure.retryable || job.attemptCount >= maxAttempts) {
            return TranscriptionFailureDecision(retryAt = null)
        }
        val delayIndex = (job.attemptCount - 1).coerceIn(0, retryDelays.lastIndex)
        return TranscriptionFailureDecision(retryAt = failedAt.plus(retryDelays[delayIndex]))
    }

    fun afterFailure(
        job: TranscriptionJob,
        failure: TranscriptionFailure,
        failedAt: Instant = clock.instant(),
    ): TranscriptionJob {
        val decision = failureDecision(job, failure, failedAt)
        return job.copy(
            state = if (decision.isTerminal) {
                TranscriptionJobState.FAILED
            } else {
                TranscriptionJobState.QUEUED
            },
            availableAt = decision.retryAt ?: failedAt,
            leaseOwner = null,
            leaseExpiresAt = null,
            lastFailure = failure,
            updatedAt = failedAt,
        )
    }

    fun markSucceeded(
        job: TranscriptionJob,
        completedAt: Instant = clock.instant(),
    ): TranscriptionJob {
        require(job.state == TranscriptionJobState.RUNNING) { "Only running jobs can succeed" }
        return job.copy(
            state = TranscriptionJobState.SUCCEEDED,
            leaseOwner = null,
            leaseExpiresAt = null,
            updatedAt = completedAt,
        )
    }

    /** Expiration consumes the claimed attempt but makes the job available again immediately. */
    fun requeueExpiredLease(
        job: TranscriptionJob,
        at: Instant = clock.instant(),
    ): TranscriptionJob {
        require(job.state == TranscriptionJobState.RUNNING) { "Only running jobs have leases" }
        require(!job.leaseExpiresAt!!.isAfter(at)) { "Lease has not expired" }
        return job.copy(
            state = if (job.attemptCount >= maxAttempts) {
                TranscriptionJobState.FAILED
            } else {
                TranscriptionJobState.QUEUED
            },
            availableAt = at,
            leaseOwner = null,
            leaseExpiresAt = null,
            updatedAt = at,
        )
    }

    companion object {
        val DEFAULT_RETRY_DELAYS: List<Duration> = listOf(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
        )
    }
}
