package com.soma.core.tracking

import com.soma.core.model.LogKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineTrackingSuggestionDetectorTest {
    @Test
    fun `workout shorthand is suggested without a cloud model`() {
        assertEquals(
            LogKind.WORKOUT,
            InlineTrackingSuggestionDetector.suggest("Leg press 3×10 80 kg"),
        )
    }

    @Test
    fun `natural workout phrases work across every supported language`() {
        val examples = listOf(
            "I did 3 sets of 10 repetitions at the gym",
            "Treniņā izdarīju 3 sērijas pa 10 atkārtojumiem",
            "Tegin jõusaalis 3 seeriat ja 10 kordust",
            "Treniruotėje dariau 3 serijas po 10 pakartojimų",
            "Tein kuntosalilla 3 sarjaa ja 10 toistoa",
            "Jag gjorde 3 set med 10 repetitioner på gymmet",
            "Im Training habe ich 3 Sätze mit 10 Wiederholungen gemacht",
            "V posilňovni som robil 3 série po 10 opakovaní",
        )

        examples.forEach { text ->
            assertEquals(text, LogKind.WORKOUT, InlineTrackingSuggestionDetector.suggest(text))
        }
    }

    @Test
    fun `future gym reminder does not become a completed workout`() {
        assertNull(InlineTrackingSuggestionDetector.suggest("I need to go to the gym tomorrow"))
        assertNull(InlineTrackingSuggestionDetector.suggest("Set an alarm for 10"))
    }

    @Test
    fun `food suggestions remain distinct`() {
        assertEquals(LogKind.MEAL, InlineTrackingSuggestionDetector.suggest("Apēdu piena rīsus"))
        assertEquals(LogKind.RECIPE, InlineTrackingSuggestionDetector.suggest("Recepte: rīsi 100 g, piens 250 ml"))
    }

    @Test
    fun `receipt wording opens a spending record in supported languages`() {
        listOf(
            "Lidl receipt",
            "Rimi čeks",
            "Selver kviitung",
            "Maxima kvitas",
            "Prisma kuitti",
            "ICA kvitto",
            "Billa Kassenbon",
            "Tesco účtenka",
        ).forEach { text ->
            assertEquals(text, LogKind.RECEIPT, InlineTrackingSuggestionDetector.suggest(text))
        }
    }

    @Test
    fun `ordinary thoughts do not receive a tracking action`() {
        assertNull(InlineTrackingSuggestionDetector.suggest("The train was late, but the forest looked calm."))
    }
}
