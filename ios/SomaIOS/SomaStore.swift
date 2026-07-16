import Foundation
import Observation

@MainActor
@Observable
final class SomaStore {
    static let shared = SomaStore()
    static let maximumTextBytes = 256 * 1_024

    private(set) var entries: [SomaEntry] = []
    private(set) var important: [ImportantItem] = []
    private(set) var suggestions: [ImportantSuggestion] = []
    private(set) var revisions: [EntryRevision] = []
    private(set) var logs: [SomaLog] = []
    private(set) var trackingSuggestions: [TrackingSuggestion] = []
    private(set) var photoTextSuggestions: [PhotoTextSuggestion] = []
    private(set) var entryMetadata: [EntryMetadata] = []
    private(set) var entryConnections: [EntryConnection] = []
    private(set) var deviceID: UUID
    private(set) var storageIssue: StoreError?
    var selectedDay = Date()

    private let fileManager: FileManager
    private let storeURL: URL
    private let cipher = SnapshotCipher()
    private var storageIsReadable = true

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
        let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let directory = support.appending(path: "Soma", directoryHint: .isDirectory)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        storeURL = directory.appending(path: "context.json")

        guard fileManager.fileExists(atPath: storeURL.path(percentEncoded: false)) else {
            deviceID = UUID()
            return
        }

        do {
            let raw = try Data(contentsOf: storeURL)
            let wasPlaintext = !cipher.isEncrypted(raw)
            let data = wasPlaintext ? raw : try cipher.open(raw)
            let snapshot = try Self.decoder.decode(LocalSnapshot.self, from: data)
            deviceID = snapshot.deviceID
            entries = snapshot.entries.map(Self.sanitizedLocalEntry)
            important = snapshot.important
            suggestions = snapshot.suggestions ?? []
            revisions = snapshot.revisions ?? []
            logs = snapshot.logs ?? []
            trackingSuggestions = snapshot.trackingSuggestions ?? []
            photoTextSuggestions = snapshot.photoTextSuggestions ?? []
            entryMetadata = snapshot.entryMetadata ?? []
            entryConnections = snapshot.entryConnections ?? []
            if wasPlaintext {
                _ = writeSnapshot()
            }
        } catch {
            deviceID = UUID()
            storageIsReadable = false
            storageIssue = .unavailable
        }
    }

    var storageStatus: String {
        storageIssue?.errorDescription ?? "Ready"
    }

    var selectedEntries: [SomaEntry] {
        let day = SomaDay.key(selectedDay)
        return entries
            .filter { $0.day == day && !$0.isDeleted }
            .sorted { $0.createdAt < $1.createdAt }
    }

    // Newest first, matching the completed list — one ordering philosophy.
    var openImportant: [ImportantItem] {
        important
            .filter { !$0.isDeleted && $0.state == .open }
            .sorted { $0.createdAt > $1.createdAt }
    }

    var completedImportant: [ImportantItem] {
        important
            .filter { !$0.isDeleted && $0.state == .done }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    func entry(id: UUID) -> SomaEntry? {
        entries.first { $0.id == id && !$0.isDeleted }
    }

    @discardableResult
    func addText(_ text: String) -> SomaEntry? {
        guard let cleaned = Self.boundedText(text) else { return nil }
        let now = Date()
        let entry = SomaEntry(
            id: UUID(),
            day: SomaDay.key(selectedDay),
            kind: .text,
            text: cleaned,
            createdAt: now,
            updatedAt: now
        )
        return commit {
            entries.append(entry)
            return entry
        }
    }

    // A staged photo may ride along: photo and spoken comment become one entry.
    @discardableResult
    func addVoice(
        fileName: String,
        duration: TimeInterval,
        imageFileName: String? = nil
    ) -> SomaEntry? {
        guard Self.isValidAudioFileName(fileName) else { return nil }
        if let imageFileName {
            guard
                Self.isValidImageFileName(imageFileName),
                availableImageURL(fileName: imageFileName) != nil
            else {
                return nil
            }
        }
        let now = Date()
        let entry = SomaEntry(
            id: UUID(),
            day: SomaDay.key(selectedDay),
            kind: .voice,
            text: "",
            createdAt: now,
            updatedAt: now,
            audioFileName: fileName,
            audioDuration: duration,
            imageFileName: imageFileName,
            transcriptionState: .queued
        )
        return commit {
            entries.append(entry)
            return entry
        }
    }

    @discardableResult
    func addPhoto(fileName: String, text: String = "") -> SomaEntry? {
        guard
            Self.isValidImageFileName(fileName),
            availableImageURL(fileName: fileName) != nil,
            let caption = Self.boundedText(text, allowingEmpty: true)
        else {
            return nil
        }
        let now = Date()
        let entry = SomaEntry(
            id: UUID(),
            day: SomaDay.key(selectedDay),
            kind: .text,
            text: caption,
            createdAt: now,
            updatedAt: now,
            imageFileName: fileName
        )
        return commit {
            entries.append(entry)
            return entry
        }
    }

    @discardableResult
    func update(entry: SomaEntry, text: String) -> SomaEntry? {
        guard
            let index = entries.firstIndex(where: { $0.id == entry.id }),
            !entries[index].isDeleted
        else {
            return nil
        }
        guard let cleaned = Self.boundedText(text, allowingEmpty: true) else { return nil }
        let now = Date()
        return commit {
            let previous = entries[index]
            if previous.text != cleaned, !previous.text.isEmpty {
                revisions.append(
                    EntryRevision(
                        id: UUID(),
                        entryID: previous.id,
                        text: previous.text,
                        recordedAt: previous.lastUserEditedAt ?? previous.updatedAt
                    )
                )
            }
            entries[index].text = cleaned
            entries[index].updatedAt = now
            entries[index].lastUserEditedAt = now
            suggestions.removeAll { $0.entryID == entry.id }
            entryMetadata.removeAll { $0.entryID == entry.id }
            entryConnections.removeAll {
                $0.sourceEntryID == entry.id || $0.targetEntryID == entry.id
            }
            return entries[index]
        }
    }

    func revisions(for entryID: UUID) -> [EntryRevision] {
        revisions
            .filter { $0.entryID == entryID }
            .sorted { $0.recordedAt > $1.recordedAt }
    }

    // Deletion is soft: the entry moves to the trash with its media intact so it
    // can be restored. Media files are only removed at purge time.
    @discardableResult
    func remove(entry: SomaEntry) -> Bool {
        guard let index = entries.firstIndex(where: { $0.id == entry.id }) else { return false }
        return commit {
            let now = Date()
            entries[index].deletedAt = now
            entries[index].updatedAt = now
            entries[index].transcriptionRunID = nil
            suggestions.removeAll { $0.entryID == entry.id }
            entryMetadata.removeAll { $0.entryID == entry.id }
            entryConnections.removeAll {
                $0.sourceEntryID == entry.id || $0.targetEntryID == entry.id
            }
            return true
        } == true
    }

    var trashedEntries: [SomaEntry] {
        entries
            .filter {
                $0.isDeleted &&
                (!$0.text.isEmpty || $0.audioFileName != nil || $0.imageFileName != nil)
            }
            .sorted { ($0.deletedAt ?? .distantPast) > ($1.deletedAt ?? .distantPast) }
    }

    var trashedImportant: [ImportantItem] {
        important
            .filter { $0.isDeleted && !$0.text.isEmpty }
            .sorted { ($0.deletedAt ?? .distantPast) > ($1.deletedAt ?? .distantPast) }
    }

    @discardableResult
    func restore(entry: SomaEntry) -> Bool {
        guard
            let index = entries.firstIndex(where: { $0.id == entry.id }),
            entries[index].isDeleted
        else {
            return false
        }
        return commit {
            entries[index].deletedAt = nil
            entries[index].updatedAt = Date()
            return true
        } == true
    }

    @discardableResult
    func restore(item: ImportantItem) -> Bool {
        guard
            let index = important.firstIndex(where: { $0.id == item.id }),
            important[index].isDeleted
        else {
            return false
        }
        return commit {
            important[index].deletedAt = nil
            important[index].updatedAt = Date()
            return true
        } == true
    }

    // Purging keeps the tombstone (so a deletion still wins in a later merge) but
    // empties the content and frees the media files.
    @discardableResult
    func purge(entry: SomaEntry) -> Bool {
        guard
            let index = entries.firstIndex(where: { $0.id == entry.id }),
            entries[index].isDeleted
        else {
            return false
        }
        let audioURL = entries[index].audioFileName.flatMap { availableAudioURL(fileName: $0) }
        let imageURL = entries[index].imageFileName.flatMap { availableImageURL(fileName: $0) }
        let purged = commit {
            entries[index].text = ""
            entries[index].audioFileName = nil
            entries[index].audioDuration = nil
            entries[index].imageFileName = nil
            entries[index].transcriptionState = nil
            entries[index].transcriptionProvenance = nil
            revisions.removeAll { $0.entryID == entry.id }
            entryMetadata.removeAll { $0.entryID == entry.id }
            entryConnections.removeAll {
                $0.sourceEntryID == entry.id || $0.targetEntryID == entry.id
            }
            return true
        } == true
        if purged, let audioURL {
            try? fileManager.removeItem(at: audioURL)
        }
        if purged, let imageURL {
            try? fileManager.removeItem(at: imageURL)
        }
        return purged
    }

    @discardableResult
    func purge(item: ImportantItem) -> Bool {
        guard
            let index = important.firstIndex(where: { $0.id == item.id }),
            important[index].isDeleted
        else {
            return false
        }
        return commit {
            important[index].text = ""
            return true
        } == true
    }

    func purgeAllTrash() {
        for entry in trashedEntries {
            _ = purge(entry: entry)
        }
        for item in trashedImportant {
            _ = purge(item: item)
        }
    }

    @discardableResult
    func setTranscriptionState(_ state: TranscriptionState, for entryID: UUID) -> Bool {
        guard let index = entries.firstIndex(where: { $0.id == entryID }) else { return false }
        return commit {
            entries[index].transcriptionState = state
            if state != .running {
                entries[index].transcriptionRunID = nil
            }
            entries[index].updatedAt = Date()
            return true
        } == true
    }

    func beginTranscription(entryID: UUID, fileName: String) -> UUID? {
        guard
            let index = entries.firstIndex(where: { $0.id == entryID }),
            !entries[index].isDeleted,
            entries[index].kind == .voice,
            entries[index].audioFileName == fileName
        else {
            return nil
        }
        let runID = UUID()
        return commit {
            entries[index].transcriptionState = .running
            entries[index].transcriptionRunID = runID
            entries[index].updatedAt = Date()
            return runID
        }
    }

    @discardableResult
    func requeueTranscription(entryID: UUID, runID: UUID) -> Bool {
        guard
            let index = entries.firstIndex(where: { $0.id == entryID }),
            entries[index].transcriptionRunID == runID
        else {
            return false
        }
        return commit {
            entries[index].transcriptionState = .queued
            entries[index].transcriptionRunID = nil
            entries[index].updatedAt = Date()
            return true
        } == true
    }

    func completeTranscription(
        entryID: UUID,
        runID: UUID,
        fileName: String,
        text: String,
        provenance: TranscriptionProvenance
    ) -> SomaEntry? {
        guard
            let index = entries.firstIndex(where: { $0.id == entryID }),
            !entries[index].isDeleted,
            entries[index].transcriptionRunID == runID,
            entries[index].audioFileName == fileName
        else {
            return nil
        }
        guard let cleaned = Self.boundedText(text) else {
            _ = failTranscription(entryID: entryID, runID: runID)
            return nil
        }
        return commit {
            if entries[index].lastUserEditedAt == nil {
                entries[index].text = cleaned
            }
            entries[index].transcriptionState = .succeeded
            entries[index].transcriptionProvenance = provenance
            entries[index].transcriptionRunID = nil
            entries[index].updatedAt = Date()
            return entries[index]
        }
    }

    @discardableResult
    func failTranscription(entryID: UUID, runID: UUID? = nil) -> Bool {
        guard
            let index = entries.firstIndex(where: { $0.id == entryID }),
            runID == nil || entries[index].transcriptionRunID == runID
        else {
            return false
        }
        return commit {
            entries[index].transcriptionState = .failed
            entries[index].transcriptionRunID = nil
            entries[index].updatedAt = Date()
            return true
        } == true
    }

    @discardableResult
    func makeImportant(from entry: SomaEntry) -> Bool {
        guard let cleaned = Self.boundedText(entry.text) else { return false }
        let now = Date()
        return commit {
            important.append(
                ImportantItem(
                    id: UUID(),
                    text: cleaned,
                    state: .open,
                    createdAt: now,
                    updatedAt: now,
                    sourceEntryID: entry.id
                )
            )
            return true
        } == true
    }

    @discardableResult
    func addImportant(_ text: String) -> Bool {
        guard let cleaned = Self.boundedText(text) else { return false }
        let now = Date()
        return commit {
            important.append(
                ImportantItem(
                    id: UUID(),
                    text: cleaned,
                    state: .open,
                    createdAt: now,
                    updatedAt: now
                )
            )
            return true
        } == true
    }

    @discardableResult
    func toggle(_ item: ImportantItem) -> Bool {
        guard let index = important.firstIndex(where: { $0.id == item.id }) else { return false }
        return commit {
            important[index].state = important[index].state == .open ? .done : .open
            important[index].updatedAt = Date()
            return true
        } == true
    }

    @discardableResult
    func remove(_ item: ImportantItem) -> Bool {
        guard let index = important.firstIndex(where: { $0.id == item.id }) else { return false }
        return commit {
            let now = Date()
            important[index].deletedAt = now
            important[index].updatedAt = now
            return true
        } == true
    }

    func pendingSuggestions(for entryID: UUID) -> [ImportantSuggestion] {
        suggestions
            .filter { $0.entryID == entryID && $0.isPending }
            .sorted { $0.createdAt < $1.createdAt }
    }

    // MARK: - Logs (meals, workouts)

    func logs(for day: Date) -> [SomaLog] {
        let key = SomaDay.key(day)
        return logs
            .filter { $0.day == key }
            .sorted { $0.createdAt < $1.createdAt }
    }

    func hasLog(day: Date, kind: SomaLogKind, title: String) -> Bool {
        let key = SomaDay.key(day)
        return logs.contains { $0.day == key && $0.kind == kind && $0.title == title }
    }

    @discardableResult
    func addLog(kind: SomaLogKind, title: String, day: Date, sourceEntryID: UUID? = nil) -> Bool {
        guard let cleaned = Self.boundedText(title) else { return false }
        let now = Date()
        return commit {
            logs.append(
                SomaLog(
                    id: UUID(),
                    day: SomaDay.key(day),
                    kind: kind,
                    title: cleaned,
                    createdAt: now,
                    updatedAt: now,
                    sourceEntryID: sourceEntryID
                )
            )
            return true
        } == true
    }

    @discardableResult
    func remove(log: SomaLog) -> Bool {
        guard logs.contains(where: { $0.id == log.id }) else { return false }
        return commit {
            logs.removeAll { $0.id == log.id }
            return true
        } == true
    }

    func pendingTrackingSuggestions(for entryID: UUID) -> [TrackingSuggestion] {
        trackingSuggestions
            .filter { $0.entryID == entryID && $0.isPending }
            .sorted { $0.createdAt < $1.createdAt }
    }

    func replaceTrackingSuggestions(
        for entryID: UUID,
        sourceUpdatedAt: Date,
        proposals: [(SomaLogKind, String)],
        engine: SuggestionEngine
    ) {
        guard entry(id: entryID)?.updatedAt == sourceUpdatedAt else { return }
        let dismissed = Set(
            trackingSuggestions
                .filter { $0.entryID == entryID && !$0.isPending }
                .map { Self.suggestionKey($0.text) }
        )
        let bounded = proposals
            .compactMap { kind, text in
                Self.boundedText(text).map { (kind, $0) }
            }
            .filter { !dismissed.contains(Self.suggestionKey($0.1)) }
        _ = commit {
            // Receipt proposals come from the deterministic parser, not the model —
            // a model rerun must not wipe them.
            trackingSuggestions.removeAll {
                $0.entryID == entryID && $0.isPending && $0.kind != .receipt
            }
            let now = Date()
            trackingSuggestions.append(contentsOf: bounded.prefix(3).map { kind, text in
                TrackingSuggestion(
                    id: UUID(),
                    entryID: entryID,
                    kind: kind,
                    text: text,
                    engine: engine,
                    createdAt: now
                )
            })
            return true
        }
    }

    @discardableResult
    func accept(_ suggestion: TrackingSuggestion) -> Bool {
        guard
            let index = trackingSuggestions.firstIndex(where: { $0.id == suggestion.id }),
            let entry = entry(id: suggestion.entryID),
            let day = SomaDay.date(fromKey: entry.day)
        else {
            return false
        }
        guard let cleaned = Self.boundedText(trackingSuggestions[index].text) else { return false }
        return commit {
            let now = Date()
            logs.append(
                SomaLog(
                    id: UUID(),
                    day: SomaDay.key(day),
                    kind: trackingSuggestions[index].kind,
                    title: cleaned,
                    createdAt: now,
                    updatedAt: now,
                    sourceEntryID: entry.id,
                    detail: trackingSuggestions[index].detail,
                    amountCents: trackingSuggestions[index].amountCents
                )
            )
            trackingSuggestions[index].dismissedAt = now
            return true
        } == true
    }

    // One receipt proposal per photo, ever — a dismissal is final.
    func addReceiptSuggestion(
        for entryID: UUID,
        summary: String,
        detail: String,
        amountCents: Int
    ) {
        guard
            !trackingSuggestions.contains(where: { $0.entryID == entryID && $0.kind == .receipt }),
            let cleaned = Self.boundedText(summary)
        else {
            return
        }
        _ = commit {
            trackingSuggestions.append(
                TrackingSuggestion(
                    id: UUID(),
                    entryID: entryID,
                    kind: .receipt,
                    text: cleaned,
                    engine: .localRules,
                    createdAt: Date(),
                    detail: String(detail.prefix(4_000)),
                    amountCents: amountCents
                )
            )
            return true
        }
    }

    func receipts(inMonthOf date: Date) -> [SomaLog] {
        let calendar = Calendar.autoupdatingCurrent
        guard let month = calendar.dateInterval(of: .month, for: date) else { return [] }
        return logs
            .filter { log in
                guard log.kind == .receipt, let day = SomaDay.date(fromKey: log.day) else {
                    return false
                }
                return month.contains(day)
            }
            .sorted { $0.day > $1.day }
    }

    func spentCents(inMonthOf date: Date) -> Int {
        let calendar = Calendar.autoupdatingCurrent
        guard let month = calendar.dateInterval(of: .month, for: date) else { return 0 }
        return logs.reduce(0) { sum, log in
            guard
                log.kind == .receipt,
                let cents = log.amountCents,
                let day = SomaDay.date(fromKey: log.day),
                month.contains(day)
            else {
                return sum
            }
            return sum + cents
        }
    }

    @discardableResult
    func dismiss(_ suggestion: TrackingSuggestion) -> Bool {
        guard let index = trackingSuggestions.firstIndex(where: { $0.id == suggestion.id }) else {
            return false
        }
        return commit {
            trackingSuggestions[index].dismissedAt = Date()
            return true
        } == true
    }

    // MARK: - Entry metadata and ambient connections

    func metadata(for entryID: UUID) -> EntryMetadata? {
        entryMetadata.first { $0.entryID == entryID }
    }

    func tags(for entryID: UUID) -> [String] {
        metadata(for: entryID)?.tags ?? []
    }

    func needsMetadata(_ entry: SomaEntry) -> Bool {
        guard !entry.isDeleted, !entry.text.isEmpty else { return false }
        let fingerprint = SomaMetadataAnalyzer.fingerprint(entry.text)
        return metadata(for: entry.id)?.sourceFingerprint != fingerprint
    }

    func entriesNeedingMetadata(limit: Int) -> [SomaEntry] {
        entries
            .filter(needsMetadata)
            .sorted { $0.updatedAt > $1.updatedAt }
            .prefix(max(0, limit))
            .map { $0 }
    }

    @discardableResult
    func clearGeneratedMetadata() -> Bool {
        commit {
            entryMetadata.removeAll()
            entryConnections.removeAll()
            return true
        } == true
    }

    @discardableResult
    func setMetadata(
        for entryID: UUID,
        sourceFingerprint: String,
        generated: GeneratedEntryMetadata,
        engine: MetadataEngine,
        proposals: [EntryConnectionProposal] = []
    ) -> Bool {
        guard
            let sourceEntry = entry(id: entryID),
            SomaMetadataAnalyzer.fingerprint(sourceEntry.text) == sourceFingerprint
        else {
            return false
        }
        let tags = Self.cleanedMetadataValues(generated.tags, limit: 5, lowercase: true)
        let people = Self.cleanedMetadataValues(generated.people, limit: 8)
        let places = Self.cleanedMetadataValues(generated.places, limit: 8)
        let organizations = Self.cleanedMetadataValues(generated.organizations, limit: 8)
        let keywords = Self.cleanedMetadataValues(generated.keywords, limit: 12, lowercase: true)
        let validProposals = proposals.filter { proposal in
            guard
                proposal.targetEntryID != entryID,
                let target = entry(id: proposal.targetEntryID),
                SomaMetadataAnalyzer.fingerprint(target.text) == proposal.targetFingerprint,
                metadata(for: target.id)?.sourceFingerprint == proposal.targetFingerprint,
                proposal.strength.isFinite,
                (0...1).contains(proposal.strength)
            else {
                return false
            }
            return true
        }
        return commit {
            entryMetadata.removeAll { $0.entryID == entryID }
            entryConnections.removeAll {
                $0.sourceEntryID == entryID || $0.targetEntryID == entryID
            }
            entryMetadata.append(
                EntryMetadata(
                    id: UUID(),
                    entryID: entryID,
                    tags: tags,
                    createdAt: Date(),
                    people: people,
                    places: places,
                    organizations: organizations,
                    keywords: keywords,
                    sourceUpdatedAt: sourceEntry.updatedAt,
                    sourceFingerprint: sourceFingerprint,
                    engine: engine
                )
            )
            let now = Date()
            entryConnections.append(contentsOf: validProposals.prefix(6).map {
                EntryConnection(
                    id: UUID(),
                    sourceEntryID: entryID,
                    targetEntryID: $0.targetEntryID,
                    kind: $0.kind,
                    labels: Self.cleanedMetadataValues($0.labels, limit: 3),
                    strength: $0.strength,
                    createdAt: now,
                    sourceFingerprint: sourceFingerprint,
                    targetFingerprint: $0.targetFingerprint
                )
            })
            return true
        } == true
    }

    // Compatibility for the first tags-only implementation.
    func setTags(for entryID: UUID, tags: [String]) {
        guard let entry = entry(id: entryID) else { return }
        let existing = metadata(for: entryID)
        _ = setMetadata(
            for: entryID,
            sourceFingerprint: SomaMetadataAnalyzer.fingerprint(entry.text),
            generated: GeneratedEntryMetadata(
                tags: tags,
                people: existing?.people ?? [],
                places: existing?.places ?? [],
                organizations: existing?.organizations ?? [],
                keywords: existing?.keywords ?? []
            ),
            engine: existing?.engine ?? .localLanguageAnalysis
        )
    }

    func connections(for entryID: UUID) -> [EntryConnection] {
        entryConnections
            .filter { $0.sourceEntryID == entryID || $0.targetEntryID == entryID }
            .filter { connection in
                guard
                    let otherID = connection.otherEntryID(than: entryID),
                    entry(id: otherID) != nil,
                    metadata(for: connection.sourceEntryID)?.sourceFingerprint
                        == connection.sourceFingerprint,
                    metadata(for: connection.targetEntryID)?.sourceFingerprint
                        == connection.targetFingerprint
                else {
                    return false
                }
                return true
            }
            .sorted {
                if $0.strength != $1.strength { return $0.strength > $1.strength }
                return $0.createdAt > $1.createdAt
            }
    }

    // MARK: - Photo text

    // Records persist even when dismissed so a photo is only ever OCR'd once.
    func hasPhotoTextSuggestion(for entryID: UUID) -> Bool {
        photoTextSuggestions.contains { $0.entryID == entryID }
    }

    func pendingPhotoText(for entryID: UUID) -> PhotoTextSuggestion? {
        photoTextSuggestions.first { $0.entryID == entryID && $0.isPending }
    }

    func setPhotoTextSuggestion(for entryID: UUID, text: String) {
        guard !hasPhotoTextSuggestion(for: entryID) else { return }
        guard let cleaned = Self.boundedText(text) else { return }
        _ = commit {
            photoTextSuggestions.append(
                PhotoTextSuggestion(
                    id: UUID(),
                    entryID: entryID,
                    text: cleaned,
                    createdAt: Date()
                )
            )
            return true
        }
    }

    // Accepting makes the recognized text the entry's text (revision-safe via
    // the ordinary update path), so the photo becomes searchable.
    @discardableResult
    func accept(_ suggestion: PhotoTextSuggestion) -> SomaEntry? {
        guard
            let index = photoTextSuggestions.firstIndex(where: { $0.id == suggestion.id }),
            let entry = entry(id: suggestion.entryID)
        else {
            return nil
        }
        let updated = update(entry: entry, text: photoTextSuggestions[index].text)
        guard updated != nil else { return nil }
        _ = commit {
            photoTextSuggestions[index].dismissedAt = Date()
            return true
        }
        return updated
    }

    @discardableResult
    func dismiss(_ suggestion: PhotoTextSuggestion) -> Bool {
        guard let index = photoTextSuggestions.firstIndex(where: { $0.id == suggestion.id }) else {
            return false
        }
        return commit {
            photoTextSuggestions[index].dismissedAt = Date()
            return true
        } == true
    }

    // A dismissal is remembered: re-running the pipeline (say, after an edit)
    // must not resurface a suggestion the user already said no to.
    func replaceSuggestions(
        for entryID: UUID,
        sourceUpdatedAt: Date,
        texts: [String],
        engine: SuggestionEngine
    ) {
        guard entry(id: entryID)?.updatedAt == sourceUpdatedAt else { return }
        let dismissed = Set(
            suggestions
                .filter { $0.entryID == entryID && !$0.isPending }
                .map { Self.suggestionKey($0.text) }
        )
        let boundedTexts = texts
            .compactMap { Self.boundedText($0) }
            .filter { !dismissed.contains(Self.suggestionKey($0)) }
        _ = commit {
            suggestions.removeAll { $0.entryID == entryID && $0.isPending }
            let now = Date()
            suggestions.append(contentsOf: boundedTexts.prefix(3).map {
                ImportantSuggestion(
                    id: UUID(),
                    entryID: entryID,
                    text: $0,
                    engine: engine,
                    createdAt: now
                )
            })
            return true
        }
    }

    // Names the user actually writes, fed to the live-preview recognizer as
    // contextual strings — the Android app's transcription vocabulary, derived
    // instead of hand-kept. Mid-sentence capitalized words approximate names.
    var transcriptionVocabulary: [String] {
        var counts: [String: Int] = [:]
        for entry in entries where !entry.isDeleted && !entry.text.isEmpty {
            let words = entry.text.split { !$0.isLetter }
            for (index, word) in words.enumerated() where index > 0 {
                guard
                    word.count >= 3,
                    word.first?.isUppercase == true,
                    word.dropFirst().allSatisfy(\.isLowercase)
                else {
                    continue
                }
                counts[String(word), default: 0] += 1
            }
        }
        return counts
            .sorted { $0.value > $1.value }
            .prefix(50)
            .map(\.key)
    }

    private static func suggestionKey(_ text: String) -> String {
        text
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    @discardableResult
    func accept(_ suggestion: ImportantSuggestion) -> Bool {
        guard let index = suggestions.firstIndex(where: { $0.id == suggestion.id }) else {
            return false
        }
        guard let cleaned = Self.boundedText(suggestions[index].text) else { return false }
        return commit {
            let now = Date()
            important.append(
                ImportantItem(
                    id: UUID(),
                    text: cleaned,
                    state: .open,
                    createdAt: now,
                    updatedAt: now
                )
            )
            suggestions[index].dismissedAt = now
            return true
        } == true
    }

    @discardableResult
    func dismiss(_ suggestion: ImportantSuggestion) -> Bool {
        guard let index = suggestions.firstIndex(where: { $0.id == suggestion.id }) else {
            return false
        }
        return commit {
            suggestions[index].dismissedAt = Date()
            return true
        } == true
    }

    // MARK: - Reversible preview content

    private static let demoEntryIDs: Set<UUID> = [
        UUID(uuidString: "D3A00000-0000-4000-8000-000000000001")!,
        UUID(uuidString: "D3A00000-0000-4000-8000-000000000002")!,
        UUID(uuidString: "D3A00000-0000-4000-8000-000000000003")!,
        UUID(uuidString: "D3A00000-0000-4000-8000-000000000004")!,
    ]

    var hasDemoContent: Bool {
        entries.contains { Self.demoEntryIDs.contains($0.id) && !$0.isDeleted }
    }

    @discardableResult
    func installDemoContent() -> Bool {
        let now = Date()
        let day = SomaDay.key(now)
        let entryIDs = Self.demoEntryIDs.sorted {
            $0.uuidString < $1.uuidString
        }
        guard entryIDs.count == 4 else { return false }

        let texts = [
            "Slow morning. Coffee by the window, then a short walk before opening the laptop.",
            "Call with Maya: keep Soma calm. The camera should feel instant; everything else can stay out of the way.",
            "Lunch at Karmelitermarkt — tomato soup, rye bread, and sparkling water.",
            "Remember to send Maya the revised Soma prototype before Friday.",
        ]
        let times = [
            now.addingTimeInterval(-26 * 60),
            now.addingTimeInterval(-18 * 60),
            now.addingTimeInterval(-9 * 60),
            now.addingTimeInterval(-3 * 60),
        ]
        let demoEntries = zip(zip(entryIDs, texts), times).map { pair, createdAt in
            SomaEntry(
                id: pair.0,
                day: day,
                kind: .text,
                text: pair.1,
                createdAt: createdAt,
                updatedAt: createdAt
            )
        }
        let generated = [
            GeneratedEntryMetadata(
                tags: ["morning", "wellbeing"],
                people: [],
                places: [],
                organizations: [],
                keywords: ["coffee", "walk", "routine"]
            ),
            GeneratedEntryMetadata(
                tags: ["soma", "design"],
                people: ["Maya"],
                places: [],
                organizations: ["Soma"],
                keywords: ["camera", "calm", "capture"]
            ),
            GeneratedEntryMetadata(
                tags: ["food", "vienna"],
                people: [],
                places: ["Karmelitermarkt"],
                organizations: [],
                keywords: ["lunch", "tomato soup", "rye bread"]
            ),
            GeneratedEntryMetadata(
                tags: ["follow-up", "soma"],
                people: ["Maya"],
                places: [],
                organizations: ["Soma"],
                keywords: ["prototype", "friday", "send"]
            ),
        ]
        let fingerprints = Dictionary(
            uniqueKeysWithValues: demoEntries.map {
                ($0.id, SomaMetadataAnalyzer.fingerprint($0.text))
            }
        )
        let metadata = zip(demoEntries, generated).enumerated().map { index, pair in
            EntryMetadata(
                id: UUID(
                    uuidString: String(
                        format: "D3A30000-0000-4000-8000-%012d",
                        index + 1
                    )
                )!,
                entryID: pair.0.id,
                tags: pair.1.tags,
                createdAt: pair.0.createdAt,
                people: pair.1.people,
                places: pair.1.places,
                organizations: pair.1.organizations,
                keywords: pair.1.keywords,
                sourceUpdatedAt: pair.0.updatedAt,
                sourceFingerprint: fingerprints[pair.0.id],
                engine: .localLanguageAnalysis
            )
        }
        let mayaConnection = EntryConnection(
            id: UUID(uuidString: "D3A40000-0000-4000-8000-000000000001")!,
            sourceEntryID: entryIDs[3],
            targetEntryID: entryIDs[1],
            kind: .person,
            labels: ["Maya", "Soma"],
            strength: 0.94,
            createdAt: times[3],
            sourceFingerprint: fingerprints[entryIDs[3]]!,
            targetFingerprint: fingerprints[entryIDs[1]]!
        )
        let importantItem = ImportantItem(
            id: UUID(uuidString: "D3A10000-0000-4000-8000-000000000001")!,
            text: texts[3],
            state: .open,
            createdAt: times[3],
            updatedAt: times[3],
            sourceEntryID: entryIDs[3]
        )
        let demoLogs = [
            SomaLog(
                id: UUID(uuidString: "D3A20000-0000-4000-8000-000000000001")!,
                day: day,
                kind: .workout,
                title: "Morning walk · 24 min",
                createdAt: times[0],
                updatedAt: times[0],
                sourceEntryID: entryIDs[0]
            ),
            SomaLog(
                id: UUID(uuidString: "D3A20000-0000-4000-8000-000000000002")!,
                day: day,
                kind: .meal,
                title: "tomato soup · rye bread · sparkling water",
                createdAt: times[2],
                updatedAt: times[2],
                sourceEntryID: entryIDs[2]
            ),
        ]

        return commit {
            removeDemoRecords()
            entries.append(contentsOf: demoEntries)
            important.append(importantItem)
            logs.append(contentsOf: demoLogs)
            entryMetadata.append(contentsOf: metadata)
            entryConnections.append(mayaConnection)
            return true
        } == true
    }

    @discardableResult
    func removeDemoContent() -> Bool {
        commit {
            removeDemoRecords()
            return true
        } == true
    }

    private func removeDemoRecords() {
        entries.removeAll { Self.demoEntryIDs.contains($0.id) }
        important.removeAll {
            $0.id == UUID(uuidString: "D3A10000-0000-4000-8000-000000000001")
        }
        logs.removeAll { $0.sourceEntryID.map(Self.demoEntryIDs.contains) == true }
        suggestions.removeAll { Self.demoEntryIDs.contains($0.entryID) }
        revisions.removeAll { Self.demoEntryIDs.contains($0.entryID) }
        trackingSuggestions.removeAll { Self.demoEntryIDs.contains($0.entryID) }
        photoTextSuggestions.removeAll { Self.demoEntryIDs.contains($0.entryID) }
        entryMetadata.removeAll { Self.demoEntryIDs.contains($0.entryID) }
        entryConnections.removeAll {
            Self.demoEntryIDs.contains($0.sourceEntryID) ||
                Self.demoEntryIDs.contains($0.targetEntryID)
        }
    }

    func contextBundle() -> SomaContextBundle {
        SomaContextBundle(
            schemaVersion: SomaContextBundle.currentSchemaVersion,
            exportedAt: Date(),
            sourceDeviceID: deviceID,
            entries: entries.map(Self.exportableEntry),
            important: important.map(Self.exportableImportant),
            entryMetadata: entryMetadata,
            entryConnections: entryConnections
        )
    }

    func merge(_ bundle: SomaContextBundle) throws {
        guard SomaContextBundle.supportedSchemaVersions.contains(bundle.schemaVersion) else {
            throw ContextError.unsupportedSchema(bundle.schemaVersion)
        }
        try Self.validate(bundle)
        let sanitizedEntries = bundle.entries.map(Self.exportableEntry)
        let sanitizedImportant = bundle.important.map(Self.exportableImportant)
        guard commit({
            entries = Self.mergeEntries(local: entries, incoming: sanitizedEntries)
            important = Self.merge(local: important, incoming: sanitizedImportant)
            entryMetadata = Self.mergeMetadata(
                local: entryMetadata,
                incoming: bundle.entryMetadata ?? []
            )
            entryConnections = Self.mergeConnections(
                local: entryConnections,
                incoming: bundle.entryConnections ?? [],
                validEntryIDs: Set(entries.map(\.id))
            )
            return true
        }) == true else {
            throw ContextError.persistenceFailed
        }
    }

    func audioURL(fileName: String) -> URL? {
        guard Self.isValidAudioFileName(fileName) else { return nil }
        let directory = storeURL.deletingLastPathComponent()
            .appending(path: "Audio", directoryHint: .isDirectory)
            .standardizedFileURL
        let url = directory.appending(path: fileName).standardizedFileURL
        guard url.deletingLastPathComponent() == directory else { return nil }
        return url
    }

    func availableAudioURL(fileName: String) -> URL? {
        guard let url = audioURL(fileName: fileName) else { return nil }
        guard
            let values = try? url.resourceValues(
                forKeys: [.isRegularFileKey, .isSymbolicLinkKey]
            ),
            values.isRegularFile == true,
            values.isSymbolicLink != true
        else {
            return nil
        }
        return url
    }

    func imageURL(fileName: String) -> URL? {
        guard Self.isValidImageFileName(fileName) else { return nil }
        let directory = storeURL.deletingLastPathComponent()
            .appending(path: "Photos", directoryHint: .isDirectory)
            .standardizedFileURL
        let url = directory.appending(path: fileName).standardizedFileURL
        guard url.deletingLastPathComponent() == directory else { return nil }
        return url
    }

    func availableImageURL(fileName: String) -> URL? {
        guard let url = imageURL(fileName: fileName) else { return nil }
        guard
            let values = try? url.resourceValues(
                forKeys: [.isRegularFileKey, .isSymbolicLinkKey]
            ),
            values.isRegularFile == true,
            values.isSymbolicLink != true
        else {
            return nil
        }
        return url
    }

    func audioDirectory() throws -> URL {
        let url = storeURL.deletingLastPathComponent()
            .appending(path: "Audio", directoryHint: .isDirectory)
        try fileManager.createDirectory(at: url, withIntermediateDirectories: true)
        try? fileManager.setAttributes(
            [.protectionKey: FileProtectionType.complete],
            ofItemAtPath: url.path(percentEncoded: false)
        )
        return url
    }

    func imageDirectory() throws -> URL {
        var url = storeURL.deletingLastPathComponent()
            .appending(path: "Photos", directoryHint: .isDirectory)
        try fileManager.createDirectory(at: url, withIntermediateDirectories: true)
        try? fileManager.setAttributes(
            [.protectionKey: FileProtectionType.complete],
            ofItemAtPath: url.path(percentEncoded: false)
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }

    private func commit<T>(_ mutation: () -> T) -> T? {
        guard storageIsReadable else {
            storageIssue = .unavailable
            return nil
        }
        let previousEntries = entries
        let previousImportant = important
        let previousSuggestions = suggestions
        let previousRevisions = revisions
        let previousLogs = logs
        let previousTracking = trackingSuggestions
        let previousPhotoText = photoTextSuggestions
        let previousMetadata = entryMetadata
        let previousConnections = entryConnections
        let value = mutation()
        guard writeSnapshot() else {
            entries = previousEntries
            important = previousImportant
            suggestions = previousSuggestions
            revisions = previousRevisions
            logs = previousLogs
            trackingSuggestions = previousTracking
            photoTextSuggestions = previousPhotoText
            entryMetadata = previousMetadata
            entryConnections = previousConnections
            return nil
        }
        return value
    }

    private func writeSnapshot() -> Bool {
        let snapshot = LocalSnapshot(
            deviceID: deviceID,
            entries: entries,
            important: important,
            suggestions: suggestions,
            revisions: revisions,
            logs: logs,
            trackingSuggestions: trackingSuggestions,
            photoTextSuggestions: photoTextSuggestions,
            entryMetadata: entryMetadata,
            entryConnections: entryConnections
        )
        do {
            let data = try Self.encoder.encode(snapshot)
            let sealed = try cipher.seal(data)
            try sealed.write(
                to: storeURL,
                options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            )
            storageIssue = nil
            return true
        } catch {
            storageIssue = .writeFailed
            return false
        }
    }

    private static func merge<T: Identifiable>(
        local: [T],
        incoming: [T]
    ) -> [T] where T.ID == UUID, T: ContextRecord {
        var merged = Dictionary(uniqueKeysWithValues: local.map { ($0.id, $0) })
        for record in incoming {
            if let current = merged[record.id], current.updatedAt >= record.updatedAt {
                continue
            }
            merged[record.id] = record
        }
        return Array(merged.values)
    }

    private static func validate(_ bundle: SomaContextBundle) throws {
        let maximumRecords = 50_000
        guard
            bundle.entries.count <= maximumRecords,
            bundle.important.count <= maximumRecords,
            (bundle.entryMetadata?.count ?? 0) <= maximumRecords,
            (bundle.entryConnections?.count ?? 0) <= maximumRecords,
            Set(bundle.entries.map(\.id)).count == bundle.entries.count,
            Set(bundle.important.map(\.id)).count == bundle.important.count,
            Set((bundle.entryMetadata ?? []).map(\.id)).count
                == (bundle.entryMetadata?.count ?? 0),
            Set((bundle.entryMetadata ?? []).map(\.entryID)).count
                == (bundle.entryMetadata?.count ?? 0),
            Set((bundle.entryConnections ?? []).map(\.id)).count
                == (bundle.entryConnections?.count ?? 0)
        else {
            throw ContextError.invalidBundle
        }
        for entry in bundle.entries {
            guard
                entry.day.utf8.count <= 32,
                entry.text.utf8.count <= maximumTextBytes,
                entry.audioDuration.map({
                    $0.isFinite && $0 >= 0 && $0 <= 24 * 60 * 60
                }) ?? true
            else {
                throw ContextError.invalidBundle
            }
        }
        guard bundle.important.allSatisfy({ $0.text.utf8.count <= maximumTextBytes }) else {
            throw ContextError.invalidBundle
        }
        let entryIDs = Set(bundle.entries.map(\.id))
        guard
            (bundle.entryMetadata ?? []).allSatisfy({
                entryIDs.contains($0.entryID) &&
                $0.tags.count <= 5 &&
                ($0.people?.count ?? 0) <= 8 &&
                ($0.places?.count ?? 0) <= 8 &&
                ($0.organizations?.count ?? 0) <= 8 &&
                ($0.keywords?.count ?? 0) <= 12 &&
                ($0.tags +
                    ($0.people ?? []) +
                    ($0.places ?? []) +
                    ($0.organizations ?? []) +
                    ($0.keywords ?? [])
                ).allSatisfy { !$0.isEmpty && $0.utf8.count <= 320 } &&
                ($0.sourceFingerprint?.utf8.count ?? 0) <= 128
            }),
            (bundle.entryConnections ?? []).allSatisfy({
                $0.sourceEntryID != $0.targetEntryID &&
                entryIDs.contains($0.sourceEntryID) &&
                entryIDs.contains($0.targetEntryID) &&
                $0.labels.count <= 3 &&
                $0.labels.allSatisfy { !$0.isEmpty && $0.utf8.count <= 320 } &&
                $0.strength.isFinite &&
                (0...1).contains($0.strength) &&
                $0.sourceFingerprint.utf8.count <= 128 &&
                $0.targetFingerprint.utf8.count <= 128
            })
        else {
            throw ContextError.invalidBundle
        }
    }

    private static func mergeEntries(
        local: [SomaEntry],
        incoming: [SomaEntry]
    ) -> [SomaEntry] {
        var merged = Dictionary(uniqueKeysWithValues: local.map { ($0.id, $0) })
        for record in incoming {
            guard let current = merged[record.id] else {
                merged[record.id] = record
                continue
            }

            var resolved = current.updatedAt >= record.updatedAt ? current : record
            let latestUserEdit: SomaEntry?
            switch (current.lastUserEditedAt, record.lastUserEditedAt) {
            case let (localDate?, incomingDate?):
                latestUserEdit = localDate >= incomingDate ? current : record
            case (.some, .none):
                latestUserEdit = current
            case (.none, .some):
                latestUserEdit = record
            case (.none, .none):
                latestUserEdit = nil
            }
            if let latestUserEdit {
                resolved.text = latestUserEdit.text
                resolved.lastUserEditedAt = latestUserEdit.lastUserEditedAt
            }
            if
                resolved.deletedAt == nil,
                current.deletedAt == nil,
                let localAudioFileName = current.audioFileName,
                resolved.audioFileName == nil
            {
                resolved.kind = .voice
                resolved.audioFileName = localAudioFileName
                resolved.audioDuration = current.audioDuration
            }
            if
                resolved.deletedAt == nil,
                current.deletedAt == nil,
                let localImageFileName = current.imageFileName,
                resolved.imageFileName == nil
            {
                resolved.imageFileName = localImageFileName
            }
            merged[record.id] = resolved
        }
        return Array(merged.values)
    }

    private static func mergeMetadata(
        local: [EntryMetadata],
        incoming: [EntryMetadata]
    ) -> [EntryMetadata] {
        var merged = Dictionary(uniqueKeysWithValues: local.map { ($0.entryID, $0) })
        for record in incoming {
            if let current = merged[record.entryID], current.createdAt >= record.createdAt {
                continue
            }
            merged[record.entryID] = record
        }
        return Array(merged.values)
    }

    private static func mergeConnections(
        local: [EntryConnection],
        incoming: [EntryConnection],
        validEntryIDs: Set<UUID>
    ) -> [EntryConnection] {
        var merged = Dictionary(uniqueKeysWithValues: local.map { ($0.id, $0) })
        for record in incoming where
            validEntryIDs.contains(record.sourceEntryID) &&
            validEntryIDs.contains(record.targetEntryID)
        {
            merged[record.id] = record
        }
        return Array(merged.values)
    }

    private static func sanitizedLocalEntry(_ entry: SomaEntry) -> SomaEntry {
        var sanitized = entry
        if let fileName = sanitized.audioFileName, !isValidAudioFileName(fileName) {
            sanitized.audioFileName = nil
            sanitized.audioDuration = nil
            if sanitized.kind == .voice && sanitized.text.isEmpty {
                sanitized.transcriptionState = .failed
            }
        }
        if let fileName = sanitized.imageFileName, !isValidImageFileName(fileName) {
            sanitized.imageFileName = nil
        }
        return sanitized
    }

    private static func exportableEntry(_ entry: SomaEntry) -> SomaEntry {
        var portable = entry
        portable.audioFileName = nil
        portable.audioDuration = nil
        portable.imageFileName = nil
        portable.transcriptionRunID = nil
        if portable.isDeleted {
            portable.text = ""
            portable.transcriptionProvenance = nil
        }
        return portable
    }

    private static func exportableImportant(_ item: ImportantItem) -> ImportantItem {
        guard item.isDeleted else { return item }
        var portable = item
        portable.text = ""
        return portable
    }

    private static func boundedText(
        _ text: String,
        allowingEmpty: Bool = false
    ) -> String? {
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            (allowingEmpty || !cleaned.isEmpty),
            cleaned.utf8.count <= maximumTextBytes
        else {
            return nil
        }
        return cleaned
    }

    private static func cleanedMetadataValues(
        _ values: [String],
        limit: Int,
        lowercase: Bool = false
    ) -> [String] {
        var seen = Set<String>()
        return values.compactMap { value in
            var cleaned = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if lowercase { cleaned = cleaned.lowercased() }
            guard
                !cleaned.isEmpty,
                cleaned.count <= 80,
                cleaned.utf8.count <= 320
            else {
                return nil
            }
            let key = cleaned.folding(
                options: [.caseInsensitive, .diacriticInsensitive],
                locale: nil
            )
            guard seen.insert(key).inserted else { return nil }
            return cleaned
        }
        .prefix(limit)
        .map { $0 }
    }

    private static func isValidAudioFileName(_ fileName: String) -> Bool {
        guard
            fileName == URL(fileURLWithPath: fileName).lastPathComponent,
            !fileName.contains("/"),
            !fileName.contains("\\"),
            fileName.hasSuffix(".m4a")
        else {
            return false
        }
        return UUID(uuidString: String(fileName.dropLast(4))) != nil
    }

    private static func isValidImageFileName(_ fileName: String) -> Bool {
        guard
            fileName == URL(fileURLWithPath: fileName).lastPathComponent,
            !fileName.contains("/"),
            !fileName.contains("\\"),
            fileName.hasSuffix(".jpg")
        else {
            return false
        }
        return UUID(uuidString: String(fileName.dropLast(4))) != nil
    }

    private struct LocalSnapshot: Codable {
        var deviceID: UUID
        var entries: [SomaEntry]
        var important: [ImportantItem]
        var suggestions: [ImportantSuggestion]?
        var revisions: [EntryRevision]?
        var logs: [SomaLog]?
        var trackingSuggestions: [TrackingSuggestion]?
        var photoTextSuggestions: [PhotoTextSuggestion]?
        var entryMetadata: [EntryMetadata]?
        var entryConnections: [EntryConnection]?
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}

private protocol ContextRecord {
    var updatedAt: Date { get }
}

extension SomaEntry: ContextRecord {}
extension ImportantItem: ContextRecord {}

enum ContextError: LocalizedError {
    case unsupportedSchema(Int)
    case invalidBundle
    case persistenceFailed

    var errorDescription: String? {
        switch self {
        case .unsupportedSchema(let version):
            "This context bundle uses unsupported schema version \(version)."
        case .invalidBundle:
            "This context bundle is invalid or too large."
        case .persistenceFailed:
            "The merged context could not be saved."
        }
    }
}

enum StoreError: LocalizedError {
    case unavailable
    case writeFailed

    var errorDescription: String? {
        switch self {
        case .unavailable:
            "Protected storage is unavailable"
        case .writeFailed:
            "Couldn’t save changes"
        }
    }
}
