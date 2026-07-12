package com.soma.voice

import com.soma.core.model.AudioAttachment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class AudioMetadata(
    val audioId: String,
    val sampleRate: Int = SAMPLE_RATE,
    val channelCount: Int = CHANNEL_COUNT,
    val bitsPerSample: Int = BITS_PER_SAMPLE,
    val pcmBytes: Long,
) {
    val durationMillis: Long
        get() = pcmBytes * 1_000L / (sampleRate * channelCount * (bitsPerSample / 8))

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
    }
}

/**
 * Crash-recoverable encrypted PCM container.
 *
 * Each chunk has its own AES-256-GCM tag. A killed recording loses at most the
 * incomplete tail chunk instead of invalidating everything before it. The
 * random data key is wrapped by a distinct Android Keystore key in the header.
 */
class EncryptedAudioWriter(
    private val partialFile: File,
    private val audioId: String,
    wrappingKeyProvider: AudioWrappingKeyProvider,
    private val sampleRate: Int = AudioMetadata.SAMPLE_RATE,
    private val channelCount: Int = AudioMetadata.CHANNEL_COUNT,
    private val bitsPerSample: Int = AudioMetadata.BITS_PER_SAMPLE,
    private val random: SecureRandom = SecureRandom(),
) : Closeable {
    private val outputStream: FileOutputStream
    private val output: DataOutputStream
    private val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
    private val dataKey: SecretKey
    private var chunkIndex = 0
    private var pcmBytes = 0L
    private var closed = false
    private var footerWritten = false

    init {
        require(AudioAttachment.isValidFileId(audioId)) { "Unsafe audio id" }
        require(sampleRate > 0 && channelCount == 1 && bitsPerSample == 16)
        partialFile.parentFile?.mkdirs()
        check(!partialFile.exists()) { "Refusing to overwrite an audio container" }

        val keyBytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        dataKey = SecretKeySpec(keyBytes, AES)
        Arrays.fill(keyBytes, 0)

        val wrapNonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
        val wrappedKey = crypt(
            mode = Cipher.ENCRYPT_MODE,
            key = wrappingKeyProvider.getOrCreate(),
            nonce = wrapNonce,
            aad = wrapAad(audioId, sampleRate, channelCount, bitsPerSample),
            input = dataKey.encoded,
        )

        outputStream = FileOutputStream(partialFile)
        output = DataOutputStream(BufferedOutputStream(outputStream)).apply {
            write(MAGIC)
            writeByte(VERSION)
            writeUTF(audioId)
            writeInt(sampleRate)
            writeShort(channelCount)
            writeShort(bitsPerSample)
            write(noncePrefix)
            write(wrapNonce)
            writeInt(wrappedKey.size)
            write(wrappedKey)
            flush()
        }
        outputStream.fd.sync()
        Arrays.fill(wrappedKey, 0)
    }

    @Synchronized
    fun writePcm(bytes: ByteArray, length: Int = bytes.size) {
        check(!closed) { "Audio writer is closed" }
        require(length in 1..bytes.size)
        check(chunkIndex < Int.MAX_VALUE) { "Recording has too many chunks" }
        val plain = if (length == bytes.size) bytes else bytes.copyOf(length)
        val nonce = chunkNonce(noncePrefix, chunkIndex)
        val encrypted = crypt(
            Cipher.ENCRYPT_MODE,
            dataKey,
            nonce,
            chunkAad(audioId, sampleRate, channelCount, bitsPerSample, chunkIndex, length),
            plain,
        )
        output.writeInt(chunkIndex)
        output.writeInt(length)
        output.writeInt(encrypted.size)
        output.write(encrypted)
        output.flush()
        outputStream.fd.sync()
        if (plain !== bytes) Arrays.fill(plain, 0)
        Arrays.fill(encrypted, 0)
        pcmBytes += length
        chunkIndex++
    }

    @Synchronized
    fun finish(finalFile: File): AudioMetadata {
        check(!closed) { "Audio writer is already closed" }
        require(finalFile.name == "$audioId.sma") { "Final audio filename must match its opaque id" }
        check(!finalFile.exists()) { "Refusing to replace an existing finalized recording" }
        writeFooter()
        close()
        finalFile.parentFile?.mkdirs()
        runCatching {
            Files.move(
                partialFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            check(!finalFile.exists()) { "Refusing to replace an existing finalized recording" }
            check(partialFile.renameTo(finalFile)) { "Could not finalize encrypted recording" }
        }
        return AudioMetadata(audioId, sampleRate, channelCount, bitsPerSample, pcmBytes)
    }

    private fun writeFooter() {
        check(!footerWritten)
        val plain = ByteBuffer.allocate(FOOTER_PLAIN_BYTES)
            .putInt(chunkIndex)
            .putLong(pcmBytes)
            .array()
        val encrypted = crypt(
            Cipher.ENCRYPT_MODE,
            dataKey,
            chunkNonce(noncePrefix, FOOTER_NONCE_INDEX),
            footerAad(audioId, sampleRate, channelCount, bitsPerSample),
            plain,
        )
        try {
            output.writeInt(FOOTER_MARKER)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
            outputStream.fd.sync()
            footerWritten = true
        } finally {
            Arrays.fill(plain, 0)
            Arrays.fill(encrypted, 0)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        output.flush()
        outputStream.fd.sync()
        output.close()
    }
}

class EncryptedAudioReader private constructor(
    private val file: File,
    private val keyProvider: AudioWrappingKeyProvider,
    private val expectedAudioId: String,
) : Closeable {
    private val input = DataInputStream(BufferedInputStream(FileInputStream(file)))
    private val header = try {
        readHeader(input, keyProvider)
    } catch (error: Throwable) {
        input.close()
        throw error
    }
    private var nextChunk = 0
    private var chunkBuffer = ByteArray(0)
    private var chunkOffset = 0
    private var eof = false
    private var loadedPcmBytes = 0L

    init {
        if (header.audioId != expectedAudioId) {
            input.close()
            throw IllegalArgumentException("Audio container id does not match its attachment")
        }
    }

    val metadata: AudioMetadata = try {
        scanMetadata(file, keyProvider, expectedAudioId)
    } catch (error: Throwable) {
        input.close()
        throw error
    }

    fun pcmStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= target.size)
            if (length == 0) return 0
            if (chunkOffset >= chunkBuffer.size && !loadChunk()) return -1
            val count = minOf(length, chunkBuffer.size - chunkOffset)
            chunkBuffer.copyInto(target, offset, chunkOffset, chunkOffset + count)
            chunkOffset += count
            return count
        }

        override fun close() = this@EncryptedAudioReader.close()
    }

    fun wavStream(): InputStream {
        require(metadata.pcmBytes <= UINT32_MAX - WAV_HEADER_BYTES) { "Recording is too large for WAV" }
        val headerBytes = wavHeader(metadata)
        return SequenceInputStream(ByteArrayInputStream(headerBytes), pcmStream())
    }

    fun readFloatSamples(): FloatArray {
        require(metadata.pcmBytes <= Int.MAX_VALUE) { "Recording is too large to transcribe in one buffer" }
        val pcm = pcmStream().use(InputStream::readBytes)
        val samples = FloatArray(pcm.size / 2)
        var byteIndex = 0
        for (index in samples.indices) {
            val low = pcm[byteIndex].toInt() and 0xff
            val high = pcm[byteIndex + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort() / 32768f
            byteIndex += 2
        }
        Arrays.fill(pcm, 0)
        return samples
    }

    @Synchronized
    private fun loadChunk(): Boolean {
        if (eof) return false
        val index = try {
            input.readInt()
        } catch (_: EOFException) {
            throw EOFException("Finalized audio is missing its authenticated footer")
        }
        if (index == FOOTER_MARKER) {
            verifyFooter(input, header, nextChunk, loadedPcmBytes)
            require(input.read() == -1) { "Audio container has trailing bytes" }
            eof = true
            Arrays.fill(chunkBuffer, 0)
            chunkBuffer = ByteArray(0)
            chunkOffset = 0
            return false
        }
        val plainLength = input.readInt()
        val encryptedLength = input.readInt()
        require(index == nextChunk) { "Unexpected audio chunk index" }
        require(plainLength in 1..MAX_CHUNK_BYTES) { "Invalid audio chunk length" }
        require(encryptedLength == plainLength + GCM_TAG_BYTES) { "Invalid encrypted chunk length" }
        val encrypted = ByteArray(encryptedLength)
        input.readFully(encrypted)
        val decrypted = crypt(
            Cipher.DECRYPT_MODE,
            header.dataKey,
            chunkNonce(header.noncePrefix, index),
            chunkAad(
                header.audioId,
                header.sampleRate,
                header.channelCount,
                header.bitsPerSample,
                index,
                plainLength,
            ),
            encrypted,
        )
        Arrays.fill(encrypted, 0)
        Arrays.fill(chunkBuffer, 0)
        chunkBuffer = decrypted
        chunkOffset = 0
        nextChunk++
        loadedPcmBytes += plainLength
        return true
    }

    override fun close() {
        Arrays.fill(chunkBuffer, 0)
        input.close()
    }

    companion object {
        fun open(
            file: File,
            keyProvider: AudioWrappingKeyProvider,
            expectedAudioId: String,
        ): EncryptedAudioReader = EncryptedAudioReader(file, keyProvider, expectedAudioId)
    }
}

object EncryptedAudioRecovery {
    /** Truncates only an incomplete crash tail; authentication failures remain hard errors. */
    fun recoverPartial(
        partialFile: File,
        finalFile: File,
        keyProvider: AudioWrappingKeyProvider,
    ): AudioMetadata {
        check(!finalFile.exists()) { "Refusing to replace an existing finalized recording" }
        var expectedIndex = 0
        var pcmBytes = 0L
        lateinit var recoveredHeader: Header
        RandomAccessFile(partialFile, "rw").use { raf ->
            val header = readHeader(raf.asDataInputStream(), keyProvider)
            recoveredHeader = header
            require(header.audioId == finalFile.nameWithoutExtension) {
                "Recovered audio id does not match its destination"
            }
            var lastGoodOffset = raf.filePointer
            while (raf.filePointer < raf.length()) {
                val chunkStart = raf.filePointer
                if (raf.length() - chunkStart < Int.SIZE_BYTES) break
                val index = raf.readInt()
                if (index == FOOTER_MARKER) break
                if (raf.length() - raf.filePointer < CHUNK_HEADER_BYTES - Int.SIZE_BYTES) break
                val plainLength = raf.readInt()
                val encryptedLength = raf.readInt()
                require(index == expectedIndex) { "Unexpected audio chunk index" }
                require(plainLength in 1..MAX_CHUNK_BYTES) { "Invalid audio chunk length" }
                require(encryptedLength == plainLength + GCM_TAG_BYTES) { "Invalid encrypted chunk length" }
                if (raf.length() - raf.filePointer < encryptedLength) break
                val encrypted = ByteArray(encryptedLength)
                raf.readFully(encrypted)
                try {
                    val plain = crypt(
                        Cipher.DECRYPT_MODE,
                        header.dataKey,
                        chunkNonce(header.noncePrefix, index),
                        chunkAad(
                            header.audioId,
                            header.sampleRate,
                            header.channelCount,
                            header.bitsPerSample,
                            index,
                            plainLength,
                        ),
                        encrypted,
                    )
                    Arrays.fill(plain, 0)
                } finally {
                    Arrays.fill(encrypted, 0)
                }
                pcmBytes += plainLength
                expectedIndex++
                lastGoodOffset = raf.filePointer
            }
            check(pcmBytes > 0L) { "Interrupted recording has no complete audio" }
            raf.setLength(lastGoodOffset)
            raf.seek(lastGoodOffset)
            appendFooter(raf, header, expectedIndex, pcmBytes)
            raf.fd.sync()
        }
        runCatching {
            Files.move(
                partialFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            check(!finalFile.exists()) { "Refusing to replace an existing finalized recording" }
            check(partialFile.renameTo(finalFile)) { "Could not finalize recovered recording" }
        }
        return AudioMetadata(
            recoveredHeader.audioId,
            recoveredHeader.sampleRate,
            recoveredHeader.channelCount,
            recoveredHeader.bitsPerSample,
            pcmBytes,
        )
    }
}

private data class Header(
    val audioId: String,
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val noncePrefix: ByteArray,
    val dataKey: SecretKey,
)

private fun readHeader(input: java.io.DataInput, keyProvider: AudioWrappingKeyProvider): Header {
    val magic = ByteArray(MAGIC.size).also(input::readFully)
    require(magic.contentEquals(MAGIC)) { "Not a Soma audio container" }
    require(input.readUnsignedByte() == VERSION) { "Unsupported audio container version" }
    val audioId = input.readUTF()
    val sampleRate = input.readInt()
    val channels = input.readUnsignedShort()
    val bits = input.readUnsignedShort()
    require(AudioAttachment.isValidFileId(audioId) && sampleRate > 0 && channels == 1 && bits == 16) {
        "Invalid audio header"
    }
    val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(input::readFully)
    val wrapNonce = ByteArray(GCM_NONCE_BYTES).also(input::readFully)
    val wrappedLength = input.readInt()
    require(wrappedLength == KEY_BYTES + GCM_TAG_BYTES) { "Invalid wrapped audio key" }
    val wrapped = ByteArray(wrappedLength).also(input::readFully)
    val rawKey = crypt(
        Cipher.DECRYPT_MODE,
        keyProvider.getOrCreate(),
        wrapNonce,
        wrapAad(audioId, sampleRate, channels, bits),
        wrapped,
    )
    require(rawKey.size == KEY_BYTES) { "Invalid audio data key" }
    val key = SecretKeySpec(rawKey, AES)
    Arrays.fill(rawKey, 0)
    Arrays.fill(wrapped, 0)
    return Header(audioId, sampleRate, channels, bits, noncePrefix, key)
}

private fun scanMetadata(
    file: File,
    keyProvider: AudioWrappingKeyProvider,
    expectedAudioId: String,
): AudioMetadata = RandomAccessFile(file, "r").use { raf ->
    val header = readHeader(raf.asDataInputStream(), keyProvider)
    require(header.audioId == expectedAudioId) { "Audio container id does not match its attachment" }
    var total = 0L
    var expected = 0
    while (raf.filePointer < raf.length()) {
        if (raf.length() - raf.filePointer < Int.SIZE_BYTES) throw EOFException("Incomplete audio footer")
        val index = raf.readInt()
        if (index == FOOTER_MARKER) {
            verifyFooter(raf.asDataInputStream(), header, expected, total)
            require(raf.filePointer == raf.length()) { "Audio container has trailing bytes" }
            return@use AudioMetadata(
                header.audioId,
                header.sampleRate,
                header.channelCount,
                header.bitsPerSample,
                total,
            )
        }
        if (raf.length() - raf.filePointer < CHUNK_HEADER_BYTES - Int.SIZE_BYTES) {
            throw EOFException("Incomplete audio chunk")
        }
        val plain = raf.readInt()
        val encrypted = raf.readInt()
        require(index == expected++ && plain in 1..MAX_CHUNK_BYTES && encrypted == plain + GCM_TAG_BYTES)
        require(raf.length() - raf.filePointer >= encrypted) { "Incomplete audio chunk" }
        raf.seek(raf.filePointer + encrypted)
        total += plain
    }
    throw EOFException("Finalized audio is missing its authenticated footer")
}

private fun RandomAccessFile.asDataInputStream(): java.io.DataInput = this

private fun crypt(
    mode: Int,
    key: SecretKey,
    nonce: ByteArray,
    aad: ByteArray,
    input: ByteArray,
): ByteArray = Cipher.getInstance(AES_GCM).run {
    init(mode, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
    updateAAD(aad)
    doFinal(input)
}

private fun wrapAad(audioId: String, rate: Int, channels: Int, bits: Int): ByteArray =
    "Soma|audio-key|v$VERSION|$audioId|$rate|$channels|$bits".toByteArray(Charsets.UTF_8)

private fun chunkAad(
    audioId: String,
    rate: Int,
    channels: Int,
    bits: Int,
    index: Int,
    plainLength: Int,
): ByteArray = "Soma|audio-chunk|v$VERSION|$audioId|$rate|$channels|$bits|$index|$plainLength"
    .toByteArray(Charsets.UTF_8)

private fun footerAad(audioId: String, rate: Int, channels: Int, bits: Int): ByteArray =
    "Soma|audio-footer|v$VERSION|$audioId|$rate|$channels|$bits".toByteArray(Charsets.UTF_8)

private fun appendFooter(
    output: java.io.DataOutput,
    header: Header,
    chunkCount: Int,
    pcmBytes: Long,
) {
    val plain = ByteBuffer.allocate(FOOTER_PLAIN_BYTES).putInt(chunkCount).putLong(pcmBytes).array()
    val encrypted = crypt(
        Cipher.ENCRYPT_MODE,
        header.dataKey,
        chunkNonce(header.noncePrefix, FOOTER_NONCE_INDEX),
        footerAad(header.audioId, header.sampleRate, header.channelCount, header.bitsPerSample),
        plain,
    )
    try {
        output.writeInt(FOOTER_MARKER)
        output.writeInt(encrypted.size)
        output.write(encrypted)
    } finally {
        Arrays.fill(plain, 0)
        Arrays.fill(encrypted, 0)
    }
}

private fun verifyFooter(
    input: java.io.DataInput,
    header: Header,
    expectedChunkCount: Int,
    expectedPcmBytes: Long,
) {
    val encryptedLength = input.readInt()
    require(encryptedLength == FOOTER_PLAIN_BYTES + GCM_TAG_BYTES) { "Invalid audio footer length" }
    val encrypted = ByteArray(encryptedLength)
    input.readFully(encrypted)
    val plain = try {
        crypt(
            Cipher.DECRYPT_MODE,
            header.dataKey,
            chunkNonce(header.noncePrefix, FOOTER_NONCE_INDEX),
            footerAad(header.audioId, header.sampleRate, header.channelCount, header.bitsPerSample),
            encrypted,
        )
    } finally {
        Arrays.fill(encrypted, 0)
    }
    try {
        val footer = ByteBuffer.wrap(plain)
        require(footer.int == expectedChunkCount && footer.long == expectedPcmBytes) {
            "Audio footer does not match the encrypted chunks"
        }
    } finally {
        Arrays.fill(plain, 0)
    }
}

private fun chunkNonce(prefix: ByteArray, index: Int): ByteArray = ByteBuffer
    .allocate(GCM_NONCE_BYTES)
    .put(prefix)
    .putInt(index)
    .array()

private fun wavHeader(metadata: AudioMetadata): ByteArray {
    val pcmLength = metadata.pcmBytes.toInt()
    val byteRate = metadata.sampleRate * metadata.channelCount * metadata.bitsPerSample / 8
    return ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(pcmLength + WAV_HEADER_BYTES - 8)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(metadata.channelCount.toShort())
        putInt(metadata.sampleRate)
        putInt(byteRate)
        putShort((metadata.channelCount * metadata.bitsPerSample / 8).toShort())
        putShort(metadata.bitsPerSample.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(pcmLength)
    }.array()
}

private val MAGIC = "SOMAUDIO".toByteArray(Charsets.US_ASCII)
private const val VERSION = 1
private const val AES = "AES"
private const val AES_GCM = "AES/GCM/NoPadding"
private const val KEY_BYTES = 32
private const val GCM_NONCE_BYTES = 12
private const val NONCE_PREFIX_BYTES = 8
private const val GCM_TAG_BITS = 128
private const val GCM_TAG_BYTES = 16
private const val CHUNK_HEADER_BYTES = 12L
private const val FOOTER_MARKER = -1
private const val FOOTER_NONCE_INDEX = -1
private const val FOOTER_PLAIN_BYTES = Int.SIZE_BYTES + Long.SIZE_BYTES
private const val MAX_CHUNK_BYTES = 1024 * 1024
private const val WAV_HEADER_BYTES = 44
private const val UINT32_MAX = 0xffff_ffffL
