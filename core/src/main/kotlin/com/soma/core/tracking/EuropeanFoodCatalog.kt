package com.soma.core.tracking

import com.soma.core.model.FoodItem
import com.soma.core.model.FoodQuantityUnit
import com.soma.core.model.NutritionBasis
import com.soma.core.model.NutritionEstimate
import com.soma.core.model.NutritionSource
import java.text.Normalizer
import java.util.Locale

data class EuropeanFoodReference(
    val id: String,
    val source: NutritionSource,
    val names: List<String>,
    val energyKcalPer100Grams: Double?,
    val proteinPer100Grams: Double?,
    val carbohydratePer100Grams: Double?,
    val fatPer100Grams: Double?,
    val pieceGrams: Double? = null,
    val servingGrams: Double? = null,
) {
    init {
        require(id.isNotBlank()) { "Food reference id must not be blank" }
        require(
            source in setOf(
                NutritionSource.FINELI,
                NutritionSource.CIQUAL,
                NutritionSource.OPEN_FOOD_FACTS,
            ),
        ) {
            "Food references must come from a supported European source"
        }
        require(names.isNotEmpty() && names.all(String::isNotBlank)) { "Food reference needs a name" }
        listOf(
            energyKcalPer100Grams,
            proteinPer100Grams,
            carbohydratePer100Grams,
            fatPer100Grams,
            pieceGrams,
            servingGrams,
        ).forEach { value ->
            require(value == null || (value.isFinite() && value >= 0.0)) {
                "Food reference values must be finite and non-negative"
            }
        }
    }

    val displayName: String get() = names.first()
    val sourceLabel: String
        get() = when (source) {
            NutritionSource.FINELI -> "Fineli / THL"
            NutritionSource.CIQUAL -> "Ciqual 2025 / Anses"
            NutritionSource.OPEN_FOOD_FACTS -> "Open Food Facts / community"
            else -> error("Unsupported food source")
        }
}

class EuropeanFoodCatalog(
    references: List<EuropeanFoodReference>,
) {
    private val indexed = references.map { reference ->
        IndexedFood(reference, reference.names.map(::normalizeSearchText))
    }

    /** Predictable token search; no network, embeddings, or hidden personalization. */
    fun search(query: String, limit: Int = DEFAULT_RESULT_LIMIT): List<EuropeanFoodReference> {
        require(limit in 1..MAX_RESULT_LIMIT) { "Invalid food result limit" }
        val normalized = normalizeSearchText(query)
        if (normalized.length < MIN_QUERY_LENGTH) return emptyList()
        val queryTokens = normalized.split(' ').filter(String::isNotBlank)
        return indexed.asSequence()
            .mapNotNull { food ->
                food.aliases.maxOfOrNull { alias -> score(alias, normalized, queryTokens) }
                    ?.takeIf { it > 0 }
                    ?.let { score -> food.reference to score }
            }
            .sortedWith(
                compareByDescending<Pair<EuropeanFoodReference, Int>> { it.second }
                    .thenBy { it.first.displayName.length }
                    .thenBy { it.first.displayName.lowercase(Locale.ROOT) }
                    .thenBy { it.first.id },
            )
            .take(limit)
            .map(Pair<EuropeanFoodReference, Int>::first)
            .toList()
    }

    /**
     * Attaches the selected official average. Nutrition is calculated only when Soma knows a
     * gram mass; otherwise the match is retained as explicitly unquantified.
     */
    fun apply(reference: EuropeanFoodReference, item: FoodItem): FoodItem {
        val gramWeight = item.gramWeight ?: when (item.unit) {
            FoodQuantityUnit.PIECE -> reference.pieceGrams?.times(item.quantity ?: 0.0)
            FoodQuantityUnit.SERVING -> reference.servingGrams?.times(item.quantity ?: 0.0)
            FoodQuantityUnit.GRAM -> item.quantity
            FoodQuantityUnit.MILLILITRE, null -> null
        }
        val sourceReference = "${reference.sourceLabel} · ${reference.id}"
        if (gramWeight == null || gramWeight <= 0.0) {
            return item.copy(
                nutrition = NutritionEstimate(
                    basis = NutritionBasis.UNQUANTIFIED,
                    source = reference.source,
                    reference = sourceReference,
                ),
            )
        }
        val scale = gramWeight / GRAMS_PER_REFERENCE_AMOUNT
        return item.copy(
            gramWeight = gramWeight,
            nutrition = NutritionEstimate(
                basis = if (reference.source == NutritionSource.OPEN_FOOD_FACTS) {
                    NutritionBasis.PACKAGE_LABEL
                } else {
                    NutritionBasis.OFFICIAL_AVERAGE
                },
                source = reference.source,
                energyKcal = reference.energyKcalPer100Grams?.times(scale),
                proteinGrams = reference.proteinPer100Grams?.times(scale),
                carbohydrateGrams = reference.carbohydratePer100Grams?.times(scale),
                fatGrams = reference.fatPer100Grams?.times(scale),
                reference = sourceReference,
            ),
        )
    }

    private fun score(alias: String, query: String, tokens: List<String>): Int = when {
        alias == query -> SCORE_EXACT
        alias.startsWith("$query ") -> SCORE_PREFIX - alias.length
        alias.contains(" $query ") || alias.endsWith(" $query") -> SCORE_PHRASE - alias.length
        tokens.all { token -> alias.split(' ').any { word -> word == token || word.startsWith(token) } } ->
            SCORE_ALL_TOKENS - alias.length
        else -> 0
    }

    private data class IndexedFood(
        val reference: EuropeanFoodReference,
        val aliases: List<String>,
    )

    companion object {
        const val DEFAULT_RESULT_LIMIT = 25
        const val MAX_RESULT_LIMIT = 100
        private const val MIN_QUERY_LENGTH = 2
        private const val GRAMS_PER_REFERENCE_AMOUNT = 100.0
        private const val SCORE_EXACT = 10_000
        private const val SCORE_PREFIX = 8_000
        private const val SCORE_PHRASE = 6_000
        private const val SCORE_ALL_TOKENS = 4_000
    }
}

internal fun normalizeSearchText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")
