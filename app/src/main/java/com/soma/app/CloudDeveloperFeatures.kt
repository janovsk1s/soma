package com.soma.app

import android.content.Context
import com.soma.core.model.EntryLink
import com.soma.core.model.EntryLinkKind
import com.soma.core.model.SupportedLanguage
import com.soma.core.model.TranscriptionEngine
import com.soma.core.model.TranscriptionFallbackReason
import com.soma.core.model.TranscriptionProvenance
import com.soma.core.model.normalizeMetadataTag
import com.soma.whisper.Transcriber
import java.util.Locale

enum class CloudSpeechProvider {
    GROQ,
    ELEVENLABS,
}

internal val CloudSpeechProvider.transcriptionEngine: TranscriptionEngine
    get() = when (this) {
        CloudSpeechProvider.GROQ -> TranscriptionEngine.GROQ_WHISPER_LARGE_V3
        CloudSpeechProvider.ELEVENLABS -> TranscriptionEngine.ELEVENLABS_SCRIBE_V2
    }

/**
 * Scribe v2 can keep language context across pauses and code-switches inside a
 * file. Sending VAD fragments as separate files makes every short fragment a
 * fresh language-detection problem, so ElevenLabs receives the recording once.
 */
internal val CloudSpeechProvider.preservesRecordingContext: Boolean
    get() = this == CloudSpeechProvider.ELEVENLABS

internal fun cloudSuccessProvenance(provider: CloudSpeechProvider) = TranscriptionProvenance(
    requestedEngine = provider.transcriptionEngine,
    usedEngine = provider.transcriptionEngine,
)

internal fun cloudFallbackProvenance(
    provider: CloudSpeechProvider,
    reason: TranscriptionFallbackReason,
) = TranscriptionProvenance(
    requestedEngine = provider.transcriptionEngine,
    usedEngine = TranscriptionEngine.LOCAL_WHISPER_TINY,
    fallbackReason = reason,
)

/**
 * Reduces provider responses to a safe, user-facing category. The response body
 * is inspected only in memory and is never persisted or shown verbatim because
 * provider errors can contain request details.
 */
internal fun cloudFailureReason(status: Int, response: String): TranscriptionFallbackReason {
    val codes = CLOUD_ERROR_CODE_PATTERN.findAll(response)
        .map { match -> match.groupValues[1].lowercase(Locale.ROOT) }
        .toSet()
    return when {
        codes.any(CLOUD_PAYMENT_CODES::contains) -> TranscriptionFallbackReason.PAYMENT_REQUIRED
        codes.any(CLOUD_PERMISSION_CODES::contains) -> TranscriptionFallbackReason.PERMISSION_ERROR
        codes.any(CLOUD_AUTHENTICATION_CODES::contains) -> TranscriptionFallbackReason.AUTHENTICATION_ERROR
        codes.any(CLOUD_RATE_LIMIT_CODES::contains) -> TranscriptionFallbackReason.RATE_LIMITED
        codes.any(CLOUD_INVALID_REQUEST_CODES::contains) -> TranscriptionFallbackReason.INVALID_REQUEST
        status == 401 -> TranscriptionFallbackReason.AUTHENTICATION_ERROR
        status == 402 -> TranscriptionFallbackReason.PAYMENT_REQUIRED
        status == 403 -> TranscriptionFallbackReason.PERMISSION_ERROR
        status == 429 -> TranscriptionFallbackReason.RATE_LIMITED
        status in 400..499 -> TranscriptionFallbackReason.INVALID_REQUEST
        else -> TranscriptionFallbackReason.PROVIDER_ERROR
    }
}

private val CLOUD_ERROR_CODE_PATTERN =
    Regex("\\\"(?:code|status|type)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")

private val CLOUD_AUTHENTICATION_CODES = setOf(
    "authentication_error",
    "invalid_api_key",
    "missing_api_key",
    "invalid_authorization_header",
    "unauthorized",
    "sign_in_required",
)
private val CLOUD_PAYMENT_CODES = setOf(
    "payment_required",
    "quota_exceeded",
    "insufficient_credits",
    "credit_quota_exceeded",
)
private val CLOUD_PERMISSION_CODES = setOf(
    "authorization_error",
    "forbidden",
    "insufficient_permissions",
    "workspace_access_denied",
    "feature_not_available",
    "subscription_required",
    "unaccepted_terms",
)
private val CLOUD_RATE_LIMIT_CODES = setOf(
    "rate_limit_error",
    "rate_limit_exceeded",
    "concurrent_limit_exceeded",
    "rate_limited",
)
private val CLOUD_INVALID_REQUEST_CODES = setOf(
    "validation_error",
    "invalid_request",
    "invalid_parameters",
    "missing_required_field",
    "invalid_audio",
    "invalid_audio_format",
    "audio_too_short",
    "bad_request",
)

internal const val CLOUD_AI_TODO_MODEL = "openai/gpt-oss-20b"
internal const val CLOUD_AI_METADATA_MODEL = CLOUD_AI_TODO_MODEL

data class CloudMetadataResult(
    val tags: List<String>,
    val links: List<EntryLink>,
)

/** Reduces provider output to the small, portable subset Soma can safely persist. */
internal fun normalizeCloudMetadata(
    rawTags: List<String>,
    rawDateLinks: List<Pair<String, String?>>,
): CloudMetadataResult {
    val tags = rawTags.asSequence()
        .mapNotNull(::normalizeMetadataTag)
        .distinct()
        .take(MAX_AI_METADATA_TAGS)
        .toList()
    val links = rawDateLinks.asSequence()
        .mapNotNull { (rawDate, rawRelation) ->
            val relation = rawRelation?.let(::normalizeMetadataTag)
            runCatching {
                EntryLink(
                    kind = EntryLinkKind.DATE,
                    target = rawDate.trim(),
                    relation = relation,
                )
            }.getOrNull()
        }
        .distinct()
        .take(MAX_AI_METADATA_LINKS)
        .toList()
    return CloudMetadataResult(tags, links)
}

data class CloudDeveloperSettings(
    val available: Boolean,
    val transcriptionEnabled: Boolean,
    val provider: CloudSpeechProvider,
    val wifiOnly: Boolean,
    val aiTodoSuggestions: Boolean,
    val aiAutoMetadata: Boolean,
    val hasGroqKey: Boolean,
    val hasElevenLabsKey: Boolean,
)

/**
 * Shared policy for local preferences and provider-specific language codes.
 * Providers accept only one forced language, so forcing is used only when the
 * user selected exactly one. With several languages they auto-detect each VAD
 * chunk. An uncertain or unexpected label falls back to the user's preferred
 * language for downstream rule processing without discarding the transcript.
 */
internal data class CloudSpeechLanguagePolicy(
    val allowed: Set<String>,
    val preferred: String?,
    val forced: String?,
) {
    fun requestCode(provider: CloudSpeechProvider): String? = forced?.let { language ->
        when (provider) {
            CloudSpeechProvider.GROQ -> language
            CloudSpeechProvider.ELEVENLABS -> ISO_639_3.getValue(language)
        }
    }

    fun accepts(reported: String): Boolean {
        val normalized = normalize(reported)
        return normalized == UNDETERMINED || normalized in allowed
    }

    fun resolved(reported: String): String {
        val normalized = normalize(reported)
        return normalized.takeIf { it in allowed }
            ?: preferred
            ?: allowed.first()
    }

    companion object {
        fun from(
            spoken: Set<SupportedLanguage>,
            appLanguage: SupportedLanguage,
        ): CloudSpeechLanguagePolicy {
            val selected = spoken.ifEmpty { SupportedLanguage.entries.toSet() }
            val allowed = selected.mapTo(linkedSetOf(), SupportedLanguage::languageTag)
            return CloudSpeechLanguagePolicy(
                allowed = allowed,
                preferred = appLanguage.languageTag.takeIf { appLanguage in selected },
                forced = selected.singleOrNull()?.languageTag,
            )
        }

        internal fun normalize(value: String): String {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return when (normalized) {
                "en", "eng", "english" -> "en"
                "lv", "lav", "latvian" -> "lv"
                "et", "est", "estonian" -> "et"
                "lt", "lit", "lithuanian" -> "lt"
                "fi", "fin", "finnish" -> "fi"
                "sv", "swe", "swedish" -> "sv"
                "de", "deu", "ger", "german" -> "de"
                "sk", "slk", "slo", "slovak" -> "sk"
                "", "und", "unknown" -> UNDETERMINED
                else -> normalized
            }
        }

        private val ISO_639_3 = mapOf(
            "en" to "eng",
            "lv" to "lav",
            "et" to "est",
            "lt" to "lit",
            "fi" to "fin",
            "sv" to "swe",
            "de" to "deu",
            "sk" to "slk",
        )
        private const val UNDETERMINED = "und"
    }
}

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

    fun setAiAutoMetadata(enabled: Boolean)

    /** Empty text deletes the provider key. Keys are Keystore-encrypted at rest. */
    fun setApiKey(provider: CloudSpeechProvider, value: CharArray)

    fun createTranscriber(localFactory: () -> Transcriber): Transcriber

    /** Suggestions only. Callers still require an explicit user tap before creating a todo. */
    suspend fun extractTodoCandidates(text: String): List<String>

    /** Null means unavailable/failed; an empty successful result means stale AI metadata can be removed. */
    suspend fun deriveEntryMetadata(
        text: String,
        languages: Set<SupportedLanguage>,
    ): CloudMetadataResult?
}

internal fun cloudFeatures(context: Context): CloudFeatureController = createCloudFeatureController(context)

private const val MAX_AI_METADATA_TAGS = 8
private const val MAX_AI_METADATA_LINKS = 8
