package com.soma.app

import com.soma.core.model.DailyNote
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.TodoState
import com.soma.core.repository.DailyNoteRepository
import com.soma.core.repository.TodoRepository
import com.soma.lanserver.AudioResource
import com.soma.lanserver.BrowserDay
import com.soma.lanserver.BrowserEntry
import com.soma.lanserver.BrowserEntryKind
import com.soma.lanserver.BrowserEntryVersion
import com.soma.lanserver.BrowserTodo
import com.soma.lanserver.BrowserTodoFilter
import com.soma.lanserver.BrowserTodoState
import com.soma.lanserver.ImageResource
import com.soma.lanserver.LanBrowserServer
import com.soma.lanserver.LanServerConfig
import com.soma.lanserver.LanServerEndpoint
import com.soma.lanserver.LanServerState
import com.soma.lanserver.LanServerStateListener
import com.soma.lanserver.LanServerStopReason
import com.soma.lanserver.PageRequest
import com.soma.lanserver.PagedResult
import com.soma.lanserver.ReadOnlySomaDataSource
import com.soma.voice.AudioWrappingKeyProvider
import com.soma.voice.EncryptedAudioReader
import com.soma.media.EncryptedImageContainer
import com.soma.media.ImageWrappingKeyProvider
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

/** A new plaintext stream must be produced for each request and is never cached by the server. */
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

class EncryptedBrowserViewImageProvider(
    private val fileForImageId: (String) -> File,
    private val keyProvider: ImageWrappingKeyProvider,
) : BrowserViewImageProvider {
    override suspend fun open(imageId: String): BrowserViewImage? {
        val file = fileForImageId(imageId)
        if (!file.isFile) return null
        val (metadata, probe) = EncryptedImageContainer.read(file, imageId, keyProvider)
        probe.fill(0)
        return BrowserViewImage(
            contentType = JPEG_MEDIA_TYPE,
            contentLength = metadata.jpegByteCount.toLong(),
            openStream = {
                val (_, bytes) = EncryptedImageContainer.read(file, imageId, keyProvider)
                WipingByteArrayInputStream(bytes)
            },
        )
    }

    private companion object {
        const val JPEG_MEDIA_TYPE = "image/jpeg"
    }
}

private class WipingByteArrayInputStream(private val bytes: ByteArray) : InputStream() {
    private var position = 0

    override fun read(): Int = if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xff

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= target.size)
        if (length == 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(target, offset, position, position + count)
        position += count
        return count
    }

    override fun close() {
        bytes.fill(0)
        position = bytes.size
    }
}

/** Opens a fresh authenticated decryption stream for every browser audio request. */
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

/** Read-only hooks. Implementations are called from the LAN server's background workers. */
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

/**
 * Ready-to-use adapter for Soma's core repositories. It asks for one extra row
 * to support next-page navigation without loading the archive or needing count queries.
 */
class RepositoryBrowserViewDataSource(
    private val notes: DailyNoteRepository,
    private val todos: TodoRepository,
    private val audioProvider: BrowserViewAudioProvider = BrowserViewAudioProvider.NONE,
    private val imageProvider: BrowserViewImageProvider = BrowserViewImageProvider.NONE,
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

/** Browser-flavor bridge. The purist flavor defines the exact same public API as a no-op. */
class BrowserViewController(
    dataSource: BrowserViewDataSource,
    private val lightMode: Boolean = false,
) : AutoCloseable {
    private val lock = Any()
    private val lanDataSource = LanDataSourceAdapter(dataSource)
    private val mutableState = MutableStateFlow<BrowserViewState>(BrowserViewState.Off)

    private var generation = 0
    private var startInProgress = false
    private var server: LanBrowserServer? = null
    private var currentEndpoint: BrowserViewEndpoint? = null

    val available: Boolean = true
    val state: StateFlow<BrowserViewState> = mutableState.asStateFlow()
    val endpoint: BrowserViewEndpoint?
        get() = (state.value as? BrowserViewState.Running)?.endpoint

    suspend fun start(): BrowserViewStartResult {
        val token = synchronized(lock) {
            currentEndpoint?.takeIf { server != null }?.let {
                return BrowserViewStartResult.Started(it)
            }
            if (startInProgress) {
                return BrowserViewStartResult.NotStarted(BrowserViewStartFailure.START_IN_PROGRESS)
            }
            generation++
            startInProgress = true
            mutableState.value = BrowserViewState.Starting
            generation
        }

        return withContext(Dispatchers.IO) {
            val address = ActiveWlanAddress.find()
                ?: return@withContext failStart(token, BrowserViewStartFailure.NO_ACTIVE_WIFI)
            if (!isCurrent(token)) {
                return@withContext BrowserViewStartResult.NotStarted(BrowserViewStartFailure.CANCELLED)
            }

            val candidate = LanBrowserServer(
                config = LanServerConfig(bindAddress = address, lightMode = lightMode),
                dataSource = lanDataSource,
                stateListener = LanServerStateListener { lanState -> onLanState(token, lanState) },
            )
            val lanEndpoint = try {
                candidate.start()
            } catch (_: Exception) {
                candidate.close()
                return@withContext failStart(token, BrowserViewStartFailure.BIND_FAILED)
            }
            val endpoint = lanEndpoint.toBrowserEndpoint()
            val accepted = synchronized(lock) {
                if (generation != token || !startInProgress) {
                    false
                } else {
                    server = candidate
                    currentEndpoint = endpoint
                    startInProgress = false
                    if (mutableState.value !is BrowserViewState.Running) {
                        mutableState.value = BrowserViewState.Running(
                            endpoint = endpoint,
                            authenticated = false,
                            wrongCodeAttempts = 0,
                        )
                    }
                    true
                }
            }
            if (!accepted) {
                candidate.close()
                BrowserViewStartResult.NotStarted(BrowserViewStartFailure.CANCELLED)
            } else {
                BrowserViewStartResult.Started(endpoint)
            }
        }
    }

    fun stop() {
        val activeServer = synchronized(lock) {
            generation++
            startInProgress = false
            currentEndpoint = null
            mutableState.value = BrowserViewState.Off
            server.also { server = null }
        }
        activeServer?.close()
    }

    override fun close() = stop()

    private fun onLanState(token: Int, lanState: LanServerState) {
        synchronized(lock) {
            if (generation != token) return
            when (lanState) {
                LanServerState.NotStarted -> Unit
                is LanServerState.AwaitingAuthentication -> {
                    val endpoint = lanState.endpoint.toBrowserEndpoint()
                    currentEndpoint = endpoint
                    mutableState.value = BrowserViewState.Running(
                        endpoint = endpoint,
                        authenticated = false,
                        wrongCodeAttempts = lanState.wrongCodeAttempts,
                    )
                }
                is LanServerState.Authenticated -> {
                    val endpoint = currentEndpoint ?: return
                    mutableState.value = BrowserViewState.Running(
                        endpoint = endpoint,
                        authenticated = true,
                        wrongCodeAttempts = 0,
                    )
                }
                is LanServerState.Stopped -> {
                    server = null
                    currentEndpoint = null
                    startInProgress = false
                    mutableState.value = when (lanState.reason) {
                        LanServerStopReason.REQUESTED -> BrowserViewState.Off
                        LanServerStopReason.IDLE_TIMEOUT ->
                            BrowserViewState.Stopped(BrowserViewStopReason.IDLE_TIMEOUT)
                        LanServerStopReason.TOO_MANY_WRONG_CODES ->
                            BrowserViewState.Stopped(BrowserViewStopReason.TOO_MANY_WRONG_CODES)
                        LanServerStopReason.SERVER_ERROR ->
                            BrowserViewState.Stopped(BrowserViewStopReason.SERVER_ERROR)
                    }
                }
            }
        }
    }

    private fun failStart(
        token: Int,
        reason: BrowserViewStartFailure,
    ): BrowserViewStartResult {
        synchronized(lock) {
            if (generation != token) {
                return BrowserViewStartResult.NotStarted(BrowserViewStartFailure.CANCELLED)
            }
            startInProgress = false
            currentEndpoint = null
            mutableState.value = BrowserViewState.StartFailed(reason)
        }
        return BrowserViewStartResult.NotStarted(reason)
    }

    private fun isCurrent(token: Int): Boolean = synchronized(lock) {
        generation == token && startInProgress
    }

    private fun LanServerEndpoint.toBrowserEndpoint(): BrowserViewEndpoint = BrowserViewEndpoint(
        url = url,
        accessCode = accessCode,
    )
}

private class LanDataSourceAdapter(
    private val delegate: BrowserViewDataSource,
) : ReadOnlySomaDataSource {
    override fun listDays(request: PageRequest): PagedResult<BrowserDay> {
        val page = await { delegate.listDays(request.toBrowserRequest()) }
        return page.toLanResult(request) { day ->
            BrowserDay(day.date, day.entryCount, day.preview)
        }
    }

    override fun entriesForDay(
        date: LocalDate,
        request: PageRequest,
    ): PagedResult<BrowserEntry>? {
        val page = await { delegate.entriesForDay(date, request.toBrowserRequest()) } ?: return null
        return page.toLanResult(request) { entry ->
            BrowserEntry(
                id = entry.id,
                text = entry.text,
                kind = when (entry.kind) {
                    BrowserViewEntryKind.TEXT -> BrowserEntryKind.TEXT
                    BrowserViewEntryKind.VOICE -> BrowserEntryKind.VOICE
                    BrowserViewEntryKind.IMAGE -> BrowserEntryKind.IMAGE
                },
                audioId = entry.audioId,
                imageId = entry.imageId,
                transcriptionPending = entry.transcriptionPending,
                markedForReturn = entry.markedForReturn,
                history = entry.history.map { version ->
                    BrowserEntryVersion(
                        number = version.number,
                        text = version.text,
                        becameCurrentAt = version.becameCurrentAt,
                        isCurrent = version.isCurrent,
                    )
                },
            )
        }
    }

    override fun listTodos(
        filter: BrowserTodoFilter,
        request: PageRequest,
    ): PagedResult<BrowserTodo> {
        val browserFilter = if (filter == BrowserTodoFilter.OPEN) {
            BrowserViewTodoFilter.OPEN
        } else {
            BrowserViewTodoFilter.COMPLETED
        }
        val page = await { delegate.listTodos(browserFilter, request.toBrowserRequest()) }
        return page.toLanResult(request) { todo ->
            BrowserTodo(
                id = todo.id,
                text = todo.text,
                createdOn = todo.createdOn,
                state = when (todo.state) {
                    BrowserViewTodoState.OPEN -> BrowserTodoState.OPEN
                    BrowserViewTodoState.DONE -> BrowserTodoState.DONE
                    BrowserViewTodoState.ARCHIVED -> BrowserTodoState.ARCHIVED
                },
                sourceDate = todo.sourceDate,
                sourceEntryId = todo.sourceEntryId,
            )
        }
    }

    override fun openAudio(audioId: String): AudioResource? {
        val audio = await { delegate.openAudio(audioId) } ?: return null
        return AudioResource(audio.contentType, audio.contentLength, audio.openStream)
    }

    override fun openImage(imageId: String): ImageResource? {
        val image = await { delegate.openImage(imageId) } ?: return null
        return ImageResource(image.contentType, image.contentLength, image.openStream)
    }

    private fun PageRequest.toBrowserRequest(): BrowserViewPageRequest =
        BrowserViewPageRequest(offset, limit)

    private fun <T, R> BrowserViewPage<T>.toLanResult(
        request: PageRequest,
        transform: (T) -> R,
    ): PagedResult<R> {
        val visibleItems = items.take(request.limit)
        val approximateTotal = request.offset + visibleItems.size + if (hasMore) 1 else 0
        return PagedResult(visibleItems.map(transform), approximateTotal)
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { block() }
}

private object ActiveWlanAddress {
    fun find(): InetAddress? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        interfaces.asSequence()
            .filter { network -> network.isUp && !network.isLoopback && network.isWifiName() }
            .flatMap { network -> network.inetAddresses.asSequence() }
            .filter { address ->
                address.isSiteLocalAddress && !address.isAnyLocalAddress &&
                    !address.isLoopbackAddress && !address.isLinkLocalAddress
            }
            .sortedBy { address -> if (address is Inet4Address) 0 else 1 }
            .firstOrNull()
    }.getOrNull()

    private fun NetworkInterface.isWifiName(): Boolean {
        val normalized = name.orEmpty().lowercase()
        return normalized.startsWith("wlan") || normalized.startsWith("wifi") ||
            normalized.startsWith("wl")
    }
}
