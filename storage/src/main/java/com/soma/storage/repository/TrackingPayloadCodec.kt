package com.soma.storage.repository

import com.soma.core.model.EntrySource
import com.soma.core.model.FoodItem
import com.soma.core.model.FoodQuantityUnit
import com.soma.core.model.LogKind
import com.soma.core.model.LogRecord
import com.soma.core.model.NutritionBasis
import com.soma.core.model.NutritionEstimate
import com.soma.core.model.NutritionSource
import com.soma.core.model.WorkoutExercise
import com.soma.core.model.WorkoutSet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/** Versioned deterministic representation encrypted as one opaque tracking payload. */
internal object TrackingPayloadCodec {
    private const val FORMAT_VERSION = 1
    private const val MAX_COLLECTION_SIZE = 500

    fun encode(log: LogRecord): String {
        val bytes = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeUTF(log.id)
                output.writeUTF(log.kind.name)
                output.writeUTF(log.title)
                output.writeUTF(log.note)
                output.writeInstant(log.occurredAt)
                output.writeInstant(log.createdAt)
                output.writeInstant(log.updatedAt)
                output.writeNullable(log.source) { source ->
                    output.writeLong(source.noteDate.toEpochDay())
                    output.writeUTF(source.entryId)
                }
                output.writeInt(log.foods.size)
                log.foods.forEach { output.writeFood(it) }
                output.writeInt(log.exercises.size)
                log.exercises.forEach { output.writeExercise(it) }
                output.writeLong(log.revision)
                output.writeNullable(log.archivedAt) { output.writeInstant(it) }
            }
            bytes.toByteArray()
        }
        return try {
            Base64.getEncoder().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun decode(encoded: String): LogRecord {
        val bytes = Base64.getDecoder().decode(encoded)
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == FORMAT_VERSION) { "Unsupported tracking payload version" }
                val log = LogRecord(
                    id = input.readUTF(),
                    kind = enumValueOf(input.readUTF()),
                    title = input.readUTF(),
                    note = input.readUTF(),
                    occurredAt = input.readInstant(),
                    createdAt = input.readInstant(),
                    updatedAt = input.readInstant(),
                    source = input.readNullable {
                        EntrySource(
                            noteDate = LocalDate.ofEpochDay(input.readLong()),
                            entryId = input.readUTF(),
                        )
                    },
                    foods = input.readBoundedList { input.readFood() },
                    exercises = input.readBoundedList { input.readExercise() },
                    revision = input.readLong(),
                    archivedAt = input.readNullable { input.readInstant() },
                )
                require(input.available() == 0) { "Trailing bytes in tracking payload" }
                log
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataOutputStream.writeFood(food: FoodItem) {
        writeUTF(food.name)
        writeNullable(food.quantity) { writeDouble(it) }
        writeNullable(food.unit) { writeUTF(it.name) }
        writeNullable(food.gramWeight) { writeDouble(it) }
        writeNullable(food.nutrition) { writeNutrition(it) }
    }

    private fun DataInputStream.readFood(): FoodItem = FoodItem(
        name = readUTF(),
        quantity = readNullable { readDouble() },
        unit = readNullable { enumValueOf<FoodQuantityUnit>(readUTF()) },
        gramWeight = readNullable { readDouble() },
        nutrition = readNullable { readNutrition() },
    )

    private fun DataOutputStream.writeNutrition(nutrition: NutritionEstimate) {
        writeUTF(nutrition.basis.name)
        writeUTF(nutrition.source.name)
        writeNullable(nutrition.energyKcal) { writeDouble(it) }
        writeNullable(nutrition.energyKcalMin) { writeDouble(it) }
        writeNullable(nutrition.energyKcalMax) { writeDouble(it) }
        writeNullable(nutrition.proteinGrams) { writeDouble(it) }
        writeNullable(nutrition.carbohydrateGrams) { writeDouble(it) }
        writeNullable(nutrition.fatGrams) { writeDouble(it) }
        writeNullable(nutrition.reference) { writeUTF(it) }
    }

    private fun DataInputStream.readNutrition(): NutritionEstimate = NutritionEstimate(
        basis = enumValueOf<NutritionBasis>(readUTF()),
        source = enumValueOf<NutritionSource>(readUTF()),
        energyKcal = readNullable { readDouble() },
        energyKcalMin = readNullable { readDouble() },
        energyKcalMax = readNullable { readDouble() },
        proteinGrams = readNullable { readDouble() },
        carbohydrateGrams = readNullable { readDouble() },
        fatGrams = readNullable { readDouble() },
        reference = readNullable { readUTF() },
    )

    private fun DataOutputStream.writeExercise(exercise: WorkoutExercise) {
        writeUTF(exercise.name)
        writeNullable(exercise.machine) { writeUTF(it) }
        writeInt(exercise.sets.size)
        exercise.sets.forEach { set ->
            writeNullable(set.repetitions) { writeInt(it) }
            writeNullable(set.weightKilograms) { writeDouble(it) }
            writeNullable(set.durationSeconds) { writeInt(it) }
        }
    }

    private fun DataInputStream.readExercise(): WorkoutExercise = WorkoutExercise(
        name = readUTF(),
        machine = readNullable { readUTF() },
        sets = readBoundedList {
            WorkoutSet(
                repetitions = readNullable { readInt() },
                weightKilograms = readNullable { readDouble() },
                durationSeconds = readNullable { readInt() },
            )
        },
    )

    private inline fun <T> DataOutputStream.writeNullable(value: T?, writer: (T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) writer(value)
    }

    private inline fun <T> DataInputStream.readNullable(reader: () -> T): T? =
        if (readBoolean()) reader() else null

    private inline fun <T> DataInputStream.readBoundedList(reader: () -> T): List<T> {
        val size = readInt()
        require(size in 0..MAX_COLLECTION_SIZE) { "Invalid tracking collection size" }
        return List(size) { reader() }
    }

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.readInstant(): Instant {
        val seconds = readLong()
        val nanos = readInt()
        require(nanos in 0..999_999_999) { "Invalid tracking timestamp" }
        return Instant.ofEpochSecond(seconds, nanos.toLong())
    }
}
