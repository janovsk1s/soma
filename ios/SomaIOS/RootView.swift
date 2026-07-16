import AVKit
import ImageIO
import SwiftUI

struct RootView: View {
    private enum SomaTab: Hashable {
        case today
        case important
        case settings
        case search
    }

    @State private var selection: SomaTab = .today

    var body: some View {
        ZStack {
            SomaForestBackground()

            TabView(selection: $selection) {
                Tab("Today", systemImage: "circle.fill", value: SomaTab.today) {
                    TodayView()
                }
                Tab("Important", systemImage: "checkmark.circle", value: SomaTab.important) {
                    ImportantView()
                }
                Tab("Settings", systemImage: "gearshape", value: SomaTab.settings) {
                    SettingsView()
                }
                Tab("Search", systemImage: "magnifyingglass", value: SomaTab.search, role: .search) {
                    SearchView(onOpenDay: { selection = .today })
                }
            }
            .tint(.primary)
            .modifier(LatestTabBehavior())
        }
    }
}

private struct TodayView: View {
    @Environment(SomaStore.self) private var store
    @Environment(SomaIntelligence.self) private var intelligence
    @Environment(HealthWorkouts.self) private var health
    @State private var showingCamera = false
    @State private var showingCalendar = false
    @State private var editingEntry: SomaEntry?
    @State private var recorder = AudioRecorder()
    @State private var errorMessage: String?
    @State private var workoutLines: [HealthWorkouts.Line] = []

    private var unkeptWorkoutLines: [HealthWorkouts.Line] {
        workoutLines.filter {
            !store.hasLog(day: store.selectedDay, kind: .workout, title: $0.title)
        }
    }

    private var dayIsEmpty: Bool {
        store.selectedEntries.isEmpty &&
        store.logs(for: store.selectedDay).isEmpty &&
        unkeptWorkoutLines.isEmpty
    }

    var body: some View {
        NavigationStack {
            Group {
                if dayIsEmpty {
                    ContentUnavailableView(
                        "Nothing here yet",
                        systemImage: "circle",
                        description: Text("Drop a thought and return to your day.")
                    )
                } else {
                    List {
                        ForEach(store.selectedEntries) { entry in
                            VStack(alignment: .leading, spacing: 8) {
                                EntryRow(entry: entry)
                                    .contentShape(.rect)
                                    .onTapGesture { editingEntry = entry }
                                    .contextMenu {
                                        Button("Make Important", systemImage: "checkmark.circle") {
                                            if !store.makeImportant(from: entry) {
                                                errorMessage = saveFailureMessage
                                            }
                                        }
                                        Button("Suggest Important", systemImage: "sparkles") {
                                            Task { await intelligence.processNewEntry(entry) }
                                        }
                                        if
                                            entry.kind == .voice,
                                            entry.transcriptionState == .failed
                                        {
                                            Button("Transcribe Again", systemImage: "waveform.badge.mic") {
                                                intelligence.retryTranscription(for: entry)
                                            }
                                        }
                                        Button("Delete", systemImage: "trash", role: .destructive) {
                                            withAnimation { _ = store.remove(entry: entry) }
                                        }
                                    }
                                if let suggestion = store.pendingSuggestions(for: entry.id).first {
                                    SuggestionRow(suggestion: suggestion)
                                        .transition(.opacity.combined(with: .move(edge: .top)))
                                }
                                ForEach(store.pendingTrackingSuggestions(for: entry.id)) { proposal in
                                    TrackingSuggestionRow(suggestion: proposal)
                                        .transition(.opacity.combined(with: .move(edge: .top)))
                                }
                            }
                            .animation(.snappy, value: store.pendingSuggestions(for: entry.id))
                            .animation(.snappy, value: store.pendingTrackingSuggestions(for: entry.id))
                            .listRowSeparator(
                                store.pendingSuggestions(for: entry.id).isEmpty &&
                                store.pendingTrackingSuggestions(for: entry.id).isEmpty
                                    ? .automatic : .hidden
                            )
                            .listRowBackground(Color.clear)
                        }

                        quietDayLines
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .scrollIndicators(.hidden)
                }
            }
            .background { SomaScreenBackground() }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Previous day", systemImage: "chevron.left") {
                        withAnimation { shiftDay(by: -1) }
                    }
                    .labelStyle(.iconOnly)
                }
                // The date itself opens the calendar — one fewer control in the bar.
                ToolbarItem(placement: .principal) {
                    Button {
                        showingCalendar = true
                    } label: {
                        Text(store.selectedDay.formatted(.dateTime.weekday(.wide).month().day()))
                            .font(.headline)
                            .foregroundStyle(.primary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Open calendar")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Next day", systemImage: "chevron.right") {
                        withAnimation { shiftDay(by: 1) }
                    }
                    .labelStyle(.iconOnly)
                }
            }
            .safeAreaInset(edge: .bottom) {
                CaptureBar(
                    recorder: recorder,
                    onSave: { text in
                        guard let entry = store.addText(text) else {
                            errorMessage = saveFailureMessage
                            return false
                        }
                        Task { await intelligence.processNewEntry(entry) }
                        return true
                    },
                    onCamera: { showingCamera = true },
                    onRecord: toggleRecording
                )
                .padding(.horizontal, 12)
            }
            .task(id: "\(SomaDay.key(store.selectedDay))-\(health.isEnabled)") {
                workoutLines = await health.workoutLines(for: store.selectedDay)
            }
            .sheet(item: $editingEntry) { entry in
                EntryEditor(entry: entry)
            }
            .sheet(isPresented: $showingCalendar) {
                DayPickerSheet()
                    .presentationDetents([.medium])
                    .presentationDragIndicator(.visible)
            }
            .fullScreenCover(isPresented: $showingCamera) {
                OneShotCameraView(onCaptured: savePhoto)
            }
            .alert("Soma", isPresented: .constant(errorMessage != nil)) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    // Ambient lines at the end of the day: workouts straight from Health (kept only
    // if the user says so) and accepted meal/workout logs. Silence when there are none.
    @ViewBuilder
    private var quietDayLines: some View {
        Section {
            ForEach(unkeptWorkoutLines) { line in
                HStack(spacing: 10) {
                    Image(systemName: "figure.run")
                        .font(.caption)
                    Text(line.title)
                        .font(.callout)
                    Spacer()
                    Button("Keep") {
                        withAnimation {
                            _ = store.addLog(kind: .workout, title: line.title, day: store.selectedDay)
                        }
                    }
                    .font(.caption)
                    .buttonStyle(.borderless)
                }
                .foregroundStyle(.secondary)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            ForEach(store.logs(for: store.selectedDay)) { log in
                HStack(spacing: 10) {
                    Image(systemName: log.kind.systemImage)
                        .font(.caption)
                    Text(log.title)
                        .font(.callout)
                    Spacer()
                }
                .foregroundStyle(.secondary)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .swipeActions {
                    Button("Delete", systemImage: "trash", role: .destructive) {
                        _ = store.remove(log: log)
                    }
                }
            }
        }
    }

    private var saveFailureMessage: String {
        "Couldn’t save this text. It may be too long, or protected storage may be unavailable."
    }

    private func shiftDay(by days: Int) {
        store.selectedDay = Calendar.autoupdatingCurrent.date(
            byAdding: .day,
            value: days,
            to: store.selectedDay
        ) ?? store.selectedDay
    }

    private func toggleRecording() {
        if recorder.isRecording {
            recorder.stop()
            return
        }
        Task {
            do {
                let directory = try store.audioDirectory()
                try await recorder.start(in: directory) { fileName, duration in
                    guard let entry = store.addVoice(fileName: fileName, duration: duration) else {
                        try? FileManager.default.removeItem(
                            at: directory.appending(path: fileName)
                        )
                        errorMessage = store.storageStatus
                        return
                    }
                    Task { await intelligence.transcribe(entry) }
                }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func savePhoto(_ photo: CapturedPhoto) async throws {
        let directory = try store.imageDirectory()
        let fileName = "\(UUID().uuidString).jpg"
        let url = directory.appending(path: fileName)
        let data = photo.jpegData

        try await Task.detached(priority: .userInitiated) {
            try data.write(to: url, options: [.atomic, .completeFileProtection])
        }.value

        guard store.addPhoto(fileName: fileName) != nil else {
            try? FileManager.default.removeItem(at: url)
            throw StoreError.writeFailed
        }
    }
}

// Capture never takes over the screen: typing expands the bar in place (keeping
// focus so several thoughts can be dropped in a row), holding the bar starts a
// voice note — the same reach-for-it feel as the Light Phone capture bar.
private struct CaptureBar: View {
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    let recorder: AudioRecorder
    let onSave: (String) -> Bool
    let onCamera: () -> Void
    let onRecord: () -> Void

    @State private var text = ""
    @FocusState private var focused: Bool

    private var trimmed: String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            HStack(alignment: .bottom, spacing: 6) {
                if recorder.isRecording {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(.red)
                            .frame(width: 8, height: 8)
                        Text(recorder.elapsed.formattedDuration)
                            .monospacedDigit()
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(minHeight: controlSize - 24)
                    .padding(.vertical, 12)
                } else {
                    TextField("Write something", text: $text, axis: .vertical)
                        .lineLimit(1...5)
                        .focused($focused)
                        .frame(minHeight: controlSize - 24)
                        .padding(.vertical, 12)
                }
                if !trimmed.isEmpty, !recorder.isRecording {
                    Button("Save", systemImage: "arrow.up.circle.fill") {
                        if onSave(trimmed) {
                            text = ""
                        }
                    }
                    .labelStyle(.iconOnly)
                    .font(.title2)
                    .padding(.bottom, 12)
                }
            }
            .padding(.horizontal, 16)
            .modifier(NativeGlassComposer())
            .onLongPressGesture {
                guard !recorder.isRecording, !focused, trimmed.isEmpty else { return }
                onRecord()
            }

            if focused {
                Button("Hide keyboard", systemImage: "keyboard.chevron.compact.down") {
                    focused = false
                }
                .labelStyle(.iconOnly)
                .font(.body)
                .frame(width: controlSize, height: controlSize)
                .buttonStyle(.plain)
                .modifier(NativeGlassCircle())
            } else {
                Button(action: onCamera) {
                    Image(systemName: "camera.fill")
                        .font(.body)
                        .frame(width: controlSize, height: controlSize)
                }
                .buttonStyle(.plain)
                .modifier(NativeGlassCircle())
                .disabled(recorder.isRecording)

                Button(action: onRecord) {
                    Image(systemName: recorder.isRecording ? "stop.fill" : "mic.fill")
                        .font(.title3)
                        .frame(width: controlSize, height: controlSize)
                        .foregroundStyle(recorder.isRecording ? .white : .primary)
                        .contentTransition(.symbolEffect(.replace))
                }
                .buttonStyle(.plain)
                .modifier(NativeRecordControl(recording: recorder.isRecording))
                .sensoryFeedback(.impact, trigger: recorder.isRecording)
            }
        }
        .animation(.snappy, value: focused)
        .animation(.snappy, value: recorder.isRecording)
        .animation(.snappy, value: trimmed.isEmpty)
    }

    private var controlSize: CGFloat {
        verticalSizeClass == .compact ? 44 : 50
    }
}

private struct EntryRow: View {
    @Environment(SomaStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let entry: SomaEntry
    @State private var player: AVPlayer?
    @State private var isPlaying = false

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 5))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 10))

        layout {
            Text(entry.createdAt.formatted(date: .omitted, time: .shortened))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(
                    width: dynamicTypeSize.isAccessibilitySize ? nil : 54,
                    alignment: .leading
                )

            VStack(alignment: .leading, spacing: 5) {
                if let fileName = entry.imageFileName {
                    EntryPhoto(fileName: fileName)
                    if !entry.text.isEmpty {
                        Text(entry.text)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                } else if entry.kind == .voice, let fileName = entry.audioFileName {
                    Button {
                        togglePlayback(fileName: fileName)
                    } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Image(systemName: isPlaying ? "stop.fill" : "waveform")
                                .contentTransition(.symbolEffect(.replace))
                            voiceLabel
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .buttonStyle(.plain)
                    .onReceive(
                        NotificationCenter.default
                            .publisher(for: AVPlayerItem.didPlayToEndTimeNotification)
                            .receive(on: DispatchQueue.main)
                    ) { notification in
                        guard
                            let item = notification.object as? AVPlayerItem,
                            item === player?.currentItem
                        else {
                            return
                        }
                        player = nil
                        isPlaying = false
                    }
                    .onDisappear {
                        player?.pause()
                        player = nil
                        isPlaying = false
                    }
                } else {
                    Text(entry.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if let provenance = entry.transcriptionProvenance {
                    Text(provenanceLabel(provenance))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func togglePlayback(fileName: String) {
        if isPlaying {
            player?.pause()
            player = nil
            isPlaying = false
            return
        }
        guard let url = store.availableAudioURL(fileName: fileName) else { return }
        let player = AVPlayer(url: url)
        self.player = player
        isPlaying = true
        player.play()
    }

    @ViewBuilder
    private var voiceLabel: some View {
        switch entry.transcriptionState {
        case .queued, .running:
            if entry.text.isEmpty {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                    Text("Transcribing…")
                        .foregroundStyle(.secondary)
                }
            } else {
                Text(entry.text)
            }
        case .failed:
            if entry.text.isEmpty {
                Text("Voice note · transcription unavailable")
                    .foregroundStyle(.secondary)
            } else {
                Text(entry.text)
            }
        default:
            Text(entry.text.isEmpty ? "Voice note" : entry.text)
        }
    }

    private func provenanceLabel(_ provenance: TranscriptionProvenance) -> String {
        if let reason = provenance.fallbackReason {
            return "\(provenance.usedEngine.displayName) · \(reason.displayName)"
        }
        return provenance.usedEngine.displayName
    }
}

private struct EntryPhoto: View {
    @Environment(SomaStore.self) private var store
    let fileName: String
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                // Fill via overlay so the image exerts no intrinsic width pressure —
                // scaledToFill inside an HStack row otherwise shoves siblings off-screen.
                Color.clear
                    .frame(maxWidth: .infinity)
                    .frame(height: 220)
                    .overlay {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                    }
                    .clipped()
            } else {
                ZStack {
                    Rectangle().fill(.quaternary)
                    ProgressView()
                }
                .frame(height: 180)
            }
        }
        .clipShape(.rect(cornerRadius: 12))
        .privacySensitive()
        .task(id: fileName) {
            guard let url = store.availableImageURL(fileName: fileName) else { return }
            image = await Task.detached(priority: .utility) {
                downsampledImage(at: url, maxPixelSize: 1200)
            }.value
        }
    }
}

/// Decodes at display size via ImageIO instead of inflating the full capture into
/// memory — a multi-megapixel JPEG would otherwise cost tens of MB per visible row.
private func downsampledImage(at url: URL, maxPixelSize: CGFloat) -> UIImage? {
    let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
    guard let source = CGImageSourceCreateWithURL(url as CFURL, sourceOptions) else { return nil }
    let options = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceShouldCacheImmediately: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
    ] as CFDictionary
    guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else { return nil }
    return UIImage(cgImage: cgImage)
}

private struct SuggestionRow: View {
    @Environment(SomaStore.self) private var store
    let suggestion: ImportantSuggestion

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "sparkles")
                .foregroundStyle(.secondary)
            Button {
                withAnimation { _ = store.accept(suggestion) }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Important?")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(suggestion.text)
                        .foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                        .lineLimit(4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .buttonStyle(.plain)
            Button("Dismiss", systemImage: "xmark") {
                withAnimation { _ = store.dismiss(suggestion) }
            }
            .labelStyle(.iconOnly)
            .buttonStyle(.borderless)
            .frame(minWidth: 44, minHeight: 44)
        }
        .padding(10)
        .background(.ultraThinMaterial, in: .rect(cornerRadius: 14))
        .accessibilityElement(children: .contain)
        .accessibilityHint("Tap the suggestion to add it to Important.")
        .contextMenu {
            Text(suggestion.engine.displayName)
        }
    }
}

private struct TrackingSuggestionRow: View {
    @Environment(SomaStore.self) private var store
    let suggestion: TrackingSuggestion

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: suggestion.kind.systemImage)
                .foregroundStyle(.secondary)
            Button {
                withAnimation { _ = store.accept(suggestion) }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(suggestion.kind.question)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(suggestion.text)
                        .foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                        .lineLimit(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .buttonStyle(.plain)
            Button("Dismiss", systemImage: "xmark") {
                withAnimation { _ = store.dismiss(suggestion) }
            }
            .labelStyle(.iconOnly)
            .buttonStyle(.borderless)
            .frame(minWidth: 44, minHeight: 44)
        }
        .padding(10)
        .background(.ultraThinMaterial, in: .rect(cornerRadius: 14))
        .accessibilityElement(children: .contain)
        .accessibilityHint("Tap the suggestion to keep it as a quiet log line.")
        .contextMenu {
            Text(suggestion.engine.displayName)
        }
    }
}

private struct EntryEditor: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SomaStore.self) private var store
    @Environment(SomaIntelligence.self) private var intelligence
    let entry: SomaEntry
    @State private var text: String
    @State private var errorMessage: String?
    @State private var showingHistory = false

    init(entry: SomaEntry) {
        self.entry = entry
        _text = State(initialValue: entry.text)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let fileName = entry.imageFileName {
                    EntryPhoto(fileName: fileName)
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                }
                TextEditor(text: $text)
                    .font(.body)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
            }
                .navigationTitle("Entry")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    if !store.revisions(for: entry.id).isEmpty {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("History", systemImage: "clock.arrow.circlepath") {
                                showingHistory = true
                            }
                            .labelStyle(.iconOnly)
                        }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") {
                            guard let updated = store.update(entry: entry, text: text) else {
                                errorMessage = "Couldn’t save this edit. It may be too long, or protected storage may be unavailable."
                                return
                            }
                            Task { await intelligence.processNewEntry(updated) }
                            dismiss()
                        }
                    }
                }
                .sheet(isPresented: $showingHistory) {
                    EntryHistorySheet(entryID: entry.id)
                }
        }
        .alert("Couldn’t save", isPresented: .constant(errorMessage != nil)) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }
}

private struct EntryHistorySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SomaStore.self) private var store
    let entryID: UUID

    var body: some View {
        NavigationStack {
            List {
                ForEach(store.revisions(for: entryID)) { revision in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(revision.recordedAt.formatted(date: .abbreviated, time: .shortened))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(revision.text)
                            .textSelection(.enabled)
                    }
                    .padding(.vertical, 2)
                }
            }
            .navigationTitle("Earlier wording")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private struct CaptureSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let onSave: (String) -> Bool
    @State private var text = ""
    @State private var errorMessage: String?
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .focused($focused)
                .font(.title3)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            if onSave(text) {
                                dismiss()
                            } else {
                                errorMessage = "Couldn’t save this text. It may be too long, or protected storage may be unavailable."
                            }
                        }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
        }
        .onAppear { focused = true }
        .alert("Couldn’t save", isPresented: .constant(errorMessage != nil)) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }
}

private struct ImportantView: View {
    @Environment(SomaStore.self) private var store
    @State private var showingCapture = false

    var body: some View {
        NavigationStack {
            List {
                if !store.openImportant.isEmpty {
                    Section {
                        ForEach(store.openImportant) { item in
                            ImportantRow(item: item)
                                .listRowBackground(Color.clear)
                        }
                    }
                }
                if !store.completedImportant.isEmpty {
                    Section("Completed") {
                        ForEach(store.completedImportant) { item in
                            ImportantRow(item: item)
                                .listRowBackground(Color.clear)
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .background { SomaScreenBackground() }
            .overlay {
                if store.openImportant.isEmpty && store.completedImportant.isEmpty {
                    ContentUnavailableView(
                        "Nothing important",
                        systemImage: "checkmark.circle",
                        description: Text("Keep only what still needs your attention.")
                    )
                }
            }
            .navigationTitle("Important")
            .toolbar {
                Button("Add", systemImage: "plus") { showingCapture = true }
            }
            .sheet(isPresented: $showingCapture) {
                CaptureSheet(title: "New important item") { store.addImportant($0) }
                    .presentationDetents([.medium, .large])
            }
        }
    }
}

// The circle completes; tapping the text opens the full item. A whole-row toggle
// makes long items impossible to read and completes them by accident.
private struct ImportantRow: View {
    @Environment(SomaStore.self) private var store
    let item: ImportantItem
    @State private var showingDetail = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Button {
                withAnimation { _ = store.toggle(item) }
            } label: {
                Image(systemName: item.state == .open ? "circle" : "checkmark.circle.fill")
                    .contentTransition(.symbolEffect(.replace))
                    .frame(minWidth: 32, minHeight: 32)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(item.state == .open ? "Mark done" : "Mark open")

            Button {
                showingDetail = true
            } label: {
                Text(item.text)
                    .lineLimit(3)
                    .strikethrough(item.state == .done)
                    .foregroundStyle(item.state == .done ? .secondary : .primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
        }
        .swipeActions {
            Button("Delete", systemImage: "trash", role: .destructive) {
                _ = store.remove(item)
            }
        }
        .sheet(isPresented: $showingDetail) {
            ImportantDetailSheet(item: item)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }
}

private struct ImportantDetailSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SomaStore.self) private var store
    let item: ImportantItem

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(item.text)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text("Added \(item.createdAt.formatted(date: .abbreviated, time: .shortened))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(20)
            }
            .navigationTitle("Important")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(item.state == .open ? "Complete" : "Reopen") {
                        withAnimation { _ = store.toggle(item) }
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private struct SettingsView: View {
    @Environment(SomaStore.self) private var store
    @State private var exporting = false
    @State private var confirmingReadableExport = false
    @State private var importing = false
    @State private var errorMessage: String?
    @State private var reminder = DailyReminder()
    @State private var showingDeveloper = false
    @Environment(HealthWorkouts.self) private var health

    var body: some View {
        @Bindable var health = health
        NavigationStack {
            List {
                Section {
                    Button("Export readable context", systemImage: "square.and.arrow.up") {
                        confirmingReadableExport = true
                    }
                    .disabled(store.storageIssue != nil)
                    Button("Import and merge", systemImage: "square.and.arrow.down") {
                        importing = true
                    }
                    .disabled(store.storageIssue != nil)
                    NavigationLink {
                        TrashView()
                    } label: {
                        Label("Trash", systemImage: "trash")
                    }
                } header: {
                    Text("Context")
                } footer: {
                    Text("The exported JSON contains readable note text. Keep it somewhere private. API keys, AI suggestions, settings, audio, and photos are never included.")
                }
                .listRowBackground(FrostedRowBackground())
                Section {
                    NavigationLink {
                        IntelligenceSettingsView()
                    } label: {
                        Label("Intelligence", systemImage: "apple.intelligence")
                    }
                    LabeledContent("Siri & Shortcuts", value: "2 actions")
                    Toggle("Daily reminder", isOn: $reminder.isEnabled)
                    if reminder.isEnabled {
                        DatePicker(
                            "Reminder time",
                            selection: $reminder.time,
                            displayedComponents: .hourAndMinute
                        )
                    }
                    if HealthWorkouts.isAvailable {
                        Toggle("Workouts from Health", isOn: $health.isEnabled)
                    }
                } header: {
                    Text("System")
                } footer: {
                    let notes = [
                        reminder.authorizationNote,
                        health.authorizationNote,
                        health.isEnabled
                            ? "Workouts appear as one quiet line in their day. Nothing is stored unless you keep it."
                            : nil,
                    ].compactMap(\.self)
                    if !notes.isEmpty {
                        Text(notes.joined(separator: " "))
                    }
                }
                .listRowBackground(FrostedRowBackground())
                Section {
                    LabeledContent("Storage", value: "On this iPhone")
                    LabeledContent("Storage status", value: store.storageStatus)
                    LabeledContent("Sync", value: "Manual context bundles")
                } footer: {
                    Text("Bundles merge by stable IDs and newest edit. This is the boundary a future encrypted device-to-device sync service will use.")
                }
                .listRowBackground(FrostedRowBackground())
                Section("About") {
                    // Triple-tap opens Developer — the same quiet door as on the Light Phone.
                    LabeledContent("Soma", value: "Native iOS preview")
                        .contentShape(.rect)
                        .onTapGesture(count: 3) { showingDeveloper = true }
                    LabeledContent("Context schema", value: "\(SomaContextBundle.currentSchemaVersion)")
                }
                .listRowBackground(FrostedRowBackground())
            }
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .background { SomaScreenBackground() }
            .navigationTitle("Settings")
            .navigationDestination(isPresented: $showingDeveloper) {
                DeveloperView()
            }
            .confirmationDialog(
                "Export readable context?",
                isPresented: $confirmingReadableExport,
                titleVisibility: .visible
            ) {
                Button("Export") { exporting = true }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Anyone with the exported file can read its note text and Important items.")
            }
            .fileExporter(
                isPresented: $exporting,
                document: SomaContextDocument(bundle: store.contextBundle()),
                contentType: .somaContext,
                defaultFilename: "Soma Context"
            ) { result in
                if case .failure(let error) = result { errorMessage = error.localizedDescription }
            }
            .fileImporter(
                isPresented: $importing,
                allowedContentTypes: [.somaContext, .json]
            ) { result in
                do {
                    let url = try result.get()
                    let accessing = url.startAccessingSecurityScopedResource()
                    defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                    let values = try url.resourceValues(
                        forKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey]
                    )
                    guard
                        values.isRegularFile == true,
                        values.isSymbolicLink != true,
                        (values.fileSize ?? 0) <= 16 * 1_024 * 1_024
                    else {
                        throw ContextError.invalidBundle
                    }
                    let data = try Data(contentsOf: url, options: [.mappedIfSafe])
                    guard data.count <= 16 * 1_024 * 1_024 else {
                        throw ContextError.invalidBundle
                    }
                    let decoder = JSONDecoder()
                    decoder.dateDecodingStrategy = .iso8601
                    try store.merge(decoder.decode(SomaContextBundle.self, from: data))
                } catch {
                    errorMessage = error.localizedDescription
                }
            }
            .alert("Couldn’t complete that", isPresented: .constant(errorMessage != nil)) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }
}

private struct IntelligenceSettingsView: View {
    @Environment(SomaIntelligence.self) private var intelligence
    @State private var editingProvider: CloudSpeechProvider?
    @State private var keyText = ""
    @State private var errorMessage: String?

    var body: some View {
        @Bindable var settings = intelligence.settings
        Form {
            Section {
                Toggle("Transcribe voice notes", isOn: $settings.voiceTranscriptionEnabled)
                Toggle("Suggest Important items", isOn: $settings.onDeviceSuggestionsEnabled)
                Toggle("Meal & workout suggestions", isOn: $settings.trackingSuggestionsEnabled)
                LabeledContent("Apple Speech", value: intelligence.speechModelStatus)
                LabeledContent("Foundation Models", value: intelligence.foundationModelStatus)
            } header: {
                Text("On Device")
            } footer: {
                Text("On-device processing keeps note text and audio on this iPhone.")
            }
            .listRowBackground(FrostedRowBackground())

            Section {
                Toggle("Cloud transcription", isOn: $settings.cloudTranscriptionEnabled)
                Toggle("Groq Important suggestions", isOn: $settings.cloudSuggestionsEnabled)
                Picker("Speech provider", selection: $settings.provider) {
                    ForEach(CloudSpeechProvider.allCases) { provider in
                        Text(provider.displayName).tag(provider)
                    }
                }
                if settings.provider == .groq {
                    Picker("Groq speech model", selection: $settings.groqModel) {
                        ForEach(GroqSpeechModel.allCases) { model in
                            Text(model.displayName).tag(model)
                        }
                    }
                }
                Toggle("Wi-Fi only", isOn: $settings.wifiOnly)
            } header: {
                Text("Optional Cloud BYOK")
            } footer: {
                Text("Cloud features are off by default. When enabled, the selected note text or recording is sent directly to your provider using your key.")
            }
            .listRowBackground(FrostedRowBackground())

            Section("Credentials") {
                credentialButton(.groq)
                credentialButton(.elevenLabs)
            }
            .listRowBackground(FrostedRowBackground())

            Section("Diagnostics") {
                if let error = settings.lastError {
                    LabeledContent(
                        "Last issue",
                        value: "\(error.reason.displayName) · \(error.occurredAt.formatted(date: .abbreviated, time: .shortened))"
                    )
                    Button("Clear diagnostic") { settings.clearLastError() }
                } else {
                    LabeledContent("Last issue", value: "None")
                }
            }
            .listRowBackground(FrostedRowBackground())
        }
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
        .background { SomaScreenBackground() }
        .navigationTitle("Intelligence")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editingProvider) { provider in
            NavigationStack {
                Form {
                    SecureField("\(provider.displayName) API key", text: $keyText)
                        .textContentType(.password)
                        .privacySensitive()
                    Text("Stored in the Keychain as device-only. Empty text removes the saved key.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .navigationTitle(provider.displayName)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") {
                            keyText = ""
                            editingProvider = nil
                        }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            do {
                                try settings.setKey(keyText, for: provider)
                                keyText = ""
                                editingProvider = nil
                            } catch {
                                errorMessage = error.localizedDescription
                            }
                        }
                    }
                }
            }
            .presentationDetents([.medium])
        }
        .alert("Couldn’t save the key", isPresented: .constant(errorMessage != nil)) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func credentialButton(_ provider: CloudSpeechProvider) -> some View {
        Button {
            keyText = ""
            editingProvider = provider
        } label: {
            LabeledContent(
                "\(provider.displayName) key",
                value: intelligence.settings.hasKey(for: provider) ? "Saved" : "Missing"
            )
        }
        .foregroundStyle(.primary)
    }
}

private struct DayPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SomaStore.self) private var store

    var body: some View {
        @Bindable var store = store
        NavigationStack {
            VStack(spacing: 0) {
                DatePicker(
                    "Day",
                    selection: $store.selectedDay,
                    displayedComponents: .date
                )
                .datePickerStyle(.graphical)
                .padding(.horizontal, 12)
                Spacer(minLength: 0)
            }
            .navigationTitle("Go to day")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Today") {
                        store.selectedDay = Date()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onChange(of: SomaDay.key(store.selectedDay)) {
                dismiss()
            }
        }
    }
}

private struct SearchView: View {
    @Environment(SomaStore.self) private var store
    @State private var query = ""
    let onOpenDay: () -> Void

    var body: some View {
        NavigationStack {
            List {
                if !matchingEntries.isEmpty {
                    Section("Notes") {
                        ForEach(matchingEntries) { entry in
                            Button {
                                openDay(for: entry)
                            } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(highlighted(entry.text))
                                        .lineLimit(3)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    Text(dayLabel(entry.day))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .buttonStyle(.plain)
                            .listRowBackground(Color.clear)
                        }
                    }
                }
                if !matchingImportant.isEmpty {
                    Section("Important") {
                        ForEach(matchingImportant) { item in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(highlighted(item.text))
                                    .lineLimit(3)
                                    .strikethrough(item.state == .done)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                Text(item.state == .done ? "Done" : "Open")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .listRowBackground(Color.clear)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .background { SomaScreenBackground() }
            .overlay {
                if trimmedQuery.isEmpty {
                    ContentUnavailableView(
                        "Search your bag",
                        systemImage: "magnifyingglass",
                        description: Text("Notes and Important items — with or without diacritics.")
                    )
                } else if matchingEntries.isEmpty && matchingImportant.isEmpty {
                    ContentUnavailableView.search(text: trimmedQuery)
                }
            }
            .navigationTitle("Search")
            .searchable(text: $query, prompt: "Search notes and Important")
        }
    }

    private var trimmedQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var matchingEntries: [SomaEntry] {
        guard !trimmedQuery.isEmpty else { return [] }
        return Array(
            store.entries
                .filter { !$0.isDeleted && matches($0.text) }
                .sorted { $0.createdAt > $1.createdAt }
                .prefix(60)
        )
    }

    private var matchingImportant: [ImportantItem] {
        guard !trimmedQuery.isEmpty else { return [] }
        return Array(
            store.important
                .filter { !$0.isDeleted && matches($0.text) }
                .sorted { $0.updatedAt > $1.updatedAt }
                .prefix(60)
        )
    }

    private func matches(_ text: String) -> Bool {
        text.range(
            of: trimmedQuery,
            options: [.caseInsensitive, .diacriticInsensitive]
        ) != nil
    }

    private func highlighted(_ text: String) -> AttributedString {
        var attributed = AttributedString(text)
        if
            let range = text.range(
                of: trimmedQuery,
                options: [.caseInsensitive, .diacriticInsensitive]
            ),
            let highlightRange = Range(range, in: attributed)
        {
            attributed[highlightRange].inlinePresentationIntent = .stronglyEmphasized
        }
        return attributed
    }

    private func dayLabel(_ key: String) -> String {
        guard let date = SomaDay.date(fromKey: key) else { return key }
        return date.formatted(date: .abbreviated, time: .omitted)
    }

    private func openDay(for entry: SomaEntry) {
        guard let date = SomaDay.date(fromKey: entry.day) else { return }
        store.selectedDay = date
        onOpenDay()
    }
}

// Trash rows never restore on a plain tap — a tap opens choices, mirroring the
// footgun fix the Android app already learned the hard way.
private struct TrashView: View {
    @Environment(SomaStore.self) private var store
    @State private var selectedEntry: SomaEntry?
    @State private var selectedItem: ImportantItem?
    @State private var confirmingEmpty = false

    var body: some View {
        List {
            if !store.trashedEntries.isEmpty {
                Section("Notes") {
                    ForEach(store.trashedEntries) { entry in
                        Button {
                            selectedEntry = entry
                        } label: {
                            trashRow(title: trashLabel(entry), deletedAt: entry.deletedAt)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .listRowBackground(FrostedRowBackground())
            }
            if !store.trashedImportant.isEmpty {
                Section("Important") {
                    ForEach(store.trashedImportant) { item in
                        Button {
                            selectedItem = item
                        } label: {
                            trashRow(title: item.text, deletedAt: item.deletedAt)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .listRowBackground(FrostedRowBackground())
            }
        }
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
        .background { SomaScreenBackground() }
        .overlay {
            if store.trashedEntries.isEmpty && store.trashedImportant.isEmpty {
                ContentUnavailableView(
                    "Trash is empty",
                    systemImage: "trash",
                    description: Text("Deleted notes and Important items wait here until you decide.")
                )
            }
        }
        .navigationTitle("Trash")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !(store.trashedEntries.isEmpty && store.trashedImportant.isEmpty) {
                Button("Empty Trash") { confirmingEmpty = true }
            }
        }
        .confirmationDialog(
            "Delete everything in the trash forever?",
            isPresented: $confirmingEmpty,
            titleVisibility: .visible
        ) {
            Button("Delete forever", role: .destructive) { store.purgeAllTrash() }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog(
            "Deleted note",
            isPresented: dialogBinding(for: $selectedEntry),
            titleVisibility: .visible,
            presenting: selectedEntry
        ) { entry in
            Button("Restore") { _ = store.restore(entry: entry) }
            Button("Delete forever", role: .destructive) { _ = store.purge(entry: entry) }
            Button("Cancel", role: .cancel) {}
        } message: { entry in
            Text(trashLabel(entry))
        }
        .confirmationDialog(
            "Deleted Important item",
            isPresented: dialogBinding(for: $selectedItem),
            titleVisibility: .visible,
            presenting: selectedItem
        ) { item in
            Button("Restore") { _ = store.restore(item: item) }
            Button("Delete forever", role: .destructive) { _ = store.purge(item: item) }
            Button("Cancel", role: .cancel) {}
        } message: { item in
            Text(item.text)
        }
    }

    private func dialogBinding<T>(for selection: Binding<T?>) -> Binding<Bool> {
        Binding(
            get: { selection.wrappedValue != nil },
            set: { if !$0 { selection.wrappedValue = nil } }
        )
    }

    private func trashLabel(_ entry: SomaEntry) -> String {
        if !entry.text.isEmpty { return entry.text }
        if entry.imageFileName != nil { return "Photo note" }
        if entry.audioFileName != nil { return "Voice note" }
        return "Empty note"
    }

    private func trashRow(title: String, deletedAt: Date?) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .lineLimit(2)
            if let deletedAt {
                Text("Deleted \(deletedAt.formatted(date: .abbreviated, time: .shortened))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct DeveloperView: View {
    @Environment(SomaStore.self) private var store
    @Environment(SomaIntelligence.self) private var intelligence
    @AppStorage("ios.dev.lightMode") private var lightMode = false

    var body: some View {
        List {
            Section {
                Toggle("Light mode", isOn: $lightMode)
            } footer: {
                Text("Soma is dark-first; this is the same hidden switch the Light Phone version keeps in Developer.")
            }
            .listRowBackground(FrostedRowBackground())
            Section("Store") {
                LabeledContent("Entries", value: "\(store.entries.count)")
                LabeledContent("Important", value: "\(store.important.count)")
                LabeledContent("Logs", value: "\(store.logs.count)")
                LabeledContent("Revisions", value: "\(store.revisions.count)")
                LabeledContent("Snapshot", value: "AES-256-GCM")
                LabeledContent("Storage status", value: store.storageStatus)
            }
            .listRowBackground(FrostedRowBackground())
            Section("Models") {
                LabeledContent("Apple Speech", value: intelligence.speechModelStatus)
                LabeledContent("Foundation Models", value: intelligence.foundationModelStatus)
            }
            .listRowBackground(FrostedRowBackground())
        }
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
        .background { SomaScreenBackground() }
        .navigationTitle("Developer")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct FrostedRowBackground: View {
    var body: some View {
        Rectangle().fill(.ultraThinMaterial)
    }
}

private struct LatestTabBehavior: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.tabBarMinimizeBehavior(.onScrollDown)
        } else {
            content
        }
    }
}

private struct NativeGlassComposer: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular.interactive(), in: .rect(cornerRadius: 25))
        } else {
            content.background(.regularMaterial, in: .rect(cornerRadius: 25))
        }
    }
}

private struct NativeGlassCircle: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular.interactive(), in: .circle)
        } else {
            content.background(.regularMaterial, in: .circle)
        }
    }
}

private struct NativeRecordControl: ViewModifier {
    let recording: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if recording {
            content.background(.red, in: .circle)
        } else if #available(iOS 26.0, *) {
            content.glassEffect(.regular.interactive(), in: .circle)
        } else {
            content
                .background(.primary, in: .circle)
                .foregroundStyle(Color(.systemBackground))
        }
    }
}

private extension TimeInterval {
    var formattedDuration: String {
        let seconds = Int(self)
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
