package com.soma.core.bridge

import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SomaBridgeProtocolTest {
    @Test
    fun `pairing uri matches the cross-platform v1 fixture`() {
        val invite = fixtureInvite()
        val expected =
            "soma://pair?v=1" +
                "&bridge=123e4567-e89b-12d3-a456-426614174000" +
                "&host=192.168.1.42" +
                "&port=44321" +
                "&secret=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8" +
                "&fingerprint=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" +
                "&expires=2000000000"

        assertEquals(expected, BridgePairingInviteUri.encode(invite))
        assertEquals(invite, BridgePairingInviteUri.decode(expected, Instant.ofEpochSecond(1_900_000_000)))
    }

    @Test
    fun `expired or duplicate-field pairing uri is rejected`() {
        val encoded = BridgePairingInviteUri.encode(fixtureInvite())

        assertThrows(IllegalArgumentException::class.java) {
            BridgePairingInviteUri.decode(encoded, Instant.ofEpochSecond(2_000_000_001))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgePairingInviteUri.decode("$encoded&port=44322", Instant.ofEpochSecond(1_900_000_000))
        }
    }

    @Test
    fun `pair body uses stable json and exact capability wire names`() {
        val x963 = ByteArray(65) { index -> if (index == 0) 0x04 else index.toByte() }
        val request = BridgePairRequest(
            deviceID = "A9FC857C-7AE0-41EF-B5D2-20C0A04F743B",
            deviceName = "Paka's Pixel Lab",
            platform = BridgeClientPlatform.ANDROID,
            publicKey = Base64.getEncoder().encodeToString(x963),
            requestedCapabilities = setOf(
                BridgeCapability.SYNC_WRITE,
                BridgeCapability.CONTEXT_READ,
                BridgeCapability.CODEX_TURN,
            ),
        )

        assertEquals(
            """{"protocolVersion":1,"deviceID":"A9FC857C-7AE0-41EF-B5D2-20C0A04F743B","deviceName":"Paka's Pixel Lab","platform":"android","publicKey":"${request.publicKey}","requestedCapabilities":["codex.turn","context.read","sync.write"]}""",
            request.toJson(),
        )
    }

    @Test
    fun `pair request rejects byte-overlong names and noncanonical public keys`() {
        val x963 = ByteArray(65) { index -> if (index == 0) 0x04 else index.toByte() }
        val publicKey = Base64.getEncoder().encodeToString(x963)

        assertThrows(IllegalArgumentException::class.java) {
            BridgePairRequest(
                deviceID = "A9FC857C-7AE0-41EF-B5D2-20C0A04F743B",
                deviceName = "é".repeat(41),
                platform = BridgeClientPlatform.ANDROID,
                publicKey = publicKey,
                requestedCapabilities = setOf(BridgeCapability.CONTEXT_READ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgePairRequest(
                deviceID = "A9FC857C-7AE0-41EF-B5D2-20C0A04F743B",
                deviceName = "Pixel",
                platform = BridgeClientPlatform.ANDROID,
                publicKey = publicKey.replace("=", ""),
                requestedCapabilities = setOf(BridgeCapability.CONTEXT_READ),
            )
        }
    }

    @Test
    fun `signed request canonical input matches the shared golden fixture`() {
        val body = """{"prompt":"connect today"}""".encodeToByteArray()
        val input = BridgeRequestSigningInput.forBody(
            bridgeID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            certificateFingerprint =
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            method = "POST",
            path = "/v1/codex/turns",
            body = body,
            deviceID = "A9FC857C-7AE0-41EF-B5D2-20C0A04F743B",
            sequence = 42,
            timestampMs = 1_900_000_000_123,
            nonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX",
        )

        assertEquals(
            "SOMA-BRIDGE-REQUEST\n" +
                "1\n" +
                "123E4567-E89B-12D3-A456-426614174000\n" +
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n" +
                "POST\n" +
                "/v1/codex/turns\n" +
                "8fee83f6583f118f31936ac43d0d1d3c263fc885de21d5bd6f8842a3873ac771\n" +
                "A9FC857C-7AE0-41EF-B5D2-20C0A04F743B\n" +
                "42\n" +
                "1900000000123\n" +
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX",
            input.canonicalString(),
        )
        assertTrue(input.canonicalBytes().contentEquals(input.canonicalString().encodeToByteArray()))
    }

    @Test
    fun `pairing record cannot persist a secret and sequences are bounded`() {
        val record = BridgePairingRecord(
            bridge = fixtureInvite().bridge,
            host = fixtureInvite().host,
            port = fixtureInvite().port,
            fingerprint = fixtureInvite().fingerprint,
            deviceID = "A9FC857C-7AE0-41EF-B5D2-20C0A04F743B",
            grantedCapabilities = setOf(BridgeCapability.CONTEXT_READ),
            pairedAtEpochMillis = 1_900_000_000_000,
        )

        assertEquals(0, record.lastSequence)
        assertTrue(BridgePairingRecord::class.java.declaredFields.none { it.name == "secret" })
    }

    @Test
    fun `pairing invite rejects DNS and public addresses`() {
        for (host in listOf("attacker.example", "10.0.0.1.attacker.example", "8.8.8.8")) {
            assertThrows(IllegalArgumentException::class.java) {
                fixtureInvite().copy(host = host)
            }
        }
        assertEquals("127.0.0.1", fixtureInvite().copy(host = "127.0.0.1").host)
        assertEquals("100.101.102.103", fixtureInvite().copy(host = "100.101.102.103").host)
        for (host in listOf("100.63.255.255", "100.128.0.0")) {
            assertThrows(IllegalArgumentException::class.java) {
                fixtureInvite().copy(host = host)
            }
        }
    }

    private fun fixtureInvite() = BridgePairingInvite(
        protocolVersion = 1,
        bridge = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        host = "192.168.1.42",
        port = 44_321,
        secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        expires = 2_000_000_000,
    )
}
