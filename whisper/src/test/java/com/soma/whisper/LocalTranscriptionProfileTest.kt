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
}
