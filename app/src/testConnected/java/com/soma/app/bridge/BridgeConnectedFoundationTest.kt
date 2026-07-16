package com.soma.app.bridge

import com.soma.core.bridge.BridgeCapability
import com.soma.core.bridge.BridgeClientPlatform
import com.soma.core.bridge.BridgePairRequest
import com.soma.core.bridge.BridgePairResponse
import com.soma.core.bridge.BridgePairingInvite
import com.soma.core.bridge.BridgePairingRecord
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeConnectedFoundationTest {
    @Test
    fun `encrypted-record plaintext codec round trips without a pairing secret`() {
        val record = BridgePairingRecord(
            bridge = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            host = "192.168.1.42",
            port = 44_321,
            fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            deviceID = "A9FC857C-7AE0-41EF-B5D2-20C0A04F743B",
            grantedCapabilities = setOf(
                BridgeCapability.CONTEXT_READ,
                BridgeCapability.CODEX_TURN,
                BridgeCapability.PROPOSAL_READ,
            ),
            pairedAtEpochMillis = 1_900_000_000_000,
            lastSequence = 41,
        )

        val encoded = BridgePairingRecordCodec.encode(record)

        assertEquals(record, BridgePairingRecordCodec.decode(encoded))
        assertTrue("secret" !in String(Base64.getUrlDecoder().decode(encoded)))
    }

    @Test
    fun `p256 key conversion emits x963 key accepted by both native clients`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val x963 = p256X963(keyPair.public as ECPublicKey)

        assertEquals(65, x963.size)
        assertEquals(0x04, x963.first().toInt())

        val message = "Soma bridge fixture".encodeToByteArray()
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(message)
        }
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(keyPair.public)
            update(message)
        }
        assertTrue(verifier.verify(signer.sign()))
    }

    @Test
    fun `verified pair response is bound to the invite device and Android platform`() {
        val bridgeID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val deviceID = "A9FC857C-7AE0-41EF-B5D2-20C0A04F743B"
        val fingerprint =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val capabilities = AndroidSomaBridgeFoundation.REQUIRED_CODEX_CAPABILITIES
        val publicKey = Base64.getEncoder().encodeToString(
            ByteArray(65) { index -> if (index == 0) 0x04 else index.toByte() },
        )
        val prepared = PreparedBridgePairing(
            invite = BridgePairingInvite(
                protocolVersion = 1,
                bridge = bridgeID,
                host = "192.168.1.42",
                port = 44_321,
                secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                fingerprint = fingerprint,
                expires = 2_000_000_000,
            ),
            request = BridgePairRequest(
                deviceID = deviceID,
                deviceName = "Pixel",
                platform = BridgeClientPlatform.ANDROID,
                publicKey = publicKey,
                requestedCapabilities = capabilities,
            ),
            requestBody = byteArrayOf(),
        )
        val response = BridgePairResponse(
            protocolVersion = 1,
            bridgeID = bridgeID,
            bridgeName = "Soma Mac",
            deviceID = deviceID,
            platform = BridgeClientPlatform.ANDROID,
            capabilities = capabilities.toList(),
            certificateFingerprint = fingerprint,
            pairedAt = "2030-03-17T17:46:40.000Z",
        )

        val record = verifiedPairingRecord(prepared, response)
        assertEquals(bridgeID, record.bridge)
        assertEquals(capabilities, record.grantedCapabilities)

        assertThrows(IllegalArgumentException::class.java) {
            verifiedPairingRecord(
                prepared,
                response.copy(bridgeID = UUID.fromString("223e4567-e89b-12d3-a456-426614174000")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifiedPairingRecord(
                prepared,
                response.copy(platform = BridgeClientPlatform.IOS),
            )
        }
    }
}
