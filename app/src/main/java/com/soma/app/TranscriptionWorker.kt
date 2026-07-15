package com.soma.app

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.soma.core.model.DEFAULT_TRANSCRIPTION_LEASE_DURATION
import com.soma.core.model.MetadataSource
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.TodoSuggestion
import com.soma.core.model.TodoSuggestionState
import com.soma.core.model.TranscriptSegment
import com.soma.core.model.TranscriptionFailure
import com.soma.core.model.TranscriptionFailureCode
import com.soma.core.policy.TranscriptionPolicy
import com.soma.core.todo.RuleBasedTodoDetector
import com.soma.voice.EncryptedAudioReader
import com.soma.whisper.WhisperCppTranscriber
import com.soma.whisper.WhisperModelException
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object TranscriptionScheduler {
    private const val UNIQUE_WORK = "soma-transcription-drain"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<TranscriptionDrainWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}

class TranscriptionDrainWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SomaApplication
        if (SomaPrefs.demoMode(app) || !SomaPrefs.transcription(app)) return Result.success()
        val power = app.getSystemService(PowerManager::class.java)
        if (power?.isPowerSaveMode == true && !SomaPrefs.transcribeInBatterySaver(app)) {
            return Result.retry()
        }

        val repositories = app.repositories()
        val clock = Clock.systemUTC()
        val policy = TranscriptionPolicy(clock)
        val workerId = UUID.randomUUID().toString()
        repositories.transcriptionJobs.releaseExpiredLeases(clock.instant())
        val startedAt = SystemClock.elapsedRealtime()
        var shouldRetry = false

        cloudFeatures(app).createTranscriber { createLocalTranscriber(app) }.use { transcriber ->
            while (!isStopped && SystemClock.elapsedRealtime() - startedAt < INTERNAL_BUDGET_MILLIS) {
                if (power?.isPowerSaveMode == true && !SomaPrefs.transcribeInBatterySaver(app)) {
                    shouldRetry = true
                    break
                }
                val now = clock.instant()
                val job = repositories.transcriptionJobs.claimNext(
                    workerId,
                    now,
                    DEFAULT_TRANSCRIPTION_LEASE_DURATION,
                ) ?: break
                val entry = repositories.notes.getEntry(job.entryId)
                val audio = entry?.activeAudio
                if (audio == null) {
                    repositories.transcriptionJobs.recordFailure(
                        job.id,
                        workerId,
                        TranscriptionFailure(TranscriptionFailureCode.AUDIO_UNAVAILABLE, retryable = false),
                        clock.instant(),
                        retryAt = null,
                    )
                    continue
                }
                val samples = try {
                    EncryptedAudioReader.open(
                        app.encryptedAudioFile(audio.fileId),
                        app.audioKeyProvider,
                        audio.fileId,
                    ).use {
                        it.readFloatSamples()
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    val failure = classifyTranscriptionFailure(error)
                    val decision = policy.failureDecision(job, failure, clock.instant())
                    repositories.transcriptionJobs.recordFailure(
                        job.id,
                        workerId,
                        failure,
                        clock.instant(),
                        decision.retryAt,
                    )
                    shouldRetry = shouldRetry || decision.retryAt != null
                    if (shouldRetry) break else continue
                }

                try {
                    val native = transcriber.transcribe(samples, audio.sampleRateHz)
                    val fallbackLanguage = SomaPrefs.language(app)
                    val segments = native.chunks.mapNotNull { chunk ->
                        chunk.text.takeIf(String::isNotBlank)?.let { text ->
                            TranscriptSegment(
                                startMillis = chunk.startMillis,
                                endMillis = chunk.endMillis,
                                text = text,
                                language = SupportedLanguage.fromLanguageTag(chunk.languageCode) ?: fallbackLanguage,
                            )
                        }
                    }
                    if (segments.isEmpty()) throw NoSpeechDetectedException()
                    val result = com.soma.core.model.TranscriptionResult(
                        segments = segments,
                        provenance = native.provenance,
                    )
                    if (repositories.transcriptionJobs.complete(job.id, workerId, result, clock.instant())) {
                        // A successful replacement transcript invalidates only
                        // metadata derived from the previous wording.
                        repositories.metadata.delete(job.entryId, MetadataSource.AI)
                        repositories.metadata.delete(job.entryId, MetadataSource.LOCAL)
                        persistSuggestions(app, job.entryId, result)
                        val completedEntry = repositories.notes.getEntry(job.entryId)
                        if (completedEntry != null) {
                            try {
                                deriveAndPersistLocalMetadata(
                                    app = app,
                                    repositories = repositories,
                                    entry = completedEntry,
                                    clock = clock,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // The local pass is independent of optional cloud work.
                            }
                            try {
                                deriveAndPersistAiMetadata(
                                    app = app,
                                    repositories = repositories,
                                    entry = completedEntry,
                                    languages = result.detectedLanguages.toSet(),
                                    clock = clock,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // Transcription is already complete. Optional
                                // cloud metadata failure cannot change that outcome.
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    val failure = classifyTranscriptionFailure(error)
                    val decision = policy.failureDecision(job, failure, clock.instant())
                    repositories.transcriptionJobs.recordFailure(
                        job.id,
                        workerId,
                        failure,
                        clock.instant(),
                        decision.retryAt,
                    )
                    shouldRetry = shouldRetry || decision.retryAt != null
                } finally {
                    samples.fill(0f)
                }
                if (shouldRetry) break
            }
        }
        if (!isStopped && SystemClock.elapsedRealtime() - startedAt >= INTERNAL_BUDGET_MILLIS) {
            // APPEND_OR_REPLACE puts a successor behind this running drain, closing
            // the wake-up gap without running two transcriptions concurrently.
            if (runCatching { TranscriptionScheduler.enqueue(app) }.isFailure) shouldRetry = true
        }
        return if (shouldRetry) Result.retry() else Result.success()
    }

    private suspend fun persistSuggestions(
        app: SomaApplication,
        entryId: String,
        transcription: com.soma.core.model.TranscriptionResult,
    ) {
        val repositories = app.repositories()
        val detector = RuleBasedTodoDetector()
        val languages = transcription.detectedLanguages.toSet()
        val localCandidates = detector.detect(transcription.text, languages)
        localCandidates.forEach { candidate ->
            repositories.suggestions.insert(
                TodoSuggestion(
                    id = UUID.randomUUID().toString(),
                    entryId = entryId,
                    suggestedText = candidate.suggestedText,
                    suggestedKind = candidate.kind,
                    language = candidate.language,
                    reason = candidate.reason,
                    matchedRule = candidate.matchedRule,
                    state = TodoSuggestionState.PENDING,
                    createdAt = Instant.now(),
                ),
            )
        }
        if (localCandidates.isEmpty()) {
            cloudFeatures(app).extractTodoCandidates(transcription.text).forEach { text ->
                repositories.suggestions.insert(
                    TodoSuggestion(
                        id = UUID.randomUUID().toString(),
                        entryId = entryId,
                        suggestedText = text,
                        language = languages.firstOrNull() ?: SomaPrefs.language(app),
                        reason = com.soma.core.model.TodoSuggestionReason.AI_EXTRACTED,
                        matchedRule = "cloud:groq:$CLOUD_AI_TODO_MODEL",
                        state = TodoSuggestionState.PENDING,
                        createdAt = Instant.now(),
                    ),
                )
            }
        }
    }

    private companion object {
        const val INTERNAL_BUDGET_MILLIS = 8 * 60 * 1_000L

        /** The app language wins ambiguous chunks when it is a spoken language. */
        fun createLocalTranscriber(context: Context): WhisperCppTranscriber {
            val spoken = SomaPrefs.speechLanguages(context)
            val appLanguage = SomaPrefs.language(context)
            val store = LocalModelStore(context)
            val model = resolveLocalWhisperModel(context, store)
            return WhisperCppTranscriber(
                context = context,
                model = model,
                modelFile = store.installedFile(model),
                allowedLanguages = spoken.map { it.languageTag }.toTypedArray(),
                preferredLanguage = appLanguage.languageTag.takeIf { appLanguage in spoken },
                vocabulary = TranscriptionVocabularyStore(context).read(),
            )
        }
    }
}

internal fun classifyTranscriptionFailure(error: Throwable): TranscriptionFailure = when (error) {
    is FileNotFoundException -> TranscriptionFailure(TranscriptionFailureCode.AUDIO_UNAVAILABLE, retryable = false)
    is IOException -> TranscriptionFailure(
        TranscriptionFailureCode.AUDIO_UNAVAILABLE,
        retryable = true,
        diagnostic = error.javaClass.simpleName,
    )
    is OutOfMemoryError -> TranscriptionFailure(TranscriptionFailureCode.OUT_OF_MEMORY, retryable = false)
    is UnsatisfiedLinkError -> TranscriptionFailure(TranscriptionFailureCode.ENGINE_UNAVAILABLE, retryable = false)
    is WhisperModelException -> TranscriptionFailure(
        TranscriptionFailureCode.MODEL_ERROR,
        retryable = false,
        diagnostic = error.javaClass.simpleName,
    )
    is NoSpeechDetectedException -> TranscriptionFailure(
        TranscriptionFailureCode.MODEL_ERROR,
        retryable = false,
        diagnostic = error.javaClass.simpleName,
    )
    else -> TranscriptionFailure(
        TranscriptionFailureCode.MODEL_ERROR,
        retryable = true,
        diagnostic = error.javaClass.simpleName,
    )
}

private class NoSpeechDetectedException : IllegalStateException("No speech was detected")
