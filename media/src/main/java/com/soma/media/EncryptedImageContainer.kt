package com.soma.media

import com.soma.core.model.ImageAttachment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedImageMetadata(
    val imageId: String,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val jpegByteCount: Int,
)

/**
 * One-shot authenticated image container. Camera JPEG bytes are encrypted in
 * memory and committed atomically; plaintext is never written to disk or MediaStore.
 */
object EncryptedImageContainer {
    fun write(
        finalFile: File,
        imageId: String,
        jpegBytes: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        keyProvider: ImageWrappingKeyProvider,
        random: SecureRandom = SecureRandom(),
    ): EncryptedImageMetadata {
        require(ImageAttachment.isValidFileId(imageId)) { "Unsafe image id" }
        require(finalFile.name == "$imageId.smi") { "Image filename must match its opaque id" }
        require(
            jpegBytes.size >= 4 && jpegBytes[0] == JPEG_START_FIRST && jpegBytes[1] == JPEG_START_SECOND &&
                jpegBytes[jpegBytes.lastIndex - 1] == JPEG_END_FIRST && jpegBytes.last() == JPEG_END_SECOND
        ) { "Camera payload is not a complete JPEG" }
        require(width > 0 && height > 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        check(!finalFile.exists()) { "Refusing to replace an encrypted image" }

        val keyBytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        val dataKey = SecretKeySpec(keyBytes, AES)
        Arrays.fill(keyBytes, 0)
        val aad = aad(imageId, width, height, rotationDegrees, jpegBytes.size)

        val wrapCipher = Cipher.getInstance(AES_GCM)
        wrapCipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
        wrapCipher.updateAAD(aad)
        val wrappedKey = wrapCipher.doFinal(dataKey.encoded)
        val wrapNonce = wrapCipher.iv

        val dataCipher = Cipher.getInstance(AES_GCM)
        dataCipher.init(Cipher.ENCRYPT_MODE, dataKey)
        dataCipher.updateAAD(aad)
        val ciphertext = dataCipher.doFinal(jpegBytes)
        val dataNonce = dataCipher.iv

        finalFile.parentFile?.mkdirs()
        val partial = File(finalFile.parentFile, "$imageId.partial")
        check(!partial.exists()) { "Refusing to replace a partial image" }
        try {
            FileOutputStream(partial).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).use { output ->
                    output.write(MAGIC)
                    output.writeByte(VERSION)
                    output.writeUTF(imageId)
                    output.writeInt(width)
                    output.writeInt(height)
                    output.writeInt(rotationDegrees)
                    output.writeInt(jpegBytes.size)
                    output.writeByte(wrapNonce.size)
                    output.write(wrapNonce)
                    output.writeInt(wrappedKey.size)
                    output.write(wrappedKey)
                    output.writeByte(dataNonce.size)
                    output.write(dataNonce)
                    output.writeInt(ciphertext.size)
                    output.write(ciphertext)
                    output.flush()
                    stream.fd.sync()
                }
            }
            runCatching {
                Files.move(partial.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                check(!finalFile.exists() && partial.renameTo(finalFile)) { "Could not finalize encrypted image" }
            }
        } finally {
            Arrays.fill(aad, 0)
            Arrays.fill(wrappedKey, 0)
            Arrays.fill(ciphertext, 0)
            if (partial.exists()) partial.delete()
        }
        return EncryptedImageMetadata(imageId, width, height, rotationDegrees, jpegBytes.size)
    }

    fun read(
        file: File,
        expectedImageId: String,
        keyProvider: ImageWrappingKeyProvider,
    ): Pair<EncryptedImageMetadata, ByteArray> {
        require(ImageAttachment.isValidFileId(expectedImageId)) { "Unsafe image id" }
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Not a Soma image" }
            require(input.readUnsignedByte() == VERSION) { "Unsupported image version" }
            val imageId = input.readUTF()
            require(imageId == expectedImageId) { "Image id does not match its attachment" }
            val width = input.readInt()
            val height = input.readInt()
            val rotation = input.readInt()
            val plainLength = input.readInt()
            require(width > 0 && height > 0 && rotation in setOf(0, 90, 180, 270))
            require(plainLength in 1..MAX_JPEG_BYTES) { "Invalid image size" }
            val aad = aad(imageId, width, height, rotation, plainLength)
            val wrapNonce = input.readBoundedBytes(MAX_NONCE_BYTES)
            val wrappedKey = input.readBoundedBytes(MAX_WRAPPED_KEY_BYTES, input.readInt())
            val dataNonce = input.readBoundedBytes(MAX_NONCE_BYTES)
            val ciphertext = input.readBoundedBytes(MAX_JPEG_BYTES + GCM_TAG_BYTES, input.readInt())
            require(input.read() == -1) { "Image container has trailing bytes" }
            try {
                val unwrap = Cipher.getInstance(AES_GCM)
                unwrap.init(Cipher.DECRYPT_MODE, keyProvider.getOrCreate(), GCMParameterSpec(GCM_TAG_BITS, wrapNonce))
                unwrap.updateAAD(aad)
                val rawDataKey = unwrap.doFinal(wrappedKey)
                val dataKey = SecretKeySpec(rawDataKey, AES)
                Arrays.fill(rawDataKey, 0)
                val decrypt = Cipher.getInstance(AES_GCM)
                decrypt.init(Cipher.DECRYPT_MODE, dataKey, GCMParameterSpec(GCM_TAG_BITS, dataNonce))
                decrypt.updateAAD(aad)
                val jpeg = decrypt.doFinal(ciphertext)
                require(jpeg.size == plainLength) { "Image length does not match its header" }
                return EncryptedImageMetadata(imageId, width, height, rotation, plainLength) to jpeg
            } finally {
                Arrays.fill(aad, 0)
                Arrays.fill(wrappedKey, 0)
                Arrays.fill(ciphertext, 0)
            }
        }
    }

    private fun DataInputStream.readBoundedBytes(maximum: Int): ByteArray {
        val length = readUnsignedByte()
        return readBoundedBytes(maximum, length)
    }

    private fun DataInputStream.readBoundedBytes(maximum: Int, length: Int): ByteArray {
        require(length in 1..maximum) { "Invalid image container field length" }
        return ByteArray(length).also { readFully(it) }
    }

    private fun aad(id: String, width: Int, height: Int, rotation: Int, length: Int): ByteArray {
        val idBytes = id.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(20 + idBytes.size)
            .putInt(VERSION)
            .putInt(width)
            .putInt(height)
            .putInt(rotation)
            .putInt(length)
            .put(idBytes)
            .array()
    }

    private val MAGIC = byteArrayOf('S'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), 'G'.code.toByte())
    private const val VERSION = 1
    private const val AES = "AES"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = 16
    private const val MAX_NONCE_BYTES = 32
    private const val MAX_WRAPPED_KEY_BYTES = 128
    private const val MAX_JPEG_BYTES = 24 * 1024 * 1024
    private val JPEG_START_FIRST = 0xff.toByte()
    private val JPEG_START_SECOND = 0xd8.toByte()
    private val JPEG_END_FIRST = 0xff.toByte()
    private val JPEG_END_SECOND = 0xd9.toByte()
}
