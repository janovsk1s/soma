import Foundation
import HealthKit
import Observation

/// Read-only ambient workouts from Apple Health: one quiet line per workout on the
/// day it happened, silence when there are none, and nothing is stored unless the
/// user chooses to keep a line as a log. Queries run only when the viewed day
/// changes, so the battery cost is a single sample query per day view.
@MainActor
@Observable
final class HealthWorkouts {
    struct Line: Identifiable, Hashable, Sendable {
        var id: String { title }
        var title: String
    }

    var isEnabled: Bool {
        didSet {
            defaults.set(isEnabled, forKey: Keys.enabled)
            if isEnabled {
                Task { await requestAuthorization() }
            }
        }
    }
    private(set) var authorizationNote: String?

    private let healthStore = HKHealthStore()
    private let defaults: UserDefaults

    static var isAvailable: Bool {
        HKHealthStore.isHealthDataAvailable()
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        isEnabled = defaults.bool(forKey: Keys.enabled)
    }

    private func requestAuthorization() async {
        guard Self.isAvailable else {
            isEnabled = false
            return
        }
        do {
            try await healthStore.requestAuthorization(
                toShare: [],
                read: [HKObjectType.workoutType()]
            )
            authorizationNote = nil
        } catch {
            authorizationNote = "Health access could not be requested."
            isEnabled = false
        }
    }

    func workoutLines(for day: Date) async -> [Line] {
        guard isEnabled, Self.isAvailable else { return [] }
        let calendar = Calendar.autoupdatingCurrent
        let start = calendar.startOfDay(for: day)
        guard let end = calendar.date(byAdding: .day, value: 1, to: start) else { return [] }
        let predicate = HKQuery.predicateForSamples(
            withStart: start,
            end: end,
            options: .strictStartDate
        )

        let samples: [HKSample] = await withCheckedContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: .workoutType(),
                predicate: predicate,
                limit: 8,
                sortDescriptors: [
                    NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
                ]
            ) { _, results, _ in
                continuation.resume(returning: results ?? [])
            }
            healthStore.execute(query)
        }

        return samples.compactMap { sample in
            guard let workout = sample as? HKWorkout else { return nil }
            let minutes = max(1, Int(workout.duration / 60))
            return Line(title: "\(Self.name(for: workout.workoutActivityType)) · \(minutes) min · from Health")
        }
    }

    private static func name(for activity: HKWorkoutActivityType) -> String {
        switch activity {
        case .running: "run"
        case .walking: "walk"
        case .hiking: "hike"
        case .cycling: "ride"
        case .swimming: "swim"
        case .yoga: "yoga"
        case .traditionalStrengthTraining, .functionalStrengthTraining: "strength"
        case .highIntensityIntervalTraining: "intervals"
        case .rowing: "rowing"
        case .elliptical: "elliptical"
        case .pilates: "pilates"
        case .dance, .socialDance: "dance"
        case .martialArts, .boxing, .kickboxing: "martial arts"
        case .climbing: "climb"
        case .skatingSports: "skating"
        case .snowSports, .downhillSkiing, .crossCountrySkiing, .snowboarding: "snow"
        case .paddleSports: "paddle"
        default: "workout"
        }
    }

    private enum Keys {
        static let enabled = "ios.health.workouts"
    }
}
