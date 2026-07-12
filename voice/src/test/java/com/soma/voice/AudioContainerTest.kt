package com.soma.voice

import java.io.FileOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioContainerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val wrappingKey = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "AES")
    private val provider = AudioWrappingKeyProvider { wrappingKey }

    @Test
    fun roundTripAndVirtualWav() {
        val partial = temporary.newFile("voice.partial").also { it.delete() }
        val final = temporary.newFile("voice-1.sma").also { it.delete() }
        val first = byteArrayOf(1, 0, 2, 0, 3, 0)
        val second = byteArrayOf(4, 0, 5, 0)
        val metadata = EncryptedAudioWriter(partial, "voice-1", provider).run {
            writePcm(first)
            writePcm(second)
            finish(final)
        }

        assertEquals(10L, metadata.pcmBytes)
        EncryptedAudioReader.open(final, provider, "voice-1").use { reader ->
            assertEquals("voice-1", reader.metadata.audioId)
            assertArrayEquals(first + second, reader.pcmStream().readBytes())
        }
        EncryptedAudioReader.open(final, provider, "voice-1").use { reader ->
            val wav = reader.wavStream().readBytes()
            assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            assertArrayEquals(first + second, wav.copyOfRange(44, wav.size))
        }
        assertTrue(final.readBytes().indexOfSubArray(first) < 0)
    }

    @Test
    fun tamperingIsRejected() {
        val partial = temporary.newFile("tamper.partial").also { it.delete() }
        val final = temporary.newFile("voice-2.sma").also { it.delete() }
        EncryptedAudioWriter(partial, "voice-2", provider).run {
            writePcm(ByteArray(128) { it.toByte() })
            finish(final)
        }
        val bytes = final.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        final.writeBytes(bytes)

        var rejected = false
        try {
            EncryptedAudioReader.open(final, provider, "voice-2").use { it.pcmStream().readBytes() }
        } catch (_: AEADBadTagException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun interruptedTailRecoversCompleteChunks() {
        val partial = temporary.newFile("recover.partial").also { it.delete() }
        val final = temporary.newFile("voice-3.sma").also { it.delete() }
        val pcm = ByteArray(64) { (it + 1).toByte() }
        EncryptedAudioWriter(partial, "voice-3", provider).run {
            writePcm(pcm)
            close()
        }
        FileOutputStream(partial, true).use { it.write(byteArrayOf(0, 0, 0, 1, 0)) }

        val recovered = EncryptedAudioRecovery.recoverPartial(partial, final, provider)
        assertEquals(pcm.size.toLong(), recovered.pcmBytes)
        EncryptedAudioReader.open(final, provider, "voice-3").use {
            assertArrayEquals(pcm, it.pcmStream().readBytes())
        }
    }

    @Test
    fun wholeChunkBoundaryTruncationIsRejectedWithoutTheAuthenticatedFooter() {
        val partial = temporary.newFile("voice-4.partial").also { it.delete() }
        val final = temporary.newFile("voice-4.sma").also { it.delete() }
        EncryptedAudioWriter(partial, "voice-4", provider).run {
            writePcm(ByteArray(64) { it.toByte() })
            finish(final)
        }
        java.io.RandomAccessFile(final, "rw").use { file ->
            file.setLength(file.length() - 36L)
        }

        try {
            EncryptedAudioReader.open(final, provider, "voice-4")
            fail("A finalized file without its footer must be rejected")
        } catch (_: java.io.EOFException) {
            // Expected.
        }
    }

    @Test
    fun containerIsBoundToTheExpectedAttachmentId() {
        val partial = temporary.newFile("voice-5.partial").also { it.delete() }
        val final = temporary.newFile("voice-5.sma").also { it.delete() }
        EncryptedAudioWriter(partial, "voice-5", provider).run {
            writePcm(ByteArray(32) { it.toByte() })
            finish(final)
        }

        try {
            EncryptedAudioReader.open(final, provider, "different-id")
            fail("A recording must not open for a different attachment")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        for (index in 0..size - needle.size) {
            if (needle.indices.all { this[index + it] == needle[it] }) return index
        }
        return -1
    }
}
