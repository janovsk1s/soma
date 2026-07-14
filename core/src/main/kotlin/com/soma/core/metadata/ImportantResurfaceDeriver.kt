package com.soma.core.metadata

import com.soma.core.model.SupportedLanguage
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Deterministic, offline extraction of a single "bring this back on" date from an
 * Important item's own text. It recognises explicit ISO dates, "tomorrow", and
 * weekday names in the eight supported languages, and returns the soonest
 * strictly-future date it finds, or null.
 *
 * It never guesses beyond these forms and never calls a network or model. The
 * result is only ever used as a *clearable* default on an item the user
 * deliberately created, so a missed or slightly-off date is harmless — the
 * authored text is never changed.
 */
object ImportantResurfaceDeriver {

    fun deriveDate(text: String, language: SupportedLanguage, today: LocalDate): LocalDate? {
        return deriveDate(text, setOf(language), today)
    }

    /**
     * Code-switched notes are checked against every language the user selected.
     * ISO dates are parsed once and the earliest strictly-future result wins.
     */
    fun deriveDate(
        text: String,
        languages: Set<SupportedLanguage>,
        today: LocalDate,
    ): LocalDate? {
        if (text.isBlank()) return null
        val haystack = text.take(MAX_INPUT_CHARS).lowercase(Locale.ROOT)
        val candidates = mutableListOf<LocalDate>()

        ISO_DATE.findAll(haystack).forEach { match ->
            runCatching { LocalDate.parse(match.value) }.getOrNull()?.let(candidates::add)
        }

        languages.ifEmpty { SupportedLanguage.entries.toSet() }.forEach { language ->
            val lexicon = LEXICONS.getValue(language)
            if (lexicon.tomorrow.any { haystack.containsWordStartingWith(it) }) {
                candidates += today.plusDays(1)
            }
            lexicon.weekdays.forEach { (stem, dayOfWeek) ->
                if (haystack.containsWordStartingWith(stem)) candidates += today.nextOccurrenceOf(dayOfWeek)
            }
        }

        return candidates.filter { it.isAfter(today) }.minOrNull()
    }

    /** The next date strictly after this one whose day-of-week is [target]. */
    private fun LocalDate.nextOccurrenceOf(target: DayOfWeek): LocalDate {
        val ahead = ((target.value - dayOfWeek.value + DAYS_IN_WEEK) % DAYS_IN_WEEK).let {
            if (it == 0) DAYS_IN_WEEK else it
        }
        return plusDays(ahead.toLong())
    }

    /**
     * True when [stem] appears at a word boundary (any preceding non-letter), while
     * still matching inflected suffixes — e.g. the Latvian stem `piektdien` matches
     * `piektdienā`, but `morgen` does not match inside `übermorgen`.
     */
    private fun String.containsWordStartingWith(stem: String): Boolean {
        var index = indexOf(stem)
        while (index >= 0) {
            val preceding = getOrNull(index - 1)
            if (preceding == null || !preceding.isLetter()) return true
            index = indexOf(stem, index + 1)
        }
        return false
    }

    private data class Lexicon(
        val tomorrow: List<String>,
        val weekdays: List<Pair<String, DayOfWeek>>,
    )

    private fun weekdays(
        monday: String,
        tuesday: String,
        wednesday: String,
        thursday: String,
        friday: String,
        saturday: String,
        sunday: String,
    ): List<Pair<String, DayOfWeek>> = listOf(
        monday to DayOfWeek.MONDAY,
        tuesday to DayOfWeek.TUESDAY,
        wednesday to DayOfWeek.WEDNESDAY,
        thursday to DayOfWeek.THURSDAY,
        friday to DayOfWeek.FRIDAY,
        saturday to DayOfWeek.SATURDAY,
        sunday to DayOfWeek.SUNDAY,
    )

    private const val MAX_INPUT_CHARS = 2_000
    private const val DAYS_IN_WEEK = 7
    private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    // Weekday entries are stems so common inflections still match. They are only
    // ever checked against text already known to be in the same language.
    private val LEXICONS: Map<SupportedLanguage, Lexicon> = mapOf(
        SupportedLanguage.ENGLISH to Lexicon(
            tomorrow = listOf("tomorrow"),
            weekdays = weekdays("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"),
        ),
        SupportedLanguage.LATVIAN to Lexicon(
            tomorrow = listOf("rīt"),
            weekdays = weekdays("pirmdien", "otrdien", "trešdien", "ceturtdien", "piektdien", "sestdien", "svētdien"),
        ),
        SupportedLanguage.ESTONIAN to Lexicon(
            tomorrow = listOf("homme"),
            weekdays = weekdays("esmaspäev", "teisipäev", "kolmapäev", "neljapäev", "reede", "laupäev", "pühapäev"),
        ),
        SupportedLanguage.LITHUANIAN to Lexicon(
            tomorrow = listOf("rytoj"),
            weekdays = weekdays("pirmadien", "antradien", "trečiadien", "ketvirtadien", "penktadien", "šeštadien", "sekmadien"),
        ),
        SupportedLanguage.FINNISH to Lexicon(
            tomorrow = listOf("huomenna"),
            weekdays = weekdays("maanantai", "tiistai", "keskiviikko", "torstai", "perjantai", "lauantai", "sunnuntai"),
        ),
        SupportedLanguage.SWEDISH to Lexicon(
            tomorrow = listOf("imorgon", "i morgon"),
            weekdays = weekdays("måndag", "tisdag", "onsdag", "torsdag", "fredag", "lördag", "söndag"),
        ),
        SupportedLanguage.GERMAN to Lexicon(
            tomorrow = listOf("morgen"),
            weekdays = weekdays("montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag"),
        ),
        SupportedLanguage.SLOVAK to Lexicon(
            tomorrow = listOf("zajtra"),
            weekdays = weekdays("pondelok", "utorok", "streda", "štvrtok", "piatok", "sobota", "nedeľa"),
        ),
    )
}
