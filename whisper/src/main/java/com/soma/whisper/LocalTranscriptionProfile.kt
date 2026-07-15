package com.soma.whisper

import android.app.ActivityManager
import android.content.Context
import android.os.Build

enum class LocalDecodingQuality { EFFICIENT, BALANCED, ACCURATE }

data class LocalTranscriptionProfile(
    val quality: LocalDecodingQuality,
    val threadCount: Int,
    /** Zero selects greedy decoding; values above one select beam search. */
    val beamSize: Int,
    val greedyBestOf: Int,
) {
    init {
        require(threadCount in 1..6)
        require(beamSize == 0 || beamSize in 2..5)
        require(greedyBestOf in 1..5)
    }

    companion object {
        fun forDevice(
            context: Context,
            model: LocalWhisperModel = LocalWhisperModel.TINY,
        ): LocalTranscriptionProfile {
            val activity = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also { activity?.getMemoryInfo(it) }
            return selectLocalTranscriptionProfile(
                processors = Runtime.getRuntime().availableProcessors(),
                totalMemoryMb = memory.totalMem / BYTES_PER_MEBIBYTE,
                lowRam = activity?.isLowRamDevice == true,
                lightPhone = isLightPhone(),
                model = model,
            )
        }

        private fun isLightPhone(): Boolean =
            Build.MANUFACTURER.equals("Light", ignoreCase = true) ||
                Build.MODEL.equals("TLP301", ignoreCase = true) ||
                Build.DEVICE.equals("LightPhoneIII", ignoreCase = true)

        private const val BYTES_PER_MEBIBYTE = 1024L * 1024L
    }
}

internal fun selectLocalTranscriptionProfile(
    processors: Int,
    totalMemoryMb: Long,
    lowRam: Boolean,
    lightPhone: Boolean,
    model: LocalWhisperModel = LocalWhisperModel.TINY,
): LocalTranscriptionProfile {
    val cores = processors.coerceAtLeast(1)
    val tiny = when {
        lightPhone || lowRam || cores <= 4 || totalMemoryMb < 3_000L -> LocalTranscriptionProfile(
            quality = LocalDecodingQuality.EFFICIENT,
            threadCount = (cores - 1).coerceIn(1, 3),
            beamSize = 0,
            greedyBestOf = 2,
        )
        cores >= 8 && totalMemoryMb >= 6_000L -> LocalTranscriptionProfile(
            quality = LocalDecodingQuality.ACCURATE,
            threadCount = (cores - 2).coerceIn(4, 6),
            beamSize = 5,
            greedyBestOf = 3,
        )
        else -> LocalTranscriptionProfile(
            quality = LocalDecodingQuality.BALANCED,
            threadCount = (cores - 1).coerceIn(3, 4),
            beamSize = 3,
            greedyBestOf = 3,
        )
    }
    if (model == LocalWhisperModel.TINY) return tiny
    // Base is roughly three times tiny's compute. Spend the extra accuracy the
    // larger encoder already brings and buy wall-clock time back from the
    // decoder: narrower or greedy search, one more thread where cores allow.
    return when (tiny.quality) {
        LocalDecodingQuality.EFFICIENT -> tiny.copy(
            threadCount = (cores - 1).coerceIn(1, 4),
            beamSize = 0,
            greedyBestOf = 1,
        )
        LocalDecodingQuality.BALANCED -> tiny.copy(
            beamSize = 0,
            greedyBestOf = 2,
        )
        LocalDecodingQuality.ACCURATE -> tiny.copy(
            beamSize = 3,
        )
    }
}
