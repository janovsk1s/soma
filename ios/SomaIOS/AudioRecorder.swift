import AVFoundation
import Foundation
import Observation

@MainActor
@Observable
final class AudioRecorder: NSObject, @preconcurrency AVAudioRecorderDelegate {
    private(set) var isRecording = false
    private(set) var elapsed: TimeInterval = 0

    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private var startedAt: Date?
    private var completion: ((String, TimeInterval) -> Void)?

    func start(in directory: URL, completion: @escaping (String, TimeInterval) -> Void) async throws {
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else { throw RecordingError.permissionDenied }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker])
        try session.setActive(true)

        let fileName = "\(UUID().uuidString).m4a"
        let url = directory.appending(path: fileName)
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder.delegate = self
        recorder.isMeteringEnabled = true
        do {
            try FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.complete],
                ofItemAtPath: url.path()
            )
        } catch {
            try? FileManager.default.removeItem(at: url)
            throw error
        }
        guard recorder.record() else {
            try? FileManager.default.removeItem(at: url)
            throw RecordingError.couldNotStart
        }

        self.recorder = recorder
        self.completion = completion
        startedAt = Date()
        elapsed = 0
        isRecording = true
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let startedAt = self.startedAt else { return }
                self.elapsed = Date().timeIntervalSince(startedAt)
            }
        }
    }

    func stop() {
        guard let recorder, isRecording else { return }
        recorder.stop()
        finalize(recorder, successfully: true)
    }

    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        finalize(recorder, successfully: flag)
    }

    func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        finalize(recorder, successfully: false)
    }

    private func finalize(_ recorder: AVAudioRecorder, successfully: Bool) {
        guard isRecording, self.recorder === recorder else { return }
        let duration = recorder.currentTime
        let url = recorder.url
        let callback = completion
        completion = nil
        finish()
        if successfully, duration > 0.2 {
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.complete],
                ofItemAtPath: url.path()
            )
            callback?(url.lastPathComponent, duration)
        } else {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func finish() {
        timer?.invalidate()
        timer = nil
        recorder = nil
        startedAt = nil
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
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
