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
    private var wrongCodeAttempts = 0
    private var lastAuthenticatedActivity: Instant = Instant.EPOCH

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
                if (!socket.inetAddress.isSiteLocalAddress) {
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
                DispatchResult(htmlResponse(200, HtmlRenderer.login(lightMode = config.lightMode)))
            }
        }

        if (!authenticated) {
            return DispatchResult(errorResponse(401, "Enter the one-time access code first."))
        }
        markAuthenticatedActivity()

        if (request.method !in READ_METHODS) {
            return DispatchResult(errorResponse(405, "Browser view is read-only.", mapOf("Allow" to "GET, HEAD")))
        }

        return DispatchResult(
            when {
                request.path == "/days" -> daysResponse(request)
                request.path.startsWith("/day/") -> dayResponse(request)
                request.path == "/todos" -> todosResponse(request)
                request.path.startsWith("/audio/") -> audioResponse(request)
                request.path.startsWith("/image/") -> imageResponse(request)
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
            htmlResponse(401, HtmlRenderer.login("That code did not match.", config.lightMode)),
        )
    }

    private fun daysResponse(request: HttpRequest): HttpResponse {
        val page = pageNumber(request)
        val result = dataSource.listDays(pageRequest(page)).bounded()
        return htmlResponse(200, HtmlRenderer.days(page, result, config.lightMode))
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
        return htmlResponse(200, HtmlRenderer.day(date, page, result, config.lightMode))
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
        return htmlResponse(200, HtmlRenderer.todos(filter, page, result, config.lightMode))
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
        message: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val title = when (status) {
            400 -> "Bad request"
            401 -> "Access required"
            404 -> "Not found"
            405 -> "Read only"
            413 -> "Request too large"
            415 -> "Unsupported form"
            416 -> "Range unavailable"
            421 -> "Wrong host"
            429 -> "Browser view stopped"
            431 -> "Headers too large"
            505 -> "HTTP version unsupported"
            else -> "Something went wrong"
        }
        val headers = linkedMapOf("Content-Type" to "text/html; charset=utf-8")
        headers.putAll(extraHeaders)
        return secureResponse(
            status,
            headers,
            ResponseBody.text(HtmlRenderer.error(status, title, message, config.lightMode)),
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
        val ACCESS_CODE = Regex("[0-9]{6}")
        val ALLOWED_METHODS = setOf("GET", "HEAD", "POST")
        val READ_METHODS = setOf("GET", "HEAD")
        const val CSP = "default-src 'none'; style-src 'unsafe-inline'; media-src 'self'; " +
            "form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
    }
}

private object SecureSixDigitCodeGenerator : AccessCodeGenerator {
    private val random = SecureRandom()

    override fun nextCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')
}
