package com.soma.core.tracking

import com.soma.core.model.FoodQuantityUnit
import com.soma.core.model.LogKind
import com.soma.core.model.NutritionBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickLogParserTest {
    @Test
    fun `European decimal quantity is normalized locally`() {
        val draft = QuickLogParser.parse(LogKind.MEAL, "Milchreis 0,3 kg")
        val food = draft.foods.single()

        assertEquals("Milchreis", food.name)
        assertEquals(300.0, food.quantity!!, 0.0)
        assertEquals(FoodQuantityUnit.GRAM, food.unit)
        assertEquals(300.0, food.gramWeight!!, 0.0)
        assertEquals(NutritionBasis.UNQUANTIFIED, food.nutrition?.basis)
    }

    @Test
    fun `multiline recipe keeps ingredients separate`() {
        val draft = QuickLogParser.parse(LogKind.RECIPE, "Rice 100 g\nMilk 250 ml")

        assertEquals(listOf("Rice", "Milk"), draft.foods.map { it.name })
        assertEquals(listOf(FoodQuantityUnit.GRAM, FoodQuantityUnit.MILLILITRE), draft.foods.map { it.unit })
    }

    @Test
    fun `workout shorthand becomes repeated authoritative sets`() {
        val draft = QuickLogParser.parse(LogKind.WORKOUT, "Leg press 3×10 80 kg")
        val exercise = draft.exercises.single()

        assertEquals("Leg press", exercise.name)
        assertEquals(3, exercise.sets.size)
        assertEquals(10, exercise.sets.first().repetitions)
        assertEquals(80.0, exercise.sets.first().weightKilograms!!, 0.0)
    }

    @Test
    fun `unknown workout wording is preserved without invented values`() {
        val exercise = QuickLogParser.parse(LogKind.WORKOUT, "Tried the red rowing machine")
            .exercises.single()

        assertEquals("Tried the red rowing machine", exercise.name)
        assertEquals(emptyList<Any>(), exercise.sets)
        assertNull(exercise.machine)
    }
}
