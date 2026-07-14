package com.soma.core.tracking

import com.soma.core.model.FoodQuantityUnit
import com.soma.core.model.LogKind
import com.soma.core.model.NutritionBasis
import com.soma.core.model.ReceiptDetails
import com.soma.core.model.ReceiptItem
import com.soma.core.model.ReceiptMoney
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

    @Test
    fun `receipt parser keeps exact totals and purchased lines`() {
        val draft = QuickLogParser.parse(
            LogKind.RECEIPT,
            "merchant: Rimi\ncurrency: EUR\nitem: Piens | 1 | 1,29 | groceries\nitem: Maize | 2 | 3,00\ntax: 0,75\ntotal: 4,29",
        )

        assertEquals("Rimi", draft.title)
        assertEquals(429L, draft.receipt?.total?.minorUnits)
        assertEquals(75L, draft.receipt?.tax?.minorUnits)
        assertEquals(listOf("Piens", "Maize"), draft.receipt?.items?.map { it.name })
        assertEquals(129L, draft.receipt?.items?.first()?.lineTotal?.minorUnits)
        assertEquals("groceries", draft.receipt?.items?.first()?.category)
    }

    @Test
    fun `declared European currency and thousands separators stay exact`() {
        val draft = QuickLogParser.parse(
            LogKind.RECEIPT,
            "merchant: Sklep\ncurrency: PLN\nitem: Zakupy | 1 | 1.234,56\ntotal: 1 234,56",
        )

        assertEquals("PLN", draft.receipt?.currencyCode)
        assertEquals(123_456L, draft.receipt?.total?.minorUnits)
        assertEquals(123_456L, draft.receipt?.items?.single()?.lineTotal?.minorUnits)
    }

    @Test
    fun `identical printed purchase lines are not deduplicated`() {
        val draft = QuickLogParser.parse(
            LogKind.RECEIPT,
            "merchant: Rimi\nitem: Piens | 1 | 1.29\nitem: Piens | 1 | 1.29\ntotal: 2.58",
        )

        assertEquals(2, draft.receipt?.items?.size)
    }

    @Test
    fun `photo only receipt can keep a neutral record without a fake merchant`() {
        val draft = QuickLogParser.parse(LogKind.RECEIPT, "currency: EUR")

        assertEquals("Receipt", draft.title)
        assertNull(draft.receipt?.merchant)
        assertEquals("EUR", draft.receipt?.currencyCode)
    }

    @Test
    fun `receipt reconciliation reports calm exact differences`() {
        val receipt = ReceiptDetails(
            total = ReceiptMoney(500, "EUR"),
            subtotal = ReceiptMoney(450, "EUR"),
            tax = ReceiptMoney(40, "EUR"),
            items = listOf(
                ReceiptItem("Milk", lineTotal = ReceiptMoney(200, "EUR")),
                ReceiptItem("Bread", lineTotal = ReceiptMoney(250, "EUR")),
            ),
        )

        val result = ReceiptReconciler.reconcile(receipt)

        assertEquals(450L, result.pricedItemSum?.minorUnits)
        assertEquals(50L, result.itemDifferenceMinorUnits)
        assertEquals(10L, result.subtotalTaxDifferenceMinorUnits)
    }

    @Test
    fun `partial item prices are summed but never compared as a complete receipt`() {
        val receipt = ReceiptDetails(
            total = ReceiptMoney(500, "EUR"),
            items = listOf(
                ReceiptItem("Milk", lineTotal = ReceiptMoney(200, "EUR")),
                ReceiptItem("Unreadable item"),
            ),
        )

        val result = ReceiptReconciler.reconcile(receipt)

        assertEquals(200L, result.pricedItemSum?.minorUnits)
        assertEquals(false, result.allItemsPriced)
        assertNull(result.itemDifferenceMinorUnits)
    }
}
