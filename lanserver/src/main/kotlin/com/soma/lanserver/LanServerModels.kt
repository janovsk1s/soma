package com.soma.lanserver

import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * True for addresses in the private LAN ranges Browser view accepts: IPv4
 * site-local (RFC 1918), deprecated IPv6 site-local (fec0::/10), and IPv6
 * unique-local (fc00::/7, RFC 4193). Wi-Fi routers commonly assign fd00::/8
 * unique-local addresses, which [InetAddress.isSiteLocalAddress] alone does
 * not match, so an IPv6-only LAN would otherwise be rejected.
 */
fun isPrivateLanAddress(address: InetAddress): Boolean =
    address.isSiteLocalAddress ||
        (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)

/**
 * Configuration for the deliberately short-lived, read-only LAN server.
 *
 * [bindAddress] must be the concrete Wi-Fi address selected by the caller. The
 * server intentionally refuses wildcard, loopback, link-local, and public
 * addresses; it never chooses an interface on the caller's behalf.
 */
data class LanServerConfig(
    val bindAddress: InetAddress,
    val port: Int = 0,
    val idleTimeout: Duration = Duration.ofMinutes(15),
    val requestReadTimeout: Duration = Duration.ofSeconds(10),
    val lightMode: Boolean = false,
    /** Enables the explicit, off-by-default data-export routes for this session. */
    val exportEnabled: Boolean = false,
    /** BCP-47 application language used for the sensitive export confirmation. */
    val languageTag: String = "en",
    /** One bundled monochrome landscape, fixed for the lifetime of this server session. */
    val forestBackground: ForestBackground = ForestBackground.LATVIA,
) {
    init {
        require(!bindAddress.isAnyLocalAddress) { "A concrete LAN address is required" }
        require(!bindAddress.isLoopbackAddress) { "Loopback addresses are not allowed" }
        require(isPrivateLanAddress(bindAddress)) { "Only private LAN addresses are allowed" }
        require(port in 0..65_535) { "Port must be between 0 and 65535" }
        require(!idleTimeout.isZero && !idleTimeout.isNegative) {
            "Idle timeout must be positive"
        }
        require(idleTimeout <= MAX_IDLE_TIMEOUT) {
            "Idle timeout must not exceed 15 minutes"
        }
        require(!requestReadTimeout.isZero && !requestReadTimeout.isNegative) {
            "Request read timeout must be positive"
        }
        require(requestReadTimeout <= MAX_REQUEST_READ_TIMEOUT) {
            "Request read timeout must not exceed 30 seconds"
        }
    }

    private companion object {
        val MAX_IDLE_TIMEOUT: Duration = Duration.ofMinutes(15)
        val MAX_REQUEST_READ_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

/** Landscapes correspond to Soma's eight application languages and never require a web request. */
enum class ForestBackground(val resourceName: String, val countryName: String) {
    ENGLAND("en.webp", "England"),
    LATVIA("lv.webp", "Latvia"),
    ESTONIA("et.webp", "Estonia"),
    LITHUANIA("lt.webp", "Lithuania"),
    FINLAND("fi.webp", "Finland"),
    SWEDEN("sv.webp", "Sweden"),
    GERMANY("de.webp", "Germany"),
    SLOVAKIA("sk.webp", "Slovakia"),
}

/** The address and single-use access code shown on Soma's Browser view screen. */
data class LanServerEndpoint(
    val address: InetAddress,
    val port: Int,
    val accessCode: String,
) {
    val url: String
        get() {
            val host = address.hostAddress.substringBefore('%')
            val urlHost = if (address is Inet6Address) "[$host]" else host
            return "http://$urlHost:$port"
        }
}

enum class LanServerStopReason {
    REQUESTED,
    IDLE_TIMEOUT,
    TOO_MANY_WRONG_CODES,
    SERVER_ERROR,
}

sealed interface LanServerState {
    data object NotStarted : LanServerState

    data class AwaitingAuthentication(
        val endpoint: LanServerEndpoint,
        val wrongCodeAttempts: Int,
    ) : LanServerState

    data class Authenticated(val url: String) : LanServerState

    data class Stopped(val reason: LanServerStopReason) : LanServerState
}

fun interface LanServerStateListener {
    fun onStateChanged(state: LanServerState)
}

/** Injectable to keep authentication tests deterministic. Production uses SecureRandom. */
fun interface AccessCodeGenerator {
    fun nextCode(): String
}

data class PageRequest(val offset: Int, val limit: Int) {
    init {
        require(offset >= 0) { "Offset must not be negative" }
        require(limit > 0) { "Limit must be positive" }
    }
}

data class PagedResult<T>(
    val items: List<T>,
    val totalCount: Int,
) {
    init {
        require(totalCount >= 0) { "Total count must not be negative" }
    }
}

data class BrowserDay(
    val date: LocalDate,
    val entryCount: Int,
    val preview: String = "",
)

enum class BrowserEntryKind {
    TEXT,
    VOICE,
    IMAGE,
}

data class BrowserEntry(
    val id: String,
    val text: String,
    val kind: BrowserEntryKind,
    val audioId: String? = null,
    val imageId: String? = null,
    val transcriptionPending: Boolean = false,
    val markedForReturn: Boolean = false,
    val history: List<BrowserEntryVersion> = emptyList(),
)

data class BrowserEntryVersion(
    val number: Int,
    val text: String,
    val becameCurrentAt: Instant,
    val isCurrent: Boolean,
) {
    init {
        require(number > 0) { "Entry history version numbers must be positive" }
    }
}

enum class BrowserTodoState {
    OPEN,
    DONE,
    ARCHIVED,
}

/** OPEN is the primary list; COMPLETED contains done and silently archived items. */
enum class BrowserTodoFilter {
    OPEN,
    COMPLETED,
}

data class BrowserTodo(
    val id: String,
    val text: String,
    val createdOn: LocalDate,
    val state: BrowserTodoState,
    val sourceDate: LocalDate? = null,
    val sourceEntryId: String? = null,
)

enum class BrowserLogFilter {
    MEALS,
    RECIPES,
    WORKOUTS,
    RECEIPTS,
    ARCHIVED,
}

enum class BrowserLogKind {
    MEAL,
    RECIPE,
    WORKOUT,
    RECEIPT,
}

data class BrowserFoodItem(
    val name: String,
    val quantity: String? = null,
    val nutrition: String? = null,
    val provenance: String? = null,
)

data class BrowserWorkoutExercise(
    val name: String,
    val machine: String? = null,
    val sets: List<String> = emptyList(),
)

data class BrowserReceiptItem(
    val name: String,
    val quantity: String? = null,
    val total: String? = null,
    val category: String? = null,
)

data class BrowserReceipt(
    val merchant: String? = null,
    val subtotal: String? = null,
    val tax: String? = null,
    val total: String? = null,
    val items: List<BrowserReceiptItem> = emptyList(),
)

data class BrowserLog(
    val id: String,
    val kind: BrowserLogKind,
    val title: String,
    val note: String,
    val occurredAt: Instant,
    val occurredLabel: String,
    val sourceDate: LocalDate? = null,
    val foods: List<BrowserFoodItem> = emptyList(),
    val exercises: List<BrowserWorkoutExercise> = emptyList(),
    val receipt: BrowserReceipt? = null,
    val revisionCount: Long = 0,
    val archived: Boolean = false,
)

enum class BrowserInsightKind {
    TAG,
    DATE,
    ENTRY,
}

data class BrowserInsight(
    val kind: BrowserInsightKind,
    val label: String,
    val occurrenceCount: Int,
) {
    init {
        require(label.isNotBlank()) { "Insight label must not be blank" }
        require(occurrenceCount > 0) { "Insight count must be positive" }
    }
}

data class BrowserInsights(
    val annotatedEntryCount: Int,
    val manualLayerCount: Int,
    val aiLayerCount: Int,
    val localLayerCount: Int,
    val tagOccurrenceCount: Int,
    val linkCount: Int,
    val connections: PagedResult<BrowserInsight>,
) {
    init {
        require(
            listOf(
                annotatedEntryCount,
                manualLayerCount,
                aiLayerCount,
                localLayerCount,
                tagOccurrenceCount,
                linkCount,
            ).all { it >= 0 },
        ) { "Insight totals must not be negative" }
    }
}

enum class BrowserGraphNodeKind {
    TAG,
    DATE,
    ENTRY,
}

enum class BrowserMetadataSource {
    MANUAL,
    AI,
    LOCAL,
}

data class BrowserGraphEdge(
    val sourceLabel: String,
    val sourceDate: LocalDate,
    val targetLabel: String,
    val targetKind: BrowserGraphNodeKind,
    val targetDate: LocalDate? = null,
    val relation: String? = null,
    val metadataSource: BrowserMetadataSource,
) {
    init {
        require(sourceLabel.isNotBlank() && sourceLabel.length <= MAX_LABEL_LENGTH) {
            "Graph source label is invalid"
        }
        require(targetLabel.isNotBlank() && targetLabel.length <= MAX_LABEL_LENGTH) {
            "Graph target label is invalid"
        }
        require(relation == null || relation.length <= MAX_RELATION_LENGTH) {
            "Graph relation is too long"
        }
        require(targetKind != BrowserGraphNodeKind.ENTRY || targetDate != null) {
            "Entry graph targets require a visible date"
        }
    }

    private companion object {
        const val MAX_LABEL_LENGTH = 120
        const val MAX_RELATION_LENGTH = 80
    }
}

/**
 * A fresh stream must be returned by [openStream] for every request. This lets
 * encrypted storage decrypt per request without ever caching plaintext on disk.
 */
class AudioResource(
    val contentType: String,
    val contentLength: Long,
    val openStream: () -> InputStream,
) {
    init {
        require(contentLength >= 0L) { "Audio length must not be negative" }
        require(MEDIA_TYPE.matches(contentType)) { "Invalid audio content type" }
    }

    private companion object {
        val MEDIA_TYPE = Regex("audio/[A-Za-z0-9!#$&^_.+\\-]+")
    }
}

class ImageResource(
    val contentType: String,
    val contentLength: Long,
    val openStream: () -> InputStream,
) {
    init {
        require(contentLength > 0L) { "Image length must be positive" }
        require(contentType == "image/jpeg") { "Only portable JPEG images are served" }
    }
}

/** Data exposed by the read-only browser flavor. Implementations may decrypt on demand. */
interface ReadOnlySomaDataSource {
    fun listDays(request: PageRequest): PagedResult<BrowserDay>

    /** Returns null when [date] has no note. */
    fun entriesForDay(date: LocalDate, request: PageRequest): PagedResult<BrowserEntry>?

    fun listTodos(filter: BrowserTodoFilter, request: PageRequest): PagedResult<BrowserTodo>

    fun listLogs(filter: BrowserLogFilter, request: PageRequest): PagedResult<BrowserLog> =
        PagedResult(emptyList(), 0)

    fun metadataInsights(request: PageRequest): BrowserInsights = BrowserInsights(
        annotatedEntryCount = 0,
        manualLayerCount = 0,
        aiLayerCount = 0,
        localLayerCount = 0,
        tagOccurrenceCount = 0,
        linkCount = 0,
        connections = PagedResult(emptyList(), 0),
    )

    fun connectionGraph(request: PageRequest): PagedResult<BrowserGraphEdge> =
        PagedResult(emptyList(), 0)

    fun openAudio(audioId: String): AudioResource?

    fun openImage(imageId: String): ImageResource? = null

    /**
     * The downloadable analysis bundle, or null when export is unavailable. Only
     * called for the export routes, which are themselves gated by [LanServerConfig.exportEnabled].
     */
    fun exportBundle(): ExportBundle? = null
}

/** A one-file, download-only export handed to the user's own AI tool. */
class ExportBundle(val fileName: String, val bytes: ByteArray)
