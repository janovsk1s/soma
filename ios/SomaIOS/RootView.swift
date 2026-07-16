import AVKit
import ImageIO
import PhotosUI
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
    @Environment(LanBrowserServer.self) private var browser
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @State private var showingCamera = false
    @State private var showingCalendar = false
    @State private var editingEntry: SomaEntry?
    @State private var recorder = AudioRecorder()
    @State private var errorMessage: String?
    @State private var workoutLines: [HealthWorkouts.Line] = []
    @State private var showJumpToLatest = false
    @State private var scrollToEndRequest = 0
    @State private var lastSeenTodayKey = SomaDay.key(Date())
    @State private var stagedPhoto: StagedPhoto?
    @State private var showingReflection = false
    @State private var recentlyDeleted: SomaEntry?
    @State private var undoDismissTask: Task<Void, Never>?
    @State private var showingLibrary = false
    @State private var pickedItem: PhotosPickerItem?
    @State private var savedTick = 0
    @State private var receiptLog: SomaLog?

    struct StagedPhoto {
        let photo: CapturedPhoto
        let thumbnail: UIImage?

        init(photo: CapturedPhoto) {
            self.photo = photo
            thumbnail = UIImage(data: photo.jpegData)?
                .preparingThumbnail(of: CGSize(width: 240, height: 240))
        }
    }

    private var isViewingLatestDay: Bool {
        SomaDay.key(store.selectedDay) >= SomaDay.key(Date())
    }

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
                        isViewingLatestDay ? "Nothing here yet" : "A quiet day",
                        systemImage: "circle",
                        description: Text(
                            isViewingLatestDay
                                ? "Drop a thought and return to your day."
                                : "Nothing was kept on this day."
                        )
                    )
                } else {
                    // ScrollViewReader drives the programmatic scrolls: ScrollPosition
                    // writes are silently ignored by List (verified — the day never
                    // moved), while proxy.scrollTo resolves ids reliably.
                    ScrollViewReader { proxy in
                    List {
                        ForEach(store.selectedEntries) { entry in
                            VStack(alignment: .leading, spacing: 8) {
                                EntryRow(
                                    entry: entry,
                                    onRetryTranscription: { intelligence.retryTranscription(for: entry) }
                                )
                                    .contentShape(.rect)
                                    .onTapGesture { editingEntry = entry }
                                    .contextMenu {
                                        if !entry.text.isEmpty {
                                            Button("Copy", systemImage: "doc.on.doc") {
                                                UIPasteboard.general.string = entry.text
                                            }
                                        }
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
                                            withAnimation { deleteWithUndo(entry) }
                                        }
                                    }
                                if let suggestion = store.pendingSuggestions(for: entry.id).first {
                                    SuggestionRow(suggestion: suggestion)
                                        .transition(.opacity.combined(with: .move(edge: .top)))
                                }
                                let tracking = store.pendingTrackingSuggestions(for: entry.id)
                                if !tracking.isEmpty {
                                    TrackingSuggestionsRow(suggestions: tracking)
                                        .transition(.opacity.combined(with: .move(edge: .top)))
                                }
                                if let photoText = store.pendingPhotoText(for: entry.id) {
                                    PhotoTextRow(suggestion: photoText)
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

                        // Stable scroll target only — visibility is tracked by
                        // geometry, so this row's laziness doesn't matter.
                        Color.clear
                            .frame(height: 1)
                            .id("dayEnd")
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .scrollIndicators(.hidden)
                    .scrollDismissesKeyboard(.interactively)
                    .onChange(of: scrollToEndRequest) {
                        withAnimation(.snappy) {
                            proxy.scrollTo("dayEnd", anchor: .bottom)
                        }
                    }
                    // Geometry, not a sentinel row: List is lazy, so an end-of-list
                    // marker is never realized on long days and its visibility
                    // callback never fires.
                    .onScrollGeometryChange(for: Bool.self) { geometry in
                        geometry.visibleRect.maxY < geometry.contentSize.height - 80
                    } action: { _, isFarFromEnd in
                        withAnimation(.snappy) { showJumpToLatest = isFarFromEnd }
                    }
                    // Messages-style return to "now" after scrolling back through the day.
                    .overlay(alignment: .bottomTrailing) {
                        if showJumpToLatest {
                            Button("Jump to latest", systemImage: "chevron.down") {
                                withAnimation { proxy.scrollTo("dayEnd", anchor: .bottom) }
                            }
                            .labelStyle(.iconOnly)
                            .font(.body)
                            .frame(width: 40, height: 40)
                            .buttonStyle(.plain)
                            .modifier(NativeGlassCircle())
                            .padding(.trailing, 16)
                            .padding(.bottom, 6)
                            .transition(.opacity.combined(with: .scale(scale: 0.8)))
                        }
                    }
                    }
                }
            }
            .background { SomaScreenBackground() }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // Bare chevrons — the default glass circles read as heavy ovals here.
                if #available(iOS 26.0, *) {
                    ToolbarItem(placement: .topBarLeading) { previousDayButton }
                        .sharedBackgroundVisibility(.hidden)
                    ToolbarItem(placement: .principal) { dayTitle }
                    ToolbarItem(placement: .topBarTrailing) { nextDayButton }
                        .sharedBackgroundVisibility(.hidden)
                } else {
                    ToolbarItem(placement: .topBarLeading) { previousDayButton }
                    ToolbarItem(placement: .principal) { dayTitle }
                    ToolbarItem(placement: .topBarTrailing) { nextDayButton }
                }
            }
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 8) {
                    // Android parity: a deletion can be taken back on the spot,
                    // not only fished out of the trash.
                    if let deleted = recentlyDeleted {
                        Button {
                            withAnimation {
                                _ = store.restore(entry: deleted)
                                recentlyDeleted = nil
                            }
                            undoDismissTask?.cancel()
                        } label: {
                            HStack(spacing: 8) {
                                Text("Deleted")
                                    .foregroundStyle(.secondary)
                                Text("Undo")
                                    .fontWeight(.medium)
                            }
                            .font(.callout)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                        }
                        .buttonStyle(.plain)
                        .modifier(NativeGlassComposer())
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }
                    CaptureBar(
                        recorder: recorder,
                        stagedThumbnail: stagedPhoto?.thumbnail,
                        onSave: { text in saveComposed(text: text) },
                        onDiscardPhoto: { withAnimation { stagedPhoto = nil } },
                        onCamera: { showingCamera = true },
                        onLibrary: { showingLibrary = true },
                        onRecord: toggleRecording
                    )
                }
                .animation(.snappy, value: recentlyDeleted == nil)
                .padding(.horizontal, 12)
                .padding(.bottom, 10)
                // Seats the bar visually: content scrolling through the gap between
                // composer and tab bar fades out instead of showing raw.
                .background {
                    LinearGradient(
                        colors: [
                            .clear,
                            (colorScheme == .dark ? Color.black : .white).opacity(0.45),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .padding(.top, -16)
                    .ignoresSafeArea()
                }
            }
            .task(id: "\(SomaDay.key(store.selectedDay))-\(health.isEnabled)") {
                workoutLines = await health.workoutLines(for: store.selectedDay)
            }
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active else { return }
                let todayKey = SomaDay.key(Date())
                if todayKey != lastSeenTodayKey {
                    // The date rolled while the app was away; follow it only if the
                    // user was sitting on the old today.
                    if SomaDay.key(store.selectedDay) == lastSeenTodayKey {
                        store.selectedDay = Date()
                    }
                    lastSeenTodayKey = todayKey
                }
            }
            #if DEBUG
            // Headless harnesses: the simulator cannot script taps, so launch
            // arguments drive a record-stop cycle, stage a photo, or open the
            // reflection sheet for end-to-end verification.
            .task {
                let arguments = ProcessInfo.processInfo.arguments
                if arguments.contains("-soma-stage-photo"),
                   let url = Bundle.main.url(forResource: "lv", withExtension: "webp"),
                   let data = try? Data(contentsOf: url),
                   let image = UIImage(data: data),
                   let jpeg = image.jpegData(compressionQuality: 0.9) {
                    stagedPhoto = StagedPhoto(photo: CapturedPhoto(jpegData: jpeg))
                }
                if arguments.contains("-soma-autoreflect") {
                    try? await Task.sleep(for: .seconds(1.5))
                    showingReflection = true
                }
                if arguments.contains("-soma-show-camera") {
                    try? await Task.sleep(for: .seconds(1))
                    showingCamera = true
                }
                if arguments.contains("-soma-show-calendar") {
                    try? await Task.sleep(for: .seconds(1))
                    showingCalendar = true
                }
                if arguments.contains("-soma-autotext") {
                    try? await Task.sleep(for: .seconds(2))
                    _ = saveComposed(text: "A saved note must be seen landing.")
                }
                if arguments.contains("-soma-edit-first") {
                    try? await Task.sleep(for: .seconds(1))
                    editingEntry = store.selectedEntries.first
                }
                if arguments.contains("-soma-start-lanserver") {
                    browser.start()
                }
                if
                    let argument = arguments
                        .first(where: { $0.hasPrefix("-soma-autorecord-seconds=") }),
                    let seconds = Double(argument.split(separator: "=").last ?? "")
                {
                    try? await Task.sleep(for: .seconds(1))
                    toggleRecording()
                    try? await Task.sleep(for: .seconds(seconds))
                    if recorder.isRecording {
                        toggleRecording()
                    }
                }
            }
            #endif
            .sheet(item: $editingEntry) { entry in
                EntryEditor(entry: entry)
            }
            .sheet(isPresented: $showingCalendar) {
                DayPickerSheet()
                    .presentationDetents([.medium])
                    .presentationDragIndicator(.visible)
            }
            // A bottom card like the ChatGPT camera — the day stays visible above,
            // and the photo lands in the composer as a staged thumbnail, where a
            // caption or a spoken comment can join it.
            .sheet(isPresented: $showingCamera) {
                OneShotCameraView { photo in
                    withAnimation { stagedPhoto = StagedPhoto(photo: photo) }
                }
                .presentationDetents([.fraction(0.62), .large])
                .presentationCornerRadius(32)
                .presentationDragIndicator(.hidden)
                .presentationBackground(.black)
            }
            .photosPicker(isPresented: $showingLibrary, selection: $pickedItem, matching: .images)
            .onChange(of: pickedItem) { _, item in
                guard let item else { return }
                Task {
                    // Re-encoding through UIImage strips EXIF/GPS from imports,
                    // the same privacy rule Paka applies to gallery photos.
                    if
                        let data = try? await item.loadTransferable(type: Data.self),
                        let image = UIImage(data: data),
                        let jpeg = image.jpegData(compressionQuality: 0.9)
                    {
                        withAnimation {
                            stagedPhoto = StagedPhoto(photo: CapturedPhoto(jpegData: jpeg))
                        }
                    }
                    pickedItem = nil
                }
            }
            .sheet(isPresented: $showingReflection) {
                ReflectionSheet(day: store.selectedDay)
                    .presentationDetents([.height(280), .medium])
                    .presentationDragIndicator(.visible)
            }
            .sheet(item: $receiptLog) { log in
                ReceiptSheet(log: log)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
            .alert("Soma", isPresented: .constant(errorMessage != nil)) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
            .sensoryFeedback(.impact(weight: .light), trigger: savedTick)
        }
    }

    private var previousDayButton: some View {
        Button("Previous day", systemImage: "chevron.left") {
            withAnimation { shiftDay(by: -1) }
        }
        .labelStyle(.iconOnly)
    }

    // Notes live in the present: the day rail stops at today (reminders are the
    // only thing allowed to point forward).
    private var nextDayButton: some View {
        Button("Next day", systemImage: "chevron.right") {
            withAnimation { shiftDay(by: 1) }
        }
        .labelStyle(.iconOnly)
        .disabled(isViewingLatestDay)
    }

    // Tap returns to today; holding opens the calendar.
    private var dayTitle: some View {
        Text(store.selectedDay.formatted(.dateTime.weekday(.wide).month().day()))
            .font(.headline)
            .foregroundStyle(.primary)
            .contentShape(.rect)
        .onTapGesture {
            withAnimation { store.selectedDay = Date() }
        }
        .onLongPressGesture {
            showingCalendar = true
        }
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel("Day")
        .accessibilityHint("Tap for today, hold for the calendar.")
    }

    // Ambient lines at the end of the day: workouts straight from Health (kept only
    // if the user says so) and accepted meal/workout logs. Silence when there are none.
    // Quiet lines share the entries' content column — same grid, softer voice.
    private static let quietLineInset: CGFloat = 64

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
                .padding(.leading, Self.quietLineInset)
                .foregroundStyle(.secondary)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            ForEach(store.logs(for: store.selectedDay)) { log in
                Button {
                    if log.detail != nil { receiptLog = log }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: log.kind.systemImage)
                            .font(.caption)
                        Text(log.title)
                            .font(.callout)
                        Spacer()
                    }
                    .padding(.leading, Self.quietLineInset)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .swipeActions {
                    Button("Delete", systemImage: "trash", role: .destructive) {
                        _ = store.remove(log: log)
                    }
                }
            }
            // A day with several receipts sums itself; one receipt already shows
            // its amount, so the line stays silent then.
            let dayReceiptCents = store.logs(for: store.selectedDay)
                .filter { $0.kind == .receipt }
                .compactMap(\.amountCents)
            if dayReceiptCents.count >= 2 {
                HStack(spacing: 10) {
                    Image(systemName: "receipt")
                        .font(.caption2)
                    Text("spent · \(ReceiptParse.decimalString(dayReceiptCents.reduce(0, +)))")
                        .font(.callout.monospacedDigit())
                    Spacer()
                }
                .padding(.leading, Self.quietLineInset)
                .foregroundStyle(.tertiary)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            // On request only, generated on-device, kept nowhere.
            if intelligence.canReflect,
               store.selectedEntries.contains(where: { !$0.text.isEmpty }) {
                Button {
                    showingReflection = true
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "moon.stars")
                            .font(.caption)
                        Text("reflect")
                            .font(.callout)
                        Spacer()
                    }
                    .padding(.leading, Self.quietLineInset)
                    .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .accessibilityHint("A quiet on-device reflection of this day. Nothing is saved.")
            }
        }
    }

    private var saveFailureMessage: String {
        "Couldn’t save this text. It may be too long, or protected storage may be unavailable."
    }

    private func shiftDay(by days: Int) {
        let shifted = Calendar.autoupdatingCurrent.date(
            byAdding: .day,
            value: days,
            to: store.selectedDay
        ) ?? store.selectedDay
        store.selectedDay = SomaDay.key(shifted) > SomaDay.key(Date()) ? Date() : shifted
    }

    // One save for whatever is composed: text alone, photo alone, or photo with
    // caption. A staged photo plus a recording becomes a single spoken-comment note.
    private func saveComposed(text: String) -> Bool {
        guard let staged = stagedPhoto else {
            guard let entry = withAnimation(.snappy, { store.addText(text) }) else {
                errorMessage = saveFailureMessage
                return false
            }
            noteSaved()
            Task { await intelligence.processNewEntry(entry) }
            return true
        }

        guard let fileName = writePhotoFile(staged.photo) else {
            errorMessage = saveFailureMessage
            return false
        }
        guard
            let entry = withAnimation(.snappy, { store.addPhoto(fileName: fileName, text: text) })
        else {
            removePhotoFile(fileName)
            errorMessage = saveFailureMessage
            return false
        }
        stagedPhoto = nil
        noteSaved()
        if entry.text.isEmpty {
            Task { await intelligence.extractPhotoText(for: entry) }
        } else {
            Task { await intelligence.processNewEntry(entry) }
        }
        return true
    }

    // A saved note must be seen landing: slide the day to its end and confirm
    // with a light tap. The scroll waits a beat so the List has laid out the
    // freshly inserted row before being asked to reveal it.
    private func noteSaved() {
        savedTick += 1
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(80))
            scrollToEndRequest += 1
        }
    }

    private func toggleRecording() {
        if recorder.isRecording {
            recorder.stop()
            return
        }
        Task {
            do {
                let directory = try store.audioDirectory()
                try await recorder.start(
                    in: directory,
                    contextualStrings: store.transcriptionVocabulary
                ) { fileName, duration in
                    let photoFileName = stagedPhoto.flatMap { writePhotoFile($0.photo) }
                    guard
                        let entry = withAnimation(.snappy, {
                            store.addVoice(
                                fileName: fileName,
                                duration: duration,
                                imageFileName: photoFileName
                            )
                        })
                    else {
                        try? FileManager.default.removeItem(
                            at: directory.appending(path: fileName)
                        )
                        if let photoFileName {
                            removePhotoFile(photoFileName)
                        }
                        errorMessage = store.storageStatus
                        return
                    }
                    if photoFileName != nil {
                        stagedPhoto = nil
                    }
                    noteSaved()
                    Task { await intelligence.transcribe(entry) }
                }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func deleteWithUndo(_ entry: SomaEntry) {
        guard store.remove(entry: entry) else { return }
        recentlyDeleted = entry
        undoDismissTask?.cancel()
        undoDismissTask = Task {
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            withAnimation { recentlyDeleted = nil }
        }
    }

    private func writePhotoFile(_ photo: CapturedPhoto) -> String? {
        guard let directory = try? store.imageDirectory() else { return nil }
        let fileName = "\(UUID().uuidString).jpg"
        let url = directory.appending(path: fileName)
        do {
            try photo.jpegData.write(to: url, options: [.atomic, .completeFileProtection])
            return fileName
        } catch {
            return nil
        }
    }

    private func removePhotoFile(_ fileName: String) {
        guard let directory = try? store.imageDirectory() else { return }
        try? FileManager.default.removeItem(at: directory.appending(path: fileName))
    }
}

// Capture never takes over the screen: typing expands the bar in place (keeping
// focus so several thoughts can be dropped in a row). Press-and-hold the bar to
// talk — release to stop, or slide right while holding to keep recording, the
// same reach-for-it feel as the Light Phone capture bar.
private struct CaptureBar: View {
    private enum HoldState {
        case idle
        case holding
        case locked
    }

    @Environment(\.verticalSizeClass) private var verticalSizeClass
    let recorder: AudioRecorder
    let stagedThumbnail: UIImage?
    let onSave: (String) -> Bool
    let onDiscardPhoto: () -> Void
    let onCamera: () -> Void
    let onLibrary: () -> Void
    let onRecord: () -> Void

    @State private var text = ""
    @State private var holdState: HoldState = .idle
    @AppStorage("ios.hint.holdToTalk") private var holdHintRetired = false
    @FocusState private var focused: Bool

    private var trimmed: String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        VStack(spacing: 5) {
        HStack(alignment: .bottom, spacing: 8) {
            VStack(alignment: .leading, spacing: 0) {
            // A captured photo stages here (ChatGPT-style): add a caption, record
            // a spoken comment onto it, or send it as it is.
            if let stagedThumbnail {
                ZStack(alignment: .topTrailing) {
                    Image(uiImage: stagedThumbnail)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 88, height: 88)
                        .clipShape(.rect(cornerRadius: 14))
                        .accessibilityLabel("Staged photo")
                    Button("Remove photo", systemImage: "xmark.circle.fill") {
                        onDiscardPhoto()
                    }
                    .labelStyle(.iconOnly)
                    .font(.body)
                    .foregroundStyle(.primary, .ultraThinMaterial)
                    .buttonStyle(.plain)
                    .offset(x: 8, y: -6)
                }
                .padding(.top, 14)
                .transition(.opacity.combined(with: .scale(scale: 0.9)))
            }
            HStack(alignment: .bottom, spacing: 6) {
                if recorder.isRecording {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 8) {
                            Circle()
                                .fill(.red)
                                .frame(width: 8, height: 8)
                            Text(recorder.elapsed.formattedDuration)
                                .monospacedDigit()
                            if holdState == .holding {
                                Text("slide right to keep")
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            } else if holdState == .locked {
                                Image(systemName: "lock.fill")
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        // Words appear as they are spoken (on-device); the head
                        // truncates so the newest words stay visible.
                        if !recorder.liveTranscript.isEmpty {
                            Text(recorder.liveTranscript)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                                .truncationMode(.head)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
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
                if !trimmed.isEmpty || stagedThumbnail != nil, !recorder.isRecording {
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
            .overlay {
                // The TextField swallows touches, so hold-to-talk lives on a clear
                // overlay that is only present while the composer is empty and idle.
                // It covers only the field row — the staged photo's X stays tappable.
                if !focused, trimmed.isEmpty {
                    Rectangle()
                        .fill(Color.clear)
                        .contentShape(.rect)
                        .onTapGesture {
                            if !recorder.isRecording { focused = true }
                        }
                        .gesture(holdToTalk)
                }
            }
            }
            .padding(.horizontal, 16)
            .modifier(NativeGlassComposer())
            .onChange(of: recorder.isRecording) { _, recording in
                if !recording { holdState = .idle }
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
                // Tap for the camera card; hold to pick from the library instead
                // (the LightOS long-press-for-photos family gesture).
                Image(systemName: "camera.fill")
                    .font(.body)
                    .frame(width: controlSize, height: controlSize)
                    .contentShape(.rect)
                    .onTapGesture(perform: onCamera)
                    .onLongPressGesture(perform: onLibrary)
                    .modifier(NativeGlassCircle())
                    .opacity(recorder.isRecording ? 0.4 : 1)
                    .allowsHitTesting(!recorder.isRecording)
                    .accessibilityLabel("Take a photo")
                    .accessibilityHint("Hold to choose from your photo library.")
                    .accessibilityAddTraits(.isButton)

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
                .accessibilityLabel(recorder.isRecording ? "Stop recording" : "Record a voice note")
            }
        }

        // The Light Phone's quiet, retiring hint: one dim line until the gesture
        // has been used once.
        if !holdHintRetired, !focused, !recorder.isRecording {
            Text("hold to talk · slide right to keep recording")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        }
        .animation(.snappy, value: focused)
        .animation(.snappy, value: recorder.isRecording)
        .animation(.snappy, value: trimmed.isEmpty)
        .animation(.snappy, value: stagedThumbnail == nil)
        .sensoryFeedback(.impact(weight: .light), trigger: holdState)
        // Compact bars cap their own type scaling (as Apple's do); accessibility
        // sizes otherwise push the field into the buttons.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }

    // Hold to record, release to stop and keep the note; slide right past the
    // threshold while holding to lock the recording open (stop ends it).
    private var holdToTalk: some Gesture {
        LongPressGesture(minimumDuration: 0.3)
            .sequenced(before: DragGesture(minimumDistance: 0))
            .onChanged { value in
                guard case .second(true, let drag) = value else { return }
                if holdState == .idle, !recorder.isRecording {
                    holdState = .holding
                    holdHintRetired = true
                    onRecord()
                }
                if holdState == .holding, let drag, drag.translation.width > 60 {
                    holdState = .locked
                }
            }
            .onEnded { value in
                guard case .second(true, _) = value else {
                    holdState = .idle
                    return
                }
                if holdState == .holding {
                    holdState = .idle
                    if recorder.isRecording { onRecord() }
                }
            }
    }

    private var controlSize: CGFloat {
        verticalSizeClass == .compact ? 44 : 50
    }
}

private struct EntryRow: View {
    @Environment(SomaStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let entry: SomaEntry
    var onRetryTranscription: (() -> Void)? = nil
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
                }
                if entry.kind == .voice, let fileName = entry.audioFileName {
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
                    .accessibilityLabel(isPlaying ? "Stop voice note" : "Play voice note")
                    .accessibilityValue(entry.text)
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
                } else if entry.imageFileName == nil || !entry.text.isEmpty {
                    Text(entry.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if
                    entry.kind == .voice,
                    entry.transcriptionState == .failed,
                    let onRetryTranscription
                {
                    Button("Try transcription again", systemImage: "arrow.clockwise") {
                        onRetryTranscription()
                    }
                    .font(.caption)
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
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
        .accessibilityLabel("Photo note")
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

private struct ReceiptSheet: View {
    @Environment(\.dismiss) private var dismiss
    let log: SomaLog

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(log.detail ?? log.title)
                    .font(.callout.monospacedDigit())
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
            }
            .scrollIndicators(.hidden)
            .background { SomaScreenBackground() }
            .navigationTitle(log.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private struct ReflectionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SomaIntelligence.self) private var intelligence
    let day: Date
    @State private var reflection: String?
    @State private var failed = false

    var body: some View {
        NavigationStack {
            Group {
                if let reflection {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            Text(reflection)
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Text("on this iPhone · nothing is saved")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                        .padding(20)
                    }
                    .scrollIndicators(.hidden)
                } else if failed {
                    ContentUnavailableView(
                        "No reflection right now",
                        systemImage: "moon.stars",
                        description: Text("Reflections need Apple Intelligence on this iPhone.")
                    )
                } else {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text("listening to the day…")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background { SomaScreenBackground() }
            .navigationTitle(day.formatted(date: .abbreviated, time: .omitted))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .task {
            do {
                reflection = try await intelligence.reflect(on: day)
            } catch {
                failed = true
            }
        }
    }
}

private struct PhotoTextRow: View {
    @Environment(SomaStore.self) private var store
    @Environment(SomaIntelligence.self) private var intelligence
    let suggestion: PhotoTextSuggestion

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "text.viewfinder")
                .foregroundStyle(.secondary)
            Button {
                withAnimation {
                    guard let updated = store.accept(suggestion) else { return }
                    Task { await intelligence.processNewEntry(updated) }
                }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Keep photo text?")
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
        .accessibilityHint("Tap to make the photo's text part of the note.")
        .contextMenu {
            Text("Recognized on this iPhone")
        }
    }
}

// One chip per entry no matter how many proposals — three stacked cards under a
// single note read as the AI taking over the day.
private struct TrackingSuggestionsRow: View {
    @Environment(SomaStore.self) private var store
    let suggestions: [TrackingSuggestion]

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Log?")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                ForEach(suggestions) { suggestion in
                    Button {
                        withAnimation { _ = store.accept(suggestion) }
                    } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Image(systemName: suggestion.kind.systemImage)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(suggestion.text)
                                .foregroundStyle(.primary)
                                .fixedSize(horizontal: false, vertical: true)
                                .lineLimit(2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            Button("Dismiss", systemImage: "xmark") {
                withAnimation {
                    for suggestion in suggestions {
                        _ = store.dismiss(suggestion)
                    }
                }
            }
            .labelStyle(.iconOnly)
            .buttonStyle(.borderless)
            .frame(minWidth: 44, minHeight: 44)
        }
        .padding(10)
        .background(.ultraThinMaterial, in: .rect(cornerRadius: 14))
        .accessibilityElement(children: .contain)
        .accessibilityHint("Tap a line to keep it as a quiet log.")
        .contextMenu {
            Text(suggestions.first?.engine.displayName ?? "")
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
                    .scrollContentBackground(.hidden)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                let tags = store.tags(for: entry.id)
                if !tags.isEmpty {
                    HStack(spacing: 8) {
                        ForEach(tags, id: \.self) { tag in
                            Text("#\(tag)")
                                .font(.caption)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(.ultraThinMaterial, in: .capsule)
                        }
                        Spacer()
                    }
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 12)
                }
            }
                .background { SomaScreenBackground() }
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
                    .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .background { SomaScreenBackground() }
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

private struct ImportantView: View {
    @Environment(SomaStore.self) private var store
    @Environment(\.colorScheme) private var colorScheme
    @State private var newItemText = ""
    @FocusState private var addFocused: Bool

    private var trimmedNewItem: String {
        newItemText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

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
            .scrollDismissesKeyboard(.interactively)
            // Adding lives at the bottom, in the same composer language as Today —
            // a top-corner "+" made the most common action the hardest reach.
            .safeAreaInset(edge: .bottom) {
                HStack(alignment: .bottom, spacing: 6) {
                    TextField("Add something important", text: $newItemText, axis: .vertical)
                        .lineLimit(1...4)
                        .focused($addFocused)
                        .frame(minHeight: 26)
                        .padding(.vertical, 12)
                    if !trimmedNewItem.isEmpty {
                        Button("Save", systemImage: "arrow.up.circle.fill") {
                            if store.addImportant(trimmedNewItem) {
                                newItemText = ""
                            }
                        }
                        .labelStyle(.iconOnly)
                        .font(.title2)
                        .padding(.bottom, 12)
                    }
                }
                .padding(.horizontal, 16)
                .modifier(NativeGlassComposer())
                .animation(.snappy, value: trimmedNewItem.isEmpty)
                .padding(.horizontal, 12)
                .padding(.bottom, 10)
                .background {
                    LinearGradient(
                        colors: [
                            .clear,
                            (colorScheme == .dark ? Color.black : .white).opacity(0.45),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .padding(.top, -16)
                    .ignoresSafeArea()
                }
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
            .scrollIndicators(.hidden)
            .background { SomaScreenBackground() }
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
    @Environment(LanBrowserServer.self) private var browser

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
                Section {
                    Toggle(
                        "Browser view",
                        isOn: Binding(
                            get: { browser.isRunning },
                            set: { _ in browser.toggle() }
                        )
                    )
                    if browser.isRunning {
                        if let address = browser.addressText {
                            LabeledContent("Address", value: address)
                        }
                        LabeledContent("Access code", value: browser.accessCode)
                    }
                } header: {
                    Text("Browser view")
                } footer: {
                    Text("Read-only, plain HTTP, for a trusted Wi-Fi only. It stops with the app.")
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
                Toggle("Automatic tags", isOn: $settings.autoTagsEnabled)
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
    @State private var showingMonthReceipts = false

    var body: some View {
        @Bindable var store = store
        NavigationStack {
            VStack(spacing: 0) {
                DatePicker(
                    "Day",
                    selection: $store.selectedDay,
                    in: ...Date(),
                    displayedComponents: .date
                )
                .datePickerStyle(.graphical)
                .padding(.horizontal, 12)

                // The month's receipts, summed — the quiet answer to "how much
                // did I spend". Silence when nothing was logged; tap for the list.
                let spent = store.spentCents(inMonthOf: store.selectedDay)
                if spent != 0 {
                    Button {
                        showingMonthReceipts = true
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "receipt")
                                .font(.caption)
                            Text("This month · \(ReceiptParse.decimalString(spent))")
                                .font(.callout.monospacedDigit())
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 24)
                        .padding(.top, 2)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
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
            .sheet(isPresented: $showingMonthReceipts) {
                MonthReceiptsSheet(month: store.selectedDay)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
            #if DEBUG
            .task {
                if ProcessInfo.processInfo.arguments.contains("-soma-show-month-receipts") {
                    // Presenting while the parent sheet is still animating collapses
                    // both; wait the animation out.
                    try? await Task.sleep(for: .seconds(2))
                    showingMonthReceipts = true
                }
            }
            #endif
        }
    }
}

private struct MonthReceiptsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SomaStore.self) private var store
    let month: Date
    @State private var detailLog: SomaLog?

    var body: some View {
        NavigationStack {
            List {
                ForEach(store.receipts(inMonthOf: month)) { log in
                    Button {
                        detailLog = log
                    } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(log.title)
                                if let day = SomaDay.date(fromKey: log.day) {
                                    Text(day.formatted(date: .abbreviated, time: .omitted))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            if let cents = log.amountCents {
                                Text(ReceiptParse.decimalString(cents))
                                    .font(.callout.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                }
                HStack {
                    Spacer()
                    Text("This month · \(ReceiptParse.decimalString(store.spentCents(inMonthOf: month)))")
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .background { SomaScreenBackground() }
            .navigationTitle(month.formatted(.dateTime.month(.wide).year()))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .sheet(item: $detailLog) { log in
                ReceiptSheet(log: log)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
    }
}

private struct SearchView: View {
    @Environment(SomaStore.self) private var store
    @State private var query = ""
    @State private var detailItem: ImportantItem?
    @State private var detailLog: SomaLog?
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
                if !matchingLogs.isEmpty {
                    Section("Logs") {
                        ForEach(matchingLogs) { log in
                            Button {
                                if log.detail != nil {
                                    detailLog = log
                                } else if let day = SomaDay.date(fromKey: log.day) {
                                    store.selectedDay = day
                                    onOpenDay()
                                }
                            } label: {
                                HStack(alignment: .firstTextBaseline, spacing: 8) {
                                    Image(systemName: log.kind.systemImage)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(highlighted(log.title))
                                        Text(dayLabel(log.day))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
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
                            Button {
                                detailItem = item
                            } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(highlighted(item.text))
                                        .lineLimit(3)
                                        .strikethrough(item.state == .done)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    Text(item.state == .done ? "Done" : "Open")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .buttonStyle(.plain)
                            .listRowBackground(Color.clear)
                        }
                    }
                }
            }
            .sheet(item: $detailItem) { item in
                ImportantDetailSheet(item: item)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
            .sheet(item: $detailLog) { log in
                ReceiptSheet(log: log)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
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
                } else if matchingEntries.isEmpty, matchingImportant.isEmpty, matchingLogs.isEmpty {
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
                .filter { entry in
                    guard !entry.isDeleted else { return false }
                    return matches(entry.text)
                        || store.tags(for: entry.id).contains(where: matches)
                }
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

    private var matchingLogs: [SomaLog] {
        guard !trimmedQuery.isEmpty else { return [] }
        return Array(
            store.logs
                .filter { matches($0.title) }
                .sorted { $0.day > $1.day }
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
