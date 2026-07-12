package com.soma.app

import com.soma.core.model.DailyNote
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.TodoState
import com.soma.core.repository.DailyNoteRepository
import com.soma.core.repository.TodoRepository
import com.soma.voice.AudioWrappingKeyProvider
import com.soma.voice.EncryptedAudioReader
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Flavor-neutral paging contract consumed by the Browser view adapter. */
data class BrowserViewPageRequest(val offset: Int, val limit: Int) {
    init {
        require(offset >= 0) { "Offset must not be negative" }
        require(limit in 1..100) { "Limit must be between 1 and 100" }
    }
}

data class BrowserViewPage<T>(
    val items: List<T>,
    val hasMore: Boolean,
)

data class BrowserViewDay(
    val date: LocalDate,
    val entryCount: Int,
    val preview: String,
)

enum class BrowserViewEntryKind {
    TEXT,
    VOICE,
}

data class BrowserViewEntry(
    val id: String,
    val text: String,
    val kind: BrowserViewEntryKind,
    val audioId: String?,
    val transcriptionPending: Boolean,
    val markedForReturn: Boolean,
)

enum class BrowserViewTodoFilter {
    OPEN,
    COMPLETED,
}

enum class BrowserViewTodoState {
    OPEN,
    DONE,
    ARCHIVED,
}

data class BrowserViewTodo(
    val id: String,
    val text: String,
    val createdOn: LocalDate,
    val state: BrowserViewTodoState,
    val sourceDate: LocalDate?,
    val sourceEntryId: String?,
)

/** Kept in the purist API so main code has identical symbols; it is never opened here. */
class BrowserViewAudio(
    val contentType: String,
    val contentLength: Long,
    val openStream: () -> InputStream,
) {
    init {
        require(contentType.startsWith("audio/")) { "Browser audio must use an audio media type" }
        require(contentLength >= 0) { "Audio length must not be negative" }
    }
}

interface BrowserViewAudioProvider {
    suspend fun open(audioId: String): BrowserViewAudio?

    companion object {
        val NONE: BrowserViewAudioProvider = object : BrowserViewAudioProvider {
            override suspend fun open(audioId: String): BrowserViewAudio? = null
        }
    }
}

/** Same flavor-neutral provider API; the purist controller never serves the resulting stream. */
class EncryptedBrowserViewAudioProvider(
    private val fileForAudioId: (String) -> File,
    private val keyProvider: AudioWrappingKeyProvider,
) : BrowserViewAudioProvider {
    override suspend fun open(audioId: String): BrowserViewAudio? {
        val file = fileForAudioId(audioId)
        if (!file.isFile) return null
        val metadata = EncryptedAudioReader.open(file, keyProvider, audioId).use { reader -> reader.metadata }
        if (metadata.audioId != audioId) return null
        return BrowserViewAudio(
            contentType = WAV_MEDIA_TYPE,
            contentLength = metadata.pcmBytes + WAV_HEADER_BYTES,
            openStream = { EncryptedAudioReader.open(file, keyProvider, audioId).wavStream() },
        )
    }

    private companion object {
        const val WAV_MEDIA_TYPE = "audio/wav"
        const val WAV_HEADER_BYTES = 44L
    }
}

interface BrowserViewDataSource {
    suspend fun listDays(request: BrowserViewPageRequest): BrowserViewPage<BrowserViewDay>

    suspend fun entriesForDay(
        date: LocalDate,
        request: BrowserViewPageRequest,
    ): BrowserViewPage<BrowserViewEntry>?

    suspend fun listTodos(
        filter: BrowserViewTodoFilter,
        request: BrowserViewPageRequest,
    ): BrowserViewPage<BrowserViewTodo>

    suspend fun openAudio(audioId: String): BrowserViewAudio?
}

/** Same repository adapter as the browser flavor, retained to keep main construction flavor-blind. */
class RepositoryBrowserViewDataSource(
    private val notes: DailyNoteRepository,
    private val todos: TodoRepository,
    private val audioProvider: BrowserViewAudioProvider = BrowserViewAudioProvider.NONE,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zoneId) },
) : BrowserViewDataSource {
    override suspend fun listDays(request: BrowserViewPageRequest): BrowserViewPage<BrowserViewDay> {
        val notes = notes.listBeforeOrOn(
            date = today(),
            limit = request.limit + 1,
            offset = request.offset,
        )
        return notes.toPage(request.limit) { note ->
            BrowserViewDay(
                date = note.date,
                entryCount = note.entries.size,
                preview = note.preview(),
            )
        }
    }

    override suspend fun entriesForDay(
        date: LocalDate,
        request: BrowserViewPageRequest,
    ): BrowserViewPage<BrowserViewEntry>? {
        val note = notes.get(date) ?: return null
        return note.entries.drop(request.offset).take(request.limit + 1).toPage(request.limit) { entry ->
            BrowserViewEntry(
                id = entry.id,
                text = entry.text,
                kind = if (entry.kind == EntryKind.VOICE) {
                    BrowserViewEntryKind.VOICE
                } else {
                    BrowserViewEntryKind.TEXT
                },
                audioId = entry.audio?.fileId,
                transcriptionPending = entry.transcription?.state in PENDING_TRANSCRIPTION_STATES,
                markedForReturn = entry.returnLater,
            )
        }
    }

    override suspend fun listTodos(
        filter: BrowserViewTodoFilter,
        request: BrowserViewPageRequest,
    ): BrowserViewPage<BrowserViewTodo> {
        val states = when (filter) {
            BrowserViewTodoFilter.OPEN -> setOf(TodoState.OPEN)
            BrowserViewTodoFilter.COMPLETED -> setOf(TodoState.DONE, TodoState.ARCHIVED)
        }
        val todos = todos.list(
            states = states,
            limit = request.limit + 1,
            offset = request.offset,
        )
        return todos.toPage(request.limit) { todo ->
            BrowserViewTodo(
                id = todo.id,
                text = todo.text,
                createdOn = todo.createdAt.atZone(zoneId).toLocalDate(),
                state = when (todo.state) {
                    TodoState.OPEN -> BrowserViewTodoState.OPEN
                    TodoState.DONE -> BrowserViewTodoState.DONE
                    TodoState.ARCHIVED -> BrowserViewTodoState.ARCHIVED
                },
                sourceDate = todo.source?.noteDate,
                sourceEntryId = todo.source?.entryId,
            )
        }
    }

    override suspend fun openAudio(audioId: String): BrowserViewAudio? = audioProvider.open(audioId)

    private fun DailyNote.preview(): String {
        val text = entries.firstOrNull { it.text.isNotBlank() }?.text
            ?: if (entries.any { it.kind == EntryKind.VOICE }) "Voice note" else ""
        return text.lineSequence().firstOrNull().orEmpty().take(PREVIEW_CHARACTERS)
    }

    private fun <T, R> List<T>.toPage(limit: Int, transform: (T) -> R): BrowserViewPage<R> =
        BrowserViewPage(
            items = take(limit).map(transform),
            hasMore = size > limit,
        )

    private companion object {
        const val PREVIEW_CHARACTERS = 160
        val PENDING_TRANSCRIPTION_STATES = setOf(
            EntryTranscriptionState.QUEUED,
            EntryTranscriptionState.RUNNING,
        )
    }
}

data class BrowserViewEndpoint(
    val url: String,
    val accessCode: String,
)

enum class BrowserViewStartFailure {
    UNAVAILABLE_IN_BUILD,
    NO_ACTIVE_WIFI,
    START_IN_PROGRESS,
    BIND_FAILED,
    CANCELLED,
}

sealed interface BrowserViewStartResult {
    data class Started(val endpoint: BrowserViewEndpoint) : BrowserViewStartResult

    data class NotStarted(val reason: BrowserViewStartFailure) : BrowserViewStartResult
}

enum class BrowserViewStopReason {
    IDLE_TIMEOUT,
    TOO_MANY_WRONG_CODES,
    SERVER_ERROR,
}

sealed interface BrowserViewState {
    data object Unavailable : BrowserViewState
    data object Off : BrowserViewState
    data object Starting : BrowserViewState

    data class Running(
        val endpoint: BrowserViewEndpoint,
        val authenticated: Boolean,
        val wrongCodeAttempts: Int,
    ) : BrowserViewState

    data class StartFailed(val reason: BrowserViewStartFailure) : BrowserViewState
    data class Stopped(val reason: BrowserViewStopReason) : BrowserViewState
}

/** No-op implementation for the build that has neither INTERNET permission nor :lanserver. */
class BrowserViewController(
    @Suppress("UNUSED_PARAMETER") dataSource: BrowserViewDataSource,
    @Suppress("UNUSED_PARAMETER") lightMode: Boolean = false,
) : AutoCloseable {
    private val mutableState = MutableStateFlow<BrowserViewState>(BrowserViewState.Unavailable)

    val available: Boolean = false
    val state: StateFlow<BrowserViewState> = mutableState.asStateFlow()
    val endpoint: BrowserViewEndpoint? = null

    suspend fun start(): BrowserViewStartResult =
        BrowserViewStartResult.NotStarted(BrowserViewStartFailure.UNAVAILABLE_IN_BUILD)

    fun stop() {
        mutableState.value = BrowserViewState.Unavailable
    }

    override fun close() = stop()
}
