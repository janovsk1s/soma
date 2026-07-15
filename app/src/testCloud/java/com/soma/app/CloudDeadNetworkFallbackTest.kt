package com.soma.app

import com.soma.core.model.SupportedLanguage
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionFallbackReason
import com.soma.core.model.TranscriptionProvenance
import com.soma.whisper.Transcriber
import com.soma.whisper.TranscriptionResult
import java.net.UnknownHostException
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudDeadNetworkFallbackTest {
    private class RecordingLocalTranscriber : Transcriber {
        override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionResult =
            TranscriptionResult(
                text = "local",
                chunks = emptyList(),
                provenance = TranscriptionProvenance(
                    requestedEngine = TranscriptionEngine.LOCAL_WHISPER_TINY,
                    usedEngine = TranscriptionEngine.LOCAL_WHISPER_TINY,
                ),
            )

        override fun close() = Unit
    }

    @Test
    fun `a dead network falls back to local whisper as a network error`() {
        val reasons = mutableListOf<TranscriptionFallbackReason>()
        val transcriber = FallbackCloudTranscriber(
            provider = CloudSpeechProvider.GROQ,
            apiKey = "key",
            wifiOnly = false,
            languagePolicy = CloudSpeechLanguagePolicy.from(emptySet(), SupportedLanguage.ENGLISH),
            vocabulary = emptyList(),
            networkStatus = { false },
            connectionOpener = { throw UnknownHostException("api.groq.com") },
            localFactory = { RecordingLocalTranscriber() },
            failureListener = reasons::add,
        )

        val result = runBlocking { transcriber.transcribe(speechThenSilence(), 16_000) }
        transcriber.close()

        assertEquals("local", result.text)
        assertEquals(TranscriptionEngine.GROQ_WHISPER_LARGE_V3_TURBO, result.provenance.requestedEngine)
        assertEquals(TranscriptionEngine.LOCAL_WHISPER_TINY, result.provenance.usedEngine)
        assertEquals(TranscriptionFallbackReason.NETWORK_ERROR, result.provenance.fallbackReason)
        assertEquals(listOf(TranscriptionFallbackReason.NETWORK_ERROR), reasons)
    }

    /** One second of tone and 0.8 s of silence, so the VAD emits a real speech chunk. */
    private fun speechThenSilence(): FloatArray = FloatArray(28_800) { index ->
        if (index < 16_000) (0.5 * sin(2.0 * Math.PI * 440.0 * index / 16_000.0)).toFloat() else 0f
    }
}
