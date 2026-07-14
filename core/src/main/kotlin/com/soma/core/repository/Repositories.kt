package com.soma.core.repository

import com.soma.core.model.DailyNote
import com.soma.core.model.EntryRevision
import com.soma.core.model.EntryMetadata
import com.soma.core.model.LogKind
import com.soma.core.model.LogRecord
import com.soma.core.model.LogRevision
import com.soma.core.model.MetadataSource
import com.soma.core.model.NoteEntry
import com.soma.core.model.StillOpenDismissal
import com.soma.core.model.Todo
import com.soma.core.model.TodoState
import com.soma.core.model.TodoSuggestion
import com.soma.core.model.TranscriptionFailure
import com.soma.core.model.TranscriptionJob
import com.soma.core.model.TranscriptionResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

data class EntryMutationResult(
    val previous: NoteEntry,
    /** Null means the entry was deleted in the same transaction. */
    val current: NoteEntry?,
)

/** Storage contract for notes. Implementations enforce one note per [LocalDate]. */
interface DailyNoteRepository {
    /** Creates an empty note atomically if absent; [createdAt] is ignored when one exists. */
    suspend fun getOrCreate(date: LocalDate, createdAt: Instant): DailyNote

    suspend fun get(date: LocalDate): DailyNote?

    fun observe(date: LocalDate): Flow<DailyNote?>

    /** Notes newest-first, capped by [limit]. Intended for backup and the LAN reader. */
    suspend fun listBeforeOrOn(
        date: LocalDate,
        limit: Int,
        offset: Int = 0,
    ): List<DailyNote>

    suspend fun getEntry(entryId: String): NoteEntry?

    /** Allocates after visible and tombstoned positions so soft deletion cannot cause a collision. */
    suspend fun nextEntryPosition(date: LocalDate): Int

    /** Returns false on a duplicate id or position; never replaces an existing entry. */
    suspend fun insertEntry(entry: NoteEntry): Boolean

    /** Replaces the entry with the same id. Returns false when it no longer exists. */
    suspend fun updateEntry(entry: NoteEntry): Boolean

    /** Atomically stores a deliberate user edit and its encrypted revision snapshot. */
    suspend fun editEntryText(entryId: String, text: String, editedAt: Instant): EntryMutationResult?

    /** User-authored revisions oldest-first. */
    suspend fun listEntryRevisions(entryId: String): List<EntryRevision>

    /** Atomically reads and transforms one current entry; a null transform result deletes it. */
    suspend fun mutateEntry(
        entryId: String,
        transform: (NoteEntry) -> NoteEntry?,
    ): EntryMutationResult?

    /** Permanently purges metadata. Normal user deletion must use a tombstone via [mutateEntry]. */
    suspend fun deleteEntry(entryId: String): Boolean

    /** Marked entries oldest-first, then by their position within a day. */
    fun observeReturnLater(): Flow<List<NoteEntry>>

    /** Soft-deleted entries or attachments, newest deletion first. */
    fun observeDeleted(): Flow<List<NoteEntry>>

    /** Dates within [from]..[to] whose note holds at least one entry, ascending. */
    suspend fun datesWithEntries(from: LocalDate, to: LocalDate): List<LocalDate>
}

interface EntryMetadataRepository {
    /** Manual, AI, and deterministic LOCAL layers, ordered by source for deterministic export. */
    suspend fun forEntry(entryId: String): List<EntryMetadata>

    suspend fun listAll(): List<EntryMetadata>

    /** Visible layers, newest note first then entry position, for plaintext read-only views. */
    suspend fun listAllVisible(): List<EntryMetadata>

    /** Replaces only [EntryMetadata.source]; manual metadata is never overwritten by AI. */
    suspend fun upsert(metadata: EntryMetadata): Boolean

    suspend fun delete(entryId: String, source: MetadataSource): Boolean
}

interface TrackingLogRepository {
    suspend fun getLog(logId: String): LogRecord?

    /** Active records newest-first, optionally restricted to one kind. */
    fun observe(kind: LogKind? = null): Flow<List<LogRecord>>

    suspend fun listAllLogs(): List<LogRecord>

    /** Newest-first page used by read-only surfaces without decrypting the full archive. */
    suspend fun listLogs(
        kind: LogKind?,
        archived: Boolean,
        limit: Int,
        offset: Int = 0,
    ): List<LogRecord>

    /** Returns false rather than replacing a duplicate id. */
    suspend fun insert(log: LogRecord): Boolean

    /**
     * Atomically stores [log] and the encrypted previous snapshot. The immutable id, creation
     * timestamp, source link, and exactly-next revision are checked by the implementation.
     */
    suspend fun update(log: LogRecord): Boolean

    /** Previous snapshots oldest-first. */
    suspend fun listRevisions(logId: String): List<LogRevision>
}

interface TodoRepository {
    suspend fun get(todoId: String): Todo?

    /** Open todos must be emitted oldest-first. */
    fun observe(states: Set<TodoState>): Flow<List<Todo>>

    suspend fun list(
        states: Set<TodoState>,
        limit: Int,
        offset: Int = 0,
    ): List<Todo>

    /** Returns false rather than replacing a duplicate id. */
    suspend fun insert(todo: Todo): Boolean

    /** Returns false when the todo no longer exists. */
    suspend fun update(todo: Todo): Boolean
}

interface TodoSuggestionRepository {
    fun observeForEntry(entryId: String): Flow<List<TodoSuggestion>>

    /** All pending suggestions in deterministic creation order. */
    fun observePending(): Flow<List<TodoSuggestion>>

    suspend fun pendingForEntry(entryId: String): List<TodoSuggestion>

    suspend fun insert(suggestion: TodoSuggestion): Boolean

    suspend fun update(suggestion: TodoSuggestion): Boolean

    /** Creates the todo and resolves its suggestion in one transaction. */
    suspend fun accept(suggestionId: String, todo: Todo, resolvedAt: Instant): Boolean
}

interface StillOpenRepository {
    fun observeDismissal(date: LocalDate): Flow<StillOpenDismissal?>

    suspend fun dismissal(date: LocalDate): StillOpenDismissal?

    /** Upserts the dismissal for that local day. */
    suspend fun dismiss(dismissal: StillOpenDismissal)
}

/**
 * Persistent one-at-a-time transcription queue.
 *
 * Claim, completion and failure methods must be transactional. Completion also updates
 * the voice entry's editable text and transcription state. A null [retryAt] makes a
 * failure terminal; otherwise the job returns to QUEUED.
 */
interface TranscriptionJobRepository {
    suspend fun getForEntry(entryId: String): TranscriptionJob?

    suspend fun enqueue(job: TranscriptionJob): Boolean

    /** Replaces any completed or failed job for the same voice entry with [job]. */
    suspend fun restart(job: TranscriptionJob): Boolean

    suspend fun claimNext(
        leaseOwner: String,
        now: Instant,
        leaseDuration: Duration,
    ): TranscriptionJob?

    suspend fun complete(
        jobId: String,
        leaseOwner: String,
        result: TranscriptionResult,
        completedAt: Instant,
    ): Boolean

    suspend fun recordFailure(
        jobId: String,
        leaseOwner: String,
        failure: TranscriptionFailure,
        failedAt: Instant,
        retryAt: Instant?,
    ): Boolean

    /** Requeues expired RUNNING jobs. Returns the number released. */
    suspend fun releaseExpiredLeases(now: Instant): Int
}
