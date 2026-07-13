package com.soma.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Recording(val audioId: String, val partialFile: File) : RecordingState
    data class Failed(val audioId: String, val partialFile: File, val error: Throwable) : RecordingState
}

data class RecordedAudio(
    val file: File,
    val metadata: AudioMetadata,
)

/** Records 16 kHz mono PCM directly into authenticated encrypted chunks. */
class EncryptedAudioRecorder(
    private val keyProvider: AudioWrappingKeyProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = mutableState.asStateFlow()

    @Volatile
    private var active: RecordingSession? = null

    @Volatile
    private var preparedBufferSize: Int? = null

    /**
     * Warms only encryption and format metadata; it never opens or starts the
     * microphone. Doing this while the home screen settles keeps Keystore work
     * out of the user's long-press-to-record path.
     */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        keyProvider.getOrCreate()
        configuredBufferSize().also { preparedBufferSize = it }
        Unit
    }

    @SuppressLint("MissingPermission") // The app requests RECORD_AUDIO immediately before calling start().
    suspend fun start(audioId: String, directory: File): RecordingSession = withContext(Dispatchers.IO) {
        check(active == null) { "A recording is already active" }
        require(SAFE_ID.matches(audioId)) { "Unsafe audio id" }
        directory.mkdirs()
        val partialFile = File(directory, "$audioId.partial")
        val finalFile = File(directory, "$audioId.sma")
        if (partialFile.exists()) error("An interrupted recording already exists for $audioId")
        if (finalFile.exists()) error("A recording already exists for $audioId")

        val bufferSize = preparedBufferSize ?: configuredBufferSize().also { preparedBufferSize = it }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioMetadata.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            record.release()
            "Could not initialize the microphone"
        }
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone did not enter the recording state"
            }
        } catch (error: Throwable) {
            record.release()
            throw error
        }

        // AudioRecord's native buffer is already collecting the first words
        // while the crash-safe encrypted container is opened and fsynced.
        val writer = try {
            EncryptedAudioWriter(partialFile, audioId, keyProvider)
        } catch (error: Throwable) {
            runCatching { record.stop() }
            record.release()
            runCatching { partialFile.delete() }
            throw error
        }

        val session = RecordingSession(
            audioId = audioId,
            partialFile = partialFile,
            finalFile = finalFile,
            record = record,
            writer = writer,
            bufferSize = bufferSize,
            scope = scope,
            onFinished = {
                active = null
                mutableState.value = RecordingState.Idle
            },
            onFailed = { failure ->
                mutableState.value = RecordingState.Failed(audioId, partialFile, failure)
            },
        )
        active = session
        mutableState.value = RecordingState.Recording(audioId, partialFile)
        session.begin()
        session
    }

    suspend fun stop(): RecordedAudio? = active?.stop()

    /** Finalizes any active container; Room reconciliation runs on the next app start. */
    override fun close() {
        runBlocking(Dispatchers.IO) { runCatching { active?.stop() } }
        scope.cancel()
    }

    companion object {
        private const val DEFAULT_CHUNK_BYTES = 8_192
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")
    }

    private fun configuredBufferSize(): Int {
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioMetadata.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "16 kHz mono recording is unavailable" }
        return maxOf(minBuffer, DEFAULT_CHUNK_BYTES).let { if (it % 2 == 0) it else it + 1 }
    }
}

class RecordingSession internal constructor(
    private val audioId: String,
    val partialFile: File,
    private val finalFile: File,
    private val record: AudioRecord,
    private val writer: EncryptedAudioWriter,
    private val bufferSize: Int,
    private val scope: CoroutineScope,
    private val onFinished: () -> Unit,
    private val onFailed: (Throwable) -> Unit,
) {
    private val stopping = AtomicBoolean(false)
    private var failure: Throwable? = null
    private lateinit var captureJob: Job

    internal fun begin() {
        captureJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            try {
                while (!stopping.get()) {
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    when {
                        read > 0 -> writer.writePcm(buffer, read - (read % 2))
                        read == 0 -> Unit
                        !stopping.get() -> error("AudioRecord read failed: $read")
                    }
                }
            } catch (error: Throwable) {
                if (!stopping.get()) {
                    failure = error
                    onFailed(error)
                }
            } finally {
                buffer.fill(0)
            }
        }
    }

    suspend fun stop(): RecordedAudio? = withContext(Dispatchers.IO) {
        if (!stopping.compareAndSet(false, true)) return@withContext null
        runCatching { record.stop() }
        captureJob.join()
        record.release()
        val result = runCatching {
            val metadata = writer.finish(finalFile)
            RecordedAudio(finalFile, metadata)
        }.onFailure { error ->
            failure = failure ?: error
            onFailed(error)
            runCatching { writer.close() }
        }.getOrNull()
        onFinished()
        result
    }
}
