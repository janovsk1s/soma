import Foundation
import Network
import Observation
import Security

enum CloudSpeechProvider: String, CaseIterable, Identifiable, Sendable {
    case groq
    case elevenLabs

    var id: Self { self }

    var displayName: String {
        switch self {
        case .groq: "Groq"
        case .elevenLabs: "ElevenLabs"
        }
    }
}

enum GroqSpeechModel: String, CaseIterable, Identifiable, Sendable {
    case turbo
    case largeV3

    var id: Self { self }

    var apiID: String {
        switch self {
        case .turbo: "whisper-large-v3-turbo"
        case .largeV3: "whisper-large-v3"
        }
    }

    var displayName: String {
        switch self {
        case .turbo: "Turbo"
        case .largeV3: "Accuracy"
        }
    }

    var engine: TranscriptionEngine {
        switch self {
        case .turbo: .groqWhisperLargeV3Turbo
        case .largeV3: .groqWhisperLargeV3
        }
    }
}

struct LastIntelligenceError: Codable, Sendable {
    var reason: IntelligenceFailureReason
    var occurredAt: Date
}

@MainActor
@Observable
final class AISettings {
    var voiceTranscriptionEnabled: Bool {
        didSet { defaults.set(voiceTranscriptionEnabled, forKey: Keys.voiceTranscriptionEnabled) }
    }
    var cloudTranscriptionEnabled: Bool {
        didSet { defaults.set(cloudTranscriptionEnabled, forKey: Keys.cloudTranscriptionEnabled) }
    }
    var onDeviceSuggestionsEnabled: Bool {
        didSet { defaults.set(onDeviceSuggestionsEnabled, forKey: Keys.onDeviceSuggestionsEnabled) }
    }
    var cloudSuggestionsEnabled: Bool {
        didSet { defaults.set(cloudSuggestionsEnabled, forKey: Keys.cloudSuggestionsEnabled) }
    }
    var trackingSuggestionsEnabled: Bool {
        didSet { defaults.set(trackingSuggestionsEnabled, forKey: Keys.trackingSuggestionsEnabled) }
    }
    var provider: CloudSpeechProvider {
        didSet { defaults.set(provider.rawValue, forKey: Keys.provider) }
    }
    var groqModel: GroqSpeechModel {
        didSet { defaults.set(groqModel.rawValue, forKey: Keys.groqModel) }
    }
    var wifiOnly: Bool {
        didSet { defaults.set(wifiOnly, forKey: Keys.wifiOnly) }
    }
    private(set) var lastError: LastIntelligenceError?
    private(set) var secretRevision = 0

    private let defaults: UserDefaults
    private let secrets: KeychainSecretStore

    init(
        defaults: UserDefaults = .standard,
        secrets: KeychainSecretStore = KeychainSecretStore()
    ) {
        self.defaults = defaults
        self.secrets = secrets
        voiceTranscriptionEnabled = defaults.object(forKey: Keys.voiceTranscriptionEnabled) as? Bool ?? true
        cloudTranscriptionEnabled = defaults.bool(forKey: Keys.cloudTranscriptionEnabled)
        onDeviceSuggestionsEnabled = defaults.object(forKey: Keys.onDeviceSuggestionsEnabled) as? Bool ?? true
        cloudSuggestionsEnabled = defaults.bool(forKey: Keys.cloudSuggestionsEnabled)
        trackingSuggestionsEnabled = defaults.object(forKey: Keys.trackingSuggestionsEnabled) as? Bool ?? true
        provider = CloudSpeechProvider(
            rawValue: defaults.string(forKey: Keys.provider) ?? ""
        ) ?? .groq
        groqModel = GroqSpeechModel(
            rawValue: defaults.string(forKey: Keys.groqModel) ?? ""
        ) ?? .turbo
        wifiOnly = defaults.bool(forKey: Keys.wifiOnly)
        if
            let rawReason = defaults.string(forKey: Keys.lastErrorReason),
            let reason = IntelligenceFailureReason(rawValue: rawReason),
            let date = defaults.object(forKey: Keys.lastErrorDate) as? Date
        {
            lastError = LastIntelligenceError(reason: reason, occurredAt: date)
        }
    }

    func hasKey(for provider: CloudSpeechProvider) -> Bool {
        _ = secretRevision
        return (try? secrets.contains(provider)) == true
    }

    func key(for provider: CloudSpeechProvider) -> String? {
        try? secrets.read(provider)
    }

    func setKey(_ value: String, for provider: CloudSpeechProvider) throws {
        try secrets.write(value, for: provider)
        secretRevision &+= 1
    }

    func record(_ reason: IntelligenceFailureReason) {
        let error = LastIntelligenceError(reason: reason, occurredAt: Date())
        lastError = error
        defaults.set(reason.rawValue, forKey: Keys.lastErrorReason)
        defaults.set(error.occurredAt, forKey: Keys.lastErrorDate)
    }

    func clearLastError() {
        lastError = nil
        defaults.removeObject(forKey: Keys.lastErrorReason)
        defaults.removeObject(forKey: Keys.lastErrorDate)
    }

    private enum Keys {
        static let voiceTranscriptionEnabled = "ios.ai.voiceTranscription"
        static let cloudTranscriptionEnabled = "ios.ai.cloudTranscription"
        static let onDeviceSuggestionsEnabled = "ios.ai.onDeviceSuggestions"
        static let cloudSuggestionsEnabled = "ios.ai.cloudSuggestions"
        static let trackingSuggestionsEnabled = "ios.ai.trackingSuggestions"
        static let provider = "ios.ai.speechProvider"
        static let groqModel = "ios.ai.groqSpeechModel"
        static let wifiOnly = "ios.ai.wifiOnly"
        static let lastErrorReason = "ios.ai.lastErrorReason"
        static let lastErrorDate = "ios.ai.lastErrorDate"
    }
}

struct KeychainSecretStore: Sendable {
    private let service = "com.soma.native.ai-credentials.v1"

    func contains(_ provider: CloudSpeechProvider) throws -> Bool {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: provider.rawValue,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        if status == errSecItemNotFound { return false }
        guard status == errSecSuccess else { throw KeychainError(status) }
        return true
    }

    func read(_ provider: CloudSpeechProvider) throws -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: provider.rawValue,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw KeychainError(status)
        }
        return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func write(_ value: String, for provider: CloudSpeechProvider) throws {
        let cleaned = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let match: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: provider.rawValue,
        ]
        guard !cleaned.isEmpty else {
            let status = SecItemDelete(match as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw KeychainError(status)
            }
            return
        }
        var data = Data(cleaned.utf8)
        defer { data.resetBytes(in: data.indices) }
        let attributes: [CFString: Any] = [
            kSecValueData: data,
            kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(match as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else { throw KeychainError(updateStatus) }
        let insert = match.merging(attributes) { _, new in new }
        let status = SecItemAdd(insert as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status) }
    }
}

struct KeychainError: LocalizedError {
    let status: OSStatus

    init(_ status: OSStatus) {
        self.status = status
    }

    var errorDescription: String? {
        SecCopyErrorMessageString(status, nil) as String? ?? "Keychain error \(status)"
    }
}

final class WiFiMonitor: @unchecked Sendable {
    static let shared = WiFiMonitor()

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.soma.native.network")
    private let lock = NSLock()
    private var path: NWPath?

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            lock.withLock { self.path = path }
        }
        monitor.start(queue: queue)
    }

    var isOnWiFi: Bool {
        lock.withLock {
            let current = path ?? monitor.currentPath
            return current.status == .satisfied && current.usesInterfaceType(.wifi)
        }
    }
}
