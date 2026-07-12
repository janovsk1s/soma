package com.soma.core.policy

import com.soma.core.model.TranscriptionEnvironment
import com.soma.core.model.TranscriptionFailure
import com.soma.core.model.TranscriptionFailureCode
import com.soma.core.model.TranscriptionJob
import com.soma.core.model.TranscriptionJobState
import com.soma.core.model.TranscriptionPreferences
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionPolicyTest {
    private val now = Instant.parse("2026-07-12T10:00:00Z")
    private val policy = TranscriptionPolicy(Clock.fixed(now, ZoneOffset.UTC))
    private val normalEnvironment = TranscriptionEnvironment(
        isBatterySaver = false,
        anotherJobIsRunning = false,
    )

    @Test
    fun `ready queued job may run`() {
        assertEquals(
            TranscriptionRunDecision.READY,
            policy.evaluate(queued(), TranscriptionPreferences(), normalEnvironment),
        )
    }

    @Test
    fun `disabled transcription always defers`() {
        assertEquals(
            TranscriptionRunDecision.DISABLED,
            policy.evaluate(queued(), TranscriptionPreferences(enabled = false), normalEnvironment),
        )
    }

    @Test
    fun `battery saver defers unless user opted in`() {
        val batterySaver = TranscriptionEnvironment(
            isBatterySaver = true,
            anotherJobIsRunning = false,
        )

        assertEquals(
            TranscriptionRunDecision.BATTERY_SAVER,
            policy.evaluate(queued(), TranscriptionPreferences(), batterySaver),
        )
        assertEquals(
            TranscriptionRunDecision.READY,
            policy.evaluate(
                queued(),
                TranscriptionPreferences(allowInBatterySaver = true),
                batterySaver,
            ),
        )
    }

    @Test
    fun `one at a time rule defers while another job runs`() {
        assertEquals(
            TranscriptionRunDecision.ANOTHER_JOB_RUNNING,
            policy.evaluate(
                queued(),
                TranscriptionPreferences(),
                normalEnvironment.copy(anotherJobIsRunning = true),
            ),
        )
    }

    @Test
    fun `future retry is not available`() {
        val delayed = queued().copy(availableAt = now.plusSeconds(60))

        assertEquals(
            TranscriptionRunDecision.NOT_YET_AVAILABLE,
            policy.evaluate(delayed, TranscriptionPreferences(), normalEnvironment),
        )
    }

    @Test
    fun `claim increments attempt and creates a bounded lease`() {
        val claimed = policy.claim(
            job = queued(),
            leaseOwner = "worker-1",
            leaseDuration = Duration.ofMinutes(7),
        )

        assertEquals(TranscriptionJobState.RUNNING, claimed.state)
        assertEquals(1, claimed.attemptCount)
        assertEquals("worker-1", claimed.leaseOwner)
        assertEquals(now.plus(Duration.ofMinutes(7)), claimed.leaseExpiresAt)
        assertEquals(
            TranscriptionRunDecision.ACTIVE_LEASE,
            policy.evaluate(claimed, TranscriptionPreferences(), normalEnvironment),
        )
        assertEquals(
            TranscriptionRunDecision.EXPIRED_LEASE,
            policy.evaluate(
                claimed,
                TranscriptionPreferences(),
                normalEnvironment,
                now.plus(Duration.ofMinutes(7)),
            ),
        )
    }

    @Test
    fun `retryable failures back off one minute then five minutes`() {
        val failure = retryableFailure()
        val firstClaim = policy.claim(queued(), "worker")
        val firstFailure = policy.afterFailure(firstClaim, failure)

        assertEquals(TranscriptionJobState.QUEUED, firstFailure.state)
        assertEquals(now.plus(Duration.ofMinutes(1)), firstFailure.availableAt)
        assertNull(firstFailure.leaseOwner)

        val secondClaim = policy.claim(firstFailure, "worker", firstFailure.availableAt)
        val secondFailure = policy.afterFailure(secondClaim, failure, secondClaim.updatedAt)

        assertEquals(2, secondFailure.attemptCount)
        assertEquals(now.plus(Duration.ofMinutes(6)), secondFailure.availableAt)
    }

    @Test
    fun `third retryable failure is terminal`() {
        val failure = retryableFailure()
        val first = policy.afterFailure(policy.claim(queued(), "worker"), failure)
        val secondClaim = policy.claim(first, "worker", first.availableAt)
        val second = policy.afterFailure(secondClaim, failure, secondClaim.updatedAt)
        val thirdClaim = policy.claim(second, "worker", second.availableAt)
        val third = policy.afterFailure(thirdClaim, failure, thirdClaim.updatedAt)

        assertEquals(3, third.attemptCount)
        assertEquals(TranscriptionJobState.FAILED, third.state)
        assertTrue(policy.failureDecision(thirdClaim, failure, thirdClaim.updatedAt).isTerminal)
    }

    @Test
    fun `non retryable failure is terminal immediately`() {
        val failure = TranscriptionFailure(
            code = TranscriptionFailureCode.MODEL_ERROR,
            retryable = false,
        )
        val failed = policy.afterFailure(policy.claim(queued(), "worker"), failure)

        assertEquals(TranscriptionJobState.FAILED, failed.state)
        assertEquals(failure, failed.lastFailure)
    }

    @Test
    fun `expired lease requeues unless attempts are exhausted`() {
        val running = policy.claim(queued(), "worker", leaseDuration = Duration.ofMinutes(1))
        val requeued = policy.requeueExpiredLease(running, now.plusSeconds(60))

        assertEquals(TranscriptionJobState.QUEUED, requeued.state)
        assertEquals(1, requeued.attemptCount)
        assertNull(requeued.leaseOwner)

        val exhausted = running.copy(attemptCount = 3)
        val terminal = policy.requeueExpiredLease(exhausted, now.plusSeconds(60))
        assertEquals(TranscriptionJobState.FAILED, terminal.state)
    }

    @Test
    fun `success clears lease and is terminal`() {
        val succeeded = policy.markSucceeded(policy.claim(queued(), "worker"))

        assertEquals(TranscriptionJobState.SUCCEEDED, succeeded.state)
        assertNull(succeeded.leaseOwner)
        assertFalse(
            policy.evaluate(succeeded, TranscriptionPreferences(), normalEnvironment).canRun,
        )
    }

    private fun queued(): TranscriptionJob = TranscriptionJob.queued("job-1", "entry-1", now)

    private fun retryableFailure(): TranscriptionFailure = TranscriptionFailure(
        code = TranscriptionFailureCode.OUT_OF_MEMORY,
        retryable = true,
    )
}
