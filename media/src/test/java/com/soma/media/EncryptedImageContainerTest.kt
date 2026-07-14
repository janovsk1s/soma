package com.soma.media

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EncryptedImageContainerTest {
    private val provider = ImageWrappingKeyProvider {
        SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    }

    @Test
    fun `round trip authenticates metadata and jpeg`() {
        val directory = Files.createTempDirectory("soma-image-").toFile()
        val file = File(directory, "image-1.smi")
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())

        val written = EncryptedImageContainer.write(file, "image-1", jpeg, 1280, 960, 90, provider)
        val (read, plaintext) = EncryptedImageContainer.read(file, "image-1", provider)

        assertEquals(written, read)
        assertArrayEquals(jpeg, plaintext)
        assertFalse(file.readBytes().containsSubsequence(jpeg))
        plaintext.fill(0)
        directory.deleteRecursively()
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
    }
}
