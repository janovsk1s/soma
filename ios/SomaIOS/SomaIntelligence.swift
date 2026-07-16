import Foundation
import Observation

@MainActor
@Observable
final class SomaIntelligence {
    let settings: AISettings

    private let store: SomaStore
    private let cloud: CloudAIClient
    private let appleActions: AppleFoundationIntelligence
    private let appleSpeech: AppleSpeechTranscriber
    private var activeTranscriptions = Set<UUID>()
    private var activeSuggestions = Set<UUID>()

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

    var speechModelStatus: String {
        appleSpeech.statusDescription
    }

    func processNewEntry(_ entry: SomaEntry) async {
        guard !entry.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        guard activeSuggestions.insert(entry.id).inserted else { return }
        defer { activeSuggestions.remove(entry.id) }

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

        let wantsImportant = ruleCandidates.isEmpty
        let wantsTracking = settings.trackingSuggestionsEnabled
        guard wantsImportant || wantsTracking else { return }

        if (wantsImportant && settings.onDeviceSuggestionsEnabled) || wantsTracking {
            do {
                let insights = try await appleActions.extractInsights(from: entry.text)
                try Task.checkCancellation()
                apply(insights, to: entry, wantsImportant: wantsImportant, engine: .appleFoundationModel)
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
            apply(insights, to: entry, wantsImportant: wantsImportant, engine: .groqGPTOSS20B)
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
