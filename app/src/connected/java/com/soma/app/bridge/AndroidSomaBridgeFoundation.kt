package com.soma.app.bridge

import android.content.Context
import com.soma.core.bridge.BridgeCapability
import com.soma.core.bridge.BridgePairRequest
import com.soma.core.bridge.BridgePairResponse
import com.soma.core.bridge.BridgePairingInvite
import com.soma.core.bridge.BridgePairingInviteUri
import com.soma.core.bridge.BridgePairingRecord
import com.soma.core.bridge.BridgeRequestSigningInput
import com.soma.core.bridge.BridgeSignedRequestHeaders
import java.time.Clock
import java.util.UUID

/**
 * Transport-independent Android bridge foundation.
 *
 * This prepares the exact bytes, pin, sequence, and signatures a future TLS
 * transport needs. It intentionally does not expose a generic HTTP client:
 * the transport must enforce TLS 1.3 and the QR-provided public-key pin before
 * calling [storeVerifiedPairing].
 */
class AndroidSomaBridgeFoundation(
    context: Context,
    private val identity: AndroidBridgeDeviceIdentity = AndroidBridgeDeviceIdentity(context),
    private val pairings: AndroidBridgePairingStore = AndroidBridgePairingStore(context),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun preparePairing(
        pairingUri: String,
        deviceName: String,
        requestedCapabilities: Set<BridgeCapability>,
    ): PreparedBridgePairing {
        val invite = BridgePairingInviteUri.decode(pairingUri, clock.instant())
        val request = identity.pairRequest(deviceName, requestedCapabilities)
        return PreparedBridgePairing(
            invite = invite,
            request = request,
            requestBody = request.toJson().encodeToByteArray(),
        )
    }

    /**
     * Persists only the authenticated result of a successful pinned-TLS pair
     * exchange. The one-use QR secret is structurally excluded from the record.
     */
    fun storeVerifiedPairing(
        prepared: PreparedBridgePairing,
        response: BridgePairResponse,
    ): BridgePairingRecord =
        verifiedPairingRecord(prepared, response).also(pairings::save)

    /**
     * Reserves a durable sequence and signs a capability-scoped request.
     * The caller receives the expected TLS pin alongside the prepared request.
     */
    fun prepareRequest(
        bridge: UUID,
        requiredCapability: BridgeCapability,
        method: String,
        path: String,
        body: ByteArray,
    ): PreparedBridgeRequest {
        val pairing = pairings.find(bridge)
            ?: throw BridgePairingStoreException("Soma bridge is not paired")
        require(requiredCapability in pairing.grantedCapabilities) {
            "Paired device has not been granted ${requiredCapability.wireName}"
        }
        require(identity.deviceID() == pairing.deviceID) {
            "Soma bridge identity changed; revoke this pairing and pair again"
        }
        val reserved = pairings.reserveNextSequence(bridge)
        val input = BridgeRequestSigningInput.forBody(
            bridgeID = reserved.bridge,
            certificateFingerprint = reserved.fingerprint,
            method = method,
            path = path,
            body = body,
            deviceID = reserved.deviceID,
            sequence = reserved.lastSequence,
            timestampMs = clock.millis(),
            nonce = identity.newNonce(),
        )
        return PreparedBridgeRequest(
            bridge = bridge,
            httpsAuthority = reserved.httpsAuthority,
            expectedServerFingerprint = reserved.fingerprint,
            minimumTlsVersion = "TLSv1.3",
            requiredCapability = requiredCapability,
            path = path,
            body = body.copyOf(),
            headers = identity.sign(input),
        )
    }

    fun pairings(): List<BridgePairingRecord> = pairings.list()

    fun revoke(bridge: UUID) = pairings.remove(bridge)

    companion object {
        val REQUIRED_CODEX_CAPABILITIES: Set<BridgeCapability> =
            REQUIRED_ANDROID_CODEX_CAPABILITIES
    }
}

internal fun verifiedPairingRecord(
    prepared: PreparedBridgePairing,
    response: BridgePairResponse,
): BridgePairingRecord {
    val grantedCapabilities = response.capabilities.toSet()
    require(response.protocolVersion == prepared.invite.protocolVersion) {
        "Bridge response protocol does not match the pairing invitation"
    }
    require(response.bridgeID == prepared.invite.bridge) {
        "Bridge response belongs to another Mac"
    }
    require(response.deviceID.equals(prepared.request.deviceID, ignoreCase = true)) {
        "Bridge response belongs to another device"
    }
    require(response.platform == prepared.request.platform) {
        "Bridge response platform does not match Android"
    }
    require(response.certificateFingerprint == prepared.invite.fingerprint) {
        "Bridge response certificate does not match the pinned Mac"
    }
    require(prepared.request.requestedCapabilities.containsAll(grantedCapabilities)) {
        "Bridge granted a capability the device did not request"
    }
    require(grantedCapabilities.containsAll(REQUIRED_ANDROID_CODEX_CAPABILITIES)) {
        "Bridge did not grant the required read-only Codex capabilities"
    }
    return BridgePairingRecord(
        bridge = prepared.invite.bridge,
        host = prepared.invite.host,
        port = prepared.invite.port,
        fingerprint = prepared.invite.fingerprint,
        deviceID = prepared.request.deviceID.uppercase(),
        grantedCapabilities = grantedCapabilities,
        pairedAtEpochMillis = java.time.Instant.parse(response.pairedAt).toEpochMilli(),
    )
}

private val REQUIRED_ANDROID_CODEX_CAPABILITIES = setOf(
    BridgeCapability.CONTEXT_READ,
    BridgeCapability.CODEX_THREAD,
    BridgeCapability.CODEX_TURN,
    BridgeCapability.CODEX_STREAM,
)

data class PreparedBridgePairing(
    val invite: BridgePairingInvite,
    val request: BridgePairRequest,
    val requestBody: ByteArray,
)

data class PreparedBridgeRequest(
    val bridge: UUID,
    val httpsAuthority: String,
    val expectedServerFingerprint: String,
    val minimumTlsVersion: String,
    val requiredCapability: BridgeCapability,
    val path: String,
    val body: ByteArray,
    val headers: BridgeSignedRequestHeaders,
)
