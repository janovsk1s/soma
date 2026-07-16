import Foundation
import Observation
import UserNotifications

@MainActor
@Observable
final class DailyReminder {
    var isEnabled: Bool {
        didSet {
            defaults.set(isEnabled, forKey: Keys.enabled)
            Task { await reschedule() }
        }
    }
    var time: Date {
        didSet {
            let components = Calendar.autoupdatingCurrent.dateComponents(
                [.hour, .minute],
                from: time
            )
            defaults.set(components.hour ?? 9, forKey: Keys.hour)
            defaults.set(components.minute ?? 0, forKey: Keys.minute)
            Task { await reschedule() }
        }
    }
    private(set) var authorizationNote: String?

    private let defaults: UserDefaults
    private static let identifier = "soma.daily.reminder"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        isEnabled = defaults.bool(forKey: Keys.enabled)
        let hour = defaults.object(forKey: Keys.hour) as? Int ?? 9
        let minute = defaults.object(forKey: Keys.minute) as? Int ?? 0
        time = Calendar.autoupdatingCurrent.date(
            bySettingHour: hour,
            minute: minute,
            second: 0,
            of: Date()
        ) ?? Date()
    }

    private func reschedule() async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [Self.identifier])
        // Turning the toggle off must not clear authorizationNote here: a denial
        // sets isEnabled = false, whose didSet re-enters this method, and the note
        // would erase itself before the user ever saw it.
        guard isEnabled else { return }
        authorizationNote = nil

        let granted = (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        guard granted else {
            authorizationNote = "Notifications are off for Soma in iOS Settings."
            isEnabled = false
            return
        }

        let content = UNMutableNotificationContent()
        content.title = "Soma"
        content.body = "A quiet moment for today’s note."
        content.sound = nil

        var components = Calendar.autoupdatingCurrent.dateComponents(
            [.hour, .minute],
            from: time
        )
        components.second = 0
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: true)
        let request = UNNotificationRequest(
            identifier: Self.identifier,
            content: content,
            trigger: trigger
        )
        try? await center.add(request)
    }

    private enum Keys {
        static let enabled = "ios.reminder.enabled"
        static let hour = "ios.reminder.hour"
        static let minute = "ios.reminder.minute"
    }
}
