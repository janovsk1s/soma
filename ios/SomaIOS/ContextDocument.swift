import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let somaContext = UTType(exportedAs: "com.soma.context", conformingTo: .json)
}

struct SomaContextDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.somaContext, .json] }

    var bundle: SomaContextBundle

    init(bundle: SomaContextBundle) {
        self.bundle = bundle
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        bundle = try decoder.decode(SomaContextBundle.self, from: data)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return FileWrapper(regularFileWithContents: try encoder.encode(bundle))
    }
}
