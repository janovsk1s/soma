package com.soma.whisper

import com.soma.core.model.TranscriptionProvenance
import java.io.Closeable

data class TranscribedChunk(
    val text: String,
    val languageCode: String,
    val startMillis: Long,
    val endMillis: Long,
)

data class TranscriptionResult(
    val text: String,
    val chunks: List<TranscribedChunk>,
    val provenance: TranscriptionProvenance,
)

/** Swappable boundary around on-device speech recognition. */
fun interface Transcriber : Closeable {
    suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionResult

    override fun close() = Unit
}
