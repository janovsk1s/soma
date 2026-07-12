package com.soma.core.time

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class DayChange {
    SAME,
    FORWARD,
    BACKWARD,
}

/** All calendar-day decisions flow through an injected clock and zone. */
class CalendarDay(
    private val clock: Clock,
    val zoneId: ZoneId,
) {
    fun now(): Instant = clock.instant()

    fun today(): LocalDate = dateAt(now())

    fun dateAt(instant: Instant): LocalDate = instant.atZone(zoneId).toLocalDate()

    fun changeSince(previousDate: LocalDate): DayChange = when {
        today().isAfter(previousDate) -> DayChange.FORWARD
        today().isBefore(previousDate) -> DayChange.BACKWARD
        else -> DayChange.SAME
    }

    fun hasRolledForwardSince(previousDate: LocalDate): Boolean =
        changeSince(previousDate) == DayChange.FORWARD

    /** Uses zone rules, so gaps, overlaps and non-24-hour DST days are handled correctly. */
    fun startOfDay(date: LocalDate): Instant = date.atStartOfDay(zoneId).toInstant()

    fun endOfDayExclusive(date: LocalDate): Instant = startOfDay(date.plusDays(1))

    fun durationOf(date: LocalDate): Duration =
        Duration.between(startOfDay(date), endOfDayExclusive(date))
}

/** Page zero is today; positive page indexes move backward. Future dates are rejected. */
class DayNavigator(
    private val calendarDay: CalendarDay,
) {
    fun dateForPage(pageIndex: Int): LocalDate {
        require(pageIndex >= 0) { "Day page index must not be negative" }
        return calendarDay.today().minusDays(pageIndex.toLong())
    }

    fun pageForDate(date: LocalDate): Long? {
        val today = calendarDay.today()
        if (date.isAfter(today)) return null
        return ChronoUnit.DAYS.between(date, today)
    }

    fun previous(date: LocalDate): LocalDate = date.minusDays(1)

    fun next(date: LocalDate): LocalDate? = when {
        date.isBefore(calendarDay.today()) -> date.plusDays(1)
        else -> null
    }

    fun isReachable(date: LocalDate): Boolean = !date.isAfter(calendarDay.today())
}
