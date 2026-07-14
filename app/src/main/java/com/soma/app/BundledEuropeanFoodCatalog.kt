package com.soma.app

import android.content.Context
import com.soma.core.model.NutritionSource
import com.soma.core.model.SupportedLanguage
import com.soma.core.tracking.EuropeanFoodCatalog
import com.soma.core.tracking.EuropeanFoodReference
import java.util.zip.GZIPInputStream

/** Public reference data only. User food records never enter this cache or leave the device. */
class BundledEuropeanFoodCatalog(
    private val context: Context,
) {
    private val cached = mutableMapOf<SupportedLanguage, EuropeanFoodCatalog>()

    fun catalog(language: SupportedLanguage): EuropeanFoodCatalog {
        return synchronized(this) {
            cached.getOrPut(language) { EuropeanFoodCatalog(load(language)) }
        }
    }

    private fun load(language: SupportedLanguage): List<EuropeanFoodReference> =
        context.assets.open(ASSET_NAME).use { asset ->
            GZIPInputStream(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filterNot { it.startsWith('#') || it.startsWith("source\t") }
                    .map(::parseLine)
                    .map { reference -> reference.copy(names = preferredNames(reference.names, language)) }
                    .take(MAX_FOODS + 1)
                    .toList()
                    .also { require(it.size <= MAX_FOODS) { "Bundled food catalog is unexpectedly large" } }
            }
        }

    private fun parseLine(line: String): EuropeanFoodReference {
        val fields = line.split('\t')
        require(fields.size == FIELD_COUNT) { "Invalid bundled food row" }
        return EuropeanFoodReference(
            source = NutritionSource.valueOf(fields[0]),
            id = fields[1],
            names = fields[2].split('|').filter(String::isNotBlank),
            energyKcalPer100Grams = fields[3].asDouble(),
            proteinPer100Grams = fields[4].asDouble(),
            carbohydratePer100Grams = fields[5].asDouble(),
            fatPer100Grams = fields[6].asDouble(),
            pieceGrams = fields[7].asDouble(),
            servingGrams = fields[8].asDouble(),
        )
    }

    private fun preferredNames(names: List<String>, language: SupportedLanguage): List<String> {
        val preferredIndex = when (language) {
            SupportedLanguage.FINNISH -> FINNISH_NAME_INDEX
            SupportedLanguage.SWEDISH -> SWEDISH_NAME_INDEX
            else -> ENGLISH_NAME_INDEX
        }
        if (preferredIndex !in names.indices) return names
        return listOf(names[preferredIndex]) + names.filterIndexed { index, _ -> index != preferredIndex }
    }

    private fun String.asDouble(): Double? = takeIf(String::isNotEmpty)?.toDouble()

    companion object {
        // Do not use a .gz suffix: Android's asset packager strips it and silently
        // changes both the runtime name and representation.
        private const val ASSET_NAME = "eu_food_catalog.bin"
        private const val FIELD_COUNT = 9
        private const val MAX_FOODS = 10_000
        private const val ENGLISH_NAME_INDEX = 0
        private const val FINNISH_NAME_INDEX = 1
        private const val SWEDISH_NAME_INDEX = 2
    }
}
