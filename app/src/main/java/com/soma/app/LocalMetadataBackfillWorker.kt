package com.soma.app

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.soma.core.model.SupportedLanguage
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Incremental, idempotent enrichment of notes created before the LOCAL layer existed. */
object LocalMetadataBackfillScheduler {
    const val CURRENT_VERSION = 1
    private const val UNIQUE_WORK = "soma-local-metadata-backfill"

    fun enqueue(context: Context, replace: Boolean = false) {
        if (SomaPrefs.localMetadataBackfillVersion(context) >= CURRENT_VERSION) return
        val request = OneTimeWorkRequestBuilder<LocalMetadataBackfillWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

class LocalMetadataBackfillWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SomaApplication
        if (SomaPrefs.demoMode(app) || !SomaPrefs.localAutoMetadata(app)) return Result.success()
        if (SomaPrefs.localMetadataBackfillVersion(app) >= LocalMetadataBackfillScheduler.CURRENT_VERSION) {
            return Result.success()
        }

        val clock = Clock.systemUTC()
        val languages = SomaPrefs.speechLanguages(app) + SomaPrefs.language(app)
        var cursor = SomaPrefs.localMetadataBackfillCursor(app)
            ?.let(LocalDate::ofEpochDay)
            ?: clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()
        val startedAt = SystemClock.elapsedRealtime()

        return try {
            while (!isStopped && SystemClock.elapsedRealtime() - startedAt < WORK_BUDGET_MILLIS) {
                val batch = backfillLocalMetadataBatch(
                    repositories = app.repositories(),
                    languages = languages,
                    clock = clock,
                    beforeOrOn = cursor,
                )
                if (batch.complete) {
                    SomaPrefs.completeLocalMetadataBackfill(app, LocalMetadataBackfillScheduler.CURRENT_VERSION)
                    return Result.success()
                }
                cursor = requireNotNull(batch.nextBeforeOrOn)
                SomaPrefs.setLocalMetadataBackfillCursor(app, cursor.toEpochDay())
            }
            Result.retry()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private companion object {
        const val WORK_BUDGET_MILLIS = 20_000L
    }
}

internal data class LocalMetadataBackfillBatch(
    val complete: Boolean,
    val nextBeforeOrOn: LocalDate? = null,
)

internal suspend fun backfillLocalMetadataBatch(
    repositories: SomaRepositories,
    languages: Set<SupportedLanguage>,
    clock: Clock,
    beforeOrOn: LocalDate,
    maximumNotes: Int = 12,
): LocalMetadataBackfillBatch {
    require(maximumNotes > 0)
    val notes = repositories.notes.listBeforeOrOn(beforeOrOn, maximumNotes)
    notes.forEach { note ->
        note.entries.asSequence()
            .filter { !it.isDeleted && it.text.isNotBlank() }
            .forEach { entry ->
                deriveAndPersistLocalMetadata(
                    repositories = repositories,
                    entry = entry,
                    languages = languages,
                    clock = clock,
                    referenceDate = entry.noteDate,
                )
            }
    }
    if (notes.size < maximumNotes) return LocalMetadataBackfillBatch(complete = true)
    val oldest = notes.last().date
    if (oldest == LocalDate.MIN) return LocalMetadataBackfillBatch(complete = true)
    return LocalMetadataBackfillBatch(complete = false, nextBeforeOrOn = oldest.minusDays(1))
}
