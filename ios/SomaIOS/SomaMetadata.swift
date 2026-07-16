import Foundation
import CryptoKit
import NaturalLanguage

struct GeneratedEntryMetadata: Sendable {
    var tags: [String]
    var people: [String]
    var places: [String]
    var organizations: [String]
    var keywords: [String]

    func merging(tags modelTags: [String]) -> GeneratedEntryMetadata {
        var copy = self
        copy.tags = Self.unique(modelTags + tags, limit: 5)
        return copy
    }

    fileprivate static func unique(_ values: [String], limit: Int) -> [String] {
        var seen = Set<String>()
        return values.compactMap { value in
            let cleaned = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !cleaned.isEmpty else { return nil }
            let key = SomaMetadataAnalyzer.fold(cleaned)
            guard !key.isEmpty, seen.insert(key).inserted else { return nil }
            return cleaned
        }
        .prefix(limit)
        .map { $0 }
    }
}

struct MetadataConnectionCandidate: Sendable {
    var entry: SomaEntry
    var metadata: EntryMetadata
}

struct EntryConnectionProposal: Sendable {
    var targetEntryID: UUID
    var kind: EntryConnectionKind
    var labels: [String]
    var strength: Double
    var targetFingerprint: String
    var targetUpdatedAt: Date
}

/// A deterministic, offline first pass. Foundation Models can improve its topic
/// labels later, but every device can still create useful metadata and links.
enum SomaMetadataAnalyzer {
    private static let maximumInputCharacters = 12_000
    private static let stopWords: Set<String> = [
        // English plus the small function-word sets shared by Soma's languages.
        "about", "after", "again", "also", "and", "are", "been", "before", "but",
        "can", "did", "does", "for", "from", "had", "has", "have", "into", "just",
        "not", "now", "out", "that", "the", "then", "there", "this", "today", "too",
        "was", "were", "will", "with", "you", "your",
        "bet", "bija", "būs", "es", "ir", "ka", "kas", "man", "par", "tas", "un",
        "ar", "et", "ja", "kui", "ma", "mis", "oli", "on", "see",
        "aš", "bet", "ir", "kad", "kaip", "man", "tai", "su",
        "että", "ja", "kun", "minä", "oli", "on", "se",
        "att", "det", "en", "ett", "för", "jag", "med", "och", "som",
        "aber", "das", "der", "die", "ein", "eine", "ich", "ist", "mit", "und",
        "aj", "ale", "ako", "ja", "je", "na", "som", "to", "v",
    ]

    static func extract(from rawText: String) -> GeneratedEntryMetadata {
        let text = String(rawText.prefix(maximumInputCharacters))
        guard !text.isEmpty else {
            return GeneratedEntryMetadata(
                tags: [],
                people: [],
                places: [],
                organizations: [],
                keywords: []
            )
        }

        let entities = namedEntities(in: text)
        let keywords = rankedKeywords(in: text, excluding: entities.all)
        let tags = Array(keywords.prefix(3))

        return GeneratedEntryMetadata(
            tags: tags,
            people: entities.people,
            places: entities.places,
            organizations: entities.organizations,
            keywords: Array(keywords.prefix(10))
        )
    }

    static func fingerprint(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func connectionProposals(
        for entry: SomaEntry,
        metadata: EntryMetadata,
        candidates: [MetadataConnectionCandidate]
    ) -> [EntryConnectionProposal] {
        let source = FoldedMetadata(metadata)
        return candidates.compactMap { candidate -> EntryConnectionProposal? in
            guard candidate.entry.id != entry.id, !candidate.entry.isDeleted else { return nil }
            let target = FoldedMetadata(candidate.metadata)

            let people = source.people.intersection(target.people)
            let places = source.places.intersection(target.places)
            let organizations = source.organizations.intersection(target.organizations)
            let tags = source.tags.intersection(target.tags)
            let keywords = source.keywords.intersection(target.keywords)

            let kind: EntryConnectionKind
            let primary: Set<String>
            if !people.isEmpty {
                kind = .person
                primary = people
            } else if !places.isEmpty {
                kind = .place
                primary = places
            } else if !organizations.isEmpty {
                kind = .organization
                primary = organizations
            } else {
                kind = .topic
                primary = tags.union(keywords)
            }

            let entityScore =
                Double(people.count) * 0.58 +
                Double(places.count) * 0.50 +
                Double(organizations.count) * 0.46
            let topicScore =
                Double(tags.count) * 0.34 +
                Double(keywords.subtracting(tags).count) * 0.18
            let score = min(1, entityScore + topicScore)

            // One named entity is meaningful. Topic-only links need either a
            // generated tag or two independent lexical signals.
            let hasEntity = !people.isEmpty || !places.isEmpty || !organizations.isEmpty
            let hasTopicEvidence = !tags.isEmpty || keywords.count >= 2
            guard score >= 0.32, hasEntity || hasTopicEvidence else { return nil }

            let labels = displayLabels(
                folded: primary,
                source: metadata,
                target: candidate.metadata
            )
            guard !labels.isEmpty else { return nil }
            return EntryConnectionProposal(
                targetEntryID: candidate.entry.id,
                kind: kind,
                labels: Array(labels.prefix(3)),
                strength: score,
                targetFingerprint: candidate.metadata.sourceFingerprint
                    ?? fingerprint(candidate.entry.text),
                targetUpdatedAt: candidate.entry.updatedAt
            )
        }
        .sorted {
            if $0.strength != $1.strength { return $0.strength > $1.strength }
            return $0.targetUpdatedAt > $1.targetUpdatedAt
        }
        .prefix(6)
        .map { $0 }
    }

    fileprivate static func fold(_ text: String) -> String {
        text
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            .lowercased()
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func namedEntities(
        in text: String
    ) -> (people: [String], places: [String], organizations: [String], all: Set<String>) {
        let tagger = NLTagger(tagSchemes: [.nameType])
        tagger.string = text
        let options: NLTagger.Options = [.omitWhitespace, .omitPunctuation, .joinNames]
        var people: [String] = []
        var places: [String] = []
        var organizations: [String] = []

        tagger.enumerateTags(
            in: text.startIndex..<text.endIndex,
            unit: .word,
            scheme: .nameType,
            options: options
        ) { tag, range in
            let value = String(text[range]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard value.count >= 2 else { return true }
            switch tag {
            case .personalName: people.append(value)
            case .placeName: places.append(value)
            case .organizationName: organizations.append(value)
            default: break
            }
            return true
        }

        people = GeneratedEntryMetadata.unique(people, limit: 8)
        places = GeneratedEntryMetadata.unique(places, limit: 8)
        organizations = GeneratedEntryMetadata.unique(organizations, limit: 8)
        return (
            people,
            places,
            organizations,
            Set((people + places + organizations).map(fold))
        )
    }

    private static func rankedKeywords(in text: String, excluding entities: Set<String>) -> [String] {
        var counts: [String: (count: Int, first: Int)] = [:]
        var position = 0
        text.enumerateSubstrings(
            in: text.startIndex..<text.endIndex,
            options: [.byWords, .localized]
        ) { substring, _, _, _ in
            defer { position += 1 }
            guard let substring else { return }
            let token = fold(substring)
            guard
                token.count >= 3,
                token.count <= 40,
                token.contains(where: \.isLetter),
                !stopWords.contains(token),
                !entities.contains(token)
            else {
                return
            }
            let current = counts[token] ?? (0, position)
            counts[token] = (current.count + 1, current.first)
        }
        return counts
            .sorted {
                if $0.value.count != $1.value.count {
                    return $0.value.count > $1.value.count
                }
                if $0.key.count != $1.key.count {
                    return $0.key.count > $1.key.count
                }
                return $0.value.first < $1.value.first
            }
            .prefix(12)
            .map(\.key)
    }

    private static func displayLabels(
        folded values: Set<String>,
        source: EntryMetadata,
        target: EntryMetadata
    ) -> [String] {
        let originals =
            (source.people ?? []) + (source.places ?? []) + (source.organizations ?? []) +
            source.tags + (source.keywords ?? []) +
            (target.people ?? []) + (target.places ?? []) + (target.organizations ?? []) +
            target.tags + (target.keywords ?? [])
        var seen = Set<String>()
        return originals.compactMap { value in
            let key = fold(value)
            guard values.contains(key), seen.insert(key).inserted else { return nil }
            return value
        }
    }

    private struct FoldedMetadata {
        var tags: Set<String>
        var people: Set<String>
        var places: Set<String>
        var organizations: Set<String>
        var keywords: Set<String>

        init(_ metadata: EntryMetadata) {
            tags = Set(metadata.tags.map(SomaMetadataAnalyzer.fold))
            people = Set((metadata.people ?? []).map(SomaMetadataAnalyzer.fold))
            places = Set((metadata.places ?? []).map(SomaMetadataAnalyzer.fold))
            organizations = Set((metadata.organizations ?? []).map(SomaMetadataAnalyzer.fold))
            keywords = Set((metadata.keywords ?? []).map(SomaMetadataAnalyzer.fold))
        }
    }
}
