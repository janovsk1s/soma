package com.soma.app.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.soma.core.bridge.BridgeCapability
import com.soma.core.bridge.BridgeClientPlatform
import com.soma.core.bridge.BridgePairRequest
import com.soma.core.bridge.BridgeRequestSigningInput
import com.soma.core.bridge.BridgeSignedRequestHeaders
import com.soma.core.bridge.BridgeWireCrypto
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * P-256 signing identity whose private key never leaves Android Keystore.
 *
 * The stable device UUID is rotated automatically if the Keystore key changes,
 * preventing a server-side identity from silently acquiring a different key.
 */
class AndroidBridgeDeviceIdentity(
    context: Context,
    private val alias: String = DEFAULT_ALIAS,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        IDENTITY_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val random = SecureRandom()

    init {
        require(alias.isNotBlank()) { "Bridge identity alias must not be blank" }
    }

    @Synchronized
    fun pairRequest(
        deviceName: String,
        requestedCapabilities: Set<BridgeCapability>,
    ): BridgePairRequest {
        val material = material()
        return BridgePairRequest(
            deviceID = material.deviceID,
            deviceName = deviceName.trim(),
            platform = BridgeClientPlatform.ANDROID,
            publicKey = Base64.getEncoder().encodeToString(material.publicKeyX963),
            requestedCapabilities = requestedCapabilities,
        )
    }

    @Synchronized
    fun deviceID(): String = material().deviceID

    @Synchronized
    fun sign(input: BridgeRequestSigningInput): BridgeSignedRequestHeaders = secureOperation {
        val material = material()
        require(input.deviceID == material.deviceID) {
            "Request belongs to a different or rotated Soma bridge identity"
        }
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(material.keyPair.private)
        signer.update(input.canonicalBytes())
        BridgeSignedRequestHeaders(
            input = input,
            signature = Base64.getEncoder().encodeToString(signer.sign()),
        )
    }

    fun newNonce(): String = ByteArray(com.soma.core.bridge.SomaBridgeProtocol.NONCE_BYTES)
        .also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** Revocation helper. Existing bridge pairings must be cleared separately. */
    @Synchronized
    fun deleteIdentity() = secureOperation {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        check(preferences.edit().clear().commit()) { "Could not clear Soma bridge identity state" }
    }

    private fun material(): IdentityMaterial = secureOperation {
        val keyPair = getOrCreateKeyPair()
        val publicKey = keyPair.public as? ECPublicKey
            ?: throw BridgeIdentityException("Soma bridge identity is not an EC key")
        val x963 = p256X963(publicKey)
        val fingerprint = BridgeWireCrypto.sha256Fingerprint(x963)
        val storedFingerprint = preferences.getString(KEY_PUBLIC_FINGERPRINT, null)
        val storedDeviceID = preferences.getString(KEY_DEVICE_ID, null)
            ?.let { stored -> runCatching { UUID.fromString(stored).toString().uppercase() }.getOrNull() }
        val deviceID = if (storedFingerprint == fingerprint && storedDeviceID != null) {
            storedDeviceID
        } else {
            UUID.randomUUID().toString().uppercase().also { replacement ->
                check(
                    preferences.edit()
                        .putString(KEY_DEVICE_ID, replacement)
                        .putString(KEY_PUBLIC_FINGERPRINT, fingerprint)
                        .commit(),
                ) { "Could not persist Soma bridge device identity" }
            }
        }
        IdentityMaterial(keyPair, deviceID, x963)
    }

    private fun getOrCreateKeyPair(): KeyPair {
        val keyStore = loadKeyStore()
        val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return KeyPair(existing.certificate.publicKey, existing.privateKey)

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKeyPair()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private inline fun <T> secureOperation(block: () -> T): T = try {
        block()
    } catch (error: BridgeIdentityException) {
        throw error
    } catch (error: GeneralSecurityException) {
        throw BridgeIdentityException("Android Keystore Soma bridge operation failed", error)
    }

    private data class IdentityMaterial(
        val keyPair: KeyPair,
        val deviceID: String,
        val publicKeyX963: ByteArray,
    )

    companion object {
        const val DEFAULT_ALIAS = "soma_bridge_device_p256_v1"
        private const val IDENTITY_PREFERENCES = "soma_bridge_identity_v1"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_FINGERPRINT = "public_key_fingerprint"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val P256_CURVE = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

class BridgeIdentityException(
    message: String,
    cause: Throwable? = null,
) : SecurityException(message, cause)

/** Converts a Java P-256 public key to the cross-platform uncompressed X9.63 form. */
internal fun p256X963(publicKey: ECPublicKey): ByteArray {
    require(publicKey.params.curve.field.fieldSize == P256_COORDINATE_BITS) {
        "Soma bridge identity must use P-256"
    }
    val x = publicKey.w.affineX.toUnsignedFixed(P256_COORDINATE_BYTES)
    val y = publicKey.w.affineY.toUnsignedFixed(P256_COORDINATE_BYTES)
    return byteArrayOf(UNCOMPRESSED_POINT_PREFIX) + x + y
}

private fun BigInteger.toUnsignedFixed(size: Int): ByteArray {
    require(signum() >= 0) { "EC coordinate must not be negative" }
    val encoded = toByteArray()
    val unsigned = if (encoded.size == size + 1 && encoded.first() == 0.toByte()) {
        encoded.copyOfRange(1, encoded.size)
    } else {
        encoded
    }
    require(unsigned.size <= size) { "EC coordinate is too large" }
    return ByteArray(size - unsigned.size) + unsigned
}

private const val P256_COORDINATE_BITS = 256
private const val P256_COORDINATE_BYTES = 32
private const val UNCOMPRESSED_POINT_PREFIX: Byte = 0x04
