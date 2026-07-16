import AppIntents
import SwiftUI

@MainActor
@main
struct SomaIOSApp: App {
    @State private var store: SomaStore
    @State private var intelligence: SomaIntelligence
    @State private var health = HealthWorkouts()
    @State private var browser: LanBrowserServer
    @AppStorage("ios.dev.lightMode") private var developerLightMode = false

    init() {
        let store = SomaStore.shared
        _store = State(initialValue: store)
        _intelligence = State(initialValue: SomaIntelligence(store: store))
        _browser = State(initialValue: LanBrowserServer(store: store))
        SomaShortcuts.updateAppShortcutParameters()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(intelligence)
                .environment(health)
                .environment(browser)
                .preferredColorScheme(developerLightMode ? .light : .dark)
                .task { await intelligence.resumePendingWork() }
        }
    }
}
