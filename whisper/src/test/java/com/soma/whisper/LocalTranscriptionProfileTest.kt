package com.soma.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTranscriptionProfileTest {
    @Test
    fun `Light Phone remains efficient despite misleading core and memory totals`() {
        val profile = selectLocalTranscriptionProfile(
            processors = 8,
            totalMemoryMb = 8_000,
            lowRam = false,
            lightPhone = true,
        )
        assertEquals(LocalDecodingQuality.EFFICIENT, profile.quality)
        assertEquals(3, profile.threadCount)
        assertEquals(0, profile.beamSize)
    }

    @Test
    fun `capable Android device uses accurate beam decoding`() {
        val profile = selectLocalTranscriptionProfile(
            processors = 8,
            totalMemoryMb = 8_000,
            lowRam = false,
            lightPhone = false,
        )
        assertEquals(LocalDecodingQuality.ACCURATE, profile.quality)
        assertEquals(6, profile.threadCount)
        assertEquals(5, profile.beamSize)
    }

    @Test
    fun `low ram always wins over core count`() {
        val profile = selectLocalTranscriptionProfile(
            processors = 10,
            totalMemoryMb = 12_000,
            lowRam = true,
            lightPhone = false,
        )
        assertEquals(LocalDecodingQuality.EFFICIENT, profile.quality)
    }

    @Test
    fun `base on the Light Phone trades decoder breadth for one more thread`() {
        val profile = selectLocalTranscriptionProfile(
            processors = 8,
            totalMemoryMb = 8_000,
            lowRam = false,
            lightPhone = true,
            model = LocalWhisperModel.BASE,
        )
        assertEquals(LocalDecodingQuality.EFFICIENT, profile.quality)
        assertEquals(4, profile.threadCount)
        assertEquals(0, profile.beamSize)
        assertEquals(1, profile.greedyBestOf)
    }

    @Test
    fun `base on a balanced device drops to greedy decoding`() {
        val profile = selectLocalTranscriptionProfile(
            processors = 6,
            totalMemoryMb = 4_000,
            lowRam = false,
            lightPhone = false,
            model = LocalWhisperModel.BASE,
        )
        assertEquals(LocalDecodingQuality.BALANCED, profile.quality)
        assertEquals(0, profile.beamSize)
        assertEquals(2, profile.greedyBestOf)
    }

    @Test
    fun `base on a capable device narrows the beam but keeps searching`() {
        val profile = selectLocalTranscriptionProfile(
            processors = 8,
            totalMemoryMb = 8_000,
            lowRam = false,
            lightPhone = false,
            model = LocalWhisperModel.BASE,
        )
        assertEquals(LocalDecodingQuality.ACCURATE, profile.quality)
        assertEquals(6, profile.threadCount)
        assertEquals(3, profile.beamSize)
    }
}
