package com.soma.app.bridge

import android.content.Context
import com.soma.core.bridge.BridgeCapability
import com.soma.core.bridge.BridgePairingRecord
import com.soma.core.bridge.BridgeWireCrypto
import com.soma.storage.crypto.AndroidKeystoreTextCipher
import com.soma.storage.crypto.TextCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID

/**
 * Encrypted connected-flavor storage for verified bridge pairings.
 *
 * Pairing QR secrets cannot enter this API: [BridgePairingRecord] deliberately
 * has no secret field. Records are AES-256-GCM encrypted with a dedicated,
 * non-exportable Android Keystore key and bound to their preference key as AAD.
 */
class AndroidBridgePairingStore(
    context: Context,
    private val cipher: TextCipher = AndroidKeystoreTextCipher(KEYSTORE_ALIAS),
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun list(): List<BridgePairingRecord> = preferences.all.entries.asSequence()
        .filter { (key, _) -> key.startsWith(RECORD_PREFIX) }
        .map { (key, value) ->
            val encoded = value as? String
                ?: throw BridgePairingStoreException("Bridge pairing storage has an invalid value")
            decodeRecord(key, encoded)
        }
        .sortedBy { it.bridge.toString() }
        .toList()

    @Synchronized
    fun find(bridge: UUID): BridgePairingRecord? {
        val key = storageKey(bridge)
        val encoded = preferences.getString(key, null) ?: return null
        return decodeRecord(key, encoded)
    }

    @Synchronized
    fun save(record: BridgePairingRecord) {
        saveLocked(record)
    }

    /**
     * Durably reserves the next anti-replay sequence before any request is
     * signed. A crash can create a harmless gap but can never reuse a number.
     */
    @Synchronized
    fun reserveNextSequence(bridge: UUID): BridgePairingRecord {
        val current = find(bridge)
            ?: throw BridgePairingStoreException("Soma bridge is not paired")
        if (current.lastSequence == Long.MAX_VALUE) {
            throw BridgePairingStoreException("Bridge request sequence is exhausted; pair again")
        }
        return current.copy(lastSequence = current.lastSequence + 1).also(::saveLocked)
    }

    @Synchronized
    fun remove(bridge: UUID) {
        check(preferences.edit().remove(storageKey(bridge)).commit()) {
            "Could not remove Soma bridge pairing"
        }
    }

    @Synchronized
    fun clear() {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(RECORD_PREFIX) }.forEach(editor::remove)
        check(editor.commit()) { "Could not clear Soma bridge pairings" }
    }

    private fun saveLocked(record: BridgePairingRecord) {
        val key = storageKey(record.bridge)
        val plaintext = BridgePairingRecordCodec.encode(record)
        val encrypted = cipher.encrypt(plaintext, aad(key))
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        if (!preferences.edit().putString(key, encoded).commit()) {
            throw BridgePairingStoreException("Could not persist Soma bridge pairing")
        }
    }

    private fun decodeRecord(key: String, encoded: String): BridgePairingRecord = try {
        val encrypted = Base64.getDecoder().decode(encoded)
        val plaintext = cipher.decrypt(encrypted, aad(key))
        BridgePairingRecordCodec.decode(plaintext).also { record ->
            require(storageKey(record.bridge) == key) { "Pairing record is bound to another bridge" }
        }
    } catch (error: Exception) {
        throw BridgePairingStoreException("Could not authenticate Soma bridge pairing", error)
    }

    private fun storageKey(bridge: UUID): String =
        RECORD_PREFIX + BridgeWireCrypto.sha256Hex(bridge.toString().encodeToByteArray())

    private fun aad(storageKey: String): ByteArray =
        "$AAD_DOMAIN\n$storageKey".encodeToByteArray()

    companion object {
        private const val PREFERENCES_FILE = "soma_bridge_pairings_v1"
        private const val KEYSTORE_ALIAS = "soma_bridge_pairings_aes_v1"
        private const val RECORD_PREFIX = "pairing."
        private const val AAD_DOMAIN = "soma-bridge-pairing-v1"
    }
}

class BridgePairingStoreException(
    message: String,
    cause: Throwable? = null,
) : SecurityException(message, cause)

/**
 * Small deterministic binary codec used only inside the encrypted preference
 * payload. It is independently versioned so storage can migrate without
 * changing the network protocol.
 */
internal object BridgePairingRecordCodec {
    private const val STORAGE_VERSION = 1
    private const val MAX_ENCODED_BYTES = 8_192
    private const val MAX_STRING_BYTES = 512

    fun encode(record: BridgePairingRecord): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(STORAGE_VERSION)
            data.writeBoundedUtf8(record.bridge.toString())
            data.writeBoundedUtf8(record.host)
            data.writeInt(record.port)
            data.writeBoundedUtf8(record.fingerprint)
            data.writeBoundedUtf8(record.deviceID)
            data.writeLong(record.pairedAtEpochMillis)
            data.writeLong(record.lastSequence)
            val capabilities = record.grantedCapabilities.sortedBy(BridgeCapability::wireName)
            data.writeInt(capabilities.size)
            capabilities.forEach { capability -> data.writeBoundedUtf8(capability.wireName) }
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_ENCODED_BYTES) { "Bridge pairing record is too large" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(value: String): BridgePairingRecord {
        val bytes = runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException("Pairing record encoding is invalid", it) }
        require(bytes.size <= MAX_ENCODED_BYTES) { "Bridge pairing record is too large" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == STORAGE_VERSION) { "Unsupported pairing storage version" }
            val bridge = UUID.fromString(data.readBoundedUtf8())
            val host = data.readBoundedUtf8()
            val port = data.readInt()
            val fingerprint = data.readBoundedUtf8()
            val deviceID = data.readBoundedUtf8()
            val pairedAt = data.readLong()
            val lastSequence = data.readLong()
            val capabilityCount = data.readInt()
            require(capabilityCount in 1..BridgeCapability.entries.size) {
                "Pairing capability count is invalid"
            }
            val capabilities = buildSet {
                repeat(capabilityCount) {
                    add(BridgeCapability.fromWireName(data.readBoundedUtf8()))
                }
            }
            require(capabilities.size == capabilityCount) { "Pairing capabilities contain duplicates" }
            require(data.available() == 0) { "Pairing record has trailing data" }
            BridgePairingRecord(
                bridge = bridge,
                host = host,
                port = port,
                fingerprint = fingerprint,
                deviceID = deviceID,
                grantedCapabilities = capabilities,
                pairedAtEpochMillis = pairedAt,
                lastSequence = lastSequence,
            )
        }
    }

    private fun DataOutputStream.writeBoundedUtf8(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_STRING_BYTES) { "Pairing record string is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedUtf8(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES && size <= available()) {
            "Pairing record string length is invalid"
        }
        return ByteArray(size).also(::readFully).decodeToString(throwOnInvalidSequence = true)
    }
}
