package com.soma.app

import com.soma.core.model.SupportedLanguage
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionFallbackReason
import com.soma.core.model.TranscriptionProvenance
import com.soma.whisper.TranscriptionResult
import com.soma.whisper.Transcriber
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudFailureListenerTest {
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
    fun `fallbacks report their reason to the failure listener`() {
        val reasons = mutableListOf<TranscriptionFallbackReason>()

        val missingKey = FallbackCloudTranscriber(
            provider = CloudSpeechProvider.GROQ,
            apiKey = null,
            wifiOnly = false,
            languagePolicy = CloudSpeechLanguagePolicy.from(emptySet(), SupportedLanguage.ENGLISH),
            vocabulary = emptyList(),
            networkStatus = { true },
            connectionOpener = { error("No connection expected") },
            localFactory = { RecordingLocalTranscriber() },
            failureListener = reasons::add,
        )
        runBlocking { missingKey.transcribe(FloatArray(160), 16_000) }
        missingKey.close()

        val wifiBlocked = FallbackCloudTranscriber(
            provider = CloudSpeechProvider.GROQ,
            apiKey = "key",
            wifiOnly = true,
            languagePolicy = CloudSpeechLanguagePolicy.from(emptySet(), SupportedLanguage.ENGLISH),
            vocabulary = emptyList(),
            networkStatus = { false },
            connectionOpener = { error("No connection expected") },
            localFactory = { RecordingLocalTranscriber() },
            failureListener = reasons::add,
        )
        runBlocking { wifiBlocked.transcribe(FloatArray(160), 16_000) }
        wifiBlocked.close()

        assertEquals(
            listOf(
                TranscriptionFallbackReason.API_KEY_MISSING,
                TranscriptionFallbackReason.WIFI_REQUIRED,
            ),
            reasons,
        )
    }
}
