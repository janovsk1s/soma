package com.soma.app

import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionFallbackReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudTranscriptionProvenanceTest {
    @Test
    fun `ElevenLabs success records Scribe v2 as the engine that completed`() {
        val provenance = cloudSuccessProvenance(CloudSpeechProvider.ELEVENLABS)

        assertEquals(TranscriptionEngine.ELEVENLABS_SCRIBE_V2, provenance.requestedEngine)
        assertEquals(TranscriptionEngine.ELEVENLABS_SCRIBE_V2, provenance.usedEngine)
        assertNull(provenance.fallbackReason)
    }

    @Test
    fun `Groq provider error records local Whisper fallback`() {
        val provenance = cloudFallbackProvenance(
            CloudSpeechProvider.GROQ,
            TranscriptionFallbackReason.PROVIDER_ERROR,
        )

        assertEquals(TranscriptionEngine.GROQ_WHISPER_LARGE_V3, provenance.requestedEngine)
        assertEquals(TranscriptionEngine.LOCAL_WHISPER_TINY, provenance.usedEngine)
        assertEquals(TranscriptionFallbackReason.PROVIDER_ERROR, provenance.fallbackReason)
    }

    @Test
    fun `ElevenLabs error body preserves safe account failure categories`() {
        assertEquals(
            TranscriptionFallbackReason.AUTHENTICATION_ERROR,
            cloudFailureReason(401, """{"detail":{"code":"invalid_api_key"}}"""),
        )
        assertEquals(
            TranscriptionFallbackReason.PERMISSION_ERROR,
            cloudFailureReason(403, """{"detail":{"code":"insufficient_permissions"}}"""),
        )
        assertEquals(
            TranscriptionFallbackReason.PAYMENT_REQUIRED,
            cloudFailureReason(401, """{"detail":{"status":"quota_exceeded"}}"""),
        )
        assertEquals(
            TranscriptionFallbackReason.RATE_LIMITED,
            cloudFailureReason(429, """{"detail":{"code":"rate_limit_exceeded"}}"""),
        )
    }

    @Test
    fun `malformed provider error falls back to its HTTP category`() {
        assertEquals(
            TranscriptionFallbackReason.INVALID_REQUEST,
            cloudFailureReason(422, "not-json"),
        )
        assertEquals(
            TranscriptionFallbackReason.PROVIDER_ERROR,
            cloudFailureReason(503, ""),
        )
    }
}
