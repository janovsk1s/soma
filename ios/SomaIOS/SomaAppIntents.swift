import AppIntents
import Foundation

struct AddThoughtIntent: AppIntent {
    static let title: LocalizedStringResource = "Add Thought"
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication
    @available(iOS 26.0, *)
    static let supportedModes: IntentModes = .background
    static let description = IntentDescription(
        "Adds a thought to today's Soma note without opening the app.",
        categoryName: "Capture",
        searchKeywords: ["note", "thought", "capture", "journal"]
    )

    @Parameter(
        title: "Thought",
        description: "The text to place in today's note.",
        inputConnectionBehavior: .connectToPreviousIntentResult
    )
    var thought: String

    static var parameterSummary: some ParameterSummary {
        Summary("Add \(\.$thought) to Soma")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let saved = await MainActor.run {
            let store = SomaStore.shared
            store.selectedDay = Date()
            return store.addText(thought) != nil
        }
        return .result(dialog: saved ? "Added to Soma." : "There was nothing to add.")
    }
}

struct AddImportantIntent: AppIntent {
    static let title: LocalizedStringResource = "Add Important Item"
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication
    @available(iOS 26.0, *)
    static let supportedModes: IntentModes = .background
    static let description = IntentDescription(
        "Adds an item that still needs your attention in Soma.",
        categoryName: "Capture",
        searchKeywords: ["important", "task", "remember", "todo"]
    )

    @Parameter(
        title: "Item",
        description: "The item to keep in Important.",
        inputConnectionBehavior: .connectToPreviousIntentResult
    )
    var item: String

    static var parameterSummary: some ParameterSummary {
        Summary("Keep \(\.$item) in Important")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let saved = await MainActor.run {
            let cleaned = item.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !cleaned.isEmpty else { return false }
            return SomaStore.shared.addImportant(cleaned)
        }
        return .result(dialog: saved ? "Added to Important." : "There was nothing to add.")
    }
}

struct SomaShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AddThoughtIntent(),
            phrases: [
                "Add a thought to \(.applicationName)",
                "Capture in \(.applicationName)",
                "Write in \(.applicationName)",
            ],
            shortTitle: "Add Thought",
            systemImageName: "square.and.pencil"
        )
        AppShortcut(
            intent: AddImportantIntent(),
            phrases: [
                "Add an important item to \(.applicationName)",
                "Remember this in \(.applicationName)",
            ],
            shortTitle: "Add Important",
            systemImageName: "checkmark.circle"
        )
    }

    static var shortcutTileColor: ShortcutTileColor { .grayBrown }
}
