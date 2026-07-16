import AVFoundation
import Foundation
import Observation
import Speech

/// Records voice notes through AVAudioEngine so the same microphone buffers can
/// feed a live on-device transcription preview while the encoded file is written.
/// The file remains the source of truth: the preview is cosmetic, and the saved
/// note is still transcribed from the file by the ordinary pipeline afterwards.
@MainActor
@Observable
final class AudioRecorder {
    private(set) var isRecording = false
    private(set) var elapsed: TimeInterval = 0
    private(set) var liveTranscript = ""

    private let engine = AVAudioEngine()
    private var tapBox: RecorderTapBox?
    private var fileURL: URL?
    private var startedAt: Date?
    private var timer: Timer?
    private var completion: ((String, TimeInterval) -> Void)?
    private var liveRecognitionTask: SFSpeechRecognitionTask?
    // Held for the recorder's lifetime; the recorder itself lives as long as the
    // day view, so the observer is never removed (and captures self weakly).
    private var interruptionObserver: NSObjectProtocol?

    init() {
        // A phone call or Siri taking the session kills the engine silently; stop
        // gracefully so the words spoken so far become a note instead of nothing.
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            let rawType = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            guard rawType == AVAudioSession.InterruptionType.began.rawValue else { return }
            MainActor.assumeIsolated {
                self?.stop()
            }
        }
    }

    func start(
        in directory: URL,
        contextualStrings: [String] = [],
        completion: @escaping (String, TimeInterval) -> Void
    ) async throws {
        guard !isRecording, tapBox == nil else { throw RecordingError.couldNotStart }
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else { throw RecordingError.permissionDenied }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker])
        try session.setActive(true)

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw RecordingError.couldNotStart
        }

        let fileName = "\(UUID().uuidString).m4a"
        let url = directory.appending(path: fileName)
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: Int(format.channelCount),
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        let file: AVAudioFile
        do {
            file = try AVAudioFile(forWriting: url, settings: settings)
        } catch {
            try? FileManager.default.removeItem(at: url)
            throw RecordingError.couldNotStart
        }
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.complete],
            ofItemAtPath: url.path(percentEncoded: false)
        )

        let box = RecorderTapBox(file: file)
        attachLivePreview(to: box, contextualStrings: contextualStrings)

        // @Sendable keeps the closure nonisolated: formed inside a MainActor
        // method it would otherwise inherit main-actor isolation, and Core Audio
        // invoking it on the audio thread traps under strict concurrency.
        input.installTap(onBus: 0, bufferSize: 4_096, format: format) { @Sendable buffer, _ in
            box.consume(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            box.finishLiveAudio()
            try? FileManager.default.removeItem(at: url)
            throw RecordingError.couldNotStart
        }

        tapBox = box
        fileURL = url
        self.completion = completion
        startedAt = Date()
        elapsed = 0
        liveTranscript = ""
        isRecording = true
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let startedAt = self.startedAt else { return }
                self.elapsed = Date().timeIntervalSince(startedAt)
            }
        }
    }

    func stop() {
        endRecording(keeping: true)
    }

    func cancel() {
        endRecording(keeping: false)
    }

    private func endRecording(keeping: Bool) {
        guard isRecording, let box = tapBox, let url = fileURL else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        box.finishLiveAudio()
        liveRecognitionTask?.cancel()
        liveRecognitionTask = nil

        let duration = startedAt.map { Date().timeIntervalSince($0) } ?? 0
        let callback = completion
        completion = nil
        tapBox = nil
        fileURL = nil
        finish()

        if keeping, duration > 0.2 {
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.complete],
                ofItemAtPath: url.path(percentEncoded: false)
            )
            callback?(url.lastPathComponent, duration)
        } else {
            try? FileManager.default.removeItem(at: url)
        }
    }

    // The preview only attaches when speech recognition was already authorized by
    // the transcription pipeline — recording never adds a permission dialog — and
    // it requires the on-device recognizer, so no audio leaves the phone.
    private func attachLivePreview(to box: RecorderTapBox, contextualStrings: [String]) {
        guard
            SFSpeechRecognizer.authorizationStatus() == .authorized,
            let recognizer = SFSpeechRecognizer(locale: .current),
            recognizer.isAvailable,
            recognizer.supportsOnDeviceRecognition
        else {
            return
        }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        request.contextualStrings = contextualStrings
        box.liveRequest = request
        liveRecognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, _ in
            guard let text = result?.bestTranscription.formattedString else { return }
            Task { @MainActor in
                guard let self, self.isRecording else { return }
                self.liveTranscript = text
            }
        }
    }

    private func finish() {
        timer?.invalidate()
        timer = nil
        startedAt = nil
        isRecording = false
        liveTranscript = ""
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// Owns everything the audio tap touches. The tap runs on the audio thread, so
/// nothing here may reach back into the main-actor recorder.
private final class RecorderTapBox: @unchecked Sendable {
    private let file: AVAudioFile
    var liveRequest: SFSpeechAudioBufferRecognitionRequest?

    init(file: AVAudioFile) {
        self.file = file
    }

    func consume(_ buffer: AVAudioPCMBuffer) {
        try? file.write(from: buffer)
        liveRequest?.append(buffer)
    }

    func finishLiveAudio() {
        liveRequest?.endAudio()
        liveRequest = nil
    }
}

enum RecordingError: LocalizedError {
    case permissionDenied
    case couldNotStart

    var errorDescription: String? {
        switch self {
        case .permissionDenied: "Microphone access is needed to record a voice note."
        case .couldNotStart: "The recording could not be started."
        }
    }
}
