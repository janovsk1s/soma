package com.soma.storage.repository

import android.content.Context
import androidx.room.withTransaction
import com.soma.core.model.DailyNote
import com.soma.core.model.DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryRevision
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.StillOpenDismissal
import com.soma.core.model.Todo
import com.soma.core.model.TodoState
import com.soma.core.model.TodoSuggestion
import com.soma.core.model.TodoSuggestionState
import com.soma.core.model.TranscriptionFailure
import com.soma.core.model.TranscriptionFailureCode
import com.soma.core.model.TranscriptionJob
import com.soma.core.model.TranscriptionJobState
import com.soma.core.model.TranscriptionResult
import com.soma.core.repository.DailyNoteRepository
import com.soma.core.repository.EntryMutationResult
import com.soma.core.repository.StillOpenRepository
import com.soma.core.repository.TodoRepository
import com.soma.core.repository.TodoSuggestionRepository
import com.soma.core.repository.TranscriptionJobRepository
import com.soma.storage.backup.BackupSnapshot
import com.soma.storage.crypto.AndroidKeystoreTextCipher
import com.soma.storage.crypto.TextCipher
import com.soma.storage.db.DailyNoteEntity
import com.soma.storage.db.DailyNoteWithEntries
import com.soma.storage.db.EntryEntity
import com.soma.storage.db.EntryTypeValue
import com.soma.storage.db.SomaDatabase
import com.soma.storage.db.StillOpenDismissalEntity
import com.soma.storage.db.TranscriptionStateValue
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of every core persistence contract.
 *
 * The only values handed to Room are encrypted entity values. Domain objects are
 * decrypted at the repository boundary; DAOs intentionally never accept plaintext.
 */
class RoomSomaRepository(
    private val database: SomaDatabase,
    textCipher: TextCipher,
) : DailyNoteRepository,
    TodoRepository,
    TodoSuggestionRepository,
    StillOpenRepository,
    TranscriptionJobRepository {
    private val mapper = EntityMapper(textCipher)
    private val noteDao = database.dailyNoteDao()
    private val entryDao = database.entryDao()
    private val revisionDao = database.entryRevisionDao()
    private val todoDao = database.todoDao()
    private val suggestionDao = database.todoSuggestionDao()
    private val dismissalDao = database.stillOpenDismissalDao()
    private val jobDao = database.transcriptionJobDao()

    override suspend fun getOrCreate(date: LocalDate, createdAt: Instant): DailyNote =
        database.withTransaction {
            noteDao.getWithEntries(date.toEpochDay())?.let(::noteFromAggregate)?.let {
                return@withTransaction it
            }
            val entity = DailyNoteEntity(
                id = NoteIds.fromDate(date),
                epochDay = date.toEpochDay(),
                createdAtMillis = createdAt.toEpochMilli(),
                updatedAtMillis = createdAt.toEpochMilli(),
            )
            noteDao.insert(entity)
            noteDao.getWithEntries(date.toEpochDay())?.let(::noteFromAggregate)
                ?: error("Daily note was not created")
        }

    override suspend fun get(date: LocalDate): DailyNote? = database.withTransaction {
        noteDao.getWithEntries(date.toEpochDay())?.let(::noteFromAggregate)
    }

    override fun observe(date: LocalDate): Flow<DailyNote?> =
        noteDao.observeWithEntries(date.toEpochDay()).map { it?.let(::noteFromAggregate) }

    override suspend fun listBeforeOrOn(
        date: LocalDate,
        limit: Int,
        offset: Int,
    ): List<DailyNote> {
        require(limit > 0) { "Limit must be positive" }
        require(offset >= 0) { "Offset must not be negative" }
        return database.withTransaction {
            noteDao.listBeforeOrOn(date.toEpochDay(), limit, offset).map { note ->
                DailyNote(
                    date = LocalDate.ofEpochDay(note.epochDay),
                    createdAt = Instant.ofEpochMilli(note.createdAtMillis),
                    entries = entryDao.listForNote(note.id)
                        .filter { it.deletedAtMillis == null }
                        .sortedBy(EntryEntity::position)
                        .map { mapper.entryFromEntity(it, LocalDate.ofEpochDay(note.epochDay)) },
                )
            }
        }
    }

    override suspend fun getEntry(entryId: String) = database.withTransaction {
        val entity = entryDao.getById(entryId) ?: return@withTransaction null
        if (entity.deletedAtMillis != null) return@withTransaction null
        val note = noteDao.getById(entity.noteId) ?: return@withTransaction null
        mapper.entryFromEntity(entity, LocalDate.ofEpochDay(note.epochDay))
    }

    override suspend fun nextEntryPosition(date: LocalDate): Int = database.withTransaction {
        val note = noteDao.getByEpochDay(date.toEpochDay()) ?: return@withTransaction 0
        entryDao.nextPosition(note.id)
    }

    override suspend fun insertEntry(entry: com.soma.core.model.NoteEntry): Boolean =
        database.withTransaction {
            val note = noteDao.getByEpochDay(entry.noteDate.toEpochDay())
                ?: return@withTransaction false
            val inserted = entryDao.insert(mapper.entryToEntity(entry, note.id, revision = 0))
            if (inserted == INSERT_CONFLICT) return@withTransaction false
            noteDao.touch(note.id, entry.updatedAt.toEpochMilli())
            true
        }

    override suspend fun updateEntry(entry: com.soma.core.model.NoteEntry): Boolean =
        database.withTransaction {
            val existing = entryDao.getById(entry.id) ?: return@withTransaction false
            val targetNote = noteDao.getByEpochDay(entry.noteDate.toEpochDay())
                ?: return@withTransaction false
            if (targetNote.id != existing.noteId) return@withTransaction false
            val changed = entryDao.update(
                mapper.entryToEntity(entry, targetNote.id, revision = existing.revision + 1),
            )
            if (changed != 1) return@withTransaction false
            noteDao.touch(existing.noteId, entry.updatedAt.toEpochMilli())
            true
        }

    override suspend fun editEntryText(
        entryId: String,
        text: String,
        editedAt: Instant,
    ): EntryMutationResult? = database.withTransaction {
        val existing = entryDao.getById(entryId) ?: return@withTransaction null
        val note = noteDao.getById(existing.noteId) ?: return@withTransaction null
        val previous = mapper.entryFromEntity(existing, LocalDate.ofEpochDay(note.epochDay))
        if (previous.isDeleted) return@withTransaction null
        if (previous.text == text) return@withTransaction EntryMutationResult(previous, previous)
        val at = maxOf(previous.updatedAt, editedAt)
        val current = previous.copy(text = text, updatedAt = at, lastUserEditedAt = at)
        val revision = existing.revision + 1
        // Preserve the wording being replaced. The current wording remains on the
        // entry itself, so together they form a complete, exportable edit history.
        revisionDao.insert(mapper.revisionToEntity(EntryRevision(entryId, revision, previous.text, at)))
        check(entryDao.update(mapper.entryToEntity(current, existing.noteId, revision)) == 1) {
            "Entry disappeared during user edit"
        }
        noteDao.touch(existing.noteId, at.toEpochMilli())
        EntryMutationResult(previous, current)
    }

    override suspend fun listEntryRevisions(entryId: String): List<EntryRevision> =
        revisionDao.listForEntry(entryId).map(mapper::revisionFromEntity)

    override suspend fun mutateEntry(
        entryId: String,
        transform: (com.soma.core.model.NoteEntry) -> com.soma.core.model.NoteEntry?,
    ): EntryMutationResult? = database.withTransaction {
        val existing = entryDao.getById(entryId) ?: return@withTransaction null
        val note = noteDao.getById(existing.noteId) ?: return@withTransaction null
        val previous = mapper.entryFromEntity(existing, LocalDate.ofEpochDay(note.epochDay))
        val current = transform(previous)
        if (current == null) {
            check(entryDao.deleteById(entryId) == 1) { "Entry disappeared during mutation" }
            noteDao.touch(existing.noteId, System.currentTimeMillis())
        } else {
            require(current.id == previous.id && current.noteDate == previous.noteDate) {
                "An entry mutation cannot change identity or date"
            }
            if (previous.kind == EntryKind.VOICE && current.kind == EntryKind.TEXT) {
                jobDao.deleteByEntryId(entryId)
            }
            check(entryDao.update(mapper.entryToEntity(current, existing.noteId, existing.revision + 1)) == 1) {
                "Entry disappeared during mutation"
            }
            noteDao.touch(existing.noteId, current.updatedAt.toEpochMilli())
        }
        EntryMutationResult(previous, current)
    }

    override suspend fun deleteEntry(entryId: String): Boolean = database.withTransaction {
        val existing = entryDao.getById(entryId) ?: return@withTransaction false
        if (entryDao.deleteById(entryId) != 1) return@withTransaction false
        noteDao.touch(existing.noteId, System.currentTimeMillis())
        true
    }

    override fun observeReturnLater() = entryDao.observeReturnLater().map { entries ->
        database.withTransaction {
            entries.mapNotNull { entry ->
                noteDao.getById(entry.noteId)?.let { note ->
                    mapper.entryFromEntity(entry, LocalDate.ofEpochDay(note.epochDay))
                }
            }
        }
    }

    override fun observeDeleted() = entryDao.observeDeleted().map { entries ->
        database.withTransaction {
            entries.mapNotNull { entry ->
                noteDao.getById(entry.noteId)?.let { note ->
                    mapper.entryFromEntity(entry, LocalDate.ofEpochDay(note.epochDay))
                }
            }
        }
    }

    override suspend fun datesWithEntries(from: LocalDate, to: LocalDate): List<LocalDate> =
        noteDao.notedDaysBetween(from.toEpochDay(), to.toEpochDay()).map(LocalDate::ofEpochDay)

    override suspend fun get(todoId: String): Todo? =
        todoDao.getById(todoId)?.let(mapper::todoFromEntity)

    override fun observe(states: Set<TodoState>): Flow<List<Todo>> {
        if (states.isEmpty()) return flowOf(emptyList())
        return todoDao.observeByStatuses(states.mapTo(mutableSetOf()) { it.name })
            .map { entities -> entities.map(mapper::todoFromEntity) }
    }

    override suspend fun list(states: Set<TodoState>, limit: Int, offset: Int): List<Todo> {
        require(limit > 0) { "Limit must be positive" }
        require(offset >= 0) { "Offset must not be negative" }
        if (states.isEmpty()) return emptyList()
        return todoDao.listByStatuses(
            statuses = states.mapTo(mutableSetOf()) { it.name },
            limit = limit,
            offset = offset,
        ).map(mapper::todoFromEntity)
    }

    override suspend fun insert(todo: Todo): Boolean = database.withTransaction {
        todoDao.insert(mapper.todoToEntity(todo)) != INSERT_CONFLICT
    }

    override suspend fun update(todo: Todo): Boolean = database.withTransaction {
        val existing = todoDao.getById(todo.id) ?: return@withTransaction false
        todoDao.update(mapper.todoToEntity(todo, existing)) == 1
    }

    override fun observeForEntry(entryId: String): Flow<List<TodoSuggestion>> =
        suggestionDao.observeForEntry(entryId)
            .map { entities -> entities.map(mapper::suggestionFromEntity) }

    override fun observePending(): Flow<List<TodoSuggestion>> =
        suggestionDao.observePending().map { entities -> entities.map(mapper::suggestionFromEntity) }

    override suspend fun pendingForEntry(entryId: String): List<TodoSuggestion> =
        suggestionDao.pendingForEntry(entryId).map(mapper::suggestionFromEntity)

    override suspend fun insert(suggestion: TodoSuggestion): Boolean = database.withTransaction {
        suggestionDao.insert(mapper.suggestionToEntity(suggestion)) != INSERT_CONFLICT
    }

    override suspend fun update(suggestion: TodoSuggestion): Boolean = database.withTransaction {
        if (suggestionDao.getById(suggestion.id) == null) return@withTransaction false
        suggestionDao.update(mapper.suggestionToEntity(suggestion)) == 1
    }

    override suspend fun accept(
        suggestionId: String,
        todo: Todo,
        resolvedAt: Instant,
    ): Boolean = database.withTransaction {
        val suggestion = suggestionDao.getById(suggestionId) ?: return@withTransaction false
        if (suggestion.state != TodoSuggestionState.PENDING.name) return@withTransaction false
        if (todoDao.insert(mapper.todoToEntity(todo)) == INSERT_CONFLICT) {
            return@withTransaction false
        }
        val accepted = mapper.suggestionFromEntity(suggestion).copy(
            state = TodoSuggestionState.ACCEPTED,
            resolvedAt = resolvedAt,
        )
        check(suggestionDao.update(mapper.suggestionToEntity(accepted)) == 1) {
            "Suggestion disappeared during acceptance"
        }
        true
    }

    override fun observeDismissal(date: LocalDate): Flow<StillOpenDismissal?> =
        dismissalDao.observe(date.toEpochDay()).map { entity ->
            entity?.let {
                StillOpenDismissal(
                    date = LocalDate.ofEpochDay(it.epochDay),
                    dismissedAt = Instant.ofEpochMilli(it.dismissedAtMillis),
                )
            }
        }

    override suspend fun dismissal(date: LocalDate): StillOpenDismissal? =
        dismissalDao.get(date.toEpochDay())?.let {
            StillOpenDismissal(
                date = LocalDate.ofEpochDay(it.epochDay),
                dismissedAt = Instant.ofEpochMilli(it.dismissedAtMillis),
            )
        }

    override suspend fun dismiss(dismissal: StillOpenDismissal) {
        dismissalDao.dismiss(
            StillOpenDismissalEntity(
                epochDay = dismissal.date.toEpochDay(),
                dismissedAtMillis = dismissal.dismissedAt.toEpochMilli(),
            ),
        )
    }

    override suspend fun getForEntry(entryId: String): TranscriptionJob? =
        jobDao.getByEntryId(entryId)?.let(mapper::jobFromEntity)

    override suspend fun enqueue(job: TranscriptionJob): Boolean = database.withTransaction {
        require(job.state == TranscriptionJobState.QUEUED) { "Only queued jobs can be enqueued" }
        val entry = entryDao.getById(job.entryId) ?: return@withTransaction false
        if (entry.type != EntryTypeValue.VOICE) return@withTransaction false
        if (jobDao.insert(mapper.jobToEntity(job)) == INSERT_CONFLICT) return@withTransaction false
        check(
            entryDao.update(
                entry.copy(
                    transcriptionState = TranscriptionStateValue.QUEUED,
                    transcriptionAttemptCount = job.attemptCount,
                    transcriptionRequestedEngine = null,
                    transcriptionUsedEngine = null,
                    transcriptionFallbackReason = null,
                    transcriptionUpdatedAtMillis = job.updatedAt.toEpochMilli(),
                    transcriptionFailureCode = null,
                    transcriptionFailureRetryable = null,
                    transcriptionFailureCiphertext = null,
                    updatedAtMillis = job.updatedAt.toEpochMilli(),
                    revision = entry.revision + 1,
                ),
            ) == 1,
        )
        noteDao.touch(entry.noteId, job.updatedAt.toEpochMilli())
        true
    }

    override suspend fun restart(job: TranscriptionJob): Boolean = database.withTransaction {
        require(job.state == TranscriptionJobState.QUEUED) { "Only queued jobs can restart transcription" }
        val entry = entryDao.getById(job.entryId) ?: return@withTransaction false
        if (entry.type != EntryTypeValue.VOICE || entry.audioFileId == null) return@withTransaction false
        jobDao.deleteByEntryId(job.entryId)
        if (jobDao.insert(mapper.jobToEntity(job)) == INSERT_CONFLICT) return@withTransaction false
        check(
            entryDao.update(
                entry.copy(
                    transcriptionState = TranscriptionStateValue.QUEUED,
                    transcriptionAttemptCount = 0,
                    transcriptionRequestedEngine = null,
                    transcriptionUsedEngine = null,
                    transcriptionFallbackReason = null,
                    transcriptionUpdatedAtMillis = job.updatedAt.toEpochMilli(),
                    transcriptionFailureCode = null,
                    transcriptionFailureRetryable = null,
                    transcriptionFailureCiphertext = null,
                    updatedAtMillis = job.updatedAt.toEpochMilli(),
                    revision = entry.revision + 1,
                ),
            ) == 1,
        )
        noteDao.touch(entry.noteId, job.updatedAt.toEpochMilli())
        true
    }

    override suspend fun claimNext(
        leaseOwner: String,
        now: Instant,
        leaseDuration: Duration,
    ): TranscriptionJob? = database.withTransaction {
        require(!leaseDuration.isZero && !leaseDuration.isNegative) {
            "Lease duration must be positive"
        }
        releaseExpiredEntries(now.toEpochMilli())
        val claimed = jobDao.claimNext(
            workerId = leaseOwner,
            nowMillis = now.toEpochMilli(),
            leaseExpiresAtMillis = now.plus(leaseDuration).toEpochMilli(),
            maxAttempts = DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS,
        ) ?: return@withTransaction null
        val entry = entryDao.getById(claimed.entryId) ?: error("Claimed job has no entry")
        check(
            entryDao.update(
                entry.copy(
                    transcriptionState = TranscriptionStateValue.RUNNING,
                    transcriptionAttemptCount = claimed.attemptCount,
                    transcriptionRequestedEngine = null,
                    transcriptionUsedEngine = null,
                    transcriptionFallbackReason = null,
                    transcriptionUpdatedAtMillis = now.toEpochMilli(),
                    transcriptionFailureCode = null,
                    transcriptionFailureRetryable = null,
                    transcriptionFailureCiphertext = null,
                    updatedAtMillis = now.toEpochMilli(),
                    revision = entry.revision + 1,
                ),
            ) == 1,
        )
        noteDao.touch(entry.noteId, now.toEpochMilli())
        mapper.jobFromEntity(claimed)
    }

    override suspend fun complete(
        jobId: String,
        leaseOwner: String,
        result: TranscriptionResult,
        completedAt: Instant,
    ): Boolean = database.withTransaction {
        val job = jobDao.getById(jobId) ?: return@withTransaction false
        if (job.state != TranscriptionJobState.RUNNING.name || job.leaseOwner != leaseOwner) {
            return@withTransaction false
        }
        if (job.leaseExpiresAtMillis == null || job.leaseExpiresAtMillis <= completedAt.toEpochMilli()) {
            return@withTransaction false
        }
        val entry = entryDao.getById(job.entryId) ?: return@withTransaction false
        val completedMillis = completedAt.toEpochMilli()
        check(
            entryDao.update(
                entry.copy(
                    textCiphertext = mapper.encryptEntryText(entry.id, result.text),
                    cryptoVersion = EntityMapper.CRYPTO_VERSION,
                    transcriptionState = TranscriptionStateValue.SUCCEEDED,
                    transcriptionAttemptCount = job.attemptCount,
                    detectedLanguages = result.detectedLanguages.joinToString(",") { it.name },
                    transcriptionRequestedEngine = result.provenance.requestedEngine.name,
                    transcriptionUsedEngine = result.provenance.usedEngine.name,
                    transcriptionFallbackReason = result.provenance.fallbackReason?.name,
                    transcriptionUpdatedAtMillis = completedMillis,
                    transcriptionFailureCode = null,
                    transcriptionFailureRetryable = null,
                    transcriptionFailureCiphertext = null,
                    updatedAtMillis = completedMillis,
                    revision = entry.revision + 1,
                ),
            ) == 1,
        )
        check(jobDao.complete(jobId, leaseOwner, completedMillis) == 1)
        noteDao.touch(entry.noteId, completedMillis)
        true
    }

    override suspend fun recordFailure(
        jobId: String,
        leaseOwner: String,
        failure: TranscriptionFailure,
        failedAt: Instant,
        retryAt: Instant?,
    ): Boolean = database.withTransaction {
        val job = jobDao.getById(jobId) ?: return@withTransaction false
        if (job.state != TranscriptionJobState.RUNNING.name || job.leaseOwner != leaseOwner) {
            return@withTransaction false
        }
        if (job.leaseExpiresAtMillis == null || job.leaseExpiresAtMillis <= failedAt.toEpochMilli()) {
            return@withTransaction false
        }
        val entry = entryDao.getById(job.entryId) ?: return@withTransaction false
        val failedMillis = failedAt.toEpochMilli()
        val nextState = if (retryAt == null) {
            TranscriptionStateValue.FAILED
        } else {
            TranscriptionStateValue.QUEUED
        }
        check(
            jobDao.fail(
                jobId = jobId,
                workerId = leaseOwner,
                nextState = nextState,
                notBeforeMillis = retryAt?.toEpochMilli() ?: job.notBeforeMillis,
                lastErrorCiphertext = failure.diagnostic?.let {
                    mapper.encryptJobFailure(jobId, it)
                },
                lastErrorCode = failure.code.name,
                lastErrorRetryable = failure.retryable,
                cryptoVersion = EntityMapper.CRYPTO_VERSION,
                nowMillis = failedMillis,
            ) == 1,
        )
        check(
            entryDao.update(
                entry.copy(
                    transcriptionState = nextState,
                    transcriptionAttemptCount = job.attemptCount,
                    transcriptionRequestedEngine = null,
                    transcriptionUsedEngine = null,
                    transcriptionFallbackReason = null,
                    transcriptionUpdatedAtMillis = failedMillis,
                    transcriptionFailureCode = failure.code.name.takeIf { retryAt == null },
                    transcriptionFailureRetryable = failure.retryable.takeIf { retryAt == null },
                    transcriptionFailureCiphertext = failure.diagnostic
                        ?.takeIf { retryAt == null }
                        ?.let { mapper.encryptEntryFailure(entry.id, it) },
                    updatedAtMillis = failedMillis,
                    revision = entry.revision + 1,
                ),
            ) == 1,
        )
        noteDao.touch(entry.noteId, failedMillis)
        true
    }

    override suspend fun releaseExpiredLeases(now: Instant): Int = database.withTransaction {
        releaseExpiredEntries(now.toEpochMilli())
    }

    suspend fun entriesNeedingAudioReconciliation(): List<com.soma.core.model.NoteEntry> =
        database.withTransaction {
            entryDao.listNeedingAudioReconciliation().mapNotNull { entry ->
                noteDao.getById(entry.noteId)?.let { note ->
                    mapper.entryFromEntity(entry, LocalDate.ofEpochDay(note.epochDay))
                }
            }
        }

    suspend fun referencedAudioFileIds(): Set<String> = database.withTransaction {
        entryDao.listAudioFileIds().toSet()
    }

    /** A point-in-time, relationally validated snapshot for portable export. */
    suspend fun createBackupSnapshot(
        exportedAt: Instant,
        maximumRows: Int = 1_000_000,
    ): BackupSnapshot = database.withTransaction {
        require(maximumRows > 0) { "Maximum rows must be positive" }
        val noteEntities = noteDao.listBeforeOrOn(LocalDate.MAX.toEpochDay(), maximumRows, 0)
        val notes = noteEntities.map { note ->
            DailyNote(
                date = LocalDate.ofEpochDay(note.epochDay),
                createdAt = Instant.ofEpochMilli(note.createdAtMillis),
                entries = entryDao.listForNote(note.id)
                    .sortedBy(EntryEntity::position)
                    .map { mapper.entryFromEntity(it, LocalDate.ofEpochDay(note.epochDay)) },
            )
        }
        val todos = todoDao.listByStatuses(TodoState.entries.mapTo(mutableSetOf()) { it.name })
            .take(maximumRows)
            .map(mapper::todoFromEntity)
        val suggestions = suggestionDao.listAll().take(maximumRows).map(mapper::suggestionFromEntity)
        val dismissals = dismissalDao.listAll().take(maximumRows).map { entity ->
            StillOpenDismissal(
                date = LocalDate.ofEpochDay(entity.epochDay),
                dismissedAt = Instant.ofEpochMilli(entity.dismissedAtMillis),
            )
        }
        val jobs = jobDao.listAll().take(maximumRows).map(mapper::jobFromEntity)
        val revisions = revisionDao.listAll().take(maximumRows).map(mapper::revisionFromEntity)
        check(noteEntities.size < maximumRows || noteDao.listBeforeOrOn(LocalDate.MAX.toEpochDay(), 1, maximumRows).isEmpty()) {
            "Backup row limit exceeded"
        }
        check(todos.size < maximumRows || todoDao.listByStatuses(
            TodoState.entries.mapTo(mutableSetOf()) { it.name },
            maximumRows + 1,
            0,
        ).size <= maximumRows) { "Backup row limit exceeded" }
        check(suggestionDao.listAll().size <= maximumRows) { "Backup row limit exceeded" }
        check(dismissalDao.listAll().size <= maximumRows) { "Backup row limit exceeded" }
        check(jobDao.listAll().size <= maximumRows) { "Backup row limit exceeded" }
        check(revisionDao.listAll().size <= maximumRows) { "Backup row limit exceeded" }
        BackupSnapshot(
            exportedAt = exportedAt,
            notes = notes,
            entryRevisions = revisions,
            todos = todos,
            suggestions = suggestions,
            stillOpenDismissals = dismissals,
            transcriptionJobs = jobs,
        )
    }

    /** Atomically replaces all Room rows after a portable backup authenticates. */
    suspend fun replaceAll(snapshot: BackupSnapshot) = database.withTransaction {
        // Child-first order keeps foreign-key enforcement explicit.
        jobDao.clear()
        suggestionDao.clear()
        todoDao.clear()
        entryDao.clear()
        dismissalDao.clear()
        noteDao.clear()

        val revisionMaxByEntry = snapshot.entryRevisions.groupBy(EntryRevision::entryId)
            .mapValues { (_, values) -> values.maxOf(EntryRevision::revision) }
        snapshot.notes.sortedBy { it.date }.forEach { note ->
            val noteId = NoteIds.fromDate(note.date)
            check(
                noteDao.insert(
                    DailyNoteEntity(
                        id = noteId,
                        epochDay = note.date.toEpochDay(),
                        createdAtMillis = note.createdAt.toEpochMilli(),
                        updatedAtMillis = note.entries.maxOfOrNull { it.updatedAt.toEpochMilli() }
                            ?: note.createdAt.toEpochMilli(),
                    ),
                ) != INSERT_CONFLICT,
            ) { "Duplicate note in backup" }
            note.entries.sortedBy { it.position }.forEach { entry ->
                check(
                    entryDao.insert(
                        mapper.entryToEntity(entry, noteId, revision = revisionMaxByEntry[entry.id] ?: 0),
                    ) != INSERT_CONFLICT,
                ) {
                    "Duplicate entry in backup"
                }
            }
        }
        snapshot.entryRevisions.sortedWith(compareBy(EntryRevision::entryId, EntryRevision::revision))
            .forEach { revisionDao.insert(mapper.revisionToEntity(it)) }
        snapshot.todos.forEach { todo ->
            check(todoDao.insert(mapper.todoToEntity(todo)) != INSERT_CONFLICT) { "Duplicate todo in backup" }
        }
        snapshot.suggestions.forEach { suggestion ->
            check(suggestionDao.insert(mapper.suggestionToEntity(suggestion)) != INSERT_CONFLICT) {
                "Duplicate suggestion in backup"
            }
        }
        snapshot.stillOpenDismissals.forEach { dismissal ->
            dismissalDao.dismiss(
                StillOpenDismissalEntity(
                    epochDay = dismissal.date.toEpochDay(),
                    dismissedAtMillis = dismissal.dismissedAt.toEpochMilli(),
                ),
            )
        }
        snapshot.transcriptionJobs.forEach { job ->
            check(jobDao.insert(mapper.jobToEntity(job)) != INSERT_CONFLICT) {
                "Duplicate transcription job in backup"
            }
        }
    }

    private suspend fun releaseExpiredEntries(nowMillis: Long): Int {
        val expired = jobDao.expiredJobs(nowMillis)
        val released = jobDao.releaseExpired(nowMillis, DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS)
        expired.forEach { job ->
            entryDao.getById(job.entryId)?.let { entry ->
                val exhausted = job.attemptCount >= DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS
                entryDao.update(
                    entry.copy(
                        transcriptionState = if (exhausted) {
                            EntryTranscriptionState.FAILED.name
                        } else {
                            TranscriptionStateValue.QUEUED
                        },
                        transcriptionRequestedEngine = null,
                        transcriptionUsedEngine = null,
                        transcriptionFallbackReason = null,
                        transcriptionUpdatedAtMillis = nowMillis,
                        transcriptionFailureCode = TranscriptionFailureCode.CANCELLED.name.takeIf { exhausted },
                        transcriptionFailureRetryable = false.takeIf { exhausted },
                        transcriptionFailureCiphertext = null,
                        updatedAtMillis = nowMillis,
                        revision = entry.revision + 1,
                    ),
                )
                noteDao.touch(entry.noteId, nowMillis)
            }
        }
        return released
    }

    private fun noteFromAggregate(aggregate: DailyNoteWithEntries): DailyNote {
        val date = LocalDate.ofEpochDay(aggregate.note.epochDay)
        return DailyNote(
            date = date,
            createdAt = Instant.ofEpochMilli(aggregate.note.createdAtMillis),
            entries = aggregate.entries
                .filter { it.deletedAtMillis == null }
                .sortedBy(EntryEntity::position)
                .map { mapper.entryFromEntity(it, date) },
        )
    }

    companion object {
        private const val INSERT_CONFLICT = -1L

        fun create(context: Context): RoomSomaRepository = RoomSomaRepository(
            database = SomaDatabase.build(context),
            textCipher = AndroidKeystoreTextCipher(),
        )
    }
}
