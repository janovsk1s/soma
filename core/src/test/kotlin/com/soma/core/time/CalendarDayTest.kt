package com.soma.core.time

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarDayTest {
    private val vienna = ZoneId.of("Europe/Vienna")

    @Test
    fun `today changes exactly at local midnight`() {
        val before = CalendarDay(
            Clock.fixed(Instant.parse("2026-07-11T21:59:59Z"), ZoneOffset.UTC),
            vienna,
        )
        val after = CalendarDay(
            Clock.fixed(Instant.parse("2026-07-11T22:00:00Z"), ZoneOffset.UTC),
            vienna,
        )

        assertEquals(LocalDate.of(2026, 7, 11), before.today())
        assertEquals(LocalDate.of(2026, 7, 12), after.today())
        assertFalse(before.hasRolledForwardSince(LocalDate.of(2026, 7, 11)))
        assertTrue(after.hasRolledForwardSince(LocalDate.of(2026, 7, 11)))
    }

    @Test
    fun `same instant resolves using injected timezone`() {
        val instant = Instant.parse("2026-01-01T00:30:00Z")
        val honolulu = CalendarDay(Clock.fixed(instant, ZoneOffset.UTC), ZoneId.of("Pacific/Honolulu"))
        val viennaDay = CalendarDay(Clock.fixed(instant, ZoneOffset.UTC), vienna)

        assertEquals(LocalDate.of(2025, 12, 31), honolulu.today())
        assertEquals(LocalDate.of(2026, 1, 1), viennaDay.today())
    }

    @Test
    fun `day change reports forward same and backward explicitly`() {
        val day = CalendarDay(
            Clock.fixed(Instant.parse("2026-07-12T10:00:00Z"), ZoneOffset.UTC),
            ZoneOffset.UTC,
        )

        assertEquals(DayChange.FORWARD, day.changeSince(LocalDate.of(2026, 7, 11)))
        assertEquals(DayChange.SAME, day.changeSince(LocalDate.of(2026, 7, 12)))
        assertEquals(DayChange.BACKWARD, day.changeSince(LocalDate.of(2026, 7, 13)))
    }

    @Test
    fun `spring DST day is 23 hours`() {
        val day = CalendarDay(Clock.systemUTC(), vienna)

        assertEquals(Duration.ofHours(23), day.durationOf(LocalDate.of(2026, 3, 29)))
    }

    @Test
    fun `autumn DST day is 25 hours`() {
        val day = CalendarDay(Clock.systemUTC(), vienna)

        assertEquals(Duration.ofHours(25), day.durationOf(LocalDate.of(2026, 10, 25)))
    }

    @Test
    fun `day navigator maps today to zero and never exposes future days`() {
        val day = CalendarDay(
            Clock.fixed(Instant.parse("2026-07-12T10:00:00Z"), ZoneOffset.UTC),
            ZoneOffset.UTC,
        )
        val navigator = DayNavigator(day)

        assertEquals(LocalDate.of(2026, 7, 12), navigator.dateForPage(0))
        assertEquals(LocalDate.of(2026, 7, 7), navigator.dateForPage(5))
        assertEquals(5L, navigator.pageForDate(LocalDate.of(2026, 7, 7)))
        assertNull(navigator.pageForDate(LocalDate.of(2026, 7, 13)))
        assertNull(navigator.next(LocalDate.of(2026, 7, 12)))
        assertEquals(LocalDate.of(2026, 7, 12), navigator.next(LocalDate.of(2026, 7, 11)))
        assertFalse(navigator.isReachable(LocalDate.of(2026, 7, 13)))
        assertTrue(navigator.isReachable(LocalDate.of(2026, 7, 12)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative day page is rejected`() {
        val day = CalendarDay(Clock.systemUTC(), ZoneOffset.UTC)
        DayNavigator(day).dateForPage(-1)
    }
}
