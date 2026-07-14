package com.soma.core.tracking

import com.soma.core.model.FoodItem
import com.soma.core.model.FoodQuantityUnit
import com.soma.core.model.LogKind
import com.soma.core.model.NutritionBasis
import com.soma.core.model.NutritionEstimate
import com.soma.core.model.NutritionSource
import com.soma.core.model.WorkoutExercise
import com.soma.core.model.WorkoutSet

data class QuickLogDraft(
    val title: String,
    val note: String,
    val foods: List<FoodItem> = emptyList(),
    val exercises: List<WorkoutExercise> = emptyList(),
)

/**
 * Small, transparent capture parser. It recognizes quantities and the familiar `3 × 10 80 kg`
 * workout shorthand without calling AI. Unknown wording is kept verbatim and remains editable.
 */
object QuickLogParser {
    fun parse(kind: LogKind, input: String): QuickLogDraft {
        val clean = input.trim()
        require(clean.isNotEmpty()) { "A tracking log needs text" }
        return when (kind) {
            LogKind.MEAL, LogKind.RECIPE -> parseFood(clean)
            LogKind.WORKOUT -> parseWorkout(clean)
        }
    }

    private fun parseFood(clean: String): QuickLogDraft {
        val lines = clean.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val foods = lines.map(::parseFoodLine)
        return QuickLogDraft(
            title = foods.first().name,
            note = clean,
            foods = foods,
        )
    }

    private fun parseFoodLine(line: String): FoodItem {
        val match = FOOD_QUANTITY.matchEntire(line)
        val unitText = match?.groups?.get("unit")?.value?.lowercase()
        val amount = match?.groups?.get("amount")?.value?.replace(',', '.')?.toDoubleOrNull()
        val name = match?.groups?.get("name")?.value?.trim()?.trimEnd(',', ';', '—', '-') ?: line
        val (quantity, unit, gramWeight) = when (unitText) {
            "g", "gr", "gram", "grams", "grammi", "grami" ->
                Triple(amount, FoodQuantityUnit.GRAM, amount)
            "kg", "kgs" -> Triple(amount?.times(GRAMS_PER_KILOGRAM), FoodQuantityUnit.GRAM, amount?.times(GRAMS_PER_KILOGRAM))
            "ml" -> Triple(amount, FoodQuantityUnit.MILLILITRE, null)
            "l", "litre", "liter" -> Triple(amount?.times(MILLILITRES_PER_LITRE), FoodQuantityUnit.MILLILITRE, null)
            "pc", "pcs", "piece", "pieces", "gab", "gabali", "stück", "stk", "kpl", "vnt" ->
                Triple(amount, FoodQuantityUnit.PIECE, null)
            "serving", "servings", "portion", "portions", "porcija", "porcijas", "annos" ->
                Triple(amount, FoodQuantityUnit.SERVING, null)
            else -> Triple(null, null, null)
        }
        return FoodItem(
            name = name,
            quantity = quantity,
            unit = unit,
            gramWeight = gramWeight,
            nutrition = NutritionEstimate(
                basis = NutritionBasis.UNQUANTIFIED,
                source = NutritionSource.USER,
                reference = "user entry",
            ),
        )
    }

    private fun parseWorkout(clean: String): QuickLogDraft {
        val lines = clean.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val exercises = lines.map(::parseExerciseLine)
        return QuickLogDraft(
            title = exercises.first().name,
            note = clean,
            exercises = exercises,
        )
    }

    private fun parseExerciseLine(line: String): WorkoutExercise {
        val match = WORKOUT_SHORTHAND.matchEntire(line)
            ?: return WorkoutExercise(name = line)
        val name = match.groups["name"]?.value?.trim()?.trimEnd(',', ';', '—', '-')
            ?.takeIf(String::isNotEmpty)
            ?: line
        val setCount = match.groups["sets"]?.value?.toIntOrNull() ?: return WorkoutExercise(line)
        val repetitions = match.groups["reps"]?.value?.toIntOrNull() ?: return WorkoutExercise(line)
        val weight = match.groups["weight"]?.value?.replace(',', '.')?.toDoubleOrNull()
        return WorkoutExercise(
            name = name,
            sets = List(setCount.coerceAtMost(WorkoutExercise.MAX_SETS)) {
                WorkoutSet(repetitions = repetitions, weightKilograms = weight)
            },
        )
    }

    private const val GRAMS_PER_KILOGRAM = 1_000.0
    private const val MILLILITRES_PER_LITRE = 1_000.0
    private val FOOD_QUANTITY = Regex(
        "^(?<name>.+?)\\s+(?<amount>\\d+(?:[.,]\\d+)?)\\s*" +
            "(?<unit>kg|kgs|g|gr|gram|grams|grammi|grami|ml|l|litre|liter|pc|pcs|piece|pieces|" +
            "gab|gabali|stück|stk|kpl|vnt|serving|servings|portion|portions|porcija|porcijas|annos)$",
        RegexOption.IGNORE_CASE,
    )
    private val WORKOUT_SHORTHAND = Regex(
        "^(?<name>.+?)\\s*[—,:;-]?\\s+(?<sets>\\d{1,3})\\s*[x×]\\s*(?<reps>\\d{1,5})" +
            "(?:\\s*(?:@|x)?\\s*(?<weight>\\d+(?:[.,]\\d+)?)\\s*kg)?$",
        RegexOption.IGNORE_CASE,
    )
}
