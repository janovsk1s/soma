import Foundation

enum SomaEntryKind: String, Codable, Sendable {
    case text
    case voice
}

enum TranscriptionState: String, Codable, Sendable {
    case queued
    case running
    case succeeded
    case failed
}

enum TranscriptionEngine: String, Codable, Sendable {
    case appleSpeech
    case groqWhisperLargeV3Turbo
    case groqWhisperLargeV3
    case elevenLabsScribeV2

    var displayName: String {
        switch self {
        case .appleSpeech: "Apple Speech"
        case .groqWhisperLargeV3Turbo: "Groq Whisper Turbo"
        case .groqWhisperLargeV3: "Groq Whisper Large v3"
        case .elevenLabsScribeV2: "ElevenLabs Scribe v2"
        }
    }
}

enum IntelligenceFailureReason: String, Codable, Sendable {
    case wifiRequired
    case apiKeyMissing
    case providerError
    case authenticationError
    case permissionError
    case paymentRequired
    case rateLimited
    case invalidRequest
    case networkError
    case onDeviceUnavailable

    var displayName: String {
        switch self {
        case .wifiRequired: "Wi-Fi required"
        case .apiKeyMissing: "API key missing"
        case .providerError: "Provider error"
        case .authenticationError: "Key rejected"
        case .permissionError: "Provider access denied"
        case .paymentRequired: "Credits required"
        case .rateLimited: "Rate limited"
        case .invalidRequest: "Request rejected"
        case .networkError: "Network error"
        case .onDeviceUnavailable: "On-device model unavailable"
        }
    }
}

struct TranscriptionProvenance: Codable, Hashable, Sendable {
    var requestedEngine: TranscriptionEngine
    var usedEngine: TranscriptionEngine
    var fallbackReason: IntelligenceFailureReason?
}

struct SomaEntry: Identifiable, Codable, Hashable, Sendable {
    var id: UUID
    var day: String
    var kind: SomaEntryKind
    var text: String
    var createdAt: Date
    var updatedAt: Date
    var audioFileName: String?
    var audioDuration: TimeInterval?
    var imageFileName: String? = nil
    var deletedAt: Date? = nil
    var transcriptionState: TranscriptionState? = nil
    var transcriptionProvenance: TranscriptionProvenance? = nil
    var lastUserEditedAt: Date? = nil
    var transcriptionRunID: UUID? = nil

    var isDeleted: Bool { deletedAt != nil }
}

enum ImportantState: String, Codable, Sendable {
    case open
    case done
}

struct ImportantItem: Identifiable, Codable, Hashable, Sendable {
    var id: UUID
    var text: String
    var state: ImportantState
    var createdAt: Date
    var updatedAt: Date
    var sourceEntryID: UUID?
    var deletedAt: Date? = nil

    var isDeleted: Bool { deletedAt != nil }
}

enum SuggestionEngine: String, Codable, Sendable {
    case localRules
    case appleFoundationModel
    case groqGPTOSS20B

    var displayName: String {
        switch self {
        case .localRules: "On-device rules"
        case .appleFoundationModel: "On-device Apple Intelligence"
        case .groqGPTOSS20B: "Groq GPT-OSS 20B"
        }
    }
}

struct EntryRevision: Identifiable, Codable, Hashable, Sendable {
    var id: UUID
    var entryID: UUID
    var text: String
    var recordedAt: Date
}

enum SomaLogKind: String, Codable, Sendable {
    case meal
    case workout

    var systemImage: String {
        switch self {
        case .meal: "fork.knife"
        case .workout: "figure.run"
        }
    }

    var question: String {
        switch self {
        case .meal: "Log meal?"
        case .workout: "Log workout?"
        }
    }
}

struct SomaLog: Identifiable, Codable, Hashable, Sendable {
    var id: UUID
    var day: String
    var kind: SomaLogKind
    var title: String
    var createdAt: Date
    var updatedAt: Date
    var sourceEntryID: UUID? = nil
}

struct TrackingSuggestion: Identifiable, Codable, Hashable, Sendable {
    var id: UUID
    var entryID: UUID
    var kind: SomaLogKind
    var text: String
    var engine: SuggestionEngine
    var createdAt: Date
    var dismissedAt: Date? = nil

    var isPending: Bool { dismissedAt == nil }
}

struct ImportantSuggestion: Identifiable, Codable, Hashable, Sendable {
    var id: UUID
    var entryID: UUID
    var text: String
    var engine: SuggestionEngine
    var createdAt: Date
    var dismissedAt: Date? = nil

    var isPending: Bool { dismissedAt == nil }
}

struct SomaContextBundle: Codable, Sendable {
    static let currentSchemaVersion = 2
    static let supportedSchemaVersions = 1...currentSchemaVersion

    var schemaVersion: Int
    var exportedAt: Date
    var sourceDeviceID: UUID
    var entries: [SomaEntry]
    var important: [ImportantItem]
}

enum SomaDay {
    /// Day keys are persisted identifiers shared across devices, so the format must
    /// not follow the user's locale: non-Gregorian calendars and non-Latin numerals
    /// would fork the data. Only the time zone stays local — a "day" is a local day.
    static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .autoupdatingCurrent
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    static func key(_ date: Date) -> String {
        formatter.string(from: date)
    }

    static func date(fromKey key: String) -> Date? {
        formatter.date(from: key)
    }
}
