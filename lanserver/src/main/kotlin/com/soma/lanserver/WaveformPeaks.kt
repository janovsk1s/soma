package com.soma.lanserver

import java.io.IOException
import java.io.InputStream

/**
 * Downsampled amplitude peaks for the small inline waveform above the audio
 * control. Reads only standard 16-bit PCM WAV — the one format Soma serves —
 * and returns null for anything else rather than guessing.
 */
internal object WaveformPeaks {
    const val BUCKETS = 200
    const val MAX_HEIGHT = 30
    private const val MAX_AUDIO_BYTES = 64L * 1024 * 1024

    fun compute(resource: AudioResource): List<Int>? {
        val type = resource.contentType.substringBefore(';').trim().lowercase()
        if (type != "audio/wav" && type != "audio/x-wav" && type != "audio/wave") return null
        if (resource.contentLength > MAX_AUDIO_BYTES) return null
        return try {
            resource.openStream().use(::fromWav)
        } catch (_: IOException) {
            null
        }
    }

    private fun fromWav(input: InputStream): List<Int>? {
        val riff = input.readExactly(12) ?: return null
        if (!riff.ascii(0, "RIFF") || !riff.ascii(8, "WAVE")) return null
        var channels = 0
        var blockAlign = 0
        var pcm16 = false
        while (true) {
            val chunkHeader = input.readExactly(8) ?: return null
            val chunkSize = chunkHeader.leInt(4).toLong() and 0xffffffffL
            when {
                chunkHeader.ascii(0, "fmt ") -> {
                    if (chunkSize < 16) return null
                    val fmt = input.readExactly(16) ?: return null
                    val audioFormat = fmt.leShort(0)
                    channels = fmt.leShort(2)
                    blockAlign = fmt.leShort(12)
                    val bitsPerSample = fmt.leShort(14)
                    pcm16 = audioFormat == 1 && bitsPerSample == 16
                    input.skipExactly(chunkSize - 16) ?: return null
                }
                chunkHeader.ascii(0, "data") -> {
                    if (!pcm16 || channels < 1 || blockAlign < channels * 2) return null
                    return fromPcm(input, chunkSize, channels, blockAlign)
                }
                else -> input.skipExactly(chunkSize) ?: return null
            }
        }
    }

    private fun fromPcm(input: InputStream, dataBytes: Long, channels: Int, blockAlign: Int): List<Int>? {
        val frames = dataBytes / blockAlign
        if (frames <= 0) return null
        val buckets = minOf(BUCKETS.toLong(), frames).toInt()
        val framesPerBucket = frames / buckets
        val maxima = IntArray(buckets)
        val frame = ByteArray(blockAlign)
        var index = 0L
        while (index < framesPerBucket * buckets) {
            if (input.readExactly(frame.size, frame) == null) break
            // The first channel carries the voice; extra channels only repeat it.
            val sample = ((frame[1].toInt() shl 8) or (frame[0].toInt() and 0xff)).toShort().toInt()
            val bucket = (index / framesPerBucket).toInt().coerceAtMost(buckets - 1)
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(sample)
            if (magnitude > maxima[bucket]) maxima[bucket] = magnitude
            index++
        }
        val loudest = maxima.max()
        if (loudest <= 0) return List(buckets) { 1 }
        // Normalized to the clip's own loudest moment so quiet notes keep a shape.
        return maxima.map { value -> ((value.toLong() * MAX_HEIGHT) / loudest).toInt().coerceAtLeast(1) }
    }

    private fun ByteArray.ascii(offset: Int, expected: String): Boolean =
        expected.withIndex().all { (index, char) -> this[offset + index] == char.code.toByte() }

    private fun ByteArray.leShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.leInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun InputStream.readExactly(count: Int, into: ByteArray = ByteArray(count)): ByteArray? {
        var read = 0
        while (read < count) {
            val step = read(into, read, count - read)
            if (step < 0) return null
            read += step
        }
        return into
    }

    private fun InputStream.skipExactly(count: Long): Unit? {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) return null
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return Unit
    }
}
