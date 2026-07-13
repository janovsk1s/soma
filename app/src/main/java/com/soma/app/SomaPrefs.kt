package com.soma.app

import android.content.Context
import com.soma.core.model.SupportedLanguage

object SomaPrefs {
    private const val FILE = "soma_preferences"
    private const val KEY_VIBRATION = "vibration_enabled"
    private const val KEY_TRANSCRIPTION = "transcription_enabled"
    private const val KEY_REMINDER = "daily_reminder_enabled"
    private const val KEY_RETURN_HOME = "return_home_on_leave"
    private const val KEY_BATTERY_SAVER = "transcribe_in_battery_saver"
    private const val KEY_DEMO_MODE = "demo_mode"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_SPEECH_LANGUAGES = "speech_languages"
    private const val KEY_LIGHT_MODE = "light_mode"
    private const val KEY_CLOUD_TRANSCRIPTION = "cloud_transcription_enabled"
    private const val KEY_CLOUD_PROVIDER = "cloud_speech_provider"
    private const val KEY_CLOUD_WIFI_ONLY = "cloud_wifi_only"
    private const val KEY_AI_TODOS = "cloud_ai_todo_suggestions"

    private fun values(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun vibration(context: Context): Boolean = values(context).getBoolean(KEY_VIBRATION, true)
    fun setVibration(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_VIBRATION, enabled).apply()

    fun transcription(context: Context): Boolean = values(context).getBoolean(KEY_TRANSCRIPTION, true)
    fun setTranscription(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_TRANSCRIPTION, enabled).apply()

    fun reminder(context: Context): Boolean = values(context).getBoolean(KEY_REMINDER, false)
    fun setReminder(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_REMINDER, enabled).apply()

    fun returnHome(context: Context): Boolean = values(context).getBoolean(KEY_RETURN_HOME, true)
    fun setReturnHome(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_RETURN_HOME, enabled).apply()

    fun transcribeInBatterySaver(context: Context): Boolean =
        values(context).getBoolean(KEY_BATTERY_SAVER, false)

    fun setTranscribeInBatterySaver(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_BATTERY_SAVER, enabled).apply()

    fun demoMode(context: Context): Boolean = values(context).getBoolean(KEY_DEMO_MODE, false)
    fun setDemoMode(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_DEMO_MODE, enabled).apply()

    fun lightMode(context: Context): Boolean = values(context).getBoolean(KEY_LIGHT_MODE, false)
    fun setLightMode(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_LIGHT_MODE, enabled).apply()

    fun cloudTranscription(context: Context): Boolean =
        values(context).getBoolean(KEY_CLOUD_TRANSCRIPTION, false)

    fun setCloudTranscription(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_CLOUD_TRANSCRIPTION, enabled).apply()

    fun cloudSpeechProvider(context: Context): CloudSpeechProvider = runCatching {
        CloudSpeechProvider.valueOf(
            values(context).getString(KEY_CLOUD_PROVIDER, CloudSpeechProvider.ELEVENLABS.name)
                ?: CloudSpeechProvider.ELEVENLABS.name,
        )
    }.getOrDefault(CloudSpeechProvider.ELEVENLABS)

    fun setCloudSpeechProvider(context: Context, provider: CloudSpeechProvider) =
        values(context).edit().putString(KEY_CLOUD_PROVIDER, provider.name).apply()

    /**
     * Cloud features may use the phone's active internet connection by default,
     * including cellular. Users who prefer to avoid mobile-data use can opt into
     * the Wi-Fi-only restriction in Developer settings.
     */
    fun cloudWifiOnly(context: Context): Boolean = values(context).getBoolean(KEY_CLOUD_WIFI_ONLY, false)

    fun setCloudWifiOnly(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_CLOUD_WIFI_ONLY, enabled).apply()

    fun aiTodoSuggestions(context: Context): Boolean = values(context).getBoolean(KEY_AI_TODOS, false)

    fun setAiTodoSuggestions(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_AI_TODOS, enabled).apply()

    fun language(context: Context): SupportedLanguage {
        val stored = values(context).getString(KEY_LANGUAGE, null)
        if (stored != null) return SupportedLanguage.fromLanguageTag(stored) ?: SupportedLanguage.ENGLISH
        val deviceTag = context.resources.configuration.locales.get(0)?.toLanguageTag().orEmpty()
        return SupportedLanguage.fromLanguageTag(deviceTag) ?: SupportedLanguage.ENGLISH
    }

    /** Languages the user actually speaks; transcription only detects among these. */
    fun speechLanguages(context: Context): Set<SupportedLanguage> {
        val stored = values(context).getString(KEY_SPEECH_LANGUAGES, null)
            ?: return SupportedLanguage.entries.toSet()
        val parsed = stored.split(',')
            .mapNotNull(SupportedLanguage::fromLanguageTag)
            .toSet()
        return parsed.ifEmpty { SupportedLanguage.entries.toSet() }
    }

    fun setSpeechLanguages(context: Context, languages: Set<SupportedLanguage>) {
        val stored = languages
            .ifEmpty { SupportedLanguage.entries.toSet() }
            .sortedBy(SupportedLanguage::ordinal)
            .joinToString(",") { it.languageTag }
        values(context).edit().putString(KEY_SPEECH_LANGUAGES, stored).apply()
    }

    fun setLanguage(context: Context, language: SupportedLanguage) =
        values(context).edit().putString(KEY_LANGUAGE, language.languageTag).apply()
}
