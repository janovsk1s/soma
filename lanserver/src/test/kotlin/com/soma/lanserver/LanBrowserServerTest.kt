package com.soma.lanserver

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LanBrowserServerTest {
    private lateinit var address: InetAddress
    private val servers = mutableListOf<LanBrowserServer>()

    @Before
    fun findLanAddress() {
        address = Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .first { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isAnyLocalAddress }
    }

    @After
    fun stopServers() {
        servers.forEach(LanBrowserServer::close)
    }

    @Test
    fun `configuration requires a concrete site-local address`() {
        assertThrows(IllegalArgumentException::class.java) {
            LanServerConfig(InetAddress.getByName("0.0.0.0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LanServerConfig(InetAddress.getLoopbackAddress())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LanServerConfig(InetAddress.getByName("203.0.113.1"))
        }
        assertEquals(address, LanServerConfig(address).bindAddress)
    }

    @Test
    fun `browser pages are dark by default and follow developer light mode`() {
        val darkServer = server(FakeDataSource())
        val darkPage = request(darkServer.start(), "GET", "/")
        assertTrue(darkPage.text.contains("color-scheme:dark"))
        assertTrue(darkPage.text.contains("--paper:#000;--ink:#fff;--dim:#888"))

        val lightServer = server(FakeDataSource(), lightMode = true)
        val lightPage = request(lightServer.start(), "GET", "/")
        assertTrue(lightPage.text.contains("color-scheme:light"))
        assertTrue(lightPage.text.contains("--paper:#fff;--ink:#000;--dim:#555"))
    }

    @Test
    fun `one-time code creates a random strict session and unlocks read-only pages`() {
        val data = FakeDataSource()
        val server = server(data)
        val endpoint = server.start()

        val unauthorized = request(endpoint, "GET", "/days")
        assertEquals(401, unauthorized.status)
        assertEquals(0, data.daysRequests.size)

        val authenticated = authenticate(endpoint)
        assertEquals(303, authenticated.response.status)
        assertEquals("/days", authenticated.response.headers["location"])
        val setCookie = authenticated.response.headers["set-cookie"]
        assertNotNull(setCookie)
        val cookieHeader = requireNotNull(setCookie)
        assertTrue(cookieHeader.contains("HttpOnly"))
        assertTrue(cookieHeader.contains("SameSite=Strict"))
        assertTrue(cookieHeader.contains("Max-Age=900"))
        assertFalse(cookieHeader.contains("123456"))
        assertTrue(authenticated.cookie.substringAfter('=').length >= 40)

        val days = request(endpoint, "GET", "/days", cookie = authenticated.cookie)
        assertEquals(200, days.status)
        assertEquals("no-store", days.headers["cache-control"])
        assertEquals("DENY", days.headers["x-frame-options"])
        assertTrue(days.headers.getValue("content-security-policy").contains("default-src 'none'"))
        assertEquals(1, data.daysRequests.size)

        val reusedCode = request(
            endpoint,
            "POST",
            "/auth",
            body = "code=123456",
            contentType = "application/x-www-form-urlencoded",
        )
        assertEquals(401, reusedCode.status)
        assertTrue(reusedCode.text.contains("expired"))
    }

    @Test
    fun `fifth incorrect code stops the server`() {
        val states = CopyOnWriteArrayList<LanServerState>()
        val server = server(FakeDataSource(), listener = LanServerStateListener(states::add))
        val endpoint = server.start()

        repeat(4) {
            val response = request(
                endpoint,
                "POST",
                "/auth",
                body = "code=000000",
                contentType = "application/x-www-form-urlencoded",
            )
            assertEquals(401, response.status)
        }
        val fifth = request(
            endpoint,
            "POST",
            "/auth",
            body = "code=000000",
            contentType = "application/x-www-form-urlencoded",
        )
        assertEquals(429, fifth.status)
        waitUntil { server.state == LanServerState.Stopped(LanServerStopReason.TOO_MANY_WRONG_CODES) }
        assertTrue(states.contains(LanServerState.Stopped(LanServerStopReason.TOO_MANY_WRONG_CODES)))
        assertEquals(listOf(0, 1, 2, 3, 4), states.filterIsInstance<LanServerState.AwaitingAuthentication>().map { it.wrongCodeAttempts })
    }

    @Test
    fun `host must match the bound numeric address and port`() {
        val server = server(FakeDataSource())
        val endpoint = server.start()

        val response = rawRequest(
            endpoint,
            "GET / HTTP/1.1\r\nHost: attacker.invalid\r\nConnection: close\r\n\r\n",
        )
        assertEquals(421, response.status)
    }

    @Test
    fun `SSR escapes stored text and pages in groups of exactly five`() {
        val malicious = "<script>alert(\"x\")</script> & 'quoted'"
        val days = (1..12).map { index ->
            BrowserDay(
                date = LocalDate.of(2026, 7, 13).minusDays(index.toLong()),
                entryCount = index,
                preview = if (index == 6) malicious else "DAY-$index",
            )
        }
        val data = FakeDataSource(days = days)
        val server = server(data)
        val endpoint = server.start()
        val cookie = authenticate(endpoint).cookie

        val response = request(endpoint, "GET", "/days?page=2", cookie = cookie)
        assertEquals(200, response.status)
        assertEquals(PageRequest(offset = 5, limit = 5), data.daysRequests.single())
        assertEquals(5, Regex("<li>").findAll(response.text).count())
        assertTrue(response.text.contains("2 / 3"))
        assertTrue(response.text.contains("Previous"))
        assertTrue(response.text.contains("Next"))
        assertFalse(response.text.contains("<script>alert"))
        assertTrue(response.text.contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &#39;quoted&#39;"))
    }

    @Test
    fun `day and todo routes stay read-only and cap a misbehaving source at five items`() {
        val date = LocalDate.of(2026, 7, 12)
        val entries = (1..6).map { index ->
            BrowserEntry(
                id = "entry-$index",
                text = if (index == 1) "<b>ENTRY-$index</b>" else "ENTRY-$index",
                kind = BrowserEntryKind.TEXT,
            )
        }
        val todos = (1..6).map { index ->
            BrowserTodo("todo-$index", "TODO-$index", date, BrowserTodoState.OPEN)
        }
        val data = FakeDataSource(entries = entries, todos = todos, ignoreRequestedLimit = true)
        val server = server(data)
        val endpoint = server.start()
        val cookie = authenticate(endpoint).cookie

        val day = request(endpoint, "GET", "/day/$date", cookie = cookie)
        assertEquals(200, day.status)
        assertEquals(5, Regex("<li>").findAll(day.text).count())
        assertFalse(day.text.contains("ENTRY-6"))
        assertTrue(day.text.contains("&lt;b&gt;ENTRY-1&lt;/b&gt;"))

        val todoPage = request(endpoint, "GET", "/todos", cookie = cookie)
        assertEquals(200, todoPage.status)
        assertEquals(5, Regex("<li>").findAll(todoPage.text).count())
        assertFalse(todoPage.text.contains("TODO-6"))

        val attemptedWrite = request(endpoint, "POST", "/todos", cookie = cookie)
        assertEquals(405, attemptedWrite.status)
    }

    @Test
    fun `insights are local escaped read only and page five connections`() {
        val items = (1..6).map { index ->
            BrowserInsight(
                kind = if (index % 2 == 0) BrowserInsightKind.DATE else BrowserInsightKind.TAG,
                label = if (index == 1) "<script>bad</script>" else "connection-$index",
                occurrenceCount = 7 - index,
            )
        }
        val data = FakeDataSource(
            insights = BrowserInsights(
                annotatedEntryCount = 4,
                manualLayerCount = 1,
                aiLayerCount = 3,
                tagOccurrenceCount = 9,
                linkCount = 2,
                connections = PagedResult(items, items.size),
            ),
        )
        val server = server(data)
        val endpoint = server.start()
        val cookie = authenticate(endpoint).cookie

        val page = request(endpoint, "GET", "/insights", cookie = cookie)

        assertEquals(200, page.status)
        assertEquals(5, Regex("<li>").findAll(page.text).count())
        assertTrue(page.text.contains("Local metadata only"))
        assertTrue(page.text.contains("<dd>4</dd>"))
        assertTrue(page.text.contains("&lt;script&gt;bad&lt;/script&gt;"))
        assertFalse(page.text.contains("<script>bad</script>"))
        assertTrue(page.text.contains("1 / 2"))
        assertEquals(405, request(endpoint, "POST", "/insights", cookie = cookie).status)
    }

    @Test
    fun `graph is server rendered escaped read only and pages five edges`() {
        val date = LocalDate.of(2026, 7, 14)
        val edges = (1..6).map { index ->
            BrowserGraphEdge(
                sourceLabel = if (index == 1) "<script>bad</script>" else "entry-$index",
                sourceDate = date.minusDays(index.toLong()),
                targetLabel = "#topic-$index",
                targetKind = BrowserGraphNodeKind.TAG,
                relation = if (index == 2) "mentions & follows" else null,
                metadataSource = if (index % 2 == 0) {
                    BrowserMetadataSource.MANUAL
                } else {
                    BrowserMetadataSource.AI
                },
            )
        }
        val server = server(FakeDataSource(graph = PagedResult(edges, edges.size)))
        val endpoint = server.start()
        val cookie = authenticate(endpoint).cookie

        val page = request(endpoint, "GET", "/graph", cookie = cookie)

        assertEquals(200, page.status)
        assertTrue(page.text.contains("<svg class=\"connection-graph\""))
        assertEquals(5, Regex("class=\"graph-edge\"").findAll(page.text).count())
        assertTrue(page.text.contains("&lt;script&gt;bad&lt;/script&gt;"))
        assertFalse(page.text.contains("<script>bad</script>"))
        assertTrue(page.text.contains("mentions &amp; follows"))
        assertTrue(page.text.contains("1 / 2"))
        assertFalse(page.text.contains("<script"))
        assertEquals(405, request(endpoint, "POST", "/graph", cookie = cookie).status)
    }

    @Test
    fun `photo with a spoken comment exposes both authenticated media controls`() {
        val date = LocalDate.of(2026, 7, 14)
        val entry = BrowserEntry(
            id = "photo-comment",
            text = "Milchreis recipe",
            kind = BrowserEntryKind.IMAGE,
            audioId = "photo-audio",
            imageId = "photo-image",
        )
        val server = server(FakeDataSource(entries = listOf(entry)))
        val endpoint = server.start()
        val cookie = authenticate(endpoint).cookie

        val response = request(endpoint, "GET", "/day/$date", cookie = cookie)

        assertEquals(200, response.status)
        assertTrue(response.text.contains("/audio/photo-audio"))
        assertTrue(response.text.contains("/image/photo-image"))
        assertTrue(response.text.contains("Milchreis recipe"))
    }

    @Test
    fun `day route exposes escaped previous wordings without script`() {
        val date = LocalDate.of(2026, 7, 14)
        val created = Instant.parse("2026-07-14T08:00:00Z")
        val entries = listOf(
            BrowserEntry(
                id = "entry-history",
                text = "current wording",
                kind = BrowserEntryKind.TEXT,
                history = listOf(
                    BrowserEntryVersion(1, "<b>original</b>", created, isCurrent = false),
                    BrowserEntryVersion(2, "current wording", created.plusSeconds(60), isCurrent = true),
                ),
            ),
        )
        val server = server(FakeDataSource(entries = entries))
        val endpoint = server.start()
        val cookie = authenticate(endpoint).cookie

        val response = request(endpoint, "GET", "/day/$date", cookie = cookie)

        assertEquals(200, response.status)
        assertTrue(response.text.contains("Edit history · 2 versions"))
        assertTrue(response.text.contains("Original"))
        assertTrue(response.text.contains("&lt;b&gt;original&lt;/b&gt;"))
        assertFalse(response.text.contains("<b>original</b>"))
        assertFalse(response.text.contains("<script"))
    }

    @Test
    fun `audio is never opened before authentication and supports a single byte range`() {
        val audio = "0123456789".toByteArray()
        val data = FakeDataSource(audio = audio)
        val server = server(data)
        val endpoint = server.start()

        val unauthorized = request(endpoint, "GET", "/audio/voice-1")
        assertEquals(401, unauthorized.status)
        assertEquals(0, data.audioOpenCount)

        val cookie = authenticate(endpoint).cookie
        val ranged = request(
            endpoint,
            "GET",
            "/audio/voice-1",
            cookie = cookie,
            extraHeaders = mapOf("Range" to "bytes=2-5"),
        )
        assertEquals(206, ranged.status)
        assertEquals("bytes 2-5/10", ranged.headers["content-range"])
        assertArrayEquals("2345".toByteArray(), ranged.body)
        assertEquals(1, data.audioOpenCount)
    }

    @Test
    fun `image is rendered and never decrypted before authentication`() {
        val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val entry = BrowserEntry(
            id = "photo-entry",
            text = "train window",
            kind = BrowserEntryKind.IMAGE,
            imageId = "image-1",
        )
        val data = FakeDataSource(entries = listOf(entry), image = image)
        val server = server(data)
        val endpoint = server.start()

        assertEquals(401, request(endpoint, "GET", "/image/image-1").status)
        assertEquals(0, data.imageOpenCount)

        val cookie = authenticate(endpoint).cookie
        val day = request(endpoint, "GET", "/day/2026-07-12", cookie = cookie)
        assertTrue(day.text.contains("src=\"/image/image-1\""))
        val response = request(endpoint, "GET", "/image/image-1", cookie = cookie)
        assertEquals(200, response.status)
        assertEquals("image/jpeg", response.headers["content-type"])
        assertArrayEquals(image, response.body)
        assertEquals(1, data.imageOpenCount)
    }

    @Test
    fun `only authenticated activity refreshes the idle deadline`() {
        val clock = MutableClock(Instant.parse("2026-07-12T08:00:00Z"))
        val server = server(FakeDataSource(), clock = clock)
        val endpoint = server.start()

        clock.advance(Duration.ofMinutes(14).plusSeconds(59))
        assertEquals(200, request(endpoint, "GET", "/").status)
        clock.advance(Duration.ofSeconds(2))
        assertTrue(server.checkIdleNow())
        assertEquals(LanServerState.Stopped(LanServerStopReason.IDLE_TIMEOUT), server.state)
    }

    @Test
    fun `authenticated reads refresh the idle deadline`() {
        val clock = MutableClock(Instant.parse("2026-07-12T08:00:00Z"))
        val server = server(FakeDataSource(), clock = clock)
        val endpoint = server.start()
        val cookie = authenticate(endpoint).cookie

        clock.advance(Duration.ofMinutes(10))
        assertEquals(200, request(endpoint, "GET", "/days", cookie = cookie).status)
        clock.advance(Duration.ofMinutes(10))
        assertFalse(server.checkIdleNow())
        clock.advance(Duration.ofMinutes(6))
        assertTrue(server.checkIdleNow())
    }

    private fun server(
        data: FakeDataSource,
        listener: LanServerStateListener = LanServerStateListener {},
        clock: Clock = Clock.systemUTC(),
        lightMode: Boolean = false,
    ): LanBrowserServer = LanBrowserServer(
        config = LanServerConfig(address, lightMode = lightMode),
        dataSource = data,
        stateListener = listener,
        clock = clock,
        accessCodeGenerator = AccessCodeGenerator { "123456" },
    ).also(servers::add)

    private fun authenticate(endpoint: LanServerEndpoint): Authentication {
        val response = request(
            endpoint,
            "POST",
            "/auth",
            body = "code=123456",
            contentType = "application/x-www-form-urlencoded",
        )
        val cookieHeader = response.headers["set-cookie"]
        assertNotNull(cookieHeader)
        val cookie = requireNotNull(cookieHeader).substringBefore(';')
        assertNotEquals("soma_session=123456", cookie)
        return Authentication(response, cookie)
    }

    private fun request(
        endpoint: LanServerEndpoint,
        method: String,
        target: String,
        cookie: String? = null,
        body: String = "",
        contentType: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): TestResponse {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val raw = buildString {
            append("$method $target HTTP/1.1\r\n")
            append("Host: ${endpoint.address.hostAddress}:${endpoint.port}\r\n")
            append("Connection: close\r\n")
            cookie?.let { append("Cookie: $it\r\n") }
            contentType?.let { append("Content-Type: $it\r\n") }
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            if (bodyBytes.isNotEmpty() || method == "POST") append("Content-Length: ${bodyBytes.size}\r\n")
            append("\r\n")
            append(body)
        }
        return rawRequest(endpoint, raw)
    }

    private fun rawRequest(endpoint: LanServerEndpoint, raw: String): TestResponse {
        val bytes = Socket(endpoint.address, endpoint.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(raw.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes()
        }
        val separator = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val split = bytes.indexOf(separator)
        assertTrue("Response had no header terminator", split >= 0)
        val head = bytes.copyOfRange(0, split).toString(Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val status = lines.first().split(' ')[1].toInt()
        val headers = lines.drop(1).associate { line ->
            val colon = line.indexOf(':')
            line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
        }
        val body = bytes.copyOfRange(split + separator.size, bytes.size)
        assertEquals(headers["content-length"]?.toLong(), body.size.toLong())
        return TestResponse(status, headers, body)
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (index in 0..size - needle.size) {
            for (needleIndex in needle.indices) {
                if (this[index + needleIndex] != needle[needleIndex]) continue@outer
            }
            return index
        }
        return -1
    }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("Condition was not reached", predicate())
    }

    private data class Authentication(val response: TestResponse, val cookie: String)

    private data class TestResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    private class MutableClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private class FakeDataSource(
        private val days: List<BrowserDay> = emptyList(),
        private val entries: List<BrowserEntry> = emptyList(),
        private val todos: List<BrowserTodo> = emptyList(),
        private val audio: ByteArray? = null,
        private val image: ByteArray? = null,
        private val ignoreRequestedLimit: Boolean = false,
        private val insights: BrowserInsights = BrowserInsights(
            annotatedEntryCount = 0,
            manualLayerCount = 0,
            aiLayerCount = 0,
            tagOccurrenceCount = 0,
            linkCount = 0,
            connections = PagedResult(emptyList(), 0),
        ),
        private val graph: PagedResult<BrowserGraphEdge> = PagedResult(emptyList(), 0),
    ) : ReadOnlySomaDataSource {
        val daysRequests = CopyOnWriteArrayList<PageRequest>()

        @Volatile
        var audioOpenCount = 0

        @Volatile
        var imageOpenCount = 0

        override fun listDays(request: PageRequest): PagedResult<BrowserDay> {
            daysRequests += request
            return PagedResult(page(days, request), days.size)
        }

        override fun entriesForDay(
            date: LocalDate,
            request: PageRequest,
        ): PagedResult<BrowserEntry> = PagedResult(page(entries, request), entries.size)

        override fun listTodos(
            filter: BrowserTodoFilter,
            request: PageRequest,
        ): PagedResult<BrowserTodo> = PagedResult(page(todos, request), todos.size)

        override fun metadataInsights(request: PageRequest): BrowserInsights = insights.copy(
            connections = PagedResult(
                page(insights.connections.items, request),
                insights.connections.totalCount,
            ),
        )

        override fun connectionGraph(request: PageRequest): PagedResult<BrowserGraphEdge> =
            PagedResult(page(graph.items, request), graph.totalCount)

        override fun openAudio(audioId: String): AudioResource? {
            audioOpenCount++
            val content = audio ?: return null
            return AudioResource("audio/wav", content.size.toLong()) { ByteArrayInputStream(content) }
        }

        override fun openImage(imageId: String): ImageResource? {
            imageOpenCount++
            val content = image ?: return null
            return ImageResource("image/jpeg", content.size.toLong()) { ByteArrayInputStream(content) }
        }

        private fun <T> page(values: List<T>, request: PageRequest): List<T> {
            if (ignoreRequestedLimit) return values
            return values.drop(request.offset).take(request.limit)
        }
    }
}
