package com.soma.core.tracking

import com.soma.core.model.FoodItem
import com.soma.core.model.FoodQuantityUnit
import com.soma.core.model.NutritionBasis
import com.soma.core.model.NutritionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EuropeanFoodCatalogTest {
    private val ricePudding = EuropeanFoodReference(
        id = "100",
        source = NutritionSource.FINELI,
        names = listOf("Rice pudding", "Riisipuuro", "Risgrynsgröt"),
        energyKcalPer100Grams = 120.0,
        proteinPer100Grams = 3.0,
        carbohydratePer100Grams = 20.0,
        fatPer100Grams = 3.0,
        servingGrams = 250.0,
    )
    private val catalog = EuropeanFoodCatalog(listOf(ricePudding))

    @Test
    fun `search ignores accents and checks every European alias`() {
        assertEquals(ricePudding, catalog.search("risgrynsgrot").single())
        assertEquals(ricePudding, catalog.search("rice pud").single())
    }

    @Test
    fun `known grams calculate an official average for that amount`() {
        val result = catalog.apply(
            ricePudding,
            FoodItem("Milchreis", 300.0, FoodQuantityUnit.GRAM, gramWeight = 300.0),
        )

        assertEquals(NutritionBasis.OFFICIAL_AVERAGE, result.nutrition?.basis)
        assertEquals(360.0, result.nutrition?.energyKcal!!, 0.0)
        assertEquals(9.0, result.nutrition?.proteinGrams!!, 0.0)
    }

    @Test
    fun `missing amount stays explicitly unquantified after a match`() {
        val result = catalog.apply(ricePudding, FoodItem("Milchreis"))

        assertEquals(NutritionBasis.UNQUANTIFIED, result.nutrition?.basis)
        assertEquals(NutritionSource.FINELI, result.nutrition?.source)
        assertNull(result.nutrition?.energyKcal)
    }

    @Test
    fun `known official serving mass can be calculated without guessing`() {
        val result = catalog.apply(
            ricePudding,
            FoodItem("Milchreis", 1.0, FoodQuantityUnit.SERVING),
        )

        assertEquals(250.0, result.gramWeight!!, 0.0)
        assertEquals(300.0, result.nutrition?.energyKcal!!, 0.0)
    }

    @Test
    fun `Open Food Facts values stay visibly package label data`() {
        val product = ricePudding.copy(
            id = "12345678",
            source = NutritionSource.OPEN_FOOD_FACTS,
        )

        val result = catalog.apply(
            product,
            FoodItem("Rice pudding", 100.0, FoodQuantityUnit.GRAM, gramWeight = 100.0),
        )

        assertEquals(NutritionBasis.PACKAGE_LABEL, result.nutrition?.basis)
        assertEquals(NutritionSource.OPEN_FOOD_FACTS, result.nutrition?.source)
    }
}
