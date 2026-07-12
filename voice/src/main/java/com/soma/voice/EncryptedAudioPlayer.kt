package com.soma.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Playing(val audioId: String) : PlaybackState
    data class Failed(val audioId: String, val error: Throwable) : PlaybackState
}

/** Streams decrypted PCM to AudioTrack; no plaintext temporary audio file is created. */
class EncryptedAudioPlayer(
    private val keyProvider: AudioWrappingKeyProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var playbackJob: Job? = null
    @Volatile private var activeTrack: AudioTrack? = null

    suspend fun play(file: File) {
        stop()
        playbackJob = scope.launch {
            val audioId = file.nameWithoutExtension
            mutableState.value = PlaybackState.Playing(audioId)
            runCatching {
                EncryptedAudioReader.open(file, keyProvider, audioId).use { reader ->
                    val metadata = reader.metadata
                    val minimum = AudioTrack.getMinBufferSize(
                        metadata.sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ).coerceAtLeast(8_192)
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(metadata.sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build(),
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(minimum * 2)
                        .build()
                    check(track.state == AudioTrack.STATE_INITIALIZED) { "Could not initialize audio playback" }
                    activeTrack = track
                    val buffer = ByteArray(minimum)
                    try {
                        track.play()
                        reader.pcmStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                var offset = 0
                                while (offset < read) {
                                    val written = track.write(buffer, offset, read - offset, AudioTrack.WRITE_BLOCKING)
                                    check(written >= 0) { "AudioTrack write failed: $written" }
                                    offset += written
                                }
                            }
                        }
                    } finally {
                        buffer.fill(0)
                        runCatching { track.stop() }
                        track.release()
                        if (activeTrack === track) activeTrack = null
                    }
                }
            }.onFailure { error -> mutableState.value = PlaybackState.Failed(audioId, error) }
            if (mutableState.value is PlaybackState.Playing) mutableState.value = PlaybackState.Idle
        }
    }

    suspend fun stop() {
        runCatching { activeTrack?.stop() }
        playbackJob?.cancelAndJoin()
        playbackJob = null
        mutableState.value = PlaybackState.Idle
    }

    override fun close() {
        runCatching { activeTrack?.stop() }
        scope.cancel()
        activeTrack = null
        playbackJob = null
        mutableState.value = PlaybackState.Idle
    }
}
