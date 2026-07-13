package com.soma.whisper

import kotlin.math.ceil
import kotlin.math.sqrt

data class SpeechChunk(
    val samples: FloatArray,
    val startSample: Int,
    val endSample: Int,
    val sampleRate: Int,
) {
    val startMillis: Long get() = startSample * 1_000L / sampleRate
    val endMillis: Long get() = endSample * 1_000L / sampleRate
}

/**
 * Deliberately modest energy VAD. Splitting at real pauses gives multilingual
 * tiny Whisper a fresh language decision for each utterance.
 */
class VoiceActivityDetector(
    private val frameMillis: Int = 30,
    // Light Phone III's unprocessed VOICE_RECOGNITION feed can put most speech
    // frames near 0.0003 RMS. Keep the static floor below that; noisy rooms are
    // handled by the adaptive noiseMultiplier and cap.
    private val minimumEnergy: Float = 0.0002f,
    private val noiseMultiplier: Float = 3.0f,
    private val startFrames: Int = 2,
    private val minimumSpeechMillis: Int = 240,
    private val splitSilenceMillis: Int = 600,
    private val paddingMillis: Int = 180,
    private val maximumSpeechMillis: Int = 28_000,
) {
    fun split(samples: FloatArray, sampleRate: Int): List<SpeechChunk> {
        require(sampleRate > 0)
        if (samples.isEmpty()) return emptyList()
        val frameSamples = (sampleRate * frameMillis / 1_000).coerceAtLeast(1)
        val frameCount = ceil(samples.size / frameSamples.toDouble()).toInt()
        val energies = FloatArray(frameCount) { frame ->
            val start = frame * frameSamples
            val end = minOf(samples.size, start + frameSamples)
            var sum = 0.0
            for (index in start until end) sum += samples[index] * samples[index]
            sqrt(sum / (end - start).coerceAtLeast(1)).toFloat()
        }
        val sorted = energies.sorted()
        val noiseFloor = sorted[(sorted.lastIndex * NOISE_PERCENTILE).toInt().coerceAtLeast(0)]
        // A clip can begin immediately with speech and contain too little true
        // silence for a percentile to represent the noise floor. Cap adaptation
        // so sustained speech is never reclassified as its own background.
        val threshold = maxOf(minimumEnergy, minOf(MAXIMUM_ADAPTIVE_THRESHOLD, noiseFloor * noiseMultiplier))
        val voiced = BooleanArray(frameCount) { energies[it] >= threshold }
        val silenceFrames = ceil(splitSilenceMillis / frameMillis.toDouble()).toInt()
        val minimumFrames = ceil(minimumSpeechMillis / frameMillis.toDouble()).toInt()
        val padFrames = ceil(paddingMillis / frameMillis.toDouble()).toInt()
        val maximumFrames = ceil(maximumSpeechMillis / frameMillis.toDouble()).toInt()

        val ranges = mutableListOf<IntRange>()
        var candidateVoiced = 0
        var speechStart = -1
        var lastVoiced = -1
        for (frame in voiced.indices) {
            if (voiced[frame]) {
                candidateVoiced++
                lastVoiced = frame
                if (speechStart < 0 && candidateVoiced >= startFrames) {
                    speechStart = (frame - startFrames + 1).coerceAtLeast(0)
                }
            } else {
                candidateVoiced = 0
            }
            if (speechStart >= 0) {
                val silentFor = if (lastVoiced >= 0) frame - lastVoiced else 0
                val forced = frame - speechStart + 1 >= maximumFrames
                if (silentFor >= silenceFrames || forced) {
                    addRange(ranges, speechStart, lastVoiced.coerceAtLeast(speechStart), minimumFrames, padFrames, frameCount)
                    speechStart = -1
                    lastVoiced = -1
                    candidateVoiced = 0
                }
            }
        }
        if (speechStart >= 0 && lastVoiced >= speechStart) {
            addRange(ranges, speechStart, lastVoiced, minimumFrames, padFrames, frameCount)
        }

        return ranges.map { frames ->
            val start = (frames.first * frameSamples).coerceAtMost(samples.size)
            val end = ((frames.last + 1) * frameSamples).coerceAtMost(samples.size)
            SpeechChunk(samples.copyOfRange(start, end), start, end, sampleRate)
        }
    }

    private fun addRange(
        target: MutableList<IntRange>,
        start: Int,
        lastVoiced: Int,
        minimumFrames: Int,
        paddingFrames: Int,
        totalFrames: Int,
    ) {
        if (lastVoiced - start + 1 < minimumFrames) return
        val paddedStart = (start - paddingFrames).coerceAtLeast(0)
        val paddedEnd = (lastVoiced + paddingFrames).coerceAtMost(totalFrames - 1)
        val previous = target.lastOrNull()
        if (previous != null && paddedStart <= previous.last) {
            target[target.lastIndex] = previous.first..maxOf(previous.last, paddedEnd)
        } else {
            target += paddedStart..paddedEnd
        }
    }

    private companion object {
        const val NOISE_PERCENTILE = 0.2f
        const val MAXIMUM_ADAPTIVE_THRESHOLD = 0.025f
    }
}
