package com.soma.app

import com.soma.core.model.DailyNote
import com.soma.core.model.EntryKind
import com.soma.core.model.EntryLinkKind
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.FoodQuantityUnit
import com.soma.core.model.LogKind
import com.soma.core.model.LogRecord
import com.soma.core.model.MetadataSource
import com.soma.core.model.NutritionBasis
import com.soma.core.model.NutritionSource
import com.soma.core.model.NoteEntry
import com.soma.core.model.TodoState
import com.soma.core.repository.DailyNoteRepository
import com.soma.core.repository.EntryMetadataRepository
import com.soma.core.repository.TrackingLogRepository
import com.soma.core.repository.TodoRepository
import com.soma.lanserver.AudioResource
import com.soma.lanserver.BrowserDay
import com.soma.lanserver.BrowserEntry
import com.soma.lanserver.BrowserEntryKind
import com.soma.lanserver.BrowserEntryVersion
import com.soma.lanserver.BrowserGraphEdge
import com.soma.lanserver.BrowserGraphNodeKind
import com.soma.lanserver.BrowserInsight
import com.soma.lanserver.BrowserInsightKind
import com.soma.lanserver.BrowserInsights
import com.soma.lanserver.BrowserFoodItem
import com.soma.lanserver.BrowserLog
import com.soma.lanserver.BrowserLogFilter
import com.soma.lanserver.BrowserLogKind
import com.soma.lanserver.BrowserMetadataSource
import com.soma.lanserver.BrowserTodo
import com.soma.lanserver.BrowserTodoFilter
import com.soma.lanserver.BrowserTodoState
import com.soma.lanserver.BrowserWorkoutExercise
import com.soma.lanserver.ImageResource
import com.soma.lanserver.ForestBackground
import com.soma.lanserver.LanBrowserServer
import com.soma.lanserver.LanServerConfig
import com.soma.lanserver.LanServerEndpoint
import com.soma.lanserver.LanServerState
import com.soma.lanserver.LanServerStateListener
import com.soma.lanserver.LanServerStopReason
import com.soma.lanserver.PageRequest
import com.soma.lanserver.PagedResult
import com.soma.lanserver.ExportBundle
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
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

enum class BrowserViewLogFilter {
    MEALS,
    RECIPES,
    WORKOUTS,
    ARCHIVED,
}

enum class BrowserViewLogKind {
    MEAL,
    RECIPE,
    WORKOUT,
}

data class BrowserViewFoodItem(
    val name: String,
    val quantity: String?,
    val nutrition: String?,
    val provenance: String?,
)

data class BrowserViewWorkoutExercise(
    val name: String,
    val machine: String?,
    val sets: List<String>,
)

data class BrowserViewLog(
    val id: String,
    val kind: BrowserViewLogKind,
    val title: String,
    val note: String,
    val occurredAt: Instant,
    val occurredLabel: String,
    val sourceDate: LocalDate?,
    val foods: List<BrowserViewFoodItem>,
    val exercises: List<BrowserViewWorkoutExercise>,
    val revisionCount: Long,
    val archived: Boolean,
)

private data class BrowserLogCopy(
    val piece: String,
    val serving: String,
    val packageLabel: String,
    val officialAverage: String,
    val estimate: String,
    val unquantified: String,
    val user: String,
    val aiEstimate: String,
    val repetitions: String,
    val seconds: String,
) {
    companion object {
        fun forLanguage(languageTag: String): BrowserLogCopy =
            when (languageTag.substringBefore('-').lowercase()) {
                "lv" -> BrowserLogCopy(
                    "gab.", "porcija", "Iepakojuma etiķete", "Oficiālais vidējais",
                    "Aplēse", "Bez daudzuma", "Lietotājs", "MI aplēse", "atk.", "s",
                )
                "et" -> BrowserLogCopy(
                    "tk", "portsjon", "Pakendi märgistus", "Ametlik keskmine",
                    "Hinnang", "Kogus määramata", "Kasutaja", "AI hinnang", "kordust", "s",
                )
                "lt" -> BrowserLogCopy(
                    "vnt.", "porcija", "Pakuotės etiketė", "Oficialus vidurkis",
                    "Apytikslė", "Kiekis nenurodytas", "Naudotojas", "DI įvertis", "kart.", "s",
                )
                "fi" -> BrowserLogCopy(
                    "kpl", "annos", "Pakkausmerkintä", "Virallinen keskiarvo",
                    "Arvio", "Määrää ei ilmoitettu", "Käyttäjä", "Tekoälyarvio", "toistoa", "s",
                )
                "sv" -> BrowserLogCopy(
                    "st", "portion", "Förpackningsetikett", "Officiellt genomsnitt",
                    "Uppskattning", "Okvantifierad", "Användare", "AI-uppskattning", "reps", "s",
                )
                "de" -> BrowserLogCopy(
                    "Stk.", "Portion", "Packungsangabe", "Offizieller Durchschnitt",
                    "Schätzung", "Ohne Mengenangabe", "Benutzer", "KI-Schätzung", "Wdh.", "s",
                )
                "sk" -> BrowserLogCopy(
                    "ks", "porcia", "Údaj z obalu", "Oficiálny priemer",
                    "Odhad", "Bez množstva", "Používateľ", "Odhad AI", "opak.", "s",
                )
                else -> BrowserLogCopy(
                    "piece", "serving", "Package label", "Official average",
                    "Estimate", "Unquantified", "User", "AI estimate", "reps", "sec",
                )
            }
    }
}

enum class BrowserViewInsightKind {
    TAG,
    DATE,
    ENTRY,
}

data class BrowserViewInsight(
    val kind: BrowserViewInsightKind,
    val label: String,
    val occurrenceCount: Int,
)

data class BrowserViewInsights(
    val annotatedEntryCount: Int,
    val manualLayerCount: Int,
    val aiLayerCount: Int,
    val localLayerCount: Int,
    val tagOccurrenceCount: Int,
    val linkCount: Int,
    val connectionCount: Int,
    val connections: BrowserViewPage<BrowserViewInsight>,
)

enum class BrowserViewGraphNodeKind {
    TAG,
    DATE,
    ENTRY,
}

data class BrowserViewGraphEdge(
    val sourceLabel: String,
    val sourceDate: LocalDate,
    val targetLabel: String,
    val targetKind: BrowserViewGraphNodeKind,
    val targetDate: LocalDate?,
    val relation: String?,
    val metadataSource: MetadataSource,
)

data class BrowserViewConnectionGraph(
    val edgeCount: Int,
    val edges: BrowserViewPage<BrowserViewGraphEdge>,
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

    suspend fun listLogs(
        filter: BrowserViewLogFilter,
        request: BrowserViewPageRequest,
    ): BrowserViewPage<BrowserViewLog> = BrowserViewPage(emptyList(), false)

    suspend fun metadataInsights(request: BrowserViewPageRequest): BrowserViewInsights = BrowserViewInsights(
        annotatedEntryCount = 0,
        manualLayerCount = 0,
        aiLayerCount = 0,
        localLayerCount = 0,
        tagOccurrenceCount = 0,
        linkCount = 0,
        connectionCount = 0,
        connections = BrowserViewPage(emptyList(), false),
    )

    suspend fun connectionGraph(request: BrowserViewPageRequest): BrowserViewConnectionGraph =
        BrowserViewConnectionGraph(0, BrowserViewPage(emptyList(), false))

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
    private val metadata: EntryMetadataRepository? = null,
    private val trackingLogs: TrackingLogRepository? = null,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zoneId) },
    languageTag: String = "en",
) : BrowserViewDataSource {
    private val logCopy = BrowserLogCopy.forLanguage(languageTag)

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

    override suspend fun listLogs(
        filter: BrowserViewLogFilter,
        request: BrowserViewPageRequest,
    ): BrowserViewPage<BrowserViewLog> {
        val repository = trackingLogs ?: return BrowserViewPage(emptyList(), false)
        val kind = when (filter) {
            BrowserViewLogFilter.MEALS -> LogKind.MEAL
            BrowserViewLogFilter.RECIPES -> LogKind.RECIPE
            BrowserViewLogFilter.WORKOUTS -> LogKind.WORKOUT
            BrowserViewLogFilter.ARCHIVED -> null
        }
        val records = repository.listLogs(
            kind = kind,
            archived = filter == BrowserViewLogFilter.ARCHIVED,
            limit = request.limit + 1,
            offset = request.offset,
        )
        return records.toPage(request.limit) { record -> record.toBrowserViewLog() }
    }

    override suspend fun metadataInsights(request: BrowserViewPageRequest): BrowserViewInsights {
        val layers = metadata?.listAllVisible().orEmpty()
        val entryTargets = layers.asSequence()
            .flatMap { it.links.asSequence() }
            .filter { it.kind == EntryLinkKind.ENTRY }
            .map { it.target }
            .distinct()
            .toList()
        val targetDates = buildMap {
            entryTargets.forEach { target -> notes.getEntry(target)?.let { put(target, it.noteDate) } }
        }
        val visibleLinks = layers.asSequence()
            .flatMap { it.links.asSequence() }
            .filter { it.kind != EntryLinkKind.ENTRY || it.target in targetDates }
            .toList()
        val counts = mutableMapOf<Pair<BrowserViewInsightKind, String>, Int>()
        fun count(kind: BrowserViewInsightKind, label: String) {
            if (label.isNotBlank()) counts[kind to label] = (counts[kind to label] ?: 0) + 1
        }
        layers.forEach { layer ->
            layer.tags.forEach { count(BrowserViewInsightKind.TAG, "#$it") }
        }
        visibleLinks.forEach { link ->
            when (link.kind) {
                EntryLinkKind.TAG -> count(BrowserViewInsightKind.TAG, "#${link.target}")
                EntryLinkKind.DATE -> count(BrowserViewInsightKind.DATE, link.target)
                EntryLinkKind.ENTRY -> {
                    val relation = link.relation?.replace('-', ' ')?.replace('_', ' ')
                    val date = targetDates.getValue(link.target).toString()
                    count(BrowserViewInsightKind.ENTRY, listOfNotNull(date, relation).joinToString(" · "))
                }
            }
        }
        val allConnections = counts.map { (key, count) ->
            BrowserViewInsight(key.first, key.second, count)
        }.sortedWith(
            compareByDescending<BrowserViewInsight>(BrowserViewInsight::occurrenceCount)
                .thenBy { it.kind.ordinal }
                .thenBy(BrowserViewInsight::label),
        )
        return BrowserViewInsights(
            annotatedEntryCount = layers.mapTo(hashSetOf()) { it.entryId }.size,
            manualLayerCount = layers.count { it.source == MetadataSource.MANUAL },
            aiLayerCount = layers.count { it.source == MetadataSource.AI },
            localLayerCount = layers.count { it.source == MetadataSource.LOCAL },
            tagOccurrenceCount = layers.sumOf { it.tags.size },
            linkCount = visibleLinks.size,
            connectionCount = allConnections.size,
            connections = BrowserViewPage(
                items = allConnections.drop(request.offset).take(request.limit),
                hasMore = allConnections.size > request.offset + request.limit,
            ),
        )
    }

    override suspend fun connectionGraph(request: BrowserViewPageRequest): BrowserViewConnectionGraph {
        val layers = metadata?.listAllVisible().orEmpty()
        val entryTargets = layers.asSequence()
            .flatMap { it.links.asSequence() }
            .filter { it.kind == EntryLinkKind.ENTRY }
            .map { it.target }
            .distinct()
            .toList()
        val visibleTargets = buildMap {
            entryTargets.forEach { entryId -> notes.getEntry(entryId)?.let { put(entryId, it) } }
        }
        val candidates = linkedMapOf<GraphEdgeKey, GraphEdgeSeed>()
        fun addCandidate(
            layerSource: MetadataSource,
            sourceEntryId: String,
            targetIdentity: String,
            targetLabel: String,
            targetKind: BrowserViewGraphNodeKind,
            targetDate: LocalDate? = null,
            relation: String? = null,
        ) {
            val normalizedRelation = relation?.replace('-', ' ')?.replace('_', ' ')
            val key = GraphEdgeKey(sourceEntryId, targetKind, targetIdentity, normalizedRelation)
            val current = candidates[key]
            if (current != null && current.metadataSource == MetadataSource.MANUAL) return
            candidates[key] = GraphEdgeSeed(
                sourceEntryId = sourceEntryId,
                targetLabel = targetLabel.toGraphLabel(GRAPH_LABEL_CHARACTERS),
                targetKind = targetKind,
                targetDate = targetDate,
                relation = normalizedRelation?.toGraphLabel(GRAPH_RELATION_CHARACTERS),
                metadataSource = layerSource,
            )
        }
        layers.forEach { layer ->
            layer.tags.forEach { tag ->
                addCandidate(
                    layerSource = layer.source,
                    sourceEntryId = layer.entryId,
                    targetIdentity = tag,
                    targetLabel = "#$tag",
                    targetKind = BrowserViewGraphNodeKind.TAG,
                )
            }
            layer.links.forEach links@ { link ->
                when (link.kind) {
                    EntryLinkKind.TAG -> addCandidate(
                        layerSource = layer.source,
                        sourceEntryId = layer.entryId,
                        targetIdentity = link.target,
                        targetLabel = "#${link.target}",
                        targetKind = BrowserViewGraphNodeKind.TAG,
                        relation = link.relation,
                    )
                    EntryLinkKind.DATE -> addCandidate(
                        layerSource = layer.source,
                        sourceEntryId = layer.entryId,
                        targetIdentity = link.target,
                        targetLabel = link.target,
                        targetKind = BrowserViewGraphNodeKind.DATE,
                        targetDate = LocalDate.parse(link.target),
                        relation = link.relation,
                    )
                    EntryLinkKind.ENTRY -> {
                        val target = visibleTargets[link.target] ?: return@links
                        addCandidate(
                            layerSource = layer.source,
                            sourceEntryId = layer.entryId,
                            targetIdentity = link.target,
                            targetLabel = target.graphLabel(),
                            targetKind = BrowserViewGraphNodeKind.ENTRY,
                            targetDate = target.noteDate,
                            relation = link.relation,
                        )
                    }
                }
            }
        }
        val allSeeds = candidates.values.toList()
        val visibleSeeds = allSeeds.drop(request.offset).take(request.limit)
        val visibleSources = buildMap {
            visibleSeeds.map(GraphEdgeSeed::sourceEntryId).distinct().forEach { entryId ->
                notes.getEntry(entryId)?.let { put(entryId, it) }
            }
        }
        val visibleEdges = visibleSeeds.mapNotNull { seed ->
            val source = visibleSources[seed.sourceEntryId] ?: return@mapNotNull null
            BrowserViewGraphEdge(
                sourceLabel = source.graphLabel(),
                sourceDate = source.noteDate,
                targetLabel = seed.targetLabel,
                targetKind = seed.targetKind,
                targetDate = seed.targetDate,
                relation = seed.relation,
                metadataSource = seed.metadataSource,
            )
        }
        return BrowserViewConnectionGraph(
            edgeCount = allSeeds.size,
            edges = BrowserViewPage(
                items = visibleEdges,
                hasMore = allSeeds.size > request.offset + request.limit,
            ),
        )
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

    private fun NoteEntry.graphLabel(): String {
        val label = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: when (kind) {
                EntryKind.TEXT -> "Text entry"
                EntryKind.VOICE -> "Voice note"
                EntryKind.IMAGE -> "Photo"
            }
        return label.toGraphLabel(GRAPH_LABEL_CHARACTERS)
    }

    private fun LogRecord.toBrowserViewLog(): BrowserViewLog = BrowserViewLog(
        id = id,
        kind = when (kind) {
            LogKind.MEAL -> BrowserViewLogKind.MEAL
            LogKind.RECIPE -> BrowserViewLogKind.RECIPE
            LogKind.WORKOUT -> BrowserViewLogKind.WORKOUT
        },
        title = title,
        note = note,
        occurredAt = occurredAt,
        occurredLabel = LOG_TIME_FORMATTER.withZone(zoneId).format(occurredAt),
        sourceDate = source?.noteDate,
        foods = foods.map { food ->
            BrowserViewFoodItem(
                name = food.name,
                quantity = food.quantity?.let { quantity ->
                    "${quantity.compact()} ${food.unit?.shortName().orEmpty()}".trim()
                },
                nutrition = food.nutrition?.let { nutrition ->
                    val energy = nutrition.energyKcal
                    val minimum = nutrition.energyKcalMin
                    val maximum = nutrition.energyKcalMax
                    when {
                        energy != null -> "${energy.compact()} kcal"
                        minimum != null && maximum != null ->
                            "${minimum.compact()}–${maximum.compact()} kcal"
                        else -> null
                    }
                },
                provenance = food.nutrition?.let { nutrition ->
                    "${nutrition.basis.displayName()} · ${nutrition.source.displayName()}"
                },
            )
        },
        exercises = exercises.map { exercise ->
            BrowserViewWorkoutExercise(
                name = exercise.name,
                machine = exercise.machine,
                sets = exercise.sets.map { set ->
                    buildList {
                        set.repetitions?.let { add("$it ${logCopy.repetitions}") }
                        set.weightKilograms?.let { add("${it.compact()} kg") }
                        set.durationSeconds?.let { add("$it ${logCopy.seconds}") }
                    }.joinToString(" · ")
                },
            )
        },
        revisionCount = revision + 1,
        archived = archivedAt != null,
    )

    private fun Double.compact(): String = BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()

    private fun FoodQuantityUnit.shortName(): String = when (this) {
        FoodQuantityUnit.GRAM -> "g"
        FoodQuantityUnit.MILLILITRE -> "ml"
        FoodQuantityUnit.PIECE -> logCopy.piece
        FoodQuantityUnit.SERVING -> logCopy.serving
    }

    private fun NutritionBasis.displayName(): String = when (this) {
        NutritionBasis.PACKAGE_LABEL -> logCopy.packageLabel
        NutritionBasis.OFFICIAL_AVERAGE -> logCopy.officialAverage
        NutritionBasis.ESTIMATED -> logCopy.estimate
        NutritionBasis.UNQUANTIFIED -> logCopy.unquantified
    }

    private fun NutritionSource.displayName(): String = when (this) {
        NutritionSource.USER -> logCopy.user
        NutritionSource.OPEN_FOOD_FACTS -> "Open Food Facts"
        NutritionSource.FINELI -> "Fineli"
        NutritionSource.CIQUAL -> "Ciqual"
        NutritionSource.AI_ESTIMATE -> logCopy.aiEstimate
    }

    private fun String.toGraphLabel(maximumCharacters: Int): String {
        val normalized = replace(WHITESPACE, " ").trim()
        return if (normalized.length <= maximumCharacters) {
            normalized
        } else {
            normalized.take(maximumCharacters - 1) + "…"
        }
    }

    private fun <T, R> List<T>.toPage(limit: Int, transform: (T) -> R): BrowserViewPage<R> =
        BrowserViewPage(
            items = take(limit).map(transform),
            hasMore = size > limit,
        )

    private companion object {
        const val PREVIEW_CHARACTERS = 160
        const val GRAPH_LABEL_CHARACTERS = 30
        const val GRAPH_RELATION_CHARACTERS = 32
        val WHITESPACE = Regex("\\s+")
        val PENDING_TRANSCRIPTION_STATES = setOf(
            EntryTranscriptionState.QUEUED,
            EntryTranscriptionState.RUNNING,
        )
        val LOG_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

private data class GraphEdgeKey(
    val sourceEntryId: String,
    val targetKind: BrowserViewGraphNodeKind,
    val targetIdentity: String,
    val relation: String?,
)

private data class GraphEdgeSeed(
    val sourceEntryId: String,
    val targetLabel: String,
    val targetKind: BrowserViewGraphNodeKind,
    val targetDate: LocalDate?,
    val relation: String?,
    val metadataSource: MetadataSource,
)

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
    private val languageTag: String = "en",
    exportProvider: (suspend () -> ByteArray?)? = null,
) : AutoCloseable {
    private val lock = Any()
    private val lanDataSource = LanDataSourceAdapter(dataSource, exportProvider)
    private val mutableState = MutableStateFlow<BrowserViewState>(BrowserViewState.Off)

    private var generation = 0
    private var startInProgress = false
    private var server: LanBrowserServer? = null
    private var currentEndpoint: BrowserViewEndpoint? = null

    val available: Boolean = true
    val state: StateFlow<BrowserViewState> = mutableState.asStateFlow()
    val endpoint: BrowserViewEndpoint?
        get() = (state.value as? BrowserViewState.Running)?.endpoint

    suspend fun start(exportEnabled: Boolean = false): BrowserViewStartResult {
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

            val forest = nextForestBackground()
            fun serverFor(port: Int) = LanBrowserServer(
                config = LanServerConfig(
                    bindAddress = address,
                    port = port,
                    lightMode = lightMode,
                    exportEnabled = exportEnabled,
                    languageTag = languageTag,
                    forestBackground = forest,
                ),
                dataSource = lanDataSource,
                stateListener = LanServerStateListener { lanState -> onLanState(token, lanState) },
            )
            var candidate = serverFor(PREFERRED_PORT)
            val lanEndpoint = try {
                candidate.start()
            } catch (_: Exception) {
                // A predictable bookmark-friendly port is preferred. Another local
                // process may own it, so capture must not be blocked by that collision.
                candidate.close()
                candidate = serverFor(0)
                try {
                    candidate.start()
                } catch (_: Exception) {
                    candidate.close()
                    return@withContext failStart(token, BrowserViewStartFailure.BIND_FAILED)
                }
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

    private companion object {
        const val PREFERRED_PORT = 8787
        val forestSequence = AtomicInteger((System.nanoTime() and Int.MAX_VALUE.toLong()).toInt())

        fun nextForestBackground(): ForestBackground {
            val all = ForestBackground.entries
            return all[Math.floorMod(forestSequence.getAndIncrement(), all.size)]
        }
    }
}

private class LanDataSourceAdapter(
    private val delegate: BrowserViewDataSource,
    private val exportProvider: (suspend () -> ByteArray?)? = null,
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

    override fun listLogs(
        filter: BrowserLogFilter,
        request: PageRequest,
    ): PagedResult<BrowserLog> {
        val browserFilter = when (filter) {
            BrowserLogFilter.MEALS -> BrowserViewLogFilter.MEALS
            BrowserLogFilter.RECIPES -> BrowserViewLogFilter.RECIPES
            BrowserLogFilter.WORKOUTS -> BrowserViewLogFilter.WORKOUTS
            BrowserLogFilter.ARCHIVED -> BrowserViewLogFilter.ARCHIVED
        }
        val page = await { delegate.listLogs(browserFilter, request.toBrowserRequest()) }
        return page.toLanResult(request) { log ->
            BrowserLog(
                id = log.id,
                kind = when (log.kind) {
                    BrowserViewLogKind.MEAL -> BrowserLogKind.MEAL
                    BrowserViewLogKind.RECIPE -> BrowserLogKind.RECIPE
                    BrowserViewLogKind.WORKOUT -> BrowserLogKind.WORKOUT
                },
                title = log.title,
                note = log.note,
                occurredAt = log.occurredAt,
                occurredLabel = log.occurredLabel,
                sourceDate = log.sourceDate,
                foods = log.foods.map { food ->
                    BrowserFoodItem(food.name, food.quantity, food.nutrition, food.provenance)
                },
                exercises = log.exercises.map { exercise ->
                    BrowserWorkoutExercise(exercise.name, exercise.machine, exercise.sets)
                },
                revisionCount = log.revisionCount,
                archived = log.archived,
            )
        }
    }

    override fun metadataInsights(request: PageRequest): BrowserInsights {
        val insights = await { delegate.metadataInsights(request.toBrowserRequest()) }
        return BrowserInsights(
            annotatedEntryCount = insights.annotatedEntryCount,
            manualLayerCount = insights.manualLayerCount,
            aiLayerCount = insights.aiLayerCount,
            localLayerCount = insights.localLayerCount,
            tagOccurrenceCount = insights.tagOccurrenceCount,
            linkCount = insights.linkCount,
            connections = PagedResult(
                items = insights.connections.items.take(request.limit).map { item ->
                    BrowserInsight(
                        kind = when (item.kind) {
                            BrowserViewInsightKind.TAG -> BrowserInsightKind.TAG
                            BrowserViewInsightKind.DATE -> BrowserInsightKind.DATE
                            BrowserViewInsightKind.ENTRY -> BrowserInsightKind.ENTRY
                        },
                        label = item.label,
                        occurrenceCount = item.occurrenceCount,
                    )
                },
                totalCount = insights.connectionCount,
            ),
        )
    }

    override fun connectionGraph(request: PageRequest): PagedResult<BrowserGraphEdge> {
        val graph = await { delegate.connectionGraph(request.toBrowserRequest()) }
        return PagedResult(
            items = graph.edges.items.take(request.limit).map { edge ->
                BrowserGraphEdge(
                    sourceLabel = edge.sourceLabel,
                    sourceDate = edge.sourceDate,
                    targetLabel = edge.targetLabel,
                    targetKind = when (edge.targetKind) {
                        BrowserViewGraphNodeKind.TAG -> BrowserGraphNodeKind.TAG
                        BrowserViewGraphNodeKind.DATE -> BrowserGraphNodeKind.DATE
                        BrowserViewGraphNodeKind.ENTRY -> BrowserGraphNodeKind.ENTRY
                    },
                    targetDate = edge.targetDate,
                    relation = edge.relation,
                    metadataSource = when (edge.metadataSource) {
                        MetadataSource.MANUAL -> BrowserMetadataSource.MANUAL
                        MetadataSource.AI -> BrowserMetadataSource.AI
                        MetadataSource.LOCAL -> BrowserMetadataSource.LOCAL
                    },
                )
            },
            totalCount = graph.edgeCount,
        )
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

    override fun exportBundle(): ExportBundle? {
        val provider = exportProvider ?: return null
        val bytes = await { provider() } ?: return null
        return ExportBundle("soma-vault.zip", bytes)
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
