package com.soma.core.bridge

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Versioned, platform-neutral wire contract between a Soma mobile client and
 * the user-owned Soma bridge running beside Codex on their Mac.
 *
 * The bridge is deliberately capability based. A paired mobile client never
 * receives Codex credentials and cannot invoke arbitrary app-server methods.
 */
object SomaBridgeProtocol {
    const val VERSION = 1
    const val PAIR_SCHEME = "soma"
    const val PAIR_HOST = "pair"
    const val PLATFORM_ANDROID = "android"
    const val MAX_PAIR_URI_BYTES = 4_096
    const val MAX_DEVICE_NAME_LENGTH = 80
    const val MAX_PATH_LENGTH = 2_048
    const val NONCE_BYTES = 24

    const val HEADER_DEVICE_ID = "X-Soma-Device"
    const val HEADER_SEQUENCE = "X-Soma-Sequence"
    const val HEADER_TIMESTAMP_MS = "X-Soma-Timestamp"
    const val HEADER_NONCE = "X-Soma-Nonce"
    const val HEADER_SIGNATURE = "X-Soma-Signature"
}

/** Capabilities have stable lowercase wire names shared by iOS, Android, and macOS. */
enum class BridgeCapability(val wireName: String) {
    CONTEXT_READ("context.read"),
    CODEX_THREAD("codex.thread"),
    CODEX_TURN("codex.turn"),
    CODEX_STREAM("codex.stream"),
    PROPOSAL_READ("proposal.read"),
    PROPOSAL_APPROVE("proposal.approve"),
    SYNC_READ("sync.read"),
    SYNC_WRITE("sync.write"),
    ;

    companion object {
        fun fromWireName(value: String): BridgeCapability =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("Unknown Soma bridge capability")
    }
}

enum class BridgeClientPlatform(val wireName: String) {
    IOS("ios"),
    ANDROID(SomaBridgeProtocol.PLATFORM_ANDROID),
}

/**
 * One-use QR payload emitted by the Mac bridge.
 *
 * [secret] is intentionally absent from [BridgePairingRecord]. Mobile clients
 * use it only for the pairing exchange and must never persist it.
 */
data class BridgePairingInvite(
    val protocolVersion: Int,
    val bridge: UUID,
    val host: String,
    val port: Int,
    val secret: String,
    /** Lowercase SHA-256 fingerprint of the bridge TLS/signing public key. */
    val fingerprint: String,
    /** Unix epoch seconds. */
    val expires: Long,
) {
    init {
        require(protocolVersion == SomaBridgeProtocol.VERSION) { "Unsupported Soma bridge protocol" }
        require(isValidBridgeHost(host)) { "Bridge host is invalid" }
        require(port in 1..65_535) { "Bridge port is invalid" }
        require(isSecurePairingSecret(secret)) { "Pairing secret is invalid" }
        require(SHA256_HEX.matches(fingerprint)) { "Bridge fingerprint must be lowercase SHA-256 hex" }
        require(expires > 0) { "Pairing expiry must be positive" }
    }

    fun requireUsable(now: Instant = Instant.now()): BridgePairingInvite {
        require(now.epochSecond <= expires) { "Pairing invitation has expired" }
        return this
    }

    /** HTTPS authority, with brackets added for an IPv6 literal when necessary. */
    val httpsAuthority: String
        get() = "${if (':' in host && !host.startsWith("[")) "[$host]" else host}:$port"
}

/**
 * Stable paired state safe to persist after the one-use secret is consumed.
 * The last sequence is committed before a request is signed, making replay
 * counters monotonic even if a request is interrupted after reservation.
 */
data class BridgePairingRecord(
    val bridge: UUID,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val deviceID: String,
    val grantedCapabilities: Set<BridgeCapability>,
    val pairedAtEpochMillis: Long,
    val lastSequence: Long = 0,
) {
    init {
        require(isValidBridgeHost(host)) { "Bridge host is invalid" }
        require(port in 1..65_535) { "Bridge port is invalid" }
        require(SHA256_HEX.matches(fingerprint)) { "Bridge fingerprint must be lowercase SHA-256 hex" }
        require(isUUID(deviceID)) { "Bridge device ID is invalid" }
        require(grantedCapabilities.isNotEmpty()) { "A pairing must grant at least one capability" }
        require(pairedAtEpochMillis > 0) { "Pairing time must be positive" }
        require(lastSequence >= 0) { "Bridge sequence must not be negative" }
    }

    val httpsAuthority: String
        get() = "${if (':' in host && !host.startsWith("[")) "[$host]" else host}:$port"
}

/**
 * Pairing request body. [publicKey] is standard Base64 containing an
 * uncompressed ANSI X9.63 P-256 key: 0x04 || X(32 bytes) || Y(32 bytes).
 */
data class BridgePairRequest(
    val protocolVersion: Int = SomaBridgeProtocol.VERSION,
    val deviceID: String,
    val deviceName: String,
    val platform: BridgeClientPlatform,
    val publicKey: String,
    val requestedCapabilities: Set<BridgeCapability>,
) {
    init {
        require(protocolVersion == SomaBridgeProtocol.VERSION) { "Unsupported Soma bridge protocol" }
        require(isUUID(deviceID)) { "Bridge device ID is invalid" }
        require(
            deviceName.isNotBlank() &&
                deviceName.encodeToByteArray().size <= SomaBridgeProtocol.MAX_DEVICE_NAME_LENGTH &&
                deviceName.none(Char::isISOControl),
        ) { "Bridge device name is invalid" }
        require(isP256X963PublicKey(publicKey)) { "Bridge public key must be Base64 X9.63 P-256" }
        require(requestedCapabilities.isNotEmpty()) { "At least one capability must be requested" }
    }

    /** Deterministic JSON used by both native clients for the v1 pair body. */
    fun toJson(): String = buildString {
        append("{\"protocolVersion\":")
        append(protocolVersion)
        append(",\"deviceID\":")
        appendJsonString(deviceID)
        append(",\"deviceName\":")
        appendJsonString(deviceName)
        append(",\"platform\":")
        appendJsonString(platform.wireName)
        append(",\"publicKey\":")
        appendJsonString(publicKey)
        append(",\"requestedCapabilities\":[")
        requestedCapabilities.sortedBy(BridgeCapability::wireName).forEachIndexed { index, capability ->
            if (index > 0) append(',')
            appendJsonString(capability.wireName)
        }
        append("]}")
    }
}

/** Authenticated response returned by `POST /v1/pair`. */
data class BridgePairResponse(
    val protocolVersion: Int,
    val bridgeID: UUID,
    val bridgeName: String,
    val deviceID: String,
    val platform: BridgeClientPlatform,
    val capabilities: List<BridgeCapability>,
    val certificateFingerprint: String,
    val pairedAt: String,
) {
    init {
        require(protocolVersion == SomaBridgeProtocol.VERSION) { "Unsupported Soma bridge protocol" }
        require(bridgeName.isNotBlank() && bridgeName.encodeToByteArray().size <= 80) {
            "Bridge name is invalid"
        }
        require(isUUID(deviceID)) { "Bridge device ID is invalid" }
        require(capabilities.isNotEmpty() && capabilities.size <= BridgeCapability.entries.size) {
            "Bridge capabilities are invalid"
        }
        require(capabilities.toSet().size == capabilities.size) {
            "Bridge capabilities contain duplicates"
        }
        require(SHA256_HEX.matches(certificateFingerprint)) {
            "Bridge fingerprint must be lowercase SHA-256 hex"
        }
        require(runCatching { Instant.parse(pairedAt) }.isSuccess) {
            "Bridge pairing time is invalid"
        }
    }
}

/**
 * Exact v1 request authentication input:
 *
 * SOMA-BRIDGE-REQUEST
 * PROTOCOL_VERSION
 * BRIDGE_UUID
 * CERTIFICATE_SHA256_HEX
 * METHOD
 * PATH
 * BODY_SHA256_HEX
 * DEVICE_ID
 * SEQUENCE
 * TIMESTAMP_MS
 * NONCE
 */
data class BridgeRequestSigningInput(
    val protocolVersion: Int = SomaBridgeProtocol.VERSION,
    val bridgeID: UUID,
    val certificateFingerprint: String,
    val method: String,
    val path: String,
    val bodySha256Hex: String,
    val deviceID: String,
    val sequence: Long,
    val timestampMs: Long,
    val nonce: String,
) {
    init {
        require(protocolVersion == SomaBridgeProtocol.VERSION) { "Unsupported Soma bridge protocol" }
        require(SHA256_HEX.matches(certificateFingerprint)) {
            "Bridge fingerprint must be lowercase SHA-256 hex"
        }
        require(method.matches(HTTP_METHOD)) { "HTTP method must be canonical uppercase ASCII" }
        require(
            path.startsWith('/') &&
                path.length <= SomaBridgeProtocol.MAX_PATH_LENGTH &&
                path.none { it == '\r' || it == '\n' || it.isISOControl() },
        ) { "Bridge request path is invalid" }
        require(SHA256_HEX.matches(bodySha256Hex)) { "Body hash must be lowercase SHA-256 hex" }
        require(isUUID(deviceID)) { "Bridge device ID is invalid" }
        require(sequence > 0) { "Bridge request sequence must be positive" }
        require(timestampMs > 0) { "Bridge timestamp must be positive" }
        require(isBase64Url(nonce, minimumDecodedBytes = 16, maximumDecodedBytes = 64)) {
            "Bridge nonce is invalid"
        }
    }

    fun canonicalString(): String = listOf(
        "SOMA-BRIDGE-REQUEST",
        protocolVersion.toString(),
        bridgeID.toString().uppercase(),
        certificateFingerprint,
        method,
        path,
        bodySha256Hex,
        deviceID.uppercase(),
        sequence.toString(),
        timestampMs.toString(),
        nonce,
    ).joinToString("\n")

    fun canonicalBytes(): ByteArray = canonicalString().encodeToByteArray()

    companion object {
        fun forBody(
            bridgeID: UUID,
            certificateFingerprint: String,
            method: String,
            path: String,
            body: ByteArray,
            deviceID: String,
            sequence: Long,
            timestampMs: Long,
            nonce: String,
        ): BridgeRequestSigningInput = BridgeRequestSigningInput(
            bridgeID = bridgeID,
            certificateFingerprint = certificateFingerprint,
            method = method,
            path = path,
            bodySha256Hex = BridgeWireCrypto.sha256Hex(body),
            deviceID = deviceID,
            sequence = sequence,
            timestampMs = timestampMs,
            nonce = nonce,
        )
    }
}

/** Headers attached after signing [input] with the device's P-256 private key. */
data class BridgeSignedRequestHeaders(
    val input: BridgeRequestSigningInput,
    /** Standard Base64 DER-encoded ECDSA-with-SHA256 signature. */
    val signature: String,
) {
    init {
        require(isStandardBase64(signature, minimumDecodedBytes = 64, maximumDecodedBytes = 80)) {
            "Bridge signature is invalid"
        }
    }

    fun asMap(): Map<String, String> = linkedMapOf(
        SomaBridgeProtocol.HEADER_DEVICE_ID to input.deviceID.uppercase(),
        SomaBridgeProtocol.HEADER_SEQUENCE to input.sequence.toString(),
        SomaBridgeProtocol.HEADER_TIMESTAMP_MS to input.timestampMs.toString(),
        SomaBridgeProtocol.HEADER_NONCE to input.nonce,
        SomaBridgeProtocol.HEADER_SIGNATURE to signature,
    )
}

/** Canonical v1 `soma://pair` URI codec shared by mobile implementations. */
object BridgePairingInviteUri {
    private val requiredFields = setOf(
        "v",
        "bridge",
        "host",
        "port",
        "secret",
        "fingerprint",
        "expires",
    )

    fun encode(invite: BridgePairingInvite): String = buildString {
        append(SomaBridgeProtocol.PAIR_SCHEME)
        append("://")
        append(SomaBridgeProtocol.PAIR_HOST)
        append("?v=")
        append(invite.protocolVersion)
        append("&bridge=")
        append(urlEncode(invite.bridge.toString()))
        append("&host=")
        append(urlEncode(invite.host))
        append("&port=")
        append(invite.port)
        append("&secret=")
        append(urlEncode(invite.secret))
        append("&fingerprint=")
        append(invite.fingerprint)
        append("&expires=")
        append(invite.expires)
    }

    fun decode(value: String, now: Instant = Instant.now()): BridgePairingInvite {
        require(value.encodeToByteArray().size <= SomaBridgeProtocol.MAX_PAIR_URI_BYTES) {
            "Pairing URI is too large"
        }
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Pairing URI is malformed", it) }
        require(uri.scheme.equals(SomaBridgeProtocol.PAIR_SCHEME, ignoreCase = true)) {
            "Pairing URI scheme is invalid"
        }
        require(uri.host.equals(SomaBridgeProtocol.PAIR_HOST, ignoreCase = true)) {
            "Pairing URI host is invalid"
        }
        require(uri.rawPath.isNullOrEmpty() && uri.rawFragment == null && uri.rawUserInfo == null) {
            "Pairing URI contains unsupported components"
        }

        val fields = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach { component ->
            val separator = component.indexOf('=')
            require(separator > 0) { "Pairing URI query is malformed" }
            val key = urlDecode(component.substring(0, separator))
            val valuePart = urlDecode(component.substring(separator + 1))
            require(fields.put(key, valuePart) == null) { "Pairing URI contains a duplicate field" }
        }
        require(fields.keys.containsAll(requiredFields)) { "Pairing URI is missing a required field" }

        return BridgePairingInvite(
            protocolVersion = fields.getValue("v").toIntOrNull()
                ?: throw IllegalArgumentException("Pairing protocol version is invalid"),
            bridge = runCatching { UUID.fromString(fields.getValue("bridge")) }
                .getOrElse { throw IllegalArgumentException("Pairing bridge ID is invalid", it) },
            host = fields.getValue("host"),
            port = fields.getValue("port").toIntOrNull()
                ?: throw IllegalArgumentException("Pairing port is invalid"),
            secret = fields.getValue("secret"),
            fingerprint = fields.getValue("fingerprint").lowercase(),
            expires = fields.getValue("expires").toLongOrNull()
                ?: throw IllegalArgumentException("Pairing expiry is invalid"),
        ).requireUsable(now)
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
            .getOrElse { throw IllegalArgumentException("Pairing URI encoding is invalid", it) }
}

object BridgeWireCrypto {
    fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { byte -> "%02x".format(byte) }

    fun sha256Fingerprint(publicKey: ByteArray): String = sha256Hex(publicKey)
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun isValidBridgeHost(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) return false
    val numbers = parts.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) {
            return false
        }
        val number = part.toIntOrNull() ?: return false
        if (number !in 0..255) return false
        number
    }
    return numbers[0] == 10 ||
        numbers[0] == 127 ||
        (numbers[0] == 100 && numbers[1] in 64..127) ||
        (numbers[0] == 172 && numbers[1] in 16..31) ||
        (numbers[0] == 192 && numbers[1] == 168)
}

private fun isSecurePairingSecret(value: String): Boolean =
    isBase64Url(value, minimumDecodedBytes = 32, maximumDecodedBytes = 64)

private fun isP256X963PublicKey(value: String): Boolean {
    val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return false
    return decoded.size == 65 &&
        decoded.firstOrNull() == 0x04.toByte() &&
        Base64.getEncoder().encodeToString(decoded) == value
}

private fun isUUID(value: String): Boolean =
    runCatching { UUID.fromString(value) }.getOrNull()
        ?.toString()
        ?.equals(value, ignoreCase = true) == true

private fun isBase64Url(value: String, minimumDecodedBytes: Int, maximumDecodedBytes: Int): Boolean {
    if (!BASE64_URL.matches(value)) return false
    val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: return false
    return decoded.size in minimumDecodedBytes..maximumDecodedBytes
}

private fun isStandardBase64(value: String, minimumDecodedBytes: Int, maximumDecodedBytes: Int): Boolean {
    if (value.isBlank() || value.any(Char::isWhitespace)) return false
    val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return false
    return decoded.size in minimumDecodedBytes..maximumDecodedBytes
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val HTTP_METHOD = Regex("[A-Z]{3,12}")
private val BASE64_URL = Regex("[A-Za-z0-9_-]{22,128}")
