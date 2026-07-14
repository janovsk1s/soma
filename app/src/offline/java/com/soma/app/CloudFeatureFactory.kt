package com.soma.app

import android.content.Context
import com.soma.whisper.Transcriber

internal fun createCloudFeatureController(context: Context): CloudFeatureController =
    OfflineCloudFeatureController(context.applicationContext)

private class OfflineCloudFeatureController(private val context: Context) : CloudFeatureController {
    override fun settings(): CloudDeveloperSettings = CloudDeveloperSettings(
        available = false,
        transcriptionEnabled = false,
        provider = SomaPrefs.cloudSpeechProvider(context),
        wifiOnly = SomaPrefs.cloudWifiOnly(context),
        aiTodoSuggestions = false,
        aiAutoMetadata = false,
        hasGroqKey = false,
        hasElevenLabsKey = false,
    )

    override fun setTranscriptionEnabled(enabled: Boolean) = Unit

    override fun setProvider(provider: CloudSpeechProvider) = Unit

    override fun setWifiOnly(enabled: Boolean) = Unit

    override fun setAiTodoSuggestions(enabled: Boolean) = Unit

    override fun setAiAutoMetadata(enabled: Boolean) = Unit

    override fun setApiKey(provider: CloudSpeechProvider, value: CharArray) = value.fill('\u0000')

    override fun createTranscriber(localFactory: () -> Transcriber): Transcriber = localFactory()

    override suspend fun extractTodoCandidates(text: String): List<String> = emptyList()

    override suspend fun deriveEntryMetadata(
        text: String,
        languages: Set<com.soma.core.model.SupportedLanguage>,
    ): CloudMetadataResult? = null
}
