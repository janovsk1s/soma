import Foundation
import Vision

/// On-device text recognition for photo notes. Runs once per photo at capture,
/// entirely locally — the groundwork for receipts without any cloud.
enum PhotoTextReader {
    static let maximumCharacters = 2_000

    static func recognizeText(at url: URL) -> String? {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        if #available(iOS 16.0, macOS 13.0, *) {
            request.automaticallyDetectsLanguage = true
        }
        let handler = VNImageRequestHandler(url: url)
        guard (try? handler.perform([request])) != nil else { return nil }
        let lines = (request.results ?? []).compactMap {
            $0.topCandidates(1).first?.string
        }
        let text = lines
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        return String(text.prefix(maximumCharacters))
    }
}
