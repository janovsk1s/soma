package com.soma.core.tracking

import com.soma.core.model.FoodItem
import com.soma.core.model.FoodQuantityUnit
import com.soma.core.model.LogKind
import com.soma.core.model.NutritionBasis
import com.soma.core.model.NutritionEstimate
import com.soma.core.model.NutritionSource
import com.soma.core.model.ReceiptDetails
import com.soma.core.model.ReceiptItem
import com.soma.core.model.ReceiptMoney
import com.soma.core.model.WorkoutExercise
import com.soma.core.model.WorkoutSet

data class QuickLogDraft(
    val title: String,
    val note: String,
    val foods: List<FoodItem> = emptyList(),
    val exercises: List<WorkoutExercise> = emptyList(),
    val receipt: ReceiptDetails? = null,
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
            LogKind.RECEIPT -> parseReceipt(clean)
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

    /**
     * Parses the stable editable format used by Soma's receipt proposal as well as ordinary
     * OCR-like lines. Anything uncertain remains in [QuickLogDraft.note]; amounts are only
     * structured when an explicit decimal value is present.
     */
    private fun parseReceipt(clean: String): QuickLogDraft {
        val lines = clean.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val currency = declaredCurrency(lines) ?: detectCurrency(clean)
        var merchant: String? = null
        var subtotal: ReceiptMoney? = null
        var tax: ReceiptMoney? = null
        var total: ReceiptMoney? = null
        val items = mutableListOf<ReceiptItem>()

        lines.forEach { line ->
            val field = RECEIPT_FIELD.matchEntire(line)
            val key = field?.groups?.get("key")?.value?.lowercase()
            val value = field?.groups?.get("value")?.value?.trim().orEmpty()
            when {
                key in MERCHANT_KEYS -> merchant = value.takeIf(String::isNotBlank)
                key in CURRENCY_KEYS -> Unit
                key in SUBTOTAL_KEYS -> subtotal = parseMoney(value, currency)
                key in TAX_KEYS -> tax = parseMoney(value, currency)
                key in TOTAL_KEYS -> total = parseMoney(value, currency)
                key in ITEM_KEYS -> parseReceiptItem(value, currency)?.let(items::add)
                field == null && total == null && TOTAL_LINE.containsMatchIn(line) -> {
                    total = parseMoney(line, currency)
                }
                field == null -> parseLooseReceiptItem(line, currency)?.let(items::add)
            }
        }

        if (merchant == null) {
            merchant = lines.firstOrNull { line ->
                val key = RECEIPT_FIELD.matchEntire(line)?.groups?.get("key")?.value?.lowercase()
                key == null && !TOTAL_LINE.containsMatchIn(line) && parseLooseReceiptItem(line, currency) == null
            }
        }
        val title = merchant ?: items.firstOrNull()?.name ?: "Receipt"
        return QuickLogDraft(
            title = title,
            note = clean,
            receipt = ReceiptDetails(
                merchant = merchant,
                currencyCode = currency,
                subtotal = subtotal,
                tax = tax,
                total = total,
                // Two identical printed lines are still two purchases. Never collapse receipt
                // evidence merely because the OCR or user wording happens to match.
                items = items,
            ),
        )
    }

    private fun parseReceiptItem(value: String, currency: String): ReceiptItem? {
        val parts = value.split('|').map(String::trim)
        val name = parts.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
        val quantity = parts.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        val total = parts.getOrNull(2)?.let { parseMoney(it, currency) }
        val category = parts.getOrNull(3)?.takeIf(String::isNotBlank)
        return ReceiptItem(name = name, quantity = quantity, lineTotal = total, category = category)
    }

    private fun parseLooseReceiptItem(line: String, currency: String): ReceiptItem? {
        if (TOTAL_LINE.containsMatchIn(line)) return null
        val match = LOOSE_RECEIPT_ITEM.matchEntire(line) ?: return null
        val name = match.groups["name"]?.value?.trim()?.trimEnd('-', '—', ':')
            ?.takeIf(String::isNotBlank) ?: return null
        val amount = parseMoney(match.groups["amount"]?.value.orEmpty(), currency) ?: return null
        return ReceiptItem(name = name, lineTotal = amount)
    }

    fun parseReceiptMoney(text: String, currencyCode: String): ReceiptMoney? {
        val currency = currencyCode.trim().uppercase().takeIf(Regex("[A-Z]{3}")::matches) ?: return null
        val match = MONEY.findAll(text).lastOrNull() ?: return null
        val compact = match.value
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace("'", "")
            .replace("’", "")
            .trimEnd('.', ',')
        val lastDot = compact.lastIndexOf('.')
        val lastComma = compact.lastIndexOf(',')
        val decimalIndex = maxOf(lastDot, lastComma).takeIf { index ->
            index >= 0 && compact.length - index - 1 in 1..2
        }
        val normalized = buildString(compact.length) {
            compact.forEachIndexed { index, character ->
                when {
                    character.isDigit() -> append(character)
                    index == decimalIndex -> append('.')
                }
            }
        }
        val amount = normalized.toBigDecimalOrNull() ?: return null
        if (amount.signum() < 0 || amount.scale() > 2) return null
        return runCatching { ReceiptMoney(amount.movePointRight(2).longValueExact(), currency) }.getOrNull()
    }

    private fun parseMoney(text: String, currency: String): ReceiptMoney? =
        parseReceiptMoney(text, currency)

    private fun declaredCurrency(lines: List<String>): String? = lines.firstNotNullOfOrNull { line ->
        val field = RECEIPT_FIELD.matchEntire(line) ?: return@firstNotNullOfOrNull null
        val key = field.groups["key"]?.value?.lowercase() ?: return@firstNotNullOfOrNull null
        if (key !in CURRENCY_KEYS) return@firstNotNullOfOrNull null
        field.groups["value"]?.value?.trim()?.uppercase()?.takeIf(Regex("[A-Z]{3}")::matches)
    }

    private fun detectCurrency(text: String): String = when {
        Regex("(?i)\\bSEK\\b|kr\\b").containsMatchIn(text) -> "SEK"
        Regex("(?i)\\bNOK\\b").containsMatchIn(text) -> "NOK"
        Regex("(?i)\\bDKK\\b").containsMatchIn(text) -> "DKK"
        Regex("(?i)\\bPLN\\b|zł").containsMatchIn(text) -> "PLN"
        Regex("(?i)\\bCZK\\b|Kč").containsMatchIn(text) -> "CZK"
        Regex("(?i)\\bHUF\\b|Ft\\b").containsMatchIn(text) -> "HUF"
        Regex("(?i)\\bRON\\b").containsMatchIn(text) -> "RON"
        Regex("(?i)\\bBGN\\b").containsMatchIn(text) -> "BGN"
        Regex("(?i)\\bCHF\\b").containsMatchIn(text) -> "CHF"
        Regex("(?i)\\bISK\\b").containsMatchIn(text) -> "ISK"
        Regex("(?i)\\bGBP\\b|£").containsMatchIn(text) -> "GBP"
        Regex("(?i)\\bUSD\\b|\\$").containsMatchIn(text) -> "USD"
        else -> "EUR"
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
    private val RECEIPT_FIELD = Regex("^(?<key>[\\p{L}_ ]{2,30})\\s*:\\s*(?<value>.*)$")
    private val MONEY = Regex("(?<!\\d)\\d[\\d\\s.,'’]*(?!\\d)")
    private val LOOSE_RECEIPT_ITEM = Regex(
        "^(?<name>.+?)[ \\t]+(?<amount>\\d{1,9}(?:[.,]\\d{1,2}))(?:\\s*(?:€|EUR|SEK|kr))?$",
        RegexOption.IGNORE_CASE,
    )
    private val TOTAL_LINE = Regex(
        "(?i)\\b(total|summa|sum|kokku|suma|yhteensa|yhteensä|totalt|gesamt|celkom|kopa|kopā)\\b",
    )
    private val MERCHANT_KEYS = setOf("merchant", "store", "shop", "veikals", "parduotuve", "parduotuvė", "kauppa", "butik", "geschaft", "geschäft", "obchod")
    private val CURRENCY_KEYS = setOf("currency", "valuta", "valūta", "valuuta", "valiuta", "wahrung", "währung", "mena")
    private val SUBTOTAL_KEYS = setOf("subtotal", "starpsumma", "netto", "zwischenbetrag", "medzisucet")
    private val TAX_KEYS = setOf("tax", "vat", "pvn", "kaibemaks", "alv", "moms", "mwst", "dph")
    private val TOTAL_KEYS = setOf("total", "summa", "sum", "kokku", "suma", "yhteensa", "yhteensä", "totalt", "gesamt", "celkom", "kopa", "kopā")
    private val ITEM_KEYS = setOf("item", "prece", "toode", "preke", "tuote", "vara", "artikel", "polozka")
}
