package com.soma.app

import android.content.Context
import com.soma.whisper.Transcriber

enum class CloudSpeechProvider {
    GROQ,
    ELEVENLABS,
}

data class CloudDeveloperSettings(
    val available: Boolean,
    val transcriptionEnabled: Boolean,
    val provider: CloudSpeechProvider,
    val wifiOnly: Boolean,
    val aiTodoSuggestions: Boolean,
    val hasGroqKey: Boolean,
    val hasElevenLabsKey: Boolean,
)

/**
 * Flavor boundary for all outbound behavior. Browser and purist APKs only compile
 * the offline implementation; the experimental cloud APK compiles the HTTPS code.
 */
interface CloudFeatureController {
    fun settings(): CloudDeveloperSettings

    fun setTranscriptionEnabled(enabled: Boolean)

    fun setProvider(provider: CloudSpeechProvider)

    fun setWifiOnly(enabled: Boolean)

    fun setAiTodoSuggestions(enabled: Boolean)

    /** Empty text deletes the provider key. Keys are Keystore-encrypted at rest. */
    fun setApiKey(provider: CloudSpeechProvider, value: CharArray)

    fun createTranscriber(localFactory: () -> Transcriber): Transcriber

    /** Suggestions only. Callers still require an explicit user tap before creating a todo. */
    suspend fun extractTodoCandidates(text: String): List<String>
}

internal fun cloudFeatures(context: Context): CloudFeatureController = createCloudFeatureController(context)
