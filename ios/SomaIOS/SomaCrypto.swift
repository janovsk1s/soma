import CryptoKit
import Foundation
import Security

/// Encrypts the context snapshot at rest with AES-256-GCM. The key lives only in
/// the Keychain (device-only, available after first unlock so launch-time loads
/// never race the lock screen), and the blob carries a magic prefix so a legacy
/// plaintext snapshot stays readable for one-time migration.
struct SnapshotCipher: Sendable {
    static let magic = Data("SOMAENC1".utf8)

    private let keyStore: SnapshotKeyStore

    init(keyStore: SnapshotKeyStore = SnapshotKeyStore()) {
        self.keyStore = keyStore
    }

    func isEncrypted(_ data: Data) -> Bool {
        data.starts(with: Self.magic)
    }

    func seal(_ plaintext: Data) throws -> Data {
        let key = try keyStore.loadOrCreate()
        let box = try AES.GCM.seal(plaintext, using: key)
        guard let combined = box.combined else {
            throw SnapshotCipherError.sealFailed
        }
        return Self.magic + combined
    }

    func open(_ data: Data) throws -> Data {
        guard isEncrypted(data) else {
            throw SnapshotCipherError.notEncrypted
        }
        let key = try keyStore.loadOrCreate()
        let box = try AES.GCM.SealedBox(combined: data.dropFirst(Self.magic.count))
        return try AES.GCM.open(box, using: key)
    }
}

enum SnapshotCipherError: Error {
    case notEncrypted
    case sealFailed
}

struct SnapshotKeyStore: Sendable {
    private let service = "com.soma.native.snapshot-key.v1"
    private let account = "context"

    func loadOrCreate() throws -> SymmetricKey {
        if let existing = try read() {
            return existing
        }
        let key = SymmetricKey(size: .bits256)
        var data = key.withUnsafeBytes { Data($0) }
        defer { data.resetBytes(in: data.indices) }
        let attributes: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecValueData: data,
            kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status == errSecDuplicateItem, let concurrent = try read() {
            return concurrent
        }
        guard status == errSecSuccess else { throw KeychainError(status) }
        return key
    }

    private func read() throws -> SymmetricKey? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data, data.count == 32 else {
            throw KeychainError(status)
        }
        return SymmetricKey(data: data)
    }
}
