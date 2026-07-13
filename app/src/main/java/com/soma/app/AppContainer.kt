package com.soma.app

import android.app.Application
import com.soma.core.model.DailyNote
import com.soma.core.model.AudioAttachment
import com.soma.core.model.DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS
import com.soma.core.model.EntrySource
import com.soma.core.model.EntryRevision
import com.soma.core.model.NoteEntry
import com.soma.core.model.StillOpenDismissal
import com.soma.core.model.SupportedLanguage
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
import com.soma.storage.repository.RoomSomaRepository
import com.soma.voice.AndroidAudioWrappingKeyProvider
import com.soma.voice.AudioWrappingKeyProvider
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SomaRepositories(
    val notes: DailyNoteRepository,
    val todos: TodoRepository,
    val suggestions: TodoSuggestionRepository,
    val stillOpen: StillOpenRepository,
    val transcriptionJobs: TranscriptionJobRepository,
)

class SomaApplication : Application() {
    private val realRepository: RoomSomaRepository by lazy { RoomSomaRepository.create(this) }
    @Volatile private var demoRepository = InMemorySomaRepository.demo()

    val audioKeyProvider: AudioWrappingKeyProvider by lazy { AndroidAudioWrappingKeyProvider() }
    val audioDirectory: File by lazy { File(noBackupFilesDir, "audio").apply { mkdirs() } }

    override fun onCreate() {
        super.onCreate()
        if (!SomaPrefs.demoMode(this) && SomaPrefs.transcription(this)) {
            TranscriptionScheduler.enqueue(this)
        }
        if (SomaPrefs.reminder(this)) DailyReminderScheduler.scheduleNext(this)
    }

    fun repositories(): SomaRepositories {
        val repository = if (SomaPrefs.demoMode(this)) demoRepository else realRepository
        return SomaRepositories(repository, repository, repository, repository, repository)
    }

    fun regenerateDemo() {
        demoRepository = InMemorySomaRepository.demo()
    }

    fun encryptedAudioFile(fileId: String): File {
        require(AudioAttachment.isValidFileId(fileId)) { "Unsafe audio file id" }
        return File(audioDirectory, "$fileId.sma")
    }
}

/** One in-memory component for developer demo mode; it never opens Room or audio files. */
class InMemorySomaRepository private constructor(
    initialNotes: Map<LocalDate, DailyNote>,
    initialTodos: Map<String, Todo>,
) : DailyNoteRepository,
    TodoRepository,
    TodoSuggestionRepository,
    StillOpenRepository,
    TranscriptionJobRepository {
    private val mutex = Mutex()
    private val notes = MutableStateFlow(initialNotes)
    private val todos = MutableStateFlow(initialTodos)
    private val suggestions = MutableStateFlow<Map<String, TodoSuggestion>>(emptyMap())
    private val dismissals = MutableStateFlow<Map<LocalDate, StillOpenDismissal>>(emptyMap())
    private val jobs = MutableStateFlow<Map<String, TranscriptionJob>>(emptyMap())
    private val revisions = mutableMapOf<String, MutableList<EntryRevision>>()

    override suspend fun getOrCreate(date: LocalDate, createdAt: Instant): DailyNote = mutex.withLock {
        notes.value[date] ?: DailyNote(date, createdAt).also { created ->
            notes.value = notes.value + (date to created)
        }
    }

    override suspend fun get(date: LocalDate): DailyNote? = notes.value[date]

    override fun observe(date: LocalDate): Flow<DailyNote?> = notes.map { it[date] }

    override suspend fun listBeforeOrOn(date: LocalDate, limit: Int, offset: Int): List<DailyNote> =
        notes.value.values.filter { !it.date.isAfter(date) }.sortedByDescending(DailyNote::date)
            .drop(offset).take(limit)

    override suspend fun getEntry(entryId: String): NoteEntry? =
        notes.value.values.asSequence().flatMap { it.entries.asSequence() }.firstOrNull { it.id == entryId }

    override suspend fun insertEntry(entry: NoteEntry): Boolean = mutex.withLock {
        if (getEntry(entry.id) != null) return@withLock false
        val note = notes.value[entry.noteDate] ?: return@withLock false
        if (note.entries.any { it.position == entry.position }) return@withLock false
        notes.value = notes.value + (entry.noteDate to note.copy(entries = (note.entries + entry).sortedBy(NoteEntry::position)))
        true
    }

    override suspend fun updateEntry(entry: NoteEntry): Boolean = mutex.withLock {
        val note = notes.value[entry.noteDate] ?: return@withLock false
        if (note.entries.none { it.id == entry.id }) return@withLock false
        notes.value = notes.value + (
            entry.noteDate to note.copy(entries = note.entries.map { if (it.id == entry.id) entry else it })
        )
        true
    }

    override suspend fun editEntryText(
        entryId: String,
        text: String,
        editedAt: Instant,
    ): EntryMutationResult? = mutex.withLock {
        val note = notes.value.values.firstOrNull { candidate -> candidate.entries.any { it.id == entryId } }
            ?: return@withLock null
        val previous = note.entries.first { it.id == entryId }
        if (previous.text == text) return@withLock EntryMutationResult(previous, previous)
        val at = maxOf(previous.updatedAt, editedAt)
        val current = previous.copy(text = text, updatedAt = at, lastUserEditedAt = at)
        val nextRevision = (revisions[entryId]?.lastOrNull()?.revision ?: 0L) + 1L
        revisions.getOrPut(entryId, ::mutableListOf) += EntryRevision(entryId, nextRevision, previous.text, at)
        notes.value = notes.value + (
            note.date to note.copy(entries = note.entries.map { if (it.id == entryId) current else it })
        )
        EntryMutationResult(previous, current)
    }

    override suspend fun listEntryRevisions(entryId: String): List<EntryRevision> =
        revisions[entryId].orEmpty().toList()

    override suspend fun mutateEntry(
        entryId: String,
        transform: (NoteEntry) -> NoteEntry?,
    ): EntryMutationResult? = mutex.withLock {
        val note = notes.value.values.firstOrNull { candidate -> candidate.entries.any { it.id == entryId } }
            ?: return@withLock null
        val previous = note.entries.first { it.id == entryId }
        val current = transform(previous)
        if (current == null) {
            notes.value = notes.value + (note.date to note.copy(entries = note.entries.filterNot { it.id == entryId }))
            suggestions.value = suggestions.value.filterValues { it.entryId != entryId }
            jobs.value = jobs.value.filterValues { it.entryId != entryId }
            revisions.remove(entryId)
        } else {
            require(current.id == previous.id && current.noteDate == previous.noteDate) {
                "An entry mutation cannot change identity or date"
            }
            if (previous.kind == com.soma.core.model.EntryKind.VOICE && current.kind == com.soma.core.model.EntryKind.TEXT) {
                jobs.value = jobs.value.filterValues { it.entryId != entryId }
            }
            notes.value = notes.value + (
                note.date to note.copy(entries = note.entries.map { if (it.id == entryId) current else it })
            )
        }
        EntryMutationResult(previous, current)
    }

    override suspend fun deleteEntry(entryId: String): Boolean = mutex.withLock {
        val note = notes.value.values.firstOrNull { candidate -> candidate.entries.any { it.id == entryId } }
            ?: return@withLock false
        notes.value = notes.value + (note.date to note.copy(entries = note.entries.filterNot { it.id == entryId }))
        suggestions.value = suggestions.value.filterValues { it.entryId != entryId }
        revisions.remove(entryId)
        true
    }

    override fun observeReturnLater(): Flow<List<NoteEntry>> = notes.map { all ->
        all.values.flatMap(DailyNote::entries).filter(NoteEntry::returnLater)
            .sortedWith(compareBy(NoteEntry::createdAt, NoteEntry::position))
    }

    override suspend fun get(todoId: String): Todo? = todos.value[todoId]

    override fun observe(states: Set<TodoState>): Flow<List<Todo>> = todos.map { all ->
        all.values.filter { it.state in states }.sortedBy(Todo::createdAt)
    }

    override suspend fun list(states: Set<TodoState>, limit: Int, offset: Int): List<Todo> =
        todos.value.values.filter { it.state in states }.sortedBy(Todo::createdAt).drop(offset).take(limit)

    override suspend fun insert(todo: Todo): Boolean = mutex.withLock {
        if (todo.id in todos.value) return@withLock false
        todos.value = todos.value + (todo.id to todo)
        true
    }

    override suspend fun update(todo: Todo): Boolean = mutex.withLock {
        if (todo.id !in todos.value) return@withLock false
        todos.value = todos.value + (todo.id to todo)
        true
    }

    override fun observeForEntry(entryId: String): Flow<List<TodoSuggestion>> = suggestions.map { all ->
        all.values.filter { it.entryId == entryId }.sortedBy(TodoSuggestion::createdAt)
    }

    override fun observePending(): Flow<List<TodoSuggestion>> = suggestions.map { all ->
        all.values.filter { it.state == TodoSuggestionState.PENDING }
            .sortedWith(compareBy(TodoSuggestion::createdAt, TodoSuggestion::id))
    }

    override suspend fun pendingForEntry(entryId: String): List<TodoSuggestion> =
        suggestions.value.values.filter { it.entryId == entryId && it.state == TodoSuggestionState.PENDING }

    override suspend fun insert(suggestion: TodoSuggestion): Boolean = mutex.withLock {
        if (suggestion.id in suggestions.value) return@withLock false
        suggestions.value = suggestions.value + (suggestion.id to suggestion)
        true
    }

    override suspend fun update(suggestion: TodoSuggestion): Boolean = mutex.withLock {
        if (suggestion.id !in suggestions.value) return@withLock false
        suggestions.value = suggestions.value + (suggestion.id to suggestion)
        true
    }

    override suspend fun accept(suggestionId: String, todo: Todo, resolvedAt: Instant): Boolean = mutex.withLock {
        val suggestion = suggestions.value[suggestionId] ?: return@withLock false
        if (suggestion.state != TodoSuggestionState.PENDING || todo.id in todos.value) return@withLock false
        todos.value = todos.value + (todo.id to todo)
        suggestions.value = suggestions.value + (
            suggestionId to suggestion.copy(state = TodoSuggestionState.ACCEPTED, resolvedAt = resolvedAt)
        )
        true
    }

    override fun observeDismissal(date: LocalDate): Flow<StillOpenDismissal?> = dismissals.map { it[date] }
    override suspend fun dismissal(date: LocalDate): StillOpenDismissal? = dismissals.value[date]
    override suspend fun dismiss(dismissal: StillOpenDismissal) = mutex.withLock {
        dismissals.value = dismissals.value + (dismissal.date to dismissal)
    }

    override suspend fun getForEntry(entryId: String): TranscriptionJob? =
        jobs.value.values.firstOrNull { it.entryId == entryId }

    override suspend fun enqueue(job: TranscriptionJob): Boolean = mutex.withLock {
        if (job.id in jobs.value || jobs.value.values.any { it.entryId == job.entryId }) return@withLock false
        jobs.value = jobs.value + (job.id to job)
        true
    }

    override suspend fun claimNext(leaseOwner: String, now: Instant, leaseDuration: Duration): TranscriptionJob? =
        mutex.withLock {
            if (jobs.value.values.any { it.state == TranscriptionJobState.RUNNING && it.leaseExpiresAt?.isAfter(now) == true }) {
                return@withLock null
            }
            val queued = jobs.value.values.filter {
                it.state == TranscriptionJobState.QUEUED &&
                    it.attemptCount < DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS &&
                    !it.availableAt.isAfter(now)
            }.minByOrNull(TranscriptionJob::availableAt) ?: return@withLock null
            val claimed = queued.copy(
                state = TranscriptionJobState.RUNNING,
                attemptCount = queued.attemptCount + 1,
                leaseOwner = leaseOwner,
                leaseExpiresAt = now.plus(leaseDuration),
                updatedAt = now,
            )
            jobs.value = jobs.value + (claimed.id to claimed)
            claimed
        }

    override suspend fun complete(
        jobId: String,
        leaseOwner: String,
        result: TranscriptionResult,
        completedAt: Instant,
    ): Boolean = mutex.withLock {
        val job = jobs.value[jobId] ?: return@withLock false
        if (job.state != TranscriptionJobState.RUNNING || job.leaseOwner != leaseOwner) return@withLock false
        val entry = getEntry(job.entryId) ?: return@withLock false
        val note = notes.value[entry.noteDate] ?: return@withLock false
        val updatedEntry = entry.copy(text = result.text, updatedAt = completedAt)
        notes.value = notes.value + (
            note.date to note.copy(entries = note.entries.map { if (it.id == entry.id) updatedEntry else it })
        )
        jobs.value = jobs.value + (
            job.id to job.copy(
                state = TranscriptionJobState.SUCCEEDED,
                leaseOwner = null,
                leaseExpiresAt = null,
                updatedAt = completedAt,
            )
        )
        true
    }

    override suspend fun recordFailure(
        jobId: String,
        leaseOwner: String,
        failure: TranscriptionFailure,
        failedAt: Instant,
        retryAt: Instant?,
    ): Boolean = mutex.withLock {
        val job = jobs.value[jobId] ?: return@withLock false
        if (job.state != TranscriptionJobState.RUNNING || job.leaseOwner != leaseOwner) return@withLock false
        jobs.value = jobs.value + (
            job.id to job.copy(
                state = if (retryAt == null) TranscriptionJobState.FAILED else TranscriptionJobState.QUEUED,
                availableAt = retryAt ?: job.availableAt,
                leaseOwner = null,
                leaseExpiresAt = null,
                lastFailure = failure,
                updatedAt = failedAt,
            )
        )
        true
    }

    override suspend fun releaseExpiredLeases(now: Instant): Int = mutex.withLock {
        val expired = jobs.value.values.filter {
            it.state == TranscriptionJobState.RUNNING && it.leaseExpiresAt?.isAfter(now) == false
        }
        jobs.value = jobs.value + expired.associate { job ->
            val exhausted = job.attemptCount >= DEFAULT_TRANSCRIPTION_MAX_ATTEMPTS
            job.id to job.copy(
                state = if (exhausted) TranscriptionJobState.FAILED else TranscriptionJobState.QUEUED,
                leaseOwner = null,
                leaseExpiresAt = null,
                lastFailure = if (exhausted) {
                    TranscriptionFailure(TranscriptionFailureCode.CANCELLED, retryable = false)
                } else {
                    job.lastFailure
                },
                updatedAt = now,
            )
        }
        expired.size
    }

    companion object {
        fun demo(today: LocalDate = LocalDate.now(), now: Instant = Instant.now()): InMemorySomaRepository {
            val entries = listOf(
                "Call Mum about Sunday lunch",
                "The quiet route home felt better.",
                "Need to renew the library card",
                "Idea: leave the phone outside the bedroom.",
                "Ask Mara which train she booked",
                "A small bag is enough.",
            ).mapIndexed { index, text ->
                NoteEntry.text(
                    id = "demo-entry-$index",
                    noteDate = today,
                    position = index,
                    text = text,
                    createdAt = now.minusSeconds((entriesAgo(index)).toLong()),
                ).copy(returnLater = index == 3)
            }
            val yesterday = today.minusDays(1)
            val yesterdayEntries = listOf(
                NoteEntry.text("demo-yesterday-1", yesterday, 0, "Buy rye bread on the way back", now.minusSeconds(90_000)),
                NoteEntry.text("demo-yesterday-2", yesterday, 1, "The park was completely empty.", now.minusSeconds(80_000)),
            )
            val demoTodos = listOf(
                Todo("demo-todo-1", "Send the form", now.minusSeconds(6 * 86_400), now.minusSeconds(6 * 86_400)),
                Todo("demo-todo-2", "Renew library card", now.minusSeconds(3 * 86_400), now.minusSeconds(3 * 86_400)),
                Todo(
                    "demo-todo-3",
                    "Call Mum about Sunday lunch",
                    now.minusSeconds(86_400),
                    now.minusSeconds(86_400),
                    source = EntrySource(today, "demo-entry-0"),
                ),
                Todo(
                    "demo-todo-done",
                    "Pick up the repaired shoes",
                    now.minusSeconds(8 * 86_400),
                    now.minusSeconds(2 * 86_400),
                    lastTouchedAt = now.minusSeconds(2 * 86_400),
                    state = TodoState.DONE,
                    closedAt = now.minusSeconds(2 * 86_400),
                ),
            ).associateBy(Todo::id)
            return InMemorySomaRepository(
                initialNotes = mapOf(
                    today to DailyNote(today, now, entries),
                    yesterday to DailyNote(yesterday, now.minusSeconds(90_000), yesterdayEntries),
                ),
                initialTodos = demoTodos,
            )
        }

        private fun entriesAgo(index: Int): Int = (6 - index) * 600
    }
}
