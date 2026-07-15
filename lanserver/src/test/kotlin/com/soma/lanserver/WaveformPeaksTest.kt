package com.soma.lanserver

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformPeaksTest {
    @Test
    fun `a mono wav produces normalized peaks with the loudest bucket full height`() {
        val samples = ShortArray(16_000) { index ->
            val amplitude = if (index < 8_000) 0.25 else 1.0
            (amplitude * Short.MAX_VALUE * sin(2.0 * PI * 440.0 * index / 16_000.0)).toInt().toShort()
        }
        val peaks = WaveformPeaks.compute(wavResource(samples))

        assertNotNull(peaks)
        assertEquals(WaveformPeaks.BUCKETS, peaks!!.size)
        assertEquals(WaveformPeaks.MAX_HEIGHT, peaks.max())
        assertTrue(peaks.first() < peaks.last())
        assertTrue(peaks.all { it in 1..WaveformPeaks.MAX_HEIGHT })
    }

    @Test
    fun `silence still draws a one-unit floor`() {
        val peaks = WaveformPeaks.compute(wavResource(ShortArray(4_000)))
        assertNotNull(peaks)
        assertTrue(peaks!!.all { it == 1 })
    }

    @Test
    fun `non wav content and malformed bytes are refused`() {
        val bytes = ByteArray(128) { it.toByte() }
        assertNull(
            WaveformPeaks.compute(
                AudioResource("audio/mpeg", bytes.size.toLong()) { ByteArrayInputStream(bytes) },
            ),
        )
        assertNull(
            WaveformPeaks.compute(
                AudioResource("audio/wav", bytes.size.toLong()) { ByteArrayInputStream(bytes) },
            ),
        )
    }

    @Test
    fun `short clips produce one bucket per frame at most`() {
        val peaks = WaveformPeaks.compute(wavResource(ShortArray(25) { Short.MAX_VALUE }))
        assertNotNull(peaks)
        assertEquals(25, peaks!!.size)
    }

    private fun wavResource(samples: ShortArray): AudioResource {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(16_000)
        buffer.putInt(32_000)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        samples.forEach(buffer::putShort)
        val bytes = buffer.array()
        return AudioResource("audio/wav", bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }
}
