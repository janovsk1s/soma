import AppIntents
import SwiftUI

@MainActor
@main
struct SomaIOSApp: App {
    @State private var store: SomaStore
    @State private var intelligence: SomaIntelligence

    init() {
        let store = SomaStore.shared
        _store = State(initialValue: store)
        _intelligence = State(initialValue: SomaIntelligence(store: store))
        SomaShortcuts.updateAppShortcutParameters()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(intelligence)
                .preferredColorScheme(.dark)
                .task { await intelligence.resumePendingWork() }
        }
    }
}
