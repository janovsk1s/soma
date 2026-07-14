package com.soma.core.model

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrackingTest {
    private val createdAt = Instant.parse("2026-07-14T08:00:00Z")

    @Test
    fun `estimated nutrition may expose an honest range`() {
        val nutrition = NutritionEstimate(
            basis = NutritionBasis.ESTIMATED,
            source = NutritionSource.AI_ESTIMATE,
            energyKcalMin = 420.0,
            energyKcalMax = 560.0,
        )

        assertEquals(420.0, nutrition.energyKcalMin)
        assertEquals(560.0, nutrition.energyKcalMax)
    }

    @Test
    fun `unquantified food cannot pretend to have calories`() {
        assertThrows(IllegalArgumentException::class.java) {
            NutritionEstimate(
                basis = NutritionBasis.UNQUANTIFIED,
                source = NutritionSource.AI_ESTIMATE,
                energyKcal = 500.0,
            )
        }
    }

    @Test
    fun `package labels cannot masquerade as ranges`() {
        assertThrows(IllegalArgumentException::class.java) {
            NutritionEstimate(
                basis = NutritionBasis.PACKAGE_LABEL,
                source = NutritionSource.OPEN_FOOD_FACTS,
                energyKcalMin = 100.0,
                energyKcalMax = 120.0,
            )
        }
    }

    @Test
    fun `revising a log preserves provenance and advances revision`() {
        val original = LogRecord(
            id = "log-1",
            kind = LogKind.MEAL,
            title = "Milchreis",
            occurredAt = createdAt,
            createdAt = createdAt,
            updatedAt = createdAt,
            source = EntrySource(LocalDate.of(2026, 7, 14), "entry-1"),
            foods = listOf(FoodItem("Milchreis")),
        )

        val edited = original.revise(
            title = "Rice pudding",
            at = createdAt.plusSeconds(60),
        )

        assertEquals(1, edited.revision)
        assertEquals(original.source, edited.source)
        assertEquals(original.createdAt, edited.createdAt)
    }

    @Test
    fun `food and workout structures cannot silently mix`() {
        assertThrows(IllegalArgumentException::class.java) {
            LogRecord(
                id = "log-1",
                kind = LogKind.WORKOUT,
                title = "Lunch",
                occurredAt = createdAt,
                createdAt = createdAt,
                updatedAt = createdAt,
                foods = listOf(FoodItem("Apple")),
            )
        }
    }
}
