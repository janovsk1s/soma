package com.soma.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.soma.core.model.TranscriptionFallbackReason
import com.soma.whisper.TranscribedChunk
import com.soma.whisper.Transcriber
import com.soma.whisper.TranscriptionResult
import com.soma.whisper.VoiceActivityDetector
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal fun createCloudFeatureController(context: Context): CloudFeatureController =
    AndroidCloudFeatureController(context.applicationContext)

private class AndroidCloudFeatureController(private val context: Context) : CloudFeatureController {
    private val secrets = CloudSecretStore(context)

    override fun settings(): CloudDeveloperSettings = CloudDeveloperSettings(
        available = true,
        transcriptionEnabled = SomaPrefs.cloudTranscription(context),
        provider = SomaPrefs.cloudSpeechProvider(context),
        wifiOnly = SomaPrefs.cloudWifiOnly(context),
        aiTodoSuggestions = SomaPrefs.aiTodoSuggestions(context),
        hasGroqKey = secrets.read(CloudSpeechProvider.GROQ) != null,
        hasElevenLabsKey = secrets.read(CloudSpeechProvider.ELEVENLABS) != null,
    )

    override fun setTranscriptionEnabled(enabled: Boolean) = SomaPrefs.setCloudTranscription(context, enabled)

    override fun setProvider(provider: CloudSpeechProvider) = SomaPrefs.setCloudSpeechProvider(context, provider)

    override fun setWifiOnly(enabled: Boolean) = SomaPrefs.setCloudWifiOnly(context, enabled)

    override fun setAiTodoSuggestions(enabled: Boolean) = SomaPrefs.setAiTodoSuggestions(context, enabled)

    override fun setApiKey(provider: CloudSpeechProvider, value: CharArray) {
        try {
            secrets.write(provider, value)
        } finally {
            value.fill('\u0000')
        }
    }

    override fun createTranscriber(localFactory: () -> Transcriber): Transcriber {
        val setting = settings()
        val key = when (setting.provider) {
            CloudSpeechProvider.GROQ -> secrets.read(CloudSpeechProvider.GROQ)
            CloudSpeechProvider.ELEVENLABS -> secrets.read(CloudSpeechProvider.ELEVENLABS)
        }
        if (!setting.transcriptionEnabled) return localFactory()
        return FallbackCloudTranscriber(
            context = context,
            provider = setting.provider,
            apiKey = key,
            wifiOnly = setting.wifiOnly,
            languagePolicy = CloudSpeechLanguagePolicy.from(
                spoken = SomaPrefs.speechLanguages(context),
                appLanguage = SomaPrefs.language(context),
            ),
            vocabulary = TranscriptionVocabularyStore(context).read(),
            localFactory = localFactory,
        )
    }

    override suspend fun extractTodoCandidates(text: String): List<String> {
        if (!SomaPrefs.aiTodoSuggestions(context) || text.isBlank()) return emptyList()
        if (SomaPrefs.cloudWifiOnly(context) && !context.isOnWifi()) return emptyList()
        val key = secrets.read(CloudSpeechProvider.GROQ) ?: return emptyList()
        return runCatching { CloudHttp.extractTodos(key, text) }.getOrDefault(emptyList())
    }
}

private class FallbackCloudTranscriber(
    private val context: Context,
    private val provider: CloudSpeechProvider,
    private val apiKey: String?,
    private val wifiOnly: Boolean,
    private val languagePolicy: CloudSpeechLanguagePolicy,
    private val vocabulary: List<String>,
    private val localFactory: () -> Transcriber,
    private val vad: VoiceActivityDetector = VoiceActivityDetector(),
) : Transcriber {
    private var local: Transcriber? = null

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionResult {
        val key = apiKey
            ?: return localFallback(samples, sampleRate, TranscriptionFallbackReason.API_KEY_MISSING)
        if (wifiOnly && !context.isOnWifi()) {
            return localFallback(samples, sampleRate, TranscriptionFallbackReason.WIFI_REQUIRED)
        }
        return try {
            require(sampleRate == SAMPLE_RATE) { "Cloud transcription requires 16 kHz PCM" }
            val speech = vad.split(samples, sampleRate)
            if (speech.isEmpty()) {
                return TranscriptionResult(
                    text = "",
                    chunks = emptyList(),
                    provenance = cloudSuccessProvenance(provider),
                )
            }
            val chunks = try {
                if (provider.preservesRecordingContext) {
                    listOf(
                        transcribeCloudChunk(
                            key = key,
                            samples = samples,
                            sampleRate = sampleRate,
                            startMillis = 0,
                            endMillis = samples.size * 1_000L / sampleRate,
                        ),
                    )
                } else {
                    speech.map { chunk ->
                        transcribeCloudChunk(
                            key = key,
                            samples = chunk.samples,
                            sampleRate = sampleRate,
                            startMillis = chunk.startMillis,
                            endMillis = chunk.endMillis,
                        )
                    }
                }
            } finally {
                speech.forEach { it.samples.fill(0f) }
            }
            TranscriptionResult(
                text = chunks.map(TranscribedChunk::text).filter(String::isNotBlank).joinToString("\n"),
                chunks = chunks,
                provenance = cloudSuccessProvenance(provider),
            )
        } catch (error: CloudProviderException) {
            localFallback(samples, sampleRate, error.fallbackReason)
        } catch (error: IOException) {
            localFallback(samples, sampleRate, TranscriptionFallbackReason.NETWORK_ERROR)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Cloud is experimental: a provider, key, or network failure must never
            // strand the recording. The encrypted original remains and local tiny runs.
            localFallback(samples, sampleRate, TranscriptionFallbackReason.PROVIDER_ERROR)
        }
    }

    private suspend fun transcribeCloudChunk(
        key: String,
        samples: FloatArray,
        sampleRate: Int,
        startMillis: Long,
        endMillis: Long,
    ): TranscribedChunk {
        val wav = pcmWav(samples, sampleRate)
        return try {
            val response = when (provider) {
                CloudSpeechProvider.GROQ -> CloudHttp.transcribeGroq(
                    key,
                    wav,
                    languagePolicy.requestCode(provider),
                    vocabulary,
                )
                CloudSpeechProvider.ELEVENLABS -> CloudHttp.transcribeElevenLabs(
                    key,
                    wav,
                    languagePolicy.requestCode(provider),
                    vocabulary,
                )
            }
            check(response.text.isNotBlank()) { "Cloud provider returned an empty transcript" }
            TranscribedChunk(
                text = response.text.trim(),
                languageCode = languagePolicy.resolved(response.languageCode),
                startMillis = startMillis,
                endMillis = endMillis,
            )
        } finally {
            wav.fill(0)
        }
    }

    private suspend fun localFallback(
        samples: FloatArray,
        sampleRate: Int,
        reason: TranscriptionFallbackReason,
    ): TranscriptionResult = local().transcribe(samples, sampleRate).copy(
        provenance = cloudFallbackProvenance(provider, reason),
    )

    private fun local(): Transcriber = local ?: localFactory().also { local = it }

    override fun close() {
        local?.close()
        local = null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

private data class CloudTranscript(val text: String, val languageCode: String)

private object CloudHttp {
    suspend fun transcribeGroq(
        apiKey: String,
        wav: ByteArray,
        language: String?,
        vocabulary: List<String>,
    ): CloudTranscript = withContext(Dispatchers.IO) {
        val fields = linkedMapOf(
            "model" to "whisper-large-v3",
            "response_format" to "verbose_json",
        ).apply {
            if (language != null) put("language", language)
            if (vocabulary.isNotEmpty()) put("prompt", vocabulary.joinToString(", "))
        }
        val json = multipart(
            url = "https://api.groq.com/openai/v1/audio/transcriptions",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            fields = fields.map { it.toPair() },
            wav = wav,
        )
        CloudTranscript(
            text = json.getString("text"),
            languageCode = CloudSpeechLanguagePolicy.normalize(json.optString("language", "und")),
        )
    }

    suspend fun transcribeElevenLabs(
        apiKey: String,
        wav: ByteArray,
        language: String?,
        keyterms: List<String>,
    ): CloudTranscript =
        withContext(Dispatchers.IO) {
            val fields = buildList {
                add("model_id" to "scribe_v2")
                if (language != null) add("language_code" to language)
                // Repeated multipart fields are the form representation used for a
                // list. Omitting them entirely keeps standard Scribe v2 billing.
                keyterms.forEach { add("keyterms" to it) }
            }
            val json = multipart(
                url = "https://api.elevenlabs.io/v1/speech-to-text",
                headers = mapOf("xi-api-key" to apiKey),
                fields = fields,
                wav = wav,
            )
            CloudTranscript(
                text = json.getString("text"),
                languageCode = CloudSpeechLanguagePolicy.normalize(json.optString("language_code", "und")),
            )
        }

    suspend fun extractTodos(apiKey: String, text: String): List<String> = withContext(Dispatchers.IO) {
        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "todos",
                    JSONObject()
                        .put("type", "array")
                        .put("maxItems", 3)
                        .put("items", JSONObject().put("type", "string")),
                ),
            )
            .put("required", JSONArray().put("todos"))
            .put("additionalProperties", false)
        val body = JSONObject()
            .put("model", CLOUD_AI_TODO_MODEL)
            .put("reasoning_effort", "low")
            .put("max_completion_tokens", 256)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                "Extract only explicit actions the writer intends or needs to do. " +
                                    "Return no items for observations, memories, or vague ideas. " +
                                    "Keep each item in the note's original language. Do not invent dates or details.",
                            ),
                    )
                    .put(JSONObject().put("role", "user").put("content", text.take(MAX_NOTE_CHARACTERS))),
            )
            .put(
                "response_format",
                JSONObject()
                    .put("type", "json_schema")
                    .put(
                        "json_schema",
                        JSONObject().put("name", "todo_candidates").put("strict", true).put("schema", schema),
                    ),
            )
        val response = jsonPost(
            "https://api.groq.com/openai/v1/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey"),
            body,
        )
        val content = response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        val todos = JSONObject(content).getJSONArray("todos")
        buildList {
            for (index in 0 until minOf(todos.length(), 3)) {
                todos.optString(index).trim().takeIf { it.isNotEmpty() && it.length <= 240 }?.let(::add)
            }
        }.distinct()
    }

    private fun multipart(
        url: String,
        headers: Map<String, String>,
        fields: List<Pair<String, String>>,
        wav: ByteArray,
    ): JSONObject {
        val boundary = "soma-${UUID.randomUUID()}"
        val request = ByteArrayOutputStream()
        DataOutputStream(request).use { output ->
            fields.forEach { (name, value) ->
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                output.write(value.toByteArray(Charsets.UTF_8))
                output.writeBytes("\r\n")
            }
            output.writeBytes("--$boundary\r\n")
            output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"soma.wav\"\r\n")
            output.writeBytes("Content-Type: audio/wav\r\n\r\n")
            output.write(wav)
            output.writeBytes("\r\n--$boundary--\r\n")
        }
        val bytes = request.toByteArray()
        return try {
            execute(url, headers + ("Content-Type" to "multipart/form-data; boundary=$boundary"), bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun jsonPost(url: String, headers: Map<String, String>, body: JSONObject): JSONObject =
        execute(url, headers + ("Content-Type" to "application/json; charset=utf-8"), body.toString().toByteArray())

    private fun execute(url: String, headers: Map<String, String>, body: ByteArray): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", "Soma/${BuildConfig.VERSION_NAME}")
            connection.setFixedLengthStreamingMode(body.size)
            headers.forEach(connection::setRequestProperty)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { it.readNBytes(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8) }.orEmpty()
            if (status !in 200..299) {
                throw CloudProviderException(cloudFailureReason(status, response))
            }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MILLIS = 20_000
    private const val READ_TIMEOUT_MILLIS = 90_000
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_NOTE_CHARACTERS = 4_000
}

private class CloudProviderException(
    val fallbackReason: TranscriptionFallbackReason,
) : IOException("Cloud provider request failed")

private class CloudSecretStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(provider: CloudSpeechProvider): String? {
        val encoded = preferences.getString(provider.name, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed, 0, IV_BYTES))
            String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    fun write(provider: CloudSpeechProvider, value: CharArray) {
        if (value.isEmpty()) {
            preferences.edit().remove(provider.name).apply()
            return
        }
        val plain = String(value).trim().toByteArray(Charsets.UTF_8)
        try {
            if (plain.isEmpty()) {
                preferences.edit().remove(provider.name).apply()
                return
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(plain)
            val packed = ByteArray(cipher.iv.size + encrypted.size)
            cipher.iv.copyInto(packed)
            encrypted.copyInto(packed, cipher.iv.size)
            preferences.edit().putString(provider.name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
            encrypted.fill(0)
            packed.fill(0)
        } finally {
            plain.fill(0)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "soma_cloud_secrets"
        const val KEY_ALIAS = "soma.cloud.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

@Suppress("DEPRECATION")
private fun Context.isOnWifi(): Boolean {
    val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
    return connectivity.allNetworks.any { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

private fun pcmWav(samples: FloatArray, sampleRate: Int): ByteArray {
    val pcmBytes = samples.size * 2
    val output = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
    output.put("RIFF".toByteArray(Charsets.US_ASCII))
    output.putInt(36 + pcmBytes)
    output.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    output.putInt(16)
    output.putShort(1.toShort())
    output.putShort(1.toShort())
    output.putInt(sampleRate)
    output.putInt(sampleRate * 2)
    output.putShort(2.toShort())
    output.putShort(16.toShort())
    output.put("data".toByteArray(Charsets.US_ASCII))
    output.putInt(pcmBytes)
    samples.forEach { sample ->
        output.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
    }
    return output.array()
}
