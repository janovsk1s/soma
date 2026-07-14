package com.soma.core.metadata

import com.soma.core.model.SupportedLanguage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportantResurfaceDeriverTest {
    private val monday = LocalDate.of(2026, 7, 13) // a Monday

    @Test
    fun `tomorrow resolves to the next day`() {
        assertEquals(
            LocalDate.of(2026, 7, 14),
            ImportantResurfaceDeriver.deriveDate("call the dentist tomorrow", SupportedLanguage.ENGLISH, monday),
        )
    }

    @Test
    fun `a weekday resolves to its next strictly-future occurrence`() {
        assertEquals(
            LocalDate.of(2026, 7, 17),
            ImportantResurfaceDeriver.deriveDate("meeting on Friday", SupportedLanguage.ENGLISH, monday),
        )
        // The same weekday as today means next week, never today.
        assertEquals(
            LocalDate.of(2026, 7, 20),
            ImportantResurfaceDeriver.deriveDate("gym Monday", SupportedLanguage.ENGLISH, monday),
        )
    }

    @Test
    fun `an explicit future ISO date is used`() {
        assertEquals(
            LocalDate.of(2026, 8, 1),
            ImportantResurfaceDeriver.deriveDate("pay rent 2026-08-01", SupportedLanguage.ENGLISH, monday),
        )
    }

    @Test
    fun `the soonest future date wins`() {
        assertEquals(
            LocalDate.of(2026, 7, 14),
            ImportantResurfaceDeriver.deriveDate("friday review, but ping them tomorrow", SupportedLanguage.ENGLISH, monday),
        )
    }

    @Test
    fun `a latvian weekday stem matches an inflected form`() {
        assertEquals(
            LocalDate.of(2026, 7, 17),
            ImportantResurfaceDeriver.deriveDate("piezvanīt piektdienā", SupportedLanguage.LATVIAN, monday),
        )
    }

    @Test
    fun `a code switched note checks each selected language`() {
        assertEquals(
            LocalDate.of(2026, 7, 14),
            ImportantResurfaceDeriver.deriveDate(
                "piezvanīt dentist tomorrow",
                setOf(SupportedLanguage.LATVIAN, SupportedLanguage.ENGLISH),
                monday,
            ),
        )
    }

    @Test
    fun `an unselected language is not inferred`() {
        assertNull(
            ImportantResurfaceDeriver.deriveDate(
                "call tomorrow",
                setOf(SupportedLanguage.LATVIAN),
                monday,
            ),
        )
    }

    @Test
    fun `german tomorrow does not match the day-after-tomorrow word`() {
        assertNull(
            ImportantResurfaceDeriver.deriveDate("das mache ich übermorgen", SupportedLanguage.GERMAN, monday),
        )
    }

    @Test
    fun `text with no recognisable date returns null`() {
        assertNull(
            ImportantResurfaceDeriver.deriveDate("just a passing thought", SupportedLanguage.ENGLISH, monday),
        )
    }
}
