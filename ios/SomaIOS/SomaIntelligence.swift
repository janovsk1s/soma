import BackgroundTasks
import Foundation
import Observation

@MainActor
@Observable
final class SomaIntelligence {
    static let metadataBackgroundTaskIdentifier = "com.soma.native.metadata"

    let settings: AISettings

    private let store: SomaStore
    private let cloud: CloudAIClient
    private let appleActions: AppleFoundationIntelligence
    private let appleSpeech: AppleSpeechTranscriber
    private var activeTranscriptions = Set<UUID>()
    private var processingTokens: [UUID: UUID] = [:]

    init(
        store: SomaStore,
        settings: AISettings = AISettings(),
        cloud: CloudAIClient = CloudAIClient(),
        appleActions: AppleFoundationIntelligence = AppleFoundationIntelligence(),
        appleSpeech: AppleSpeechTranscriber = AppleSpeechTranscriber()
    ) {
        self.store = store
        self.settings = settings
        self.cloud = cloud
        self.appleActions = appleActions
        self.appleSpeech = appleSpeech
    }

    var foundationModelStatus: String {
        appleActions.statusDescription
    }

    var canReflect: Bool {
        appleActions.isAvailable
    }

    func reflect(on day: Date) async throws -> String {
        let key = SomaDay.key(day)
        let texts = store.entries
            .filter { $0.day == key && !$0.isDeleted && !$0.text.isEmpty }
            .sorted { $0.createdAt < $1.createdAt }
            .map(\.text)
        guard !texts.isEmpty else { throw AppleIntelligenceError.unavailable }
        return try await appleActions.reflect(onDay: texts.joined(separator: "\n\n"))
    }

    var speechModelStatus: String {
        appleSpeech.statusDescription
    }

    func processNewEntry(_ entry: SomaEntry) async {
        guard !entry.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let token = UUID()
        processingTokens[entry.id] = token
        defer {
            if processingTokens[entry.id] == token {
                processingTokens[entry.id] = nil
            }
        }

        // Deterministic rules run first so Important suggestions never depend on
        // a model, a key, or a network. AI only answers what the rules left open.
        let ruleCandidates = SomaRules.importantCandidates(in: entry.text)
        if !ruleCandidates.isEmpty {
            store.replaceSuggestions(
                for: entry.id,
                sourceUpdatedAt: entry.updatedAt,
                texts: ruleCandidates,
                engine: .localRules
            )
        }

        let fingerprint = SomaMetadataAnalyzer.fingerprint(entry.text)
        let localMetadata = await Task.detached(priority: .utility) {
            SomaMetadataAnalyzer.extract(from: entry.text)
        }.value
        guard isCurrent(token, for: entry.id) else { return }
        if settings.autoTagsEnabled {
            await persistMetadata(
                localMetadata,
                for: entry,
                fingerprint: fingerprint,
                engine: .localLanguageAnalysis,
                token: token
            )
        }

        let canSuggestImportant = ruleCandidates.isEmpty
        let wantsOnDeviceImportant =
            canSuggestImportant && settings.onDeviceSuggestionsEnabled
        let wantsTracking = settings.trackingSuggestionsEnabled
        let wantsMetadata = settings.autoTagsEnabled
        guard wantsOnDeviceImportant || wantsTracking || wantsMetadata else { return }

        if wantsOnDeviceImportant || wantsTracking || wantsMetadata {
            do {
                let insights = try await appleActions.extractInsights(from: entry.text)
                try Task.checkCancellation()
                guard isCurrent(token, for: entry.id) else { return }
                apply(
                    insights,
                    to: entry,
                    wantsImportant: wantsOnDeviceImportant,
                    engine: .appleFoundationModel
                )
                if wantsMetadata {
                    await persistMetadata(
                        localMetadata.merging(tags: insights.tags),
                        for: entry,
                        fingerprint: fingerprint,
                        engine: .appleFoundationModel,
                        token: token
                    )
                }
                return
            } catch is CancellationError {
                return
            } catch {
                // Apple Intelligence is capability-gated. A configured cloud
                // fallback may still help, but note text never leaves the
                // device unless the separate cloud toggle is on.
            }
        }

        guard settings.cloudSuggestionsEnabled else { return }
        guard let key = settings.key(for: .groq), !key.isEmpty else {
            settings.record(.apiKeyMissing)
            return
        }
        do {
            let insights = try await cloud.extractInsights(
                text: entry.text,
                apiKey: key,
                wifiOnly: settings.wifiOnly
            )
            try Task.checkCancellation()
            guard isCurrent(token, for: entry.id) else { return }
            apply(
                insights,
                to: entry,
                wantsImportant: canSuggestImportant,
                engine: .groqGPTOSS20B
            )
            if wantsMetadata {
                await persistMetadata(
                    localMetadata.merging(tags: insights.tags),
                    for: entry,
                    fingerprint: fingerprint,
                    engine: .groqGPTOSS20B,
                    token: token
                )
            }
        } catch is CancellationError {
            return
        } catch let error as CloudProviderError {
            settings.record(error.reason)
        } catch {
            settings.record(.providerError)
        }
    }

    private func apply(
        _ insights: NoteInsights,
        to entry: SomaEntry,
        wantsImportant: Bool,
        engine: SuggestionEngine
    ) {
        if wantsImportant {
            store.replaceSuggestions(
                for: entry.id,
                sourceUpdatedAt: entry.updatedAt,
                texts: insights.actions,
                engine: engine
            )
        }
        if settings.trackingSuggestionsEnabled {
            store.replaceTrackingSuggestions(
                for: entry.id,
                sourceUpdatedAt: entry.updatedAt,
                proposals: insights.trackingProposals,
                engine: engine
            )
        }
    }

    private func persistMetadata(
        _ generated: GeneratedEntryMetadata,
        for entry: SomaEntry,
        fingerprint: String,
        engine: MetadataEngine,
        token: UUID
    ) async {
        guard isCurrent(token, for: entry.id) else { return }
        let candidates = store.entries
            .filter { $0.id != entry.id && !$0.isDeleted && !$0.text.isEmpty }
            .sorted { $0.updatedAt > $1.updatedAt }
            .prefix(300)
            .compactMap { candidate -> MetadataConnectionCandidate? in
                guard let metadata = store.metadata(for: candidate.id) else { return nil }
                return MetadataConnectionCandidate(entry: candidate, metadata: metadata)
            }
        let sourceMetadata = EntryMetadata(
            id: UUID(),
            entryID: entry.id,
            tags: generated.tags,
            createdAt: Date(),
            people: generated.people,
            places: generated.places,
            organizations: generated.organizations,
            keywords: generated.keywords,
            sourceUpdatedAt: entry.updatedAt,
            sourceFingerprint: fingerprint,
            engine: engine
        )
        let proposals = await Task.detached(priority: .utility) {
            SomaMetadataAnalyzer.connectionProposals(
                for: entry,
                metadata: sourceMetadata,
                candidates: Array(candidates)
            )
        }.value
        guard isCurrent(token, for: entry.id) else { return }
        _ = store.setMetadata(
            for: entry.id,
            sourceFingerprint: fingerprint,
            generated: generated,
            engine: engine,
            proposals: proposals
        )
    }

    private func isCurrent(_ token: UUID, for entryID: UUID) -> Bool {
        processingTokens[entryID] == token
    }

    // Photo notes get one on-device Vision pass at capture; recognized text is
    // offered as an accept-gated chip and never applied on its own.
    func extractPhotoText(for entry: SomaEntry) async {
        guard
            entry.text.isEmpty,
            let fileName = entry.imageFileName,
            !store.hasPhotoTextSuggestion(for: entry.id),
            let url = store.availableImageURL(fileName: fileName)
        else {
            return
        }
        let recognized = await Task.detached(priority: .utility) {
            PhotoTextReader.recognizeText(at: url)
        }.value
        guard let recognized else { return }
        store.setPhotoTextSuggestion(for: entry.id, text: recognized)
        if let receipt = ReceiptParse.parse(recognized) {
            store.addReceiptSuggestion(
                for: entry.id,
                summary: receipt.summary,
                detail: receipt.detail,
                amountCents: receipt.totalCents
            )
        }
    }

    func transcribe(_ entry: SomaEntry) async {
        guard settings.voiceTranscriptionEnabled, entry.kind == .voice else { return }
        guard activeTranscriptions.insert(entry.id).inserted else { return }
        defer { activeTranscriptions.remove(entry.id) }
        guard
            let fileName = entry.audioFileName,
            store.entry(id: entry.id)?.audioFileName == fileName,
            let audioURL = store.availableAudioURL(fileName: fileName)
        else {
            store.failTranscription(entryID: entry.id)
            return
        }

        guard let runID = store.beginTranscription(entryID: entry.id, fileName: fileName) else {
            return
        }

        if settings.cloudTranscriptionEnabled {
            let provider = settings.provider
            let requestedEngine = cloudEngine(provider: provider)
            guard let key = settings.key(for: provider), !key.isEmpty else {
                await fallBackToAppleSpeech(
                    entryID: entry.id,
                    runID: runID,
                    fileName: fileName,
                    audioURL: audioURL,
                    requestedEngine: requestedEngine,
                    reason: .apiKeyMissing
                )
                return
            }
            do {
                let result = try await cloud.transcribe(
                    audioURL: audioURL,
                    provider: provider,
                    groqModel: settings.groqModel,
                    apiKey: key,
                    wifiOnly: settings.wifiOnly
                )
                try Task.checkCancellation()
                let text = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { throw CloudProviderError(reason: .providerError) }
                let provenance = TranscriptionProvenance(
                    requestedEngine: requestedEngine,
                    usedEngine: requestedEngine
                )
                if let completed = store.completeTranscription(
                    entryID: entry.id,
                    runID: runID,
                    fileName: fileName,
                    text: text,
                    provenance: provenance
                ) {
                    await processNewEntry(completed)
                }
                return
            } catch is CancellationError {
                store.requeueTranscription(entryID: entry.id, runID: runID)
                return
            } catch let error as CloudProviderError {
                await fallBackToAppleSpeech(
                    entryID: entry.id,
                    runID: runID,
                    fileName: fileName,
                    audioURL: audioURL,
                    requestedEngine: requestedEngine,
                    reason: error.reason
                )
                return
            } catch {
                await fallBackToAppleSpeech(
                    entryID: entry.id,
                    runID: runID,
                    fileName: fileName,
                    audioURL: audioURL,
                    requestedEngine: requestedEngine,
                    reason: .providerError
                )
                return
            }
        }

        await transcribeWithAppleSpeech(
            entryID: entry.id,
            runID: runID,
            fileName: fileName,
            audioURL: audioURL
        )
    }

    func retryTranscription(for entry: SomaEntry) {
        store.setTranscriptionState(.queued, for: entry.id)
        Task { await transcribe(entry) }
    }

    func resumePendingWork() async {
        let queued = store.entries.filter {
            let stateNeedsWork =
                $0.transcriptionState == nil ||
                $0.transcriptionState == .queued ||
                $0.transcriptionState == .running
            let audioExists = $0.audioFileName.map {
                store.availableAudioURL(fileName: $0) != nil
            } ?? false
            return $0.kind == .voice &&
                !$0.isDeleted &&
                stateNeedsWork &&
                audioExists
        }
        for entry in queued {
            guard !Task.isCancelled else { return }
            await transcribe(entry)
        }
        await resumeMetadataWork(limit: 24)
    }

    func resumeMetadataWork(limit: Int = 24) async {
        guard settings.autoTagsEnabled else { return }
        let pending = store.entriesNeedingMetadata(limit: limit)
        for entry in pending {
            guard !Task.isCancelled else { return }
            await processNewEntry(entry)
        }
        scheduleMetadataBackgroundRefreshIfNeeded()
    }

    func scheduleMetadataBackgroundRefreshIfNeeded() {
        guard
            settings.autoTagsEnabled,
            !store.entriesNeedingMetadata(limit: 1).isEmpty
        else {
            BGTaskScheduler.shared.cancel(
                taskRequestWithIdentifier: Self.metadataBackgroundTaskIdentifier
            )
            return
        }
        let request = BGAppRefreshTaskRequest(
            identifier: Self.metadataBackgroundTaskIdentifier
        )
        request.earliestBeginDate = Date().addingTimeInterval(15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Background execution is best-effort. The durable fingerprint scan
            // runs again on launch and scene activation.
        }
    }

    private func transcribeWithAppleSpeech(
        entryID: UUID,
        runID: UUID,
        fileName: String,
        audioURL: URL
    ) async {
        do {
            let text = try await appleSpeech.transcribe(audioURL: audioURL)
            try Task.checkCancellation()
            let provenance = TranscriptionProvenance(
                requestedEngine: .appleSpeech,
                usedEngine: .appleSpeech
            )
            if let completed = store.completeTranscription(
                entryID: entryID,
                runID: runID,
                fileName: fileName,
                text: text,
                provenance: provenance
            ) {
                await processNewEntry(completed)
            }
        } catch is CancellationError {
            store.requeueTranscription(entryID: entryID, runID: runID)
        } catch let error as AppleIntelligenceError {
            settings.record(failureReason(for: error))
            store.failTranscription(entryID: entryID, runID: runID)
        } catch {
            settings.record(.onDeviceUnavailable)
            store.failTranscription(entryID: entryID, runID: runID)
        }
    }

    private func fallBackToAppleSpeech(
        entryID: UUID,
        runID: UUID,
        fileName: String,
        audioURL: URL,
        requestedEngine: TranscriptionEngine,
        reason: IntelligenceFailureReason
    ) async {
        settings.record(reason)
        do {
            let text = try await appleSpeech.transcribe(audioURL: audioURL)
            try Task.checkCancellation()
            let provenance = TranscriptionProvenance(
                requestedEngine: requestedEngine,
                usedEngine: .appleSpeech,
                fallbackReason: reason
            )
            if let completed = store.completeTranscription(
                entryID: entryID,
                runID: runID,
                fileName: fileName,
                text: text,
                provenance: provenance
            ) {
                await processNewEntry(completed)
            }
        } catch is CancellationError {
            store.requeueTranscription(entryID: entryID, runID: runID)
        } catch {
            store.failTranscription(entryID: entryID, runID: runID)
        }
    }

    private func cloudEngine(provider: CloudSpeechProvider) -> TranscriptionEngine {
        switch provider {
        case .groq: settings.groqModel.engine
        case .elevenLabs: .elevenLabsScribeV2
        }
    }

    private func failureReason(for error: AppleIntelligenceError) -> IntelligenceFailureReason {
        switch error {
        case .permissionDenied: .permissionError
        case .unavailable, .unsupportedLocale, .noSpeech: .onDeviceUnavailable
        }
    }
}
