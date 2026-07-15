package com.soma.app

import android.content.Context
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.TranscriptionFallbackReason
import com.soma.whisper.LocalWhisperModel
import java.time.Instant

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
    private const val KEY_LIGHT_GEAR = "light_sdk_settings_gear"
    private const val KEY_CLOUD_TRANSCRIPTION = "cloud_transcription_enabled"
    private const val KEY_CLOUD_PROVIDER = "cloud_speech_provider"
    private const val KEY_GROQ_SPEECH_MODEL = "groq_speech_model"
    private const val KEY_LAST_CLOUD_ERROR_REASON = "last_cloud_error_reason"
    private const val KEY_LAST_CLOUD_ERROR_AT = "last_cloud_error_at"
    private const val KEY_CLOUD_WIFI_ONLY = "cloud_wifi_only"
    private const val KEY_AI_TODOS = "cloud_ai_todo_suggestions"
    private const val KEY_AI_AUTO_METADATA = "cloud_ai_auto_metadata"
    private const val KEY_AI_TRACKING = "cloud_ai_tracking_suggestions"
    private const val KEY_LOCAL_AUTO_METADATA = "local_auto_metadata"
    private const val KEY_LOCAL_WHISPER_MODEL = "local_whisper_model"
    private const val KEY_LOCAL_METADATA_BACKFILL_VERSION = "local_metadata_backfill_version"
    private const val KEY_LOCAL_METADATA_BACKFILL_CURSOR = "local_metadata_backfill_cursor"

    private fun values(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * One quiet inline hint per hidden gesture, each retiring forever the first
     * time its gesture is actually used. Never a popup, never repeated.
     */
    enum class GestureHint { VOICE, PHOTO, CALENDAR }

    fun nextGestureHint(context: Context): GestureHint? =
        GestureHint.entries.firstOrNull { hint ->
            !values(context).getBoolean(gestureHintKey(hint), false)
        }

    fun markGestureHintUsed(context: Context, hint: GestureHint) {
        values(context).edit().putBoolean(gestureHintKey(hint), true).apply()
    }

    private fun gestureHintKey(hint: GestureHint) = "gesture_hint_used_" + hint.name.lowercase()

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

    /** Paka's exact LightOS-style settings asset; the drawn fallback remains a developer option. */
    fun lightGear(context: Context): Boolean = values(context).getBoolean(KEY_LIGHT_GEAR, true)
    fun setLightGear(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_LIGHT_GEAR, enabled).apply()

    fun cloudTranscription(context: Context): Boolean =
        values(context).getBoolean(KEY_CLOUD_TRANSCRIPTION, false)

    fun setCloudTranscription(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_CLOUD_TRANSCRIPTION, enabled).apply()

    fun cloudSpeechProvider(context: Context): CloudSpeechProvider = runCatching {
        CloudSpeechProvider.valueOf(
            values(context).getString(KEY_CLOUD_PROVIDER, CloudSpeechProvider.GROQ.name)
                ?: CloudSpeechProvider.GROQ.name,
        )
    }.getOrDefault(CloudSpeechProvider.GROQ)

    fun setCloudSpeechProvider(context: Context, provider: CloudSpeechProvider) =
        values(context).edit().putString(KEY_CLOUD_PROVIDER, provider.name).apply()

    fun groqSpeechModel(context: Context): GroqSpeechModel = runCatching {
        GroqSpeechModel.valueOf(
            values(context).getString(KEY_GROQ_SPEECH_MODEL, GroqSpeechModel.TURBO.name)
                ?: GroqSpeechModel.TURBO.name,
        )
    }.getOrDefault(GroqSpeechModel.TURBO)

    fun setGroqSpeechModel(context: Context, model: GroqSpeechModel) =
        values(context).edit().putString(KEY_GROQ_SPEECH_MODEL, model.name).apply()

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

    fun aiAutoMetadata(context: Context): Boolean = values(context).getBoolean(KEY_AI_AUTO_METADATA, false)

    fun setAiAutoMetadata(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_AI_AUTO_METADATA, enabled).apply()

    /** On by default: local metadata never leaves the device and costs nothing. */
    fun localAutoMetadata(context: Context): Boolean = values(context).getBoolean(KEY_LOCAL_AUTO_METADATA, true)

    fun setLocalAutoMetadata(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_LOCAL_AUTO_METADATA, enabled).apply()

    fun localMetadataBackfillVersion(context: Context): Int =
        values(context).getInt(KEY_LOCAL_METADATA_BACKFILL_VERSION, 0)

    fun localMetadataBackfillCursor(context: Context): Long? = values(context).let { preferences ->
        preferences.getLong(KEY_LOCAL_METADATA_BACKFILL_CURSOR, Long.MIN_VALUE)
            .takeUnless { it == Long.MIN_VALUE }
    }

    fun setLocalMetadataBackfillCursor(context: Context, epochDay: Long) =
        values(context).edit().putLong(KEY_LOCAL_METADATA_BACKFILL_CURSOR, epochDay).apply()

    fun completeLocalMetadataBackfill(context: Context, version: Int) =
        values(context).edit()
            .putInt(KEY_LOCAL_METADATA_BACKFILL_VERSION, version)
            .remove(KEY_LOCAL_METADATA_BACKFILL_CURSOR)
            .apply()

    fun resetLocalMetadataBackfill(context: Context) =
        values(context).edit()
            .putInt(KEY_LOCAL_METADATA_BACKFILL_VERSION, 0)
            .remove(KEY_LOCAL_METADATA_BACKFILL_CURSOR)
            .apply()

    /**
     * The user's local engine choice. Tiny is the default; a stored name that
     * no longer parses (downgrade) also falls back to tiny rather than failing.
     */
    fun localWhisperModel(context: Context): LocalWhisperModel = runCatching {
        LocalWhisperModel.valueOf(
            values(context).getString(KEY_LOCAL_WHISPER_MODEL, LocalWhisperModel.TINY.name)
                ?: LocalWhisperModel.TINY.name,
        )
    }.getOrDefault(LocalWhisperModel.TINY)

    fun setLocalWhisperModel(context: Context, model: LocalWhisperModel) =
        values(context).edit().putString(KEY_LOCAL_WHISPER_MODEL, model.name).apply()

    fun aiTrackingSuggestions(context: Context): Boolean = values(context).getBoolean(KEY_AI_TRACKING, false)

    fun setAiTrackingSuggestions(context: Context, enabled: Boolean) =
        values(context).edit().putBoolean(KEY_AI_TRACKING, enabled).apply()

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

    /**
     * Most recent cloud failure category for the Developer diagnostics row.
     * Only the safe category and time are stored — never provider responses,
     * note text, or key material.
     */
    fun lastCloudError(context: Context): LastCloudError? {
        val stored = values(context).getString(KEY_LAST_CLOUD_ERROR_REASON, null) ?: return null
        val reason = runCatching { TranscriptionFallbackReason.valueOf(stored) }.getOrNull()
            ?: return null
        val at = values(context).getLong(KEY_LAST_CLOUD_ERROR_AT, 0L)
        if (at <= 0L) return null
        return LastCloudError(reason, Instant.ofEpochMilli(at))
    }

    fun setLastCloudError(context: Context, reason: TranscriptionFallbackReason, at: Instant) =
        values(context).edit()
            .putString(KEY_LAST_CLOUD_ERROR_REASON, reason.name)
            .putLong(KEY_LAST_CLOUD_ERROR_AT, at.toEpochMilli())
            .apply()

    fun clearLastCloudError(context: Context) =
        values(context).edit()
            .remove(KEY_LAST_CLOUD_ERROR_REASON)
            .remove(KEY_LAST_CLOUD_ERROR_AT)
            .apply()
}
