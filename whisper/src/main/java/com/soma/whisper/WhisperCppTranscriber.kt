package com.soma.whisper

import android.content.Context
import android.os.Process
import com.soma.core.model.TranscriptionVocabulary
import com.soma.core.model.TranscriptionProvenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WhisperModelException(cause: Throwable? = null) : IllegalStateException(
    "The bundled Whisper model could not be loaded",
    cause,
)

class WhisperCppTranscriber(
    context: Context,
    private val vad: VoiceActivityDetector = VoiceActivityDetector(),
    private val profile: LocalTranscriptionProfile = LocalTranscriptionProfile.forDevice(context),
    /** The user's spoken languages; identification never picks outside this set. */
    private val allowedLanguages: Array<String> = ALL_SUPPORTED_LANGUAGES,
    /** Wins ambiguous chunks unless another allowed language clearly outscores it. */
    private val preferredLanguage: String? = null,
    private val vocabulary: List<String> = emptyList(),
) : Transcriber {
    private val assets = context.applicationContext.assets
    private val mutex = Mutex()
    private var nativeContext = 0L

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionResult =
        mutex.withLock {
            withContext(Dispatchers.Default) {
                require(sampleRate == SAMPLE_RATE) { "Whisper requires 16 kHz PCM" }
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val chunks = vad.split(samples, sampleRate)
                if (chunks.isEmpty()) {
                    return@withContext TranscriptionResult(
                        text = "",
                        chunks = emptyList(),
                        provenance = TranscriptionProvenance.local(),
                    )
                }
                val results = try {
                    val pointer = contextPointer()
                    chunks.map { chunk ->
                        val native = WhisperNative.transcribe(
                            pointer,
                            chunk.samples,
                            profile.threadCount,
                            profile.beamSize,
                            profile.greedyBestOf,
                            allowedLanguages,
                            preferredLanguage,
                            TranscriptionVocabulary.asWhisperPrompt(vocabulary),
                        )
                        check(native.size == 2) { "Unexpected Whisper response" }
                        TranscribedChunk(
                            text = cleanWhisperTranscript(native[1]),
                            languageCode = native[0],
                            startMillis = chunk.startMillis,
                            endMillis = chunk.endMillis,
                        )
                    }
                } finally {
                    chunks.forEach { it.samples.fill(0f) }
                }
                // tiny Q5 is intentionally small and offline. Dense mid-sentence
                // code-switching can still be imperfect; transcripts stay editable.
                TranscriptionResult(
                    text = results.map(TranscribedChunk::text).filter(String::isNotBlank).joinToString("\n"),
                    chunks = results,
                    provenance = TranscriptionProvenance.local(),
                )
            }
        }

    private fun contextPointer(): Long {
        if (nativeContext == 0L) {
            nativeContext = try {
                WhisperNative.createContext(assets, MODEL_ASSET)
            } catch (error: UnsatisfiedLinkError) {
                throw error
            } catch (error: Throwable) {
                throw WhisperModelException(error)
            }
            if (nativeContext == 0L) throw WhisperModelException()
        }
        return nativeContext
    }

    override fun close() {
        val pointer = nativeContext
        nativeContext = 0L
        if (pointer != 0L) WhisperNative.freeContext(pointer)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MODEL_ASSET = "ggml-tiny-q5_1.bin"

        /** Fallback when no user selection exists: the full supported set. */
        val ALL_SUPPORTED_LANGUAGES: Array<String> =
            com.soma.core.model.SupportedLanguage.entries.map { it.languageTag }.toTypedArray()

    }
}

internal fun cleanWhisperTranscript(text: String): String =
    text.replace(BLANK_AUDIO_MARKER, " ", ignoreCase = true)
        .replace(REPEATED_INLINE_WHITESPACE, " ")
        .trim()

private const val BLANK_AUDIO_MARKER = "[BLANK_AUDIO]"
private val REPEATED_INLINE_WHITESPACE = Regex("[ \\t]{2,}")
