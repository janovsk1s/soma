package com.soma.app

import com.soma.core.model.TranscriptionFallbackReason
import com.soma.core.model.TranscriptionProvenance
import com.soma.whisper.Transcriber
import com.soma.whisper.TranscriptionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the network gating that preview 12 changed. Both call sites
 * (`FallbackCloudTranscriber` and `extractTodoCandidates`) share the pure
 * [cloudNetworkAllowed] decision, and the transcriber's Wi-Fi gate is exercised
 * through an injected [NetworkStatus] so no real request is attempted.
 */
class CloudNetworkGatingTest {

    @Test
    fun `cellular is allowed unless wifi only is enabled`() {
        // Preview-12 default: Wi-Fi only off, so cellular (not on Wi-Fi) is allowed.
        assertTrue(cloudNetworkAllowed(wifiOnly = false, onWifi = false))
        assertTrue(cloudNetworkAllowed(wifiOnly = false, onWifi = true))
        assertTrue(cloudNetworkAllowed(wifiOnly = true, onWifi = true))
        // The only case that blocks a request: Wi-Fi only on and no Wi-Fi.
        assertFalse(cloudNetworkAllowed(wifiOnly = true, onWifi = false))
        assertEquals(CloudConnectionRoute.DEFAULT, cloudConnectionRoute(wifiOnly = false, onWifi = false))
        assertEquals(CloudConnectionRoute.WIFI, cloudConnectionRoute(wifiOnly = true, onWifi = true))
        assertEquals(CloudConnectionRoute.BLOCKED, cloudConnectionRoute(wifiOnly = true, onWifi = false))
    }

    @Test
    fun `wifi only falls back to local whisper when not on wifi`() {
        val local = CountingLocalTranscriber()
        val transcriber = FallbackCloudTranscriber(
            provider = CloudSpeechProvider.GROQ,
            apiKey = "present-key",
            wifiOnly = true,
            languagePolicy = anyLanguagePolicy(),
            vocabulary = emptyList(),
            networkStatus = NetworkStatus { false },
            connectionOpener = CloudConnectionOpener { error("Cloud connection must stay blocked") },
            localFactory = { local },
        )

        val result = runBlocking { transcriber.transcribe(FloatArray(16), SAMPLE_RATE) }

        assertEquals(TranscriptionFallbackReason.WIFI_REQUIRED, result.provenance.fallbackReason)
        assertEquals(LOCAL_TEXT, result.text)
        assertEquals(1, local.calls)
    }

    @Test
    fun `a missing key falls back to local whisper before any network use`() {
        val local = CountingLocalTranscriber()
        val transcriber = FallbackCloudTranscriber(
            provider = CloudSpeechProvider.ELEVENLABS,
            apiKey = null,
            wifiOnly = false,
            languagePolicy = anyLanguagePolicy(),
            vocabulary = emptyList(),
            networkStatus = NetworkStatus { true },
            connectionOpener = CloudConnectionOpener { error("Missing key must not open a connection") },
            localFactory = { local },
        )

        val result = runBlocking { transcriber.transcribe(FloatArray(16), SAMPLE_RATE) }

        assertEquals(TranscriptionFallbackReason.API_KEY_MISSING, result.provenance.fallbackReason)
        assertEquals(1, local.calls)
    }

    private fun anyLanguagePolicy() = CloudSpeechLanguagePolicy(
        allowed = setOf("en"),
        preferred = "en",
        forced = "en",
    )

    private class CountingLocalTranscriber : Transcriber {
        var calls = 0

        override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionResult {
            calls++
            return TranscriptionResult(
                text = LOCAL_TEXT,
                chunks = emptyList(),
                provenance = TranscriptionProvenance.local(),
            )
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val LOCAL_TEXT = "local transcript"
    }
}
