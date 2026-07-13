package com.soma.storage.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encodes portable, passphrase-protected Soma backups without any network or JSON dependency.
 *
 * The complete binary header, including KDF parameters, salt, IV, payload version, and encrypted
 * length, is authenticated as AES-GCM AAD. Import reports the same authentication failure for an
 * incorrect passphrase and for authenticated-byte tampering, avoiding a password oracle.
 *
 * Callers should clear their own [CharArray] after this method returns. The codec works on a copy
 * and clears that copy, derived key bytes, and plaintext serialization buffers in `finally` blocks.
 */
class PortableBackupCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    @Throws(BackupException::class)
    fun encode(snapshot: BackupSnapshot, newPassphrase: CharArray): ByteArray {
        validateNewPassphrase(newPassphrase)
        val password = newPassphrase.copyOf()
        var plaintext: ByteArray? = null
        var keyBytes: ByteArray? = null
        var ciphertext: ByteArray? = null
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
        try {
            plaintext = BackupPayloadCodec.encode(snapshot)
            if (plaintext.size > MAX_PLAINTEXT_BYTES) {
                throw BackupFormatException("Backup payload is too large")
            }
            val encryptedLength = Math.addExact(plaintext.size, GCM_TAG_BYTES)
            val header = buildHeader(
                payloadVersion = snapshot.payloadVersion,
                salt = salt,
                iv = iv,
                ciphertextLength = encryptedLength,
            )
            keyBytes = deriveKey(password, salt)
            ciphertext = encrypt(plaintext, keyBytes, iv, header)
            check(ciphertext.size == encryptedLength) { "Unexpected AES-GCM output length" }
            return ByteArray(header.size + ciphertext.size).also { encoded ->
                header.copyInto(encoded)
                ciphertext.copyInto(encoded, header.size)
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw BackupCryptoException(error)
        } catch (error: ArithmeticException) {
            throw BackupFormatException("Backup payload is too large", error)
        } finally {
            Arrays.fill(password, '\u0000')
            plaintext?.let { Arrays.fill(it, 0) }
            keyBytes?.let { Arrays.fill(it, 0) }
            ciphertext?.let { Arrays.fill(it, 0) }
        }
    }

    @Throws(BackupException::class)
    fun decode(encoded: ByteArray, passphrase: CharArray): BackupSnapshot {
        val parsed = parseHeader(encoded)
        val password = passphrase.copyOf()
        var keyBytes: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            keyBytes = deriveKey(password, parsed.salt)
            plaintext = decrypt(
                encoded = encoded,
                ciphertextOffset = parsed.headerLength,
                ciphertextLength = parsed.ciphertextLength,
                keyBytes = keyBytes,
                iv = parsed.iv,
                aad = encoded.copyOfRange(0, parsed.headerLength),
            )
            return BackupPayloadCodec.decode(plaintext, parsed.payloadVersion)
        } catch (error: AEADBadTagException) {
            throw BackupAuthenticationException(error)
        } catch (error: BackupException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw BackupCryptoException(error)
        } finally {
            Arrays.fill(password, '\u0000')
            keyBytes?.let { Arrays.fill(it, 0) }
            plaintext?.let { Arrays.fill(it, 0) }
        }
    }

    private fun validateNewPassphrase(passphrase: CharArray) {
        if (passphrase.size < MINIMUM_PASSPHRASE_LENGTH) {
            throw BackupPassphraseException(
                "Backup passphrase must contain at least $MINIMUM_PASSPHRASE_LENGTH characters",
            )
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val specification = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(JCA_KDF).generateSecret(specification).encoded
        } finally {
            specification.clearPassword()
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun encrypt(
        plaintext: ByteArray,
        keyBytes: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): ByteArray = Cipher.getInstance(JCA_CIPHER).run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, AES), GCMParameterSpec(GCM_TAG_BITS, iv))
        updateAAD(aad)
        doFinal(plaintext)
    }

    @Throws(GeneralSecurityException::class)
    private fun decrypt(
        encoded: ByteArray,
        ciphertextOffset: Int,
        ciphertextLength: Int,
        keyBytes: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): ByteArray = Cipher.getInstance(JCA_CIPHER).run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, AES), GCMParameterSpec(GCM_TAG_BITS, iv))
        updateAAD(aad)
        doFinal(encoded, ciphertextOffset, ciphertextLength)
    }

    private fun buildHeader(
        payloadVersion: Int,
        salt: ByteArray,
        iv: ByteArray,
        ciphertextLength: Int,
    ): ByteArray {
        val buffer = ByteArrayOutputStream(128)
        DataOutputStream(buffer).use { output ->
            output.write(MAGIC)
            output.writeInt(CONTAINER_VERSION)
            output.writeInt(payloadVersion)
            output.writeAscii(KDF_ID)
            output.writeInt(PBKDF2_ITERATIONS)
            output.writeInt(KEY_BITS)
            output.writeInt(salt.size)
            output.write(salt)
            output.writeAscii(CIPHER_ID)
            output.writeInt(iv.size)
            output.write(iv)
            output.writeInt(ciphertextLength)
        }
        return buffer.toByteArray()
    }

    private fun parseHeader(encoded: ByteArray): ParsedHeader {
        if (encoded.size !in MINIMUM_BACKUP_BYTES..MAX_BACKUP_BYTES) {
            throw BackupFormatException("Backup size is invalid")
        }
        try {
            val byteInput = ByteArrayInputStream(encoded)
            val input = DataInputStream(byteInput)
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw BackupFormatException("Not a Soma backup")
            val containerVersion = input.readInt()
            if (containerVersion != CONTAINER_VERSION) {
                throw BackupFormatException("Unsupported backup container version: $containerVersion")
            }
            val payloadVersion = input.readInt()
            if (payloadVersion !in BackupSnapshot.SUPPORTED_PAYLOAD_VERSIONS) {
                throw BackupFormatException("Unsupported backup payload version: $payloadVersion")
            }
            if (input.readAscii() != KDF_ID) throw BackupFormatException("Unsupported backup KDF")
            if (input.readInt() != PBKDF2_ITERATIONS) {
                throw BackupFormatException("Unsupported PBKDF2 iteration count")
            }
            if (input.readInt() != KEY_BITS) throw BackupFormatException("Unsupported key size")
            val salt = input.readExactBytes(SALT_BYTES, "salt")
            if (input.readAscii() != CIPHER_ID) throw BackupFormatException("Unsupported cipher")
            val iv = input.readExactBytes(IV_BYTES, "IV")
            val ciphertextLength = input.readInt()
            if (ciphertextLength < GCM_TAG_BYTES || ciphertextLength > MAX_CIPHERTEXT_BYTES) {
                throw BackupFormatException("Invalid encrypted payload length")
            }
            val headerLength = encoded.size - byteInput.available()
            if (byteInput.available() != ciphertextLength) {
                throw BackupFormatException("Backup is truncated or has trailing bytes")
            }
            return ParsedHeader(payloadVersion, salt, iv, ciphertextLength, headerLength)
        } catch (error: BackupException) {
            throw error
        } catch (error: EOFException) {
            throw BackupFormatException("Backup header is truncated", error)
        }
    }

    private fun DataOutputStream.writeAscii(value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readAscii(): String {
        val size = readInt()
        if (size !in 1..MAX_HEADER_ID_BYTES) throw BackupFormatException("Invalid header id length")
        val bytes = ByteArray(size)
        readFully(bytes)
        if (bytes.any { it.toInt() !in 0x20..0x7e }) {
            throw BackupFormatException("Header id is not ASCII")
        }
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun DataInputStream.readExactBytes(expectedSize: Int, label: String): ByteArray {
        if (readInt() != expectedSize) throw BackupFormatException("Invalid $label length")
        return ByteArray(expectedSize).also(::readFully)
    }

    private data class ParsedHeader(
        val payloadVersion: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val ciphertextLength: Int,
        val headerLength: Int,
    )

    companion object {
        const val CONTAINER_VERSION: Int = 1
        const val PBKDF2_ITERATIONS: Int = 600_000
        const val MINIMUM_PASSPHRASE_LENGTH: Int = 12
        const val SALT_BYTES: Int = 16
        const val IV_BYTES: Int = 12
        const val KEY_BITS: Int = 256

        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val AES = "AES"
        private const val JCA_KDF = "PBKDF2WithHmacSHA256"
        private const val JCA_CIPHER = "AES/GCM/NoPadding"
        private const val KDF_ID = "PBKDF2-HMAC-SHA256"
        private const val CIPHER_ID = "AES-256-GCM"
        private const val MAX_HEADER_ID_BYTES = 64
        private const val MAX_PLAINTEXT_BYTES = 128 * 1024 * 1024
        private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
        private const val MAX_BACKUP_BYTES = MAX_CIPHERTEXT_BYTES + 1_024
        private const val MINIMUM_BACKUP_BYTES = 100
        private val MAGIC = "SOMABACK".toByteArray(Charsets.US_ASCII)
    }
}
