package com.soma.storage.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyNoteDao {
    @Query("DELETE FROM daily_notes")
    suspend fun clear(): Int

    @Query("SELECT * FROM daily_notes WHERE epoch_day = :epochDay LIMIT 1")
    suspend fun getByEpochDay(epochDay: Long): DailyNoteEntity?

    @Query("SELECT * FROM daily_notes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DailyNoteEntity?

    @Query("SELECT * FROM daily_notes WHERE epoch_day = :epochDay LIMIT 1")
    fun observeByEpochDay(epochDay: Long): Flow<DailyNoteEntity?>

    @Transaction
    @Query("SELECT * FROM daily_notes WHERE epoch_day = :epochDay LIMIT 1")
    fun observeWithEntries(epochDay: Long): Flow<DailyNoteWithEntries?>

    @Transaction
    @Query("SELECT * FROM daily_notes WHERE epoch_day = :epochDay LIMIT 1")
    suspend fun getWithEntries(epochDay: Long): DailyNoteWithEntries?

    @Query("SELECT epoch_day FROM daily_notes WHERE epoch_day <= :epochDay ORDER BY epoch_day DESC")
    suspend fun listEpochDaysThrough(epochDay: Long): List<Long>

    @Query(
        """
        SELECT DISTINCT daily_notes.epoch_day FROM daily_notes
        INNER JOIN entries ON entries.note_id = daily_notes.id
        WHERE daily_notes.epoch_day BETWEEN :fromEpochDay AND :toEpochDay
          AND entries.deleted_at_millis IS NULL
        ORDER BY daily_notes.epoch_day ASC
        """,
    )
    suspend fun notedDaysBetween(fromEpochDay: Long, toEpochDay: Long): List<Long>

    @Query(
        """
        SELECT * FROM daily_notes
        WHERE epoch_day <= :epochDay
        ORDER BY epoch_day DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listBeforeOrOn(epochDay: Long, limit: Int, offset: Int): List<DailyNoteEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(note: DailyNoteEntity): Long

    @Query("UPDATE daily_notes SET updated_at_millis = :updatedAtMillis WHERE id = :id")
    suspend fun touch(id: String, updatedAtMillis: Long): Int
}

@Dao
interface EntryDao {
    @Query("DELETE FROM entries")
    suspend fun clear(): Int

    @Query("SELECT * FROM entries WHERE note_id = :noteId ORDER BY position ASC")
    fun observeForNote(noteId: String): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE note_id = :noteId ORDER BY position ASC")
    suspend fun listForNote(noteId: String): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EntryEntity?

    @Query(
        """
        SELECT e.* FROM entries e
        LEFT JOIN transcription_jobs j ON j.entry_id = e.id
        WHERE e.audio_file_id IS NOT NULL
          AND e.deleted_at_millis IS NULL AND e.audio_deleted_at_millis IS NULL
          AND (
            e.audio_duration_millis = 0 OR e.audio_byte_count = 0 OR
            (e.transcription_state = 'QUEUED' AND j.id IS NULL)
          )
        ORDER BY e.created_at_millis ASC
        """,
    )
    suspend fun listNeedingAudioReconciliation(): List<EntryEntity>

    @Query("SELECT audio_file_id FROM entries WHERE audio_file_id IS NOT NULL")
    suspend fun listAudioFileIds(): List<String>

    @Query("SELECT image_file_id FROM entries WHERE image_file_id IS NOT NULL")
    suspend fun listImageFileIds(): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM entries WHERE note_id = :noteId")
    suspend fun nextPosition(noteId: String): Int

    @Query(
        "SELECT * FROM entries WHERE return_later = 1 AND deleted_at_millis IS NULL " +
            "ORDER BY created_at_millis ASC",
    )
    fun observeReturnLater(): Flow<List<EntryEntity>>

    @Query(
        """
        SELECT * FROM entries
        WHERE deleted_at_millis IS NOT NULL OR audio_deleted_at_millis IS NOT NULL
           OR image_deleted_at_millis IS NOT NULL
        ORDER BY COALESCE(deleted_at_millis, audio_deleted_at_millis, image_deleted_at_millis) DESC, id ASC
        """,
    )
    fun observeDeleted(): Flow<List<EntryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: EntryEntity): Long

    @Update
    suspend fun update(entry: EntryEntity): Int

    @Delete
    suspend fun delete(entry: EntryEntity): Int

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query(
        """
        UPDATE entries
        SET transcription_state = :state, updated_at_millis = :updatedAtMillis, revision = revision + 1
        WHERE id = :entryId
        """,
    )
    suspend fun updateTranscriptionState(entryId: String, state: String, updatedAtMillis: Long): Int
}

@Dao
interface EntryRevisionDao {
    @Query("SELECT * FROM entry_revisions WHERE entry_id = :entryId ORDER BY revision ASC")
    suspend fun listForEntry(entryId: String): List<EntryRevisionEntity>

    @Query("SELECT * FROM entry_revisions ORDER BY edited_at_millis ASC, entry_id ASC, revision ASC")
    suspend fun listAll(): List<EntryRevisionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(revision: EntryRevisionEntity)
}

@Dao
interface TodoDao {
    @Query("DELETE FROM todos")
    suspend fun clear(): Int

    @Query("SELECT * FROM todos WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TodoEntity?

    @Query(
        """
        SELECT * FROM todos
        WHERE status IN (:statuses)
        ORDER BY CASE WHEN status = 'OPEN' THEN 0 ELSE 1 END, created_at_millis ASC
        """,
    )
    fun observeByStatuses(statuses: Set<String>): Flow<List<TodoEntity>>

    @Query(
        """
        SELECT * FROM todos
        WHERE status IN (:statuses)
        ORDER BY CASE WHEN status = 'OPEN' THEN 0 ELSE 1 END, created_at_millis ASC
        """,
    )
    suspend fun listByStatuses(statuses: Set<String>): List<TodoEntity>

    @Query(
        """
        SELECT * FROM todos
        WHERE status IN (:statuses)
        ORDER BY CASE WHEN status = 'OPEN' THEN 0 ELSE 1 END, created_at_millis ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listByStatuses(
        statuses: Set<String>,
        limit: Int,
        offset: Int,
    ): List<TodoEntity>

    @Query("SELECT COUNT(*) FROM todos WHERE status = 'OPEN'")
    fun observeOpenCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM todos
        WHERE status = 'OPEN'
          AND last_touched_at_millis <= :untouchedBeforeMillis
          AND review_prompted_at_millis IS NULL
        ORDER BY created_at_millis ASC
        """,
    )
    suspend fun listNeedingReview(untouchedBeforeMillis: Long): List<TodoEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(todo: TodoEntity): Long

    @Update
    suspend fun update(todo: TodoEntity): Int
}

@Dao
interface TodoSuggestionDao {
    @Query("DELETE FROM todo_suggestions")
    suspend fun clear(): Int

    @Query("SELECT * FROM todo_suggestions WHERE entry_id = :entryId ORDER BY sentence_start ASC")
    fun observeForEntry(entryId: String): Flow<List<TodoSuggestionEntity>>

    @Query("SELECT * FROM todo_suggestions WHERE state = 'PENDING' ORDER BY created_at_millis ASC, id ASC")
    fun observePending(): Flow<List<TodoSuggestionEntity>>

    @Query("SELECT * FROM todo_suggestions WHERE entry_id = :entryId ORDER BY sentence_start ASC")
    suspend fun listForEntry(entryId: String): List<TodoSuggestionEntity>

    @Query("SELECT * FROM todo_suggestions ORDER BY created_at_millis ASC, id ASC")
    suspend fun listAll(): List<TodoSuggestionEntity>

    @Query(
        """
        SELECT * FROM todo_suggestions
        WHERE entry_id = :entryId AND state = 'PENDING'
        ORDER BY sentence_start ASC
        """,
    )
    suspend fun pendingForEntry(entryId: String): List<TodoSuggestionEntity>

    @Query("SELECT * FROM todo_suggestions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TodoSuggestionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(suggestion: TodoSuggestionEntity): Long

    @Upsert
    suspend fun upsert(suggestion: TodoSuggestionEntity)

    @Update
    suspend fun update(suggestion: TodoSuggestionEntity): Int
}

@Dao
interface StillOpenDismissalDao {
    @Query("DELETE FROM still_open_dismissals")
    suspend fun clear(): Int

    @Query("SELECT * FROM still_open_dismissals WHERE epoch_day = :epochDay LIMIT 1")
    suspend fun get(epochDay: Long): StillOpenDismissalEntity?

    @Query("SELECT * FROM still_open_dismissals WHERE epoch_day = :epochDay LIMIT 1")
    fun observe(epochDay: Long): Flow<StillOpenDismissalEntity?>

    @Query("SELECT * FROM still_open_dismissals ORDER BY epoch_day ASC")
    suspend fun listAll(): List<StillOpenDismissalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun dismiss(dismissal: StillOpenDismissalEntity)

    @Query("DELETE FROM still_open_dismissals WHERE epoch_day < :oldestEpochDayToKeep")
    suspend fun pruneBefore(oldestEpochDayToKeep: Long): Int
}

@Dao
abstract class TranscriptionJobDao {
    @Query("DELETE FROM transcription_jobs")
    abstract suspend fun clear(): Int

    @Query("SELECT * FROM transcription_jobs WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): TranscriptionJobEntity?

    @Query("SELECT * FROM transcription_jobs WHERE entry_id = :entryId LIMIT 1")
    abstract suspend fun getByEntryId(entryId: String): TranscriptionJobEntity?

    @Query("DELETE FROM transcription_jobs WHERE entry_id = :entryId")
    abstract suspend fun deleteByEntryId(entryId: String): Int

    @Query("SELECT * FROM transcription_jobs ORDER BY enqueued_at_millis ASC, id ASC")
    abstract suspend fun listAll(): List<TranscriptionJobEntity>

    @Upsert
    abstract suspend fun upsert(job: TranscriptionJobEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(job: TranscriptionJobEntity): Long

    @Query(
        """
        SELECT * FROM transcription_jobs
        WHERE state = 'RUNNING'
          AND lease_expires_at_millis IS NOT NULL
          AND lease_expires_at_millis <= :nowMillis
        ORDER BY enqueued_at_millis ASC
        """,
    )
    abstract suspend fun expiredJobs(nowMillis: Long): List<TranscriptionJobEntity>

    @Query(
        """
        UPDATE transcription_jobs
        SET state = CASE WHEN attempt_count >= :maxAttempts THEN 'FAILED' ELSE 'QUEUED' END,
            lease_owner = NULL, lease_expires_at_millis = NULL,
            last_error_ciphertext = CASE WHEN attempt_count >= :maxAttempts THEN NULL ELSE last_error_ciphertext END,
            last_error_code = CASE WHEN attempt_count >= :maxAttempts THEN 'CANCELLED' ELSE last_error_code END,
            last_error_retryable = CASE WHEN attempt_count >= :maxAttempts THEN 0 ELSE last_error_retryable END,
            updated_at_millis = :nowMillis
        WHERE state = 'RUNNING'
          AND lease_expires_at_millis IS NOT NULL
          AND lease_expires_at_millis <= :nowMillis
        """,
    )
    abstract suspend fun releaseExpired(nowMillis: Long, maxAttempts: Int): Int

    @Query(
        """
        SELECT COUNT(*) FROM transcription_jobs
        WHERE state = 'RUNNING'
          AND lease_expires_at_millis IS NOT NULL
          AND lease_expires_at_millis > :nowMillis
        """,
    )
    abstract suspend fun activeLeaseCount(nowMillis: Long): Int

    @Query(
        """
        SELECT transcription_jobs.* FROM transcription_jobs
        INNER JOIN entries ON entries.id = transcription_jobs.entry_id
        WHERE transcription_jobs.state = 'QUEUED'
          AND transcription_jobs.not_before_millis <= :nowMillis
          AND transcription_jobs.attempt_count < :maxAttempts
          AND entries.deleted_at_millis IS NULL
          AND entries.audio_deleted_at_millis IS NULL
        ORDER BY transcription_jobs.enqueued_at_millis ASC, transcription_jobs.id ASC
        LIMIT 1
        """,
    )
    abstract suspend fun nextCandidate(nowMillis: Long, maxAttempts: Int): TranscriptionJobEntity?

    @Query(
        """
        UPDATE transcription_jobs
        SET state = 'RUNNING', lease_owner = :workerId,
            lease_expires_at_millis = :leaseExpiresAtMillis,
            attempt_count = attempt_count + 1, updated_at_millis = :nowMillis
        WHERE id = :jobId AND state = 'QUEUED' AND not_before_millis <= :nowMillis
        """,
    )
    abstract suspend fun tryAcquire(
        jobId: String,
        workerId: String,
        nowMillis: Long,
        leaseExpiresAtMillis: Long,
    ): Int

    /** Globally serializes transcription: at most one non-expired lease is returned. */
    @Transaction
    open suspend fun claimNext(
        workerId: String,
        nowMillis: Long,
        leaseExpiresAtMillis: Long,
        maxAttempts: Int,
    ): TranscriptionJobEntity? {
        require(workerId.isNotBlank()) { "Worker id must not be blank" }
        require(leaseExpiresAtMillis > nowMillis) { "Lease must expire in the future" }
        require(maxAttempts > 0) { "Max attempts must be positive" }
        releaseExpired(nowMillis, maxAttempts)
        if (activeLeaseCount(nowMillis) != 0) return null
        val candidate = nextCandidate(nowMillis, maxAttempts) ?: return null
        if (tryAcquire(candidate.id, workerId, nowMillis, leaseExpiresAtMillis) != 1) return null
        return getById(candidate.id)
    }

    @Query(
        """
        UPDATE transcription_jobs
        SET state = 'SUCCEEDED', lease_owner = NULL, lease_expires_at_millis = NULL,
            last_error_ciphertext = NULL, last_error_code = NULL,
            last_error_retryable = NULL, updated_at_millis = :nowMillis
        WHERE id = :jobId AND state = 'RUNNING' AND lease_owner = :workerId
          AND lease_expires_at_millis > :nowMillis
        """,
    )
    abstract suspend fun complete(jobId: String, workerId: String, nowMillis: Long): Int

    @Query(
        """
        UPDATE transcription_jobs
        SET state = :nextState, not_before_millis = :notBeforeMillis,
            lease_owner = NULL, lease_expires_at_millis = NULL,
            last_error_ciphertext = :lastErrorCiphertext,
            last_error_code = :lastErrorCode, last_error_retryable = :lastErrorRetryable,
            crypto_version = :cryptoVersion,
            updated_at_millis = :nowMillis
        WHERE id = :jobId AND state = 'RUNNING' AND lease_owner = :workerId
          AND lease_expires_at_millis > :nowMillis
        """,
    )
    abstract suspend fun fail(
        jobId: String,
        workerId: String,
        nextState: String,
        notBeforeMillis: Long,
        lastErrorCiphertext: ByteArray?,
        lastErrorCode: String?,
        lastErrorRetryable: Boolean?,
        cryptoVersion: Int,
        nowMillis: Long,
    ): Int
}
