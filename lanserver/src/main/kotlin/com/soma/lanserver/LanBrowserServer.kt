package com.soma.lanserver

import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal inbound-only HTTP/1.1 server for Soma's temporary Browser view.
 *
 * There is intentionally no HTTP client or general-purpose server framework in
 * this module. Create a new instance for each toggle-on session and close it as
 * soon as the Browser view screen is left or the app enters the background.
 */
class LanBrowserServer(
    private val config: LanServerConfig,
    private val dataSource: ReadOnlySomaDataSource,
    private val stateListener: LanServerStateListener = LanServerStateListener {},
    private val clock: Clock = Clock.systemUTC(),
    private val accessCodeGenerator: AccessCodeGenerator = SecureSixDigitCodeGenerator,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val secureRandom = SecureRandom()

    @Volatile
    var state: LanServerState = LanServerState.NotStarted
        private set

    @Volatile
    private var running = false

    private var started = false
    private var serverSocket: ServerSocket? = null
    private var clientExecutor: ExecutorService? = null
    private var acceptThread: Thread? = null
    private var endpoint: LanServerEndpoint? = null
    private var accessCodeBytes: ByteArray? = null
    private var sessionToken: String? = null
    private var csrfToken: String? = null
    private var wrongCodeAttempts = 0
    private var lastAuthenticatedActivity: Instant = Instant.EPOCH
    private val exportInProgress = AtomicBoolean(false)
    private val forestAsset: ByteArray by lazy { ForestAssets.load(config.forestBackground) }

    /** Binds synchronously and returns the URL/code to display on the phone. */
    @Throws(IOException::class)
    fun start(): LanServerEndpoint {
        synchronized(lifecycleLock) {
            check(!started) { "A LanBrowserServer instance can only be started once" }
            started = true
        }
        var socket: ServerSocket? = null
        var executor: ExecutorService? = null
        try {
            val code = accessCodeGenerator.nextCode()
            require(ACCESS_CODE.matches(code)) { "Access codes must contain exactly six digits" }
            socket = ServerSocket()
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(config.bindAddress, config.port), ACCEPT_BACKLOG)
            socket.soTimeout = idlePollMillis()
            executor = Executors.newFixedThreadPool(
                CLIENT_THREADS,
                NamedDaemonThreadFactory("soma-lan-client"),
            )
            val thread = Thread({ acceptLoop() }, "soma-lan-accept").apply { isDaemon = true }
            val createdEndpoint: LanServerEndpoint
            synchronized(lifecycleLock) {
                running = true
                serverSocket = socket
                clientExecutor = executor
                createdEndpoint = LanServerEndpoint(config.bindAddress, socket.localPort, code)
                endpoint = createdEndpoint
                accessCodeBytes = code.toByteArray(Charsets.UTF_8)
                sessionToken = null
                wrongCodeAttempts = 0
                // Also limits an abandoned, never-authenticated listener to one idle window.
                lastAuthenticatedActivity = clock.instant()
                acceptThread = thread
                state = LanServerState.AwaitingAuthentication(createdEndpoint, wrongCodeAttempts = 0)
            }
            notifyState(state)
            thread.start()
            return createdEndpoint
        } catch (failure: Throwable) {
            runCatching { socket?.close() }
            executor?.shutdown()
            synchronized(lifecycleLock) {
                started = false
                running = false
                serverSocket = null
                clientExecutor = null
                acceptThread = null
            }
            throw failure
        }
    }

    override fun close() {
        stop(LanServerStopReason.REQUESTED)
    }

    /** Primarily useful to deterministic tests; the accept loop invokes the same policy. */
    internal fun checkIdleNow(): Boolean {
        val timedOut = synchronized(lifecycleLock) {
            if (!running) {
                false
            } else {
                val idleFor = Duration.between(lastAuthenticatedActivity, clock.instant())
                !idleFor.isNegative && idleFor >= config.idleTimeout
            }
        }
        if (timedOut) stop(LanServerStopReason.IDLE_TIMEOUT)
        return timedOut
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = serverSocket?.accept() ?: break
                if (!isPrivateLanAddress(socket.inetAddress)) {
                    runCatching { socket.close() }
                    continue
                }
                socket.soTimeout = durationToIntMillis(config.requestReadTimeout)
                try {
                    clientExecutor?.execute { handleClient(socket) } ?: socket.close()
                } catch (_: RejectedExecutionException) {
                    runCatching { socket.close() }
                }
            } catch (_: SocketTimeoutException) {
                checkIdleNow()
            } catch (_: SocketException) {
                if (running) stop(LanServerStopReason.SERVER_ERROR)
                break
            } catch (_: IOException) {
                if (running) stop(LanServerStopReason.SERVER_ERROR)
                break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            var request: HttpRequest? = null
            val response = try {
                request = HttpProtocol.readRequest(client)
                dispatch(request).response
            } catch (exception: HttpParseException) {
                errorResponse(exception.status, exception.message)
            } catch (_: SocketTimeoutException) {
                errorResponse(400, "The request timed out.")
            } catch (_: Exception) {
                errorResponse(500, "The request could not be completed.")
            }

            try {
                HttpProtocol.writeResponse(
                    output = client.getOutputStream(),
                    response = response,
                    omitBody = request?.method == "HEAD",
                )
            } catch (_: Exception) {
                // A peer can disappear at any point. No user data is logged.
            }
        }
    }

    private fun dispatch(request: HttpRequest): DispatchResult {
        if (!validHost(request)) {
            return DispatchResult(errorResponse(421, "This Host header is not accepted."))
        }
        if (request.method !in ALLOWED_METHODS) {
            return DispatchResult(
                errorResponse(405, "Browser view is read-only.", mapOf("Allow" to "GET, HEAD, POST")),
            )
        }

        // This is a bundled, non-user-specific image used by the login page too.
        // Serving it before authentication does not expose any Soma data.
        if (request.path == FOREST_ASSET_PATH) {
            return if (request.method in READ_METHODS) {
                DispatchResult(forestResponse())
            } else {
                DispatchResult(errorResponse(405, "Only reading is available."))
            }
        }

        if (request.path == "/auth") {
            if (request.method != "POST") {
                return DispatchResult(
                    errorResponse(405, "Submit the access-code form.", mapOf("Allow" to "POST")),
                )
            }
            return authenticate(request)
        }

        val authenticated = authenticateCookie(request)
        if (request.path == "/") {
            if (request.method !in READ_METHODS) {
                return DispatchResult(errorResponse(405, "Only reading is available."))
            }
            return if (authenticated) {
                markAuthenticatedActivity()
                DispatchResult(redirect("/days"))
            } else {
                DispatchResult(
                    htmlResponse(200, HtmlRenderer.login(lightMode = config.lightMode, languageTag = config.languageTag)),
                )
            }
        }

        if (!authenticated) {
            return DispatchResult(errorResponse(401, "Enter the one-time access code first."))
        }
        markAuthenticatedActivity()

        if (request.method == "POST") {
            if (!config.editEnabled) {
                return DispatchResult(errorResponse(405, "Browser view is read-only.", mapOf("Allow" to "GET, HEAD")))
            }
            return DispatchResult(writeResponse(request))
        }

        return DispatchResult(
            when {
                request.path == "/days" -> daysResponse(request)
                request.path == "/search" -> searchResponse(request)
                request.path.startsWith("/day/") -> dayResponse(request)
                request.path == "/todos" -> todosResponse(request)
                request.path == "/logs" -> logsResponse(request)
                request.path == "/insights" -> insightsResponse(request)
                request.path == "/graph" -> graphResponse(request)
                request.path.startsWith("/audio/") -> audioResponse(request)
                request.path.startsWith("/image/") -> imageResponse(request)
                request.path == "/export" -> exportPageResponse()
                request.path == "/export/vault.zip" -> exportDownloadResponse(request)
                else -> errorResponse(404, "That page does not exist.")
            },
        )
    }

    private fun authenticate(request: HttpRequest): DispatchResult {
        val form = HttpProtocol.parseForm(request)
        val submittedValues = form["code"]
        if (submittedValues == null || submittedValues.size != 1) {
            throw HttpParseException(400, "One access code is required")
        }
        val submitted = submittedValues.single().toByteArray(Charsets.UTF_8)

        var newState: LanServerState? = null
        var token: String? = null
        var stopReason: LanServerStopReason? = null
        var alreadyUsed = false
        synchronized(lifecycleLock) {
            val expected = accessCodeBytes
            if (!running || expected == null) {
                alreadyUsed = true
            } else if (MessageDigest.isEqual(expected, submitted)) {
                token = newSessionToken()
                sessionToken = token
                csrfToken = newSessionToken()
                accessCodeBytes?.fill(0)
                accessCodeBytes = null
                lastAuthenticatedActivity = clock.instant()
                newState = LanServerState.Authenticated(requireNotNull(endpoint).url)
                state = requireNotNull(newState)
            } else {
                wrongCodeAttempts++
                if (wrongCodeAttempts >= MAX_WRONG_CODES) {
                    accessCodeBytes?.fill(0)
                    accessCodeBytes = null
                    stopReason = LanServerStopReason.TOO_MANY_WRONG_CODES
                } else {
                    newState = LanServerState.AwaitingAuthentication(
                        endpoint = requireNotNull(endpoint),
                        wrongCodeAttempts = wrongCodeAttempts,
                    )
                    state = requireNotNull(newState)
                }
            }
        }
        newState?.let(::notifyState)

        if (alreadyUsed) {
            return DispatchResult(errorResponse(401, "The one-time access code has expired."))
        }
        token?.let { session ->
            return DispatchResult(
                redirect(
                    location = "/days",
                    extraHeaders = mapOf("Set-Cookie" to sessionCookie(session)),
                ),
            )
        }
        if (stopReason != null) {
            stop(stopReason)
            return DispatchResult(
                response = errorResponse(429, "Too many incorrect codes. Browser view has stopped."),
            )
        }
        return DispatchResult(
            htmlResponse(
                401,
                HtmlRenderer.login("That code did not match.", config.lightMode, config.languageTag),
            ),
        )
    }

    /**
     * Handles an authenticated write. The submitted CSRF token must match the
     * one minted with the session, so a page loaded over this LAN cannot be
     * driven by a form served from elsewhere. Writes are delegated to the data
     * source, which applies them through the encrypted, revisioned repository.
     */
    private fun writeResponse(request: HttpRequest): HttpResponse {
        val form = HttpProtocol.parseForm(request)
        if (!csrfValid(form["csrf"]?.singleOrNull())) {
            return errorResponse(403, "This form is no longer valid. Reload the page and try again.")
        }
        val result = when (request.path) {
            "/entry/new" -> {
                val date = form["date"]?.singleOrNull()?.let { value ->
                    try {
                        LocalDate.parse(value)
                    } catch (_: DateTimeParseException) {
                        null
                    }
                } ?: return errorResponse(400, "A valid day is required.")
                dataSource.addEntry(date, form["text"]?.singleOrNull().orEmpty())
            }
            "/entry/edit" -> {
                val id = form["id"]?.singleOrNull()?.takeIf(String::isNotEmpty)
                    ?: return errorResponse(400, "An entry is required.")
                dataSource.editEntry(id, form["text"]?.singleOrNull().orEmpty())
            }
            "/todo/new" -> dataSource.addTodo(form["text"]?.singleOrNull().orEmpty())
            "/todo/edit" -> {
                val id = form["id"]?.singleOrNull()?.takeIf(String::isNotEmpty)
                    ?: return errorResponse(400, "An item is required.")
                dataSource.editTodo(id, form["text"]?.singleOrNull().orEmpty())
            }
            else -> return errorResponse(404, "That action does not exist.")
        }
        return when (result) {
            is BrowserWriteResult.Success -> {
                val base = safeReturnPath(form["return"]?.singleOrNull())
                val anchor = result.anchor?.takeIf { it.all(::isAnchorChar) }
                redirect(if (anchor != null) "$base#$anchor" else base)
            }
            BrowserWriteResult.Unavailable -> errorResponse(405, "Editing is not available.")
            is BrowserWriteResult.Rejected -> errorResponse(400, result.reason)
        }
    }

    private fun isAnchorChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'

    private fun csrfValid(submitted: String?): Boolean {
        val expected = synchronized(lifecycleLock) { csrfToken } ?: return false
        if (submitted.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            submitted.toByteArray(Charsets.US_ASCII),
        )
    }

    /**
     * Only a same-origin absolute path may be a redirect target after a write.
     * Rejects protocol-relative forms — both `//host` and `/\host`, since
     * browsers treat a backslash as a slash — and any control characters, so the
     * value cannot escape the origin or inject a response header.
     */
    private fun safeReturnPath(value: String?): String {
        val target = value.orEmpty()
        val safe = target.length in 1..512 &&
            target[0] == '/' &&
            (target.length == 1 || (target[1] != '/' && target[1] != '\\')) &&
            target.none { it == '\\' || it == '\r' || it == '\n' || it.code < 0x20 }
        return if (safe) target else "/days"
    }

    private fun currentCsrf(): String? = synchronized(lifecycleLock) { csrfToken }

    private fun daysResponse(request: HttpRequest): HttpResponse {
        val page = pageNumber(request)
        val result = dataSource.listDays(pageRequest(page)).bounded()
        val edit = if (config.editEnabled) currentCsrf()?.let(::EditContext) else null
        return htmlResponse(
            200,
            HtmlRenderer.days(page, result, config.lightMode, config.languageTag, edit, dataSource.today()),
        )
    }

    private fun searchResponse(request: HttpRequest): HttpResponse {
        val values = request.query["q"]
        if (values != null && values.size != 1) throw HttpParseException(400, "One query is accepted")
        val query = values?.single()?.take(MAX_SEARCH_QUERY_CHARS)?.trim().orEmpty()
        val hits = if (query.isEmpty()) emptyList() else dataSource.search(query, SEARCH_RESULT_LIMIT)
        return htmlResponse(
            200,
            HtmlRenderer.search(query, hits, config.lightMode, config.languageTag),
        )
    }

    private fun dayResponse(request: HttpRequest): HttpResponse {
        val encodedId = request.path.removePrefix("/day/")
        if (encodedId.isBlank() || '/' in encodedId) return errorResponse(404, "That day does not exist.")
        val date = try {
            LocalDate.parse(encodedId)
        } catch (_: DateTimeParseException) {
            return errorResponse(404, "That day does not exist.")
        }
        val page = pageNumber(request)
        val result = dataSource.entriesForDay(date, pageRequest(page))?.bounded()
            ?: return errorResponse(404, "That day does not exist.")
        val edit = if (config.editEnabled) currentCsrf()?.let(::EditContext) else null
        return htmlResponse(200, HtmlRenderer.day(date, page, result, config.lightMode, config.languageTag, edit))
    }

    private fun todosResponse(request: HttpRequest): HttpResponse {
        val stateValues = request.query["state"]
        if (stateValues != null && stateValues.size != 1) {
            throw HttpParseException(400, "Only one todo state is accepted")
        }
        val filter = when (stateValues?.single()?.lowercase()) {
            null, "", "open" -> BrowserTodoFilter.OPEN
            "completed" -> BrowserTodoFilter.COMPLETED
            else -> throw HttpParseException(400, "Unknown todo state")
        }
        val page = pageNumber(request)
        val result = dataSource.listTodos(filter, pageRequest(page)).bounded()
        val edit = if (config.editEnabled) currentCsrf()?.let(::EditContext) else null
        return htmlResponse(200, HtmlRenderer.todos(filter, page, result, config.lightMode, config.languageTag, edit))
    }

    private fun logsResponse(request: HttpRequest): HttpResponse {
        val kindValues = request.query["kind"]
        if (kindValues != null && kindValues.size != 1) throw HttpParseException(400, "One log kind is required")
        val filter = when (kindValues?.singleOrNull()?.lowercase()) {
            null, "meal" -> BrowserLogFilter.MEALS
            "recipe" -> BrowserLogFilter.RECIPES
            "workout" -> BrowserLogFilter.WORKOUTS
            "receipt" -> BrowserLogFilter.RECEIPTS
            "archived" -> BrowserLogFilter.ARCHIVED
            else -> throw HttpParseException(400, "Unknown log kind")
        }
        val page = pageNumber(request)
        val result = dataSource.listLogs(filter, pageRequest(page)).bounded()
        return htmlResponse(
            200,
            HtmlRenderer.logs(filter, page, result, config.lightMode, config.languageTag),
        )
    }

    private fun insightsResponse(request: HttpRequest): HttpResponse {
        val page = pageNumber(request)
        val insights = dataSource.metadataInsights(pageRequest(page))
        val bounded = insights.copy(connections = insights.connections.bounded())
        return htmlResponse(
            200,
            HtmlRenderer.insights(page, bounded, config.lightMode, config.exportEnabled, config.languageTag),
        )
    }

    private fun exportPageResponse(): HttpResponse {
        if (!config.exportEnabled) return errorResponse(404, "That page does not exist.")
        return htmlResponse(200, HtmlRenderer.export(config.lightMode, config.languageTag))
    }

    private fun exportDownloadResponse(request: HttpRequest): HttpResponse {
        if (!config.exportEnabled) return errorResponse(404, "That page does not exist.")
        if (request.method != "GET") {
            return errorResponse(405, "Export requires an explicit download.", mapOf("Allow" to "GET"))
        }
        if (!exportInProgress.compareAndSet(false, true)) {
            return errorResponse(429, "An export is already in progress.")
        }
        return try {
            val bundle = dataSource.exportBundle()
                ?: return errorResponse(404, "Export is not available.").also { exportInProgress.set(false) }
            secureResponse(
                status = 200,
                headers = linkedMapOf(
                    "Content-Type" to "application/zip",
                    "Content-Disposition" to "attachment; filename=\"${bundle.fileName}\"",
                ),
                body = ResponseBody.Bytes(bundle.bytes) {
                    bundle.bytes.fill(0)
                    exportInProgress.set(false)
                },
            )
        } catch (error: Throwable) {
            exportInProgress.set(false)
            throw error
        }
    }

    private fun graphResponse(request: HttpRequest): HttpResponse {
        val page = pageNumber(request)
        val graph = dataSource.connectionGraph(pageRequest(page)).bounded()
        return htmlResponse(200, HtmlRenderer.graph(page, graph, config.lightMode, config.languageTag))
    }

    private fun audioResponse(request: HttpRequest): HttpResponse {
        val audioId = request.path.removePrefix("/audio/")
        if (audioId.isBlank() || '/' in audioId) return errorResponse(404, "That recording does not exist.")
        val resource = dataSource.openAudio(audioId)
            ?: return errorResponse(404, "That recording does not exist.")
        val range = parseRange(request.singleHeader("range"), resource.contentLength)
        if (range == RequestedRange.Unsatisfiable) {
            return errorResponse(
                status = 416,
                message = "That audio range is not available.",
                extraHeaders = mapOf("Content-Range" to "bytes */${resource.contentLength}"),
            )
        }
        val commonHeaders = linkedMapOf(
            "Content-Type" to resource.contentType,
            "Accept-Ranges" to "bytes",
        )
        return when (range) {
            RequestedRange.Full -> secureResponse(
                status = 200,
                headers = commonHeaders,
                body = ResponseBody.Stream(resource.contentLength, 0, resource.openStream),
            )
            RequestedRange.Unsatisfiable -> error("Handled above")
            is RequestedRange.Partial -> {
                commonHeaders["Content-Range"] =
                    "bytes ${range.start}-${range.endInclusive}/${resource.contentLength}"
                secureResponse(
                    status = 206,
                    headers = commonHeaders,
                    body = ResponseBody.Stream(
                        length = range.endInclusive - range.start + 1,
                        offset = range.start,
                        source = resource.openStream,
                    ),
                )
            }
        }
    }

    private fun imageResponse(request: HttpRequest): HttpResponse {
        val imageId = request.path.removePrefix("/image/")
        if (imageId.isBlank() || '/' in imageId) return errorResponse(404, "That image does not exist.")
        val resource = dataSource.openImage(imageId)
            ?: return errorResponse(404, "That image does not exist.")
        return secureResponse(
            status = 200,
            headers = mapOf("Content-Type" to resource.contentType),
            body = ResponseBody.Stream(resource.contentLength, 0, resource.openStream),
        )
    }

    private fun forestResponse(): HttpResponse = secureResponse(
        status = 200,
        headers = mapOf("Content-Type" to "image/webp"),
        body = ResponseBody.Bytes(forestAsset),
    )

    private fun pageNumber(request: HttpRequest): Int {
        val values = request.query["page"] ?: return 1
        if (values.size != 1) throw HttpParseException(400, "Only one page is accepted")
        val page = values.single().toIntOrNull()
            ?: throw HttpParseException(400, "Page must be a positive number")
        if (page < 1 || page > MAX_PAGE_NUMBER) {
            throw HttpParseException(400, "Page is outside the supported range")
        }
        return page
    }

    private fun pageRequest(page: Int): PageRequest = PageRequest(
        offset = (page - 1) * PAGE_SIZE,
        limit = PAGE_SIZE,
    )

    private fun <T> PagedResult<T>.bounded(): PagedResult<T> = PagedResult(
        items = items.take(PAGE_SIZE),
        totalCount = totalCount,
    )

    private fun validHost(request: HttpRequest): Boolean {
        val host = try {
            request.singleHeader("host")
        } catch (_: HttpParseException) {
            return false
        } ?: return false
        val activeEndpoint = synchronized(lifecycleLock) { endpoint } ?: return false
        val address = activeEndpoint.address.hostAddress.substringBefore('%')
        val expected = if (activeEndpoint.address is Inet6Address) {
            "[$address]:${activeEndpoint.port}"
        } else {
            "$address:${activeEndpoint.port}"
        }
        return host.equals(expected, ignoreCase = true)
    }

    private fun authenticateCookie(request: HttpRequest): Boolean {
        val expected = synchronized(lifecycleLock) { sessionToken } ?: return false
        val candidates = request.headers["cookie"]
            .orEmpty()
            .flatMap { header -> header.split(';') }
            .mapNotNull { cookie ->
                val equals = cookie.indexOf('=')
                if (equals <= 0 || cookie.substring(0, equals).trim() != SESSION_COOKIE) {
                    null
                } else {
                    cookie.substring(equals + 1).trim()
                }
            }
        if (candidates.size != 1) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            candidates.single().toByteArray(Charsets.US_ASCII),
        )
    }

    private fun markAuthenticatedActivity() {
        synchronized(lifecycleLock) {
            if (running && sessionToken != null) lastAuthenticatedActivity = clock.instant()
        }
    }

    private fun stop(reason: LanServerStopReason) {
        val socket: ServerSocket?
        val executor: ExecutorService?
        val stoppedState: LanServerState.Stopped
        synchronized(lifecycleLock) {
            if (!running) return
            running = false
            accessCodeBytes?.fill(0)
            accessCodeBytes = null
            sessionToken = null
            csrfToken = null
            socket = serverSocket
            serverSocket = null
            executor = clientExecutor
            clientExecutor = null
            stoppedState = LanServerState.Stopped(reason)
            state = stoppedState
        }
        runCatching { socket?.close() }
        executor?.shutdown()
        notifyState(stoppedState)
    }

    private fun notifyState(newState: LanServerState) {
        runCatching { stateListener.onStateChanged(newState) }
    }

    private fun newSessionToken(): String {
        val bytes = ByteArray(SESSION_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sessionCookie(token: String): String {
        val maxAge = config.idleTimeout.seconds.coerceAtLeast(1)
        // `Secure` would make browsers discard this cookie over the intentionally
        // plain-HTTP LAN URL. The random token, HttpOnly, strict same-site scope,
        // one-session lifetime, and read-only routes are the compensating controls.
        return "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=$maxAge"
    }

    private fun parseRange(header: String?, length: Long): RequestedRange {
        if (header == null) return RequestedRange.Full
        if (!header.startsWith("bytes=") || ',' in header || length <= 0) {
            return RequestedRange.Unsatisfiable
        }
        val value = header.removePrefix("bytes=").trim()
        val dash = value.indexOf('-')
        if (dash < 0 || value.indexOf('-', dash + 1) >= 0) return RequestedRange.Unsatisfiable
        val startText = value.substring(0, dash).trim()
        val endText = value.substring(dash + 1).trim()
        if (startText.isEmpty()) {
            val suffixLength = endText.toLongOrNull() ?: return RequestedRange.Unsatisfiable
            if (suffixLength <= 0) return RequestedRange.Unsatisfiable
            val actualLength = minOf(suffixLength, length)
            return RequestedRange.Partial(length - actualLength, length - 1)
        }
        val start = startText.toLongOrNull() ?: return RequestedRange.Unsatisfiable
        if (start < 0 || start >= length) return RequestedRange.Unsatisfiable
        val requestedEnd = if (endText.isEmpty()) {
            length - 1
        } else {
            endText.toLongOrNull() ?: return RequestedRange.Unsatisfiable
        }
        if (requestedEnd < start) return RequestedRange.Unsatisfiable
        return RequestedRange.Partial(start, minOf(requestedEnd, length - 1))
    }

    private fun htmlResponse(status: Int, html: String): HttpResponse = secureResponse(
        status = status,
        headers = mapOf("Content-Type" to "text/html; charset=utf-8"),
        body = ResponseBody.text(html),
    )

    private fun redirect(location: String, extraHeaders: Map<String, String> = emptyMap()): HttpResponse {
        val headers = linkedMapOf("Location" to location)
        headers.putAll(extraHeaders)
        return secureResponse(303, headers, ResponseBody.empty())
    }

    private fun errorResponse(
        status: Int,
        // Kept for call-site readability; the rendered page is localized by
        // status, so the specific English message is not shown.
        @Suppress("UNUSED_PARAMETER") message: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val headers = linkedMapOf("Content-Type" to "text/html; charset=utf-8")
        headers.putAll(extraHeaders)
        return secureResponse(
            status,
            headers,
            ResponseBody.text(HtmlRenderer.error(status, config.lightMode, config.languageTag)),
        )
    }

    private fun secureResponse(
        status: Int,
        headers: Map<String, String>,
        body: ResponseBody,
    ): HttpResponse {
        val secured = linkedMapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to CSP,
            "X-Content-Type-Options" to "nosniff",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
            "Permissions-Policy" to "camera=(), microphone=(), geolocation=()",
            "Cross-Origin-Resource-Policy" to "same-origin",
        )
        secured.putAll(headers)
        return HttpResponse(status, secured, body)
    }

    private fun idlePollMillis(): Int {
        val timeoutMillis = runCatching { config.idleTimeout.toMillis() }.getOrDefault(Long.MAX_VALUE)
        return timeoutMillis.coerceIn(MIN_IDLE_POLL_MILLIS.toLong(), MAX_IDLE_POLL_MILLIS.toLong()).toInt()
    }

    private fun durationToIntMillis(duration: Duration): Int {
        val millis = runCatching { duration.toMillis() }.getOrDefault(Long.MAX_VALUE)
        return millis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    private data class DispatchResult(val response: HttpResponse)

    private sealed interface RequestedRange {
        data object Full : RequestedRange
        data object Unsatisfiable : RequestedRange
        data class Partial(val start: Long, val endInclusive: Long) : RequestedRange
    }

    private class NamedDaemonThreadFactory(private val prefix: String) : ThreadFactory {
        private val nextId = AtomicInteger(1)

        override fun newThread(task: Runnable): Thread = Thread(task, "$prefix-${nextId.getAndIncrement()}").apply {
            isDaemon = true
        }
    }

    private companion object {
        const val ACCEPT_BACKLOG = 8
        const val CLIENT_THREADS = 2
        const val MAX_WRONG_CODES = 5
        const val SESSION_TOKEN_BYTES = 32
        const val SESSION_COOKIE = "soma_session"
        const val MIN_IDLE_POLL_MILLIS = 25
        const val MAX_IDLE_POLL_MILLIS = 1_000
        const val MAX_PAGE_NUMBER = Int.MAX_VALUE / PAGE_SIZE
        const val MAX_SEARCH_QUERY_CHARS = 120
        const val SEARCH_RESULT_LIMIT = 30
        const val FOREST_ASSET_PATH = "/assets/forest.webp"
        val ACCESS_CODE = Regex("[0-9]{6}")
        val ALLOWED_METHODS = setOf("GET", "HEAD", "POST")
        val READ_METHODS = setOf("GET", "HEAD")
        const val CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src 'self'; media-src 'self'; " +
            "form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
    }
}

private object ForestAssets {
    private const val MAX_FOREST_BYTES = 512 * 1024

    fun load(background: ForestBackground): ByteArray {
        val path = "/com/soma/lanserver/forest/${background.resourceName}"
        val bytes = requireNotNull(ForestAssets::class.java.getResourceAsStream(path)) {
            "Missing bundled forest background: ${background.resourceName}"
        }.use { input -> input.readBytes() }
        require(bytes.isNotEmpty() && bytes.size <= MAX_FOREST_BYTES) {
            "Bundled forest background has an invalid size"
        }
        return bytes
    }
}

private object SecureSixDigitCodeGenerator : AccessCodeGenerator {
    private val random = SecureRandom()

    override fun nextCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')
}
