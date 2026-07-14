package com.soma.core.model

import java.time.Instant

/** A user-confirmed structured record derived from, or captured beside, a daily note. */
enum class LogKind {
    MEAL,
    RECIPE,
    WORKOUT,
}

/**
 * How Soma obtained a nutrition value. The UI must always show this distinction so an
 * approximation can never be mistaken for a package label or an official food average.
 */
enum class NutritionBasis {
    PACKAGE_LABEL,
    OFFICIAL_AVERAGE,
    ESTIMATED,
    UNQUANTIFIED,
}

enum class NutritionSource {
    USER,
    OPEN_FOOD_FACTS,
    FINELI,
    CIQUAL,
    AI_ESTIMATE,
}

enum class FoodQuantityUnit {
    GRAM,
    MILLILITRE,
    PIECE,
    SERVING,
}

data class NutritionEstimate(
    val basis: NutritionBasis,
    val source: NutritionSource,
    val energyKcal: Double? = null,
    val energyKcalMin: Double? = null,
    val energyKcalMax: Double? = null,
    val proteinGrams: Double? = null,
    val carbohydrateGrams: Double? = null,
    val fatGrams: Double? = null,
    /** Human-readable product, dataset, or model reference; never an undisclosed authority. */
    val reference: String? = null,
) {
    init {
        requireNonNegativeFinite(energyKcal, "Energy")
        requireNonNegativeFinite(energyKcalMin, "Minimum energy")
        requireNonNegativeFinite(energyKcalMax, "Maximum energy")
        requireNonNegativeFinite(proteinGrams, "Protein")
        requireNonNegativeFinite(carbohydrateGrams, "Carbohydrate")
        requireNonNegativeFinite(fatGrams, "Fat")
        require(energyKcalMin == null || energyKcalMax == null || energyKcalMin <= energyKcalMax) {
            "Minimum energy cannot exceed maximum energy"
        }
        require(reference == null || reference.length <= MAX_REFERENCE_LENGTH) {
            "Nutrition reference is too long"
        }
        if (basis == NutritionBasis.UNQUANTIFIED) {
            require(
                energyKcal == null && energyKcalMin == null && energyKcalMax == null &&
                    proteinGrams == null && carbohydrateGrams == null && fatGrams == null,
            ) { "Unquantified food cannot contain calculated nutrition" }
        }
        if (basis != NutritionBasis.ESTIMATED) {
            require(energyKcalMin == null && energyKcalMax == null) {
                "Only estimates may contain an energy range"
            }
        }
    }

    val energyKilojoules: Double?
        get() = energyKcal?.times(KILOJOULES_PER_KILOCALORIE)

    companion object {
        const val MAX_REFERENCE_LENGTH = 500
        private const val KILOJOULES_PER_KILOCALORIE = 4.184

        private fun requireNonNegativeFinite(value: Double?, label: String) {
            require(value == null || (value.isFinite() && value >= 0.0)) {
                "$label must be finite and non-negative"
            }
        }
    }
}

data class FoodItem(
    val name: String,
    val quantity: Double? = null,
    val unit: FoodQuantityUnit? = null,
    /** Normalized quantity used for local calculations when known. */
    val gramWeight: Double? = null,
    val nutrition: NutritionEstimate? = null,
) {
    init {
        require(name.isNotBlank()) { "Food name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) { "Food name is too long" }
        require(quantity == null || (quantity.isFinite() && quantity > 0.0)) {
            "Food quantity must be finite and positive"
        }
        require((quantity == null) == (unit == null)) {
            "Food quantity and unit must be provided together"
        }
        require(gramWeight == null || (gramWeight.isFinite() && gramWeight > 0.0)) {
            "Food gram weight must be finite and positive"
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 300
    }
}

data class WorkoutSet(
    val repetitions: Int? = null,
    val weightKilograms: Double? = null,
    val durationSeconds: Int? = null,
) {
    init {
        require(repetitions == null || repetitions in 1..MAX_REPETITIONS) {
            "Repetitions must be between 1 and $MAX_REPETITIONS"
        }
        require(
            weightKilograms == null ||
                (weightKilograms.isFinite() && weightKilograms in 0.0..MAX_WEIGHT_KILOGRAMS),
        ) { "Weight must be finite and between 0 and $MAX_WEIGHT_KILOGRAMS kg" }
        require(durationSeconds == null || durationSeconds in 1..MAX_DURATION_SECONDS) {
            "Duration must be between 1 and $MAX_DURATION_SECONDS seconds"
        }
        require(repetitions != null || weightKilograms != null || durationSeconds != null) {
            "A workout set needs repetitions, weight, or duration"
        }
    }

    companion object {
        const val MAX_REPETITIONS = 10_000
        const val MAX_DURATION_SECONDS = 86_400
        const val MAX_WEIGHT_KILOGRAMS = 2_000.0
    }
}

data class WorkoutExercise(
    val name: String,
    val machine: String? = null,
    val sets: List<WorkoutSet> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "Exercise name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) { "Exercise name is too long" }
        require(machine == null || machine.length <= MAX_MACHINE_LENGTH) { "Machine name is too long" }
        require(sets.size <= MAX_SETS) { "Exercise contains too many sets" }
    }

    companion object {
        const val MAX_NAME_LENGTH = 300
        const val MAX_MACHINE_LENGTH = 300
        const val MAX_SETS = 100
    }
}

/**
 * Structured data is additive. [source] points back to the untouched entry; editing this record
 * creates a [LogRevision] and never rewrites the note, photo, recording, or transcript.
 */
data class LogRecord(
    val id: String,
    val kind: LogKind,
    val title: String,
    val note: String = "",
    val occurredAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val source: EntrySource? = null,
    val foods: List<FoodItem> = emptyList(),
    val exercises: List<WorkoutExercise> = emptyList(),
    val revision: Long = 0,
    val archivedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "Log id must not be blank" }
        require(title.isNotBlank()) { "Log title must not be blank" }
        require(title.length <= MAX_TITLE_LENGTH) { "Log title is too long" }
        require(note.length <= MAX_NOTE_LENGTH) { "Log note is too long" }
        require(!occurredAt.isAfter(updatedAt)) { "Log occurrence cannot be after its update" }
        require(!createdAt.isAfter(updatedAt)) { "Log creation cannot be after its update" }
        require(revision >= 0) { "Log revision must not be negative" }
        require(archivedAt == null || !archivedAt.isBefore(createdAt)) {
            "Log archive cannot precede creation"
        }
        require(foods.size <= MAX_FOOD_ITEMS) { "Log contains too many food items" }
        require(exercises.size <= MAX_EXERCISES) { "Log contains too many exercises" }
        when (kind) {
            LogKind.MEAL, LogKind.RECIPE -> require(exercises.isEmpty()) {
                "Food logs cannot contain exercises"
            }
            LogKind.WORKOUT -> require(foods.isEmpty()) { "Workout logs cannot contain foods" }
        }
    }

    fun revise(
        title: String = this.title,
        note: String = this.note,
        foods: List<FoodItem> = this.foods,
        exercises: List<WorkoutExercise> = this.exercises,
        at: Instant,
    ): LogRecord = copy(
        title = title.trim(),
        note = note.trim(),
        foods = foods,
        exercises = exercises,
        updatedAt = at,
        revision = revision + 1,
    )

    fun archive(at: Instant): LogRecord = copy(
        archivedAt = at,
        updatedAt = at,
        revision = revision + 1,
    )

    companion object {
        const val MAX_TITLE_LENGTH = 500
        const val MAX_NOTE_LENGTH = 20_000
        const val MAX_FOOD_ITEMS = 200
        const val MAX_EXERCISES = 100
    }
}

data class LogRevision(
    val logId: String,
    /** Revision number of [snapshot], before the edit represented by [editedAt]. */
    val revision: Long,
    val snapshot: LogRecord,
    val editedAt: Instant,
) {
    init {
        require(logId.isNotBlank()) { "Revision log id must not be blank" }
        require(logId == snapshot.id) { "Revision snapshot belongs to another log" }
        require(revision == snapshot.revision) { "Revision number does not match its snapshot" }
        require(!editedAt.isBefore(snapshot.updatedAt)) { "Edit cannot precede the stored revision" }
    }
}
