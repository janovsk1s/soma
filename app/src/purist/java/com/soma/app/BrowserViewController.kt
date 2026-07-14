package com.soma.app

import com.soma.core.model.DailyNote
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.TodoState
import com.soma.core.repository.DailyNoteRepository
import com.soma.core.repository.EntryMetadataRepository
import com.soma.core.repository.TodoRepository
import com.soma.voice.AudioWrappingKeyProvider
import com.soma.voice.EncryptedAudioReader
import com.soma.media.EncryptedImageContainer
import com.soma.media.ImageWrappingKeyProvider
import java.io.File
import java.io.InputStream
import java.time.Instant
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
    IMAGE,
}

data class BrowserViewEntry(
    val id: String,
    val text: String,
    val kind: BrowserViewEntryKind,
    val audioId: String?,
    val imageId: String? = null,
    val transcriptionPending: Boolean,
    val markedForReturn: Boolean,
    val history: List<BrowserViewEntryVersion> = emptyList(),
)

data class BrowserViewEntryVersion(
    val number: Int,
    val text: String,
    val becameCurrentAt: Instant,
    val isCurrent: Boolean,
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

class BrowserViewImage(
    val contentType: String,
    val contentLength: Long,
    val openStream: () -> InputStream,
)

interface BrowserViewImageProvider {
    suspend fun open(imageId: String): BrowserViewImage?

    companion object {
        val NONE: BrowserViewImageProvider = object : BrowserViewImageProvider {
            override suspend fun open(imageId: String): BrowserViewImage? = null
        }
    }
}

/** Kept for source-set parity; the purist flavor has no server and never calls open. */
class EncryptedBrowserViewImageProvider(
    private val fileForImageId: (String) -> File,
    private val keyProvider: ImageWrappingKeyProvider,
) : BrowserViewImageProvider {
    override suspend fun open(imageId: String): BrowserViewImage? {
        val file = fileForImageId(imageId)
        if (!file.isFile) return null
        val (metadata, probe) = EncryptedImageContainer.read(file, imageId, keyProvider)
        probe.fill(0)
        return BrowserViewImage("image/jpeg", metadata.jpegByteCount.toLong()) {
            EncryptedImageContainer.read(file, imageId, keyProvider).second.inputStream()
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

    suspend fun openImage(imageId: String): BrowserViewImage? = null
}

/** Same repository adapter as the browser flavor, retained to keep main construction flavor-blind. */
class RepositoryBrowserViewDataSource(
    private val notes: DailyNoteRepository,
    private val todos: TodoRepository,
    private val audioProvider: BrowserViewAudioProvider = BrowserViewAudioProvider.NONE,
    private val imageProvider: BrowserViewImageProvider = BrowserViewImageProvider.NONE,
    @Suppress("UNUSED_PARAMETER") private val metadata: EntryMetadataRepository? = null,
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
        val entries = note.entries.drop(request.offset).take(request.limit + 1).map { entry ->
            val history = buildEntryHistory(entry, notes.listEntryRevisions(entry.id))
            BrowserViewEntry(
                id = entry.id,
                text = entry.text,
                kind = when (entry.kind) {
                    EntryKind.TEXT -> BrowserViewEntryKind.TEXT
                    EntryKind.VOICE -> BrowserViewEntryKind.VOICE
                    EntryKind.IMAGE -> BrowserViewEntryKind.IMAGE
                },
                audioId = entry.activeAudio?.fileId,
                imageId = entry.activeImage?.fileId,
                transcriptionPending = entry.transcription?.state in PENDING_TRANSCRIPTION_STATES,
                markedForReturn = entry.returnLater,
                history = history.map { version ->
                    BrowserViewEntryVersion(
                        number = version.number,
                        text = version.text,
                        becameCurrentAt = version.becameCurrentAt,
                        isCurrent = version.isCurrent,
                    )
                },
            )
        }
        return entries.toPage(request.limit) { it }
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

    override suspend fun openImage(imageId: String): BrowserViewImage? = imageProvider.open(imageId)

    private fun DailyNote.preview(): String {
        val text = entries.firstOrNull { it.text.isNotBlank() }?.text
            ?: when {
                entries.any { it.kind == EntryKind.IMAGE } -> "Photo"
                entries.any { it.kind == EntryKind.VOICE } -> "Voice note"
                else -> ""
            }
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
    @Suppress("UNUSED_PARAMETER") exportEnabled: () -> Boolean = { false },
    @Suppress("UNUSED_PARAMETER") exportProvider: (suspend () -> ByteArray?)? = null,
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
