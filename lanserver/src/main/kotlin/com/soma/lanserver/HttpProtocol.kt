package com.soma.lanserver

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean

internal data class HttpRequest(
    val method: String,
    val rawTarget: String,
    val path: String,
    val query: Map<String, List<String>>,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun singleHeader(name: String): String? {
        val values = headers[name.lowercase()] ?: return null
        if (values.size != 1) throw HttpParseException(400, "Duplicate header")
        return values.single()
    }
}

internal class HttpParseException(
    val status: Int,
    override val message: String,
) : Exception(message)

internal object HttpProtocol {
    private const val MAX_REQUEST_LINE_BYTES = 4_096
    private const val MAX_HEADER_LINE_BYTES = 8_192
    private const val MAX_HEADER_BYTES = 32_768
    private const val MAX_HEADER_COUNT = 64
    // Large enough for an edited note entry, still bounded. The access-code
    // form is tiny; note text is the reason this is not a few hundred bytes.
    private const val MAX_FORM_BYTES = 64 * 1_024

    fun readRequest(socket: Socket): HttpRequest {
        val input = socket.getInputStream()
        val requestLine = readLine(input, MAX_REQUEST_LINE_BYTES)
            ?: throw HttpParseException(400, "Empty request")
        val requestParts = requestLine.split(' ')
        if (requestParts.size != 3 || requestParts.any(String::isEmpty)) {
            throw HttpParseException(400, "Malformed request line")
        }
        val method = requestParts[0]
        if (!TOKEN.matches(method)) throw HttpParseException(400, "Invalid method")
        val target = requestParts[1]
        if (requestParts[2] != "HTTP/1.1") {
            throw HttpParseException(505, "HTTP/1.1 is required")
        }
        if (!target.startsWith('/') || '#' in target || target.any { it.code > 0x7f }) {
            throw HttpParseException(400, "Only origin-form request targets are accepted")
        }

        val headers = linkedMapOf<String, MutableList<String>>()
        var headerBytes = 0
        var headerCount = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_LINE_BYTES)
                ?: throw HttpParseException(400, "Headers ended unexpectedly")
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) throw HttpParseException(431, "Headers too large")
            if (line.isEmpty()) break
            headerCount++
            if (headerCount > MAX_HEADER_COUNT) throw HttpParseException(431, "Too many headers")
            if (line.firstOrNull() == ' ' || line.firstOrNull() == '\t') {
                throw HttpParseException(400, "Folded headers are not accepted")
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpParseException(400, "Malformed header")
            val name = line.substring(0, colon)
            if (!TOKEN.matches(name)) throw HttpParseException(400, "Invalid header name")
            val value = line.substring(colon + 1).trim()
            if (value.any { it == '\u0000' || it == '\r' || it == '\n' }) {
                throw HttpParseException(400, "Invalid header value")
            }
            headers.getOrPut(name.lowercase()) { mutableListOf() }.add(value)
        }

        val transferEncoding = headers["transfer-encoding"]
        if (transferEncoding != null) throw HttpParseException(400, "Transfer-Encoding is not accepted")
        val contentLengths = headers["content-length"]
        if (contentLengths != null && contentLengths.size != 1) {
            throw HttpParseException(400, "Duplicate Content-Length")
        }
        val contentLength = contentLengths?.single()?.toIntOrNull()
            ?: if (contentLengths == null) 0 else throw HttpParseException(400, "Invalid Content-Length")
        if (contentLength < 0 || contentLength > MAX_FORM_BYTES) {
            throw HttpParseException(413, "Request body too large")
        }
        if (method != "POST" && contentLength != 0) {
            throw HttpParseException(400, "A request body is not accepted for this method")
        }
        val body = ByteArray(contentLength)
        var position = 0
        while (position < body.size) {
            val read = input.read(body, position, body.size - position)
            if (read < 0) throw HttpParseException(400, "Request body ended unexpectedly")
            position += read
        }

        val question = target.indexOf('?')
        val encodedPath = if (question >= 0) target.substring(0, question) else target
        val encodedQuery = if (question >= 0) target.substring(question + 1) else ""
        return HttpRequest(
            method = method,
            rawTarget = target,
            path = decodeComponent(encodedPath, plusAsSpace = false),
            query = parseParameters(encodedQuery),
            headers = headers.mapValues { it.value.toList() },
            body = body,
        )
    }

    fun parseForm(request: HttpRequest): Map<String, List<String>> {
        val contentType = request.singleHeader("content-type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (contentType != "application/x-www-form-urlencoded") {
            throw HttpParseException(415, "Form encoding is required")
        }
        if (request.body.any { it.toInt() < 0 }) throw HttpParseException(400, "Invalid form encoding")
        val encoded = request.body.toString(Charsets.US_ASCII)
        return parseParameters(encoded)
    }

    fun writeResponse(output: OutputStream, response: HttpResponse, omitBody: Boolean = false) {
        try {
            val bodyLength = response.body.length
            val headers = linkedMapOf<String, String>()
            headers.putAll(response.headers)
            headers["Content-Length"] = bodyLength.toString()
            headers["Connection"] = "close"
            headers.forEach { (name, value) ->
                require(HEADER_NAME.matches(name) && '\r' !in value && '\n' !in value) {
                    "Unsafe response header"
                }
            }
            val head = buildString {
                append("HTTP/1.1 ")
                append(response.status)
                append(' ')
                append(reason(response.status))
                append("\r\n")
                headers.forEach { (name, value) ->
                    append(name)
                    append(": ")
                    append(value)
                    append("\r\n")
                }
                append("\r\n")
            }.toByteArray(Charsets.ISO_8859_1)
            output.write(head)
            if (!omitBody) response.body.writeTo(output)
            output.flush()
        } finally {
            response.body.close()
        }
    }

    private fun parseParameters(encoded: String): Map<String, List<String>> {
        if (encoded.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, MutableList<String>>()
        val pairs = encoded.split('&')
        if (pairs.size > 32) throw HttpParseException(400, "Too many parameters")
        pairs.forEach { pair ->
            val equals = pair.indexOf('=')
            val encodedName = if (equals >= 0) pair.substring(0, equals) else pair
            val encodedValue = if (equals >= 0) pair.substring(equals + 1) else ""
            val name = decodeComponent(encodedName, plusAsSpace = true)
            val value = decodeComponent(encodedValue, plusAsSpace = true)
            result.getOrPut(name) { mutableListOf() }.add(value)
        }
        return result.mapValues { it.value.toList() }
    }

    private fun decodeComponent(value: String, plusAsSpace: Boolean): String {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character == '%' -> {
                    if (index + 2 >= value.length) throw HttpParseException(400, "Invalid percent encoding")
                    val high = value[index + 1].digitToIntOrNull(16)
                    val low = value[index + 2].digitToIntOrNull(16)
                    if (high == null || low == null) throw HttpParseException(400, "Invalid percent encoding")
                    bytes.write((high shl 4) or low)
                    index += 3
                }
                plusAsSpace && character == '+' -> {
                    bytes.write(' '.code)
                    index++
                }
                character.code <= 0x7f -> {
                    bytes.write(character.code)
                    index++
                }
                else -> throw HttpParseException(400, "Request target must be ASCII")
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: Exception) {
            throw HttpParseException(400, "Invalid UTF-8 encoding")
        }
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= maxBytes) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else throw HttpParseException(400, "Line ended unexpectedly")
            if (next == '\r'.code) {
                if (input.read() != '\n'.code) throw HttpParseException(400, "Lines must end with CRLF")
                return bytes.toString(Charsets.ISO_8859_1.name())
            }
            if (next == '\n'.code || next == 0) throw HttpParseException(400, "Invalid line ending")
            bytes.write(next)
        }
        throw HttpParseException(431, "Request line or header too large")
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        303 -> "See Other"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Content Too Large"
        415 -> "Unsupported Media Type"
        416 -> "Range Not Satisfiable"
        421 -> "Misdirected Request"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        505 -> "HTTP Version Not Supported"
        else -> "Error"
    }

    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val HEADER_NAME = TOKEN
}

internal data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ResponseBody,
)

internal sealed interface ResponseBody {
    val length: Long

    fun writeTo(output: OutputStream)

    fun close() = Unit

    class Bytes(
        private val value: ByteArray,
        private val onClose: (() -> Unit)? = null,
    ) : ResponseBody {
        private val closed = AtomicBoolean(false)
        override val length: Long = value.size.toLong()

        override fun writeTo(output: OutputStream) {
            output.write(value)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) onClose?.invoke()
        }
    }

    class Stream(
        override val length: Long,
        private val offset: Long,
        private val source: () -> InputStream,
    ) : ResponseBody {
        override fun writeTo(output: OutputStream) {
            source().use { input ->
                skipFully(input, offset)
                var remaining = length
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IllegalStateException("Audio stream ended before its declared length")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }

        private fun skipFully(input: InputStream, byteCount: Long) {
            var remaining = byteCount
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    if (input.read() < 0) throw IllegalStateException("Audio stream is shorter than its declared length")
                    remaining--
                }
            }
        }
    }

    companion object {
        fun text(value: String): ResponseBody = Bytes(value.toByteArray(Charsets.UTF_8))

        fun empty(): ResponseBody = Bytes(ByteArray(0))
    }
}
