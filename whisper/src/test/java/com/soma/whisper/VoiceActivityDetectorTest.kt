package com.soma.whisper

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {
    private val detector = VoiceActivityDetector()
    private val rate = 16_000

    @Test
    fun silenceProducesNoChunks() {
        assertTrue(detector.split(FloatArray(rate * 2), rate).isEmpty())
    }

    @Test
    fun longPauseSplitsUtterances() {
        val speech = tone(800)
        val pause = FloatArray(rate)
        val chunks = detector.split(speech + pause + speech, rate)

        assertEquals(2, chunks.size)
        assertTrue(chunks[0].endMillis < chunks[1].startMillis)
        assertTrue(chunks.all { it.endMillis - it.startMillis >= 700 })
    }

    @Test
    fun shortPauseStaysOneUtterance() {
        val speech = tone(700)
        val shortPause = FloatArray(rate / 5)
        assertEquals(1, detector.split(speech + shortPause + speech, rate).size)
    }

    @Test
    fun shortClickIsRejected() {
        assertTrue(detector.split(tone(100), rate).isEmpty())
    }

    private fun tone(milliseconds: Int): FloatArray =
        FloatArray(rate * milliseconds / 1_000) { index ->
            (sin(2.0 * PI * 220.0 * index / rate) * 0.15).toFloat()
        }
}
