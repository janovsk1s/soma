import SwiftUI
import UIKit
import VisionKit

struct SomaBridgeSettingsView: View {
    @Environment(SomaBridgeClient.self) private var bridge
    @State private var pairingCode = ""
    @State private var showingScanner = false
    @State private var errorMessage: String?
    @State private var confirmingUnpair = false

    var body: some View {
        List {
            if let pairing = bridge.pairing {
                Section {
                    LabeledContent("Mac", value: pairing.bridgeName)
                    LabeledContent(
                        "Codex",
                        value: bridge.status.map {
                            $0.codexReady ? "Ready" : "Sign in on Mac"
                        } ?? "Not checked"
                    )
                    LabeledContent("Connection", value: pairing.connectionLabel)
                    Button("Check connection", systemImage: "bolt.horizontal.circle") {
                        Task { await checkConnection() }
                    }
                    .disabled(bridge.activity != .idle)
                } header: {
                    Text("Paired")
                } footer: {
                    Text("Your Mac keeps the Codex login. This iPhone holds only a device signing key and the Mac certificate fingerprint.")
                }
                .listRowBackground(FrostedBridgeRowBackground())

                Section {
                    ForEach(pairing.capabilities, id: \.rawValue) { capability in
                        Label(capabilityLabel(capability), systemImage: "checkmark.circle")
                    }
                } header: {
                    Text("Granted access")
                } footer: {
                    Text("The current bridge can read only the entry you choose and its connected context. It cannot edit Soma or run commands from the phone.")
                }
                .listRowBackground(FrostedBridgeRowBackground())

                Section {
                    Button("Disconnect this iPhone", systemImage: "xmark.circle", role: .destructive) {
                        confirmingUnpair = true
                    }
                }
                .listRowBackground(FrostedBridgeRowBackground())
            } else {
                Section {
                    Button("Scan pairing code", systemImage: "qrcode.viewfinder") {
                        showingScanner = true
                    }
                    .disabled(
                        !DataScannerViewController.isSupported
                            || !DataScannerViewController.isAvailable
                            || bridge.activity != .idle
                    )
                    Button("Paste pairing code", systemImage: "doc.on.clipboard") {
                        if let value = UIPasteboard.general.string {
                            pairingCode = value
                            Task { await pair() }
                        }
                    }
                    .disabled(bridge.activity != .idle)
                    TextField("soma://pair?…", text: $pairingCode, axis: .vertical)
                        .lineLimit(2...4)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .privacySensitive()
                    Button("Pair with Mac", systemImage: "link") {
                        Task { await pair() }
                    }
                    .disabled(
                        pairingCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || bridge.activity != .idle
                    )
                } header: {
                    Text("Pair a Mac")
                } footer: {
                    Text("Run the Soma companion on your Mac, then scan its one-use code. Pair over a private local network or your private Tailscale tailnet.")
                }
                .listRowBackground(FrostedBridgeRowBackground())

                Section {
                    Label("Codex credentials stay on the Mac", systemImage: "key")
                    Label("Every request is signed by this iPhone", systemImage: "checkmark.seal")
                    Label("The scanned certificate is pinned", systemImage: "lock.shield")
                } header: {
                    Text("Security")
                }
                .listRowBackground(FrostedBridgeRowBackground())
            }

            if bridge.activity != .idle {
                Section {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(activityLabel)
                            .foregroundStyle(.secondary)
                    }
                }
                .listRowBackground(FrostedBridgeRowBackground())
            }
        }
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
        .background { SomaScreenBackground() }
        .navigationTitle("Codex on Mac")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingScanner) {
            SomaBridgeScanner { value in
                showingScanner = false
                pairingCode = value
                Task { await pair() }
            }
            .ignoresSafeArea()
        }
        .confirmationDialog(
            "Disconnect this iPhone?",
            isPresented: $confirmingUnpair,
            titleVisibility: .visible
        ) {
            Button("Revoke on Mac and disconnect", role: .destructive) {
                Task {
                    do {
                        try await bridge.unpair()
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                }
            }
            Button("Forget only on this iPhone", role: .destructive) {
                do {
                    try bridge.forgetPairing()
                } catch {
                    errorMessage = error.localizedDescription
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Use local forget if the Mac is gone or its certificate changed. Revoke the device from the Mac separately when it is available.")
        }
        .alert("Couldn’t connect", isPresented: .constant(errorMessage != nil)) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private var activityLabel: String {
        switch bridge.activity {
        case .idle: ""
        case .pairing: "Pairing securely…"
        case .checking: "Checking the Mac…"
        case .asking: "Codex is thinking…"
        case .cancelling: "Stopping…"
        }
    }

    private func capabilityLabel(_ capability: SomaBridgeCapability) -> String {
        switch capability {
        case .contextRead: "Read selected context"
        case .codexThread: "Keep a private Codex thread"
        case .codexTurn: "Ask Codex"
        case .codexStream: "Stream the answer"
        case .proposalRead: "Read proposed changes"
        case .proposalApprove: "Approve proposed changes"
        case .syncRead: "Read encrypted sync"
        case .syncWrite: "Write encrypted sync"
        }
    }

    private func pair() async {
        do {
            let code = pairingCode
            pairingCode = ""
            try await bridge.pair(using: code)
            _ = try await bridge.refreshStatus()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func checkConnection() async {
        do {
            _ = try await bridge.refreshStatus()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

struct SomaCodexEntryView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SomaBridgeClient.self) private var bridge
    @Environment(SomaStore.self) private var store
    let entryID: UUID
    @AppStorage("soma.codex.context-disclosure.v1")
    private var acceptedContextDisclosure = false
    @State private var question = ""
    @State private var answer = ""
    @State private var errorMessage: String?
    @State private var pendingPrompt: String?
    @State private var showingContextDisclosure = false

    private let prompts = [
        "What connects this to my related notes?",
        "Summarize what matters here.",
        "What is one useful next step?",
    ]

    var body: some View {
        NavigationStack {
            Group {
                if !bridge.isPaired {
                    ContentUnavailableView(
                        "Pair your Mac first",
                        systemImage: "macbook.and.iphone",
                        description: Text("Open Settings → Codex on Mac. Your Mac keeps the login and securely relays only the context you approve.")
                    )
                } else if store.entry(id: entryID) == nil {
                    ContentUnavailableView(
                        "Entry unavailable",
                        systemImage: "doc.questionmark",
                        description: Text("This entry may have been deleted.")
                    )
                } else {
                    ScrollViewReader { proxy in
                        ScrollView {
                            VStack(alignment: .leading, spacing: 18) {
                                contextCard
                if answer.isEmpty, relevantJob?.output.isEmpty != false {
                                    promptSuggestions
                                }
                                if !liveAnswer.isEmpty {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Label("Codex", systemImage: "sparkles")
                                            .font(.caption.weight(.semibold))
                                            .foregroundStyle(.secondary)
                                        Text(liveAnswer)
                                            .textSelection(.enabled)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                    .padding(16)
                                    .background(.ultraThinMaterial, in: .rect(cornerRadius: 18))
                                    .id("answer")
                                }
                            }
                            .padding(16)
                        }
                        .scrollIndicators(.hidden)
                        .onChange(of: liveAnswer) {
                            withAnimation(.snappy) {
                                proxy.scrollTo("answer", anchor: .bottom)
                            }
                        }
                    }
                    .safeAreaInset(edge: .bottom) {
                        composer
                    }
                }
            }
            .background { SomaScreenBackground() }
            .navigationTitle("Ask Codex")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                if relevantJob?.status == .running {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Stop", systemImage: "stop.circle") {
                            Task { await bridge.cancelActiveJob() }
                        }
                    }
                }
            }
        }
        .alert("Codex couldn’t answer", isPresented: .constant(errorMessage != nil)) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
        .confirmationDialog(
            "Send \(contextEntryCount) \(contextEntryCount == 1 ? "entry" : "entries") to Codex?",
            isPresented: $showingContextDisclosure,
            titleVisibility: .visible
        ) {
            Button("Send to Codex") {
                acceptedContextDisclosure = true
                guard let pendingPrompt else { return }
                self.pendingPrompt = nil
                question = ""
                Task { await ask(pendingPrompt) }
            }
            Button("Cancel", role: .cancel) {
                pendingPrompt = nil
            }
        } message: {
            Text(
                "Full text from the selected entry and its connected entries travels through your paired Mac to the configured Codex/OpenAI model service. Follow-ups continue only in this entry’s private thread."
            )
        }
    }

    private var liveAnswer: String {
        let streamed = relevantJob?.output ?? ""
        return streamed.isEmpty ? answer : streamed
    }

    private var relevantJob: SomaBridgeJob? {
        bridge.activeEntryID == entryID ? bridge.activeJob : nil
    }

    private var contextCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Selected context", systemImage: "link")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            if let entry = store.entry(id: entryID) {
                Text(entry.text.isEmpty ? "Photo or voice entry" : entry.text)
                    .lineLimit(4)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Text(
                "\(contextEntryCount) full-text \(contextEntryCount == 1 ? "entry" : "entries") will be sent through your Mac to Codex/OpenAI. Follow-ups stay scoped to this entry."
            )
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(16)
        .background(.ultraThinMaterial, in: .rect(cornerRadius: 18))
    }

    private var promptSuggestions: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Try asking")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            ForEach(prompts, id: \.self) { prompt in
                Button {
                    question = prompt
                } label: {
                    HStack {
                        Text(prompt)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "arrow.up.left")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .padding(.vertical, 6)
            }
        }
        .padding(16)
        .background(.ultraThinMaterial, in: .rect(cornerRadius: 18))
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField("Ask about this entry", text: $question, axis: .vertical)
                .lineLimit(1...5)
                .padding(.vertical, 12)
            if bridge.activity != .idle {
                ProgressView()
                    .frame(width: 36, height: 44)
            } else {
                Button("Ask", systemImage: "arrow.up.circle.fill") {
                    beginAsk()
                }
                .labelStyle(.iconOnly)
                .font(.title2)
                .frame(width: 44, height: 44)
                .disabled(question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal, 16)
        .modifier(SomaBridgeComposerBackground())
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
    }

    private var contextEntryCount: Int {
        guard store.entry(id: entryID) != nil else { return 0 }
        let connectedIDs = Set(
            store.connections(for: entryID).compactMap {
                $0.otherEntryID(than: entryID)
            }
        )
        return 1 + min(connectedIDs.count, 11)
    }

    private func beginAsk() {
        let prompt = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty else { return }
        if acceptedContextDisclosure {
            question = ""
            Task { await ask(prompt) }
        } else {
            pendingPrompt = prompt
            showingContextDisclosure = true
        }
    }

    private func ask(_ prompt: String) async {
        do {
            let result = try await bridge.askCodex(question: prompt, about: entryID)
            answer = result.output
        } catch is CancellationError {
            return
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct SomaBridgeScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onCode: onCode)
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: true,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        try? controller.startScanning()
        return controller
    }

    func updateUIViewController(
        _ uiViewController: DataScannerViewController,
        context: Context
    ) {
        if !uiViewController.isScanning {
            try? uiViewController.startScanning()
        }
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        private let onCode: (String) -> Void
        private var completed = false

        init(onCode: @escaping (String) -> Void) {
            self.onCode = onCode
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            guard !completed else { return }
            for item in addedItems {
                guard
                    case .barcode(let barcode) = item,
                    let value = barcode.payloadStringValue,
                    value.lowercased().hasPrefix("soma://pair?")
                else {
                    continue
                }
                completed = true
                dataScanner.stopScanning()
                onCode(value)
                return
            }
        }
    }
}

private struct SomaBridgeComposerBackground: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular.interactive(), in: .rect(cornerRadius: 25))
        } else {
            content.background(.regularMaterial, in: .rect(cornerRadius: 25))
        }
    }
}

private struct FrostedBridgeRowBackground: View {
    var body: some View {
        Rectangle().fill(.ultraThinMaterial)
    }
}
